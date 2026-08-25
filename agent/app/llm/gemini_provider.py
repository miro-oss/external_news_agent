import logging
import math
import re
from datetime import UTC, datetime
from email.utils import parsedate_to_datetime
from typing import Any

from google import genai
from google.genai import errors, types

from app.core.config import Settings
from app.core.errors import AgentError
from app.core.safecast import safe_int
from app.llm.base import ProviderResponse, ProviderUsage

logger = logging.getLogger(__name__)

_UNSUPPORTED_SCHEMA_KEYS = frozenset({"maxLength", "minLength", "pattern"})
_RETRY_DELAY = re.compile(r"^\s*(\d+(?:\.\d+)?)s\s*$")


class GeminiAnalyzeProvider:
    def __init__(self, settings: Settings, client: Any | None = None) -> None:
        self._model = settings.gemini_model
        self._max_output_tokens = settings.max_output_tokens
        self._retry_attempts = settings.provider_retry_attempts
        self._owns_client = client is None
        self._client = client or genai.Client(
            api_key=settings.gemini_api_key,
            http_options=types.HttpOptions(
                timeout=int(settings.provider_timeout_seconds * 1_000)
            ),
        )

    def generate(
        self,
        *,
        system_instruction: str,
        prompt: str,
        response_schema: dict[str, Any],
    ) -> ProviderResponse:
        try:
            response = self._generate_content(
                system_instruction=system_instruction,
                prompt=prompt,
                response_schema=response_schema,
            )
            text = response.text
            if not isinstance(text, str) or not text.strip():
                raise ValueError("Gemini 응답 본문이 비어 있습니다.")
            usage = getattr(response, "usage_metadata", None)
            candidates = getattr(response, "candidates", None) or []
            finish_reason = getattr(candidates[0], "finish_reason", None) if candidates else None
            return ProviderResponse(
                text=text,
                provider="gemini",
                model=self._model,
                usage=ProviderUsage(
                    input_tokens=safe_int(getattr(usage, "prompt_token_count", 0), 0) or 0,
                    output_tokens=safe_int(getattr(usage, "candidates_token_count", 0), 0) or 0,
                ),
                truncated=str(finish_reason).upper().endswith("MAX_TOKENS"),
            )
        except errors.APIError as exc:
            details = _provider_error_details(exc)
            logger.warning(
                "Gemini provider 호출에 실패했습니다. model=%s statusCode=%s "
                "status=%s rateLimited=%s",
                self._model,
                details["providerStatusCode"],
                details["providerStatus"],
                details["rateLimited"],
            )
            raise AgentError(
                status_code=503,
                code="PROVIDER_UNAVAILABLE",
                message="Gemini provider를 호출할 수 없습니다.",
                details=details,
            ) from exc
        except AgentError:
            raise
        except Exception as exc:
            logger.warning(
                "Gemini provider 호출에 실패했습니다. model=%s errorType=%s",
                self._model,
                type(exc).__name__,
            )
            raise AgentError(
                status_code=503,
                code="PROVIDER_UNAVAILABLE",
                message="Gemini provider를 호출할 수 없습니다.",
            ) from exc

    def _generate_content(
        self,
        *,
        system_instruction: str,
        prompt: str,
        response_schema: dict[str, Any],
    ) -> Any:
        for attempt in range(self._retry_attempts + 1):
            try:
                return self._client.models.generate_content(
                    model=self._model,
                    contents=prompt,
                    config=types.GenerateContentConfig(
                        system_instruction=system_instruction,
                        temperature=0,
                        max_output_tokens=self._max_output_tokens,
                        response_mime_type="application/json",
                        response_json_schema=_gemini_schema(response_schema),
                        automatic_function_calling=types.AutomaticFunctionCallingConfig(
                            disable=True
                        ),
                    ),
                )
            except errors.ServerError:
                if attempt >= self._retry_attempts:
                    raise
                logger.warning(
                    "Gemini provider 일시 오류로 재시도합니다. model=%s attempt=%s/%s",
                    self._model,
                    attempt + 2,
                    self._retry_attempts + 1,
                )
        raise RuntimeError("Gemini provider 재시도 상태가 올바르지 않습니다.")

    def close(self) -> None:
        if self._owns_client:
            self._client.close()


def _gemini_schema(value: Any) -> Any:
    if isinstance(value, dict):
        return {
            key: _gemini_schema(child)
            for key, child in value.items()
            if key not in _UNSUPPORTED_SCHEMA_KEYS
        }
    if isinstance(value, list):
        return [_gemini_schema(child) for child in value]
    return value


def _provider_error_details(error: errors.APIError) -> dict[str, object]:
    code = error.code if isinstance(error.code, int) and not isinstance(error.code, bool) else 0
    status = _safe_status(error.status)
    retry_after = _retry_after_seconds(error)
    quota_violations = _quota_violations(error)
    daily_quota_exhausted = code == 429 and _has_daily_quota(quota_violations)
    details: dict[str, object] = {
        "provider": "gemini",
        "providerStatusCode": code,
        "providerStatus": status,
        "rateLimited": code == 429,
        "retryable": (code == 429 or code == 408 or code >= 500)
        and not daily_quota_exhausted,
    }
    if retry_after is not None:
        details["retryAfterSeconds"] = retry_after
    if quota_violations:
        details["quotaViolations"] = quota_violations
    return details


def _safe_status(value: object) -> str:
    if not isinstance(value, str):
        return "UNKNOWN"
    normalized = re.sub(r"[^A-Za-z0-9_-]", "_", value.strip())[:64]
    return normalized or "UNKNOWN"


def _retry_after_seconds(error: errors.APIError) -> float | None:
    response = getattr(error, "response", None)
    headers = getattr(response, "headers", None)
    if headers is not None:
        value = headers.get("retry-after")
        parsed = _retry_after_header_seconds(value)
        if parsed is not None:
            return parsed

    for detail in _rpc_details(error):
        parsed = _seconds(detail.get("retryDelay"))
        if parsed is not None:
            return parsed
    return None


def _quota_violations(error: errors.APIError) -> list[dict[str, str]]:
    result: list[dict[str, str]] = []
    for detail in _rpc_details(error):
        violations = detail.get("violations")
        if not isinstance(violations, list):
            continue
        for violation in violations:
            if not isinstance(violation, dict):
                continue
            sanitized = {
                output_key: safe
                for source_key, output_key in (
                    ("quotaMetric", "quotaMetric"),
                    ("quotaId", "quotaId"),
                    ("quotaValue", "quotaValue"),
                )
                if (safe := _safe_quota_text(violation.get(source_key))) is not None
            }
            if sanitized:
                result.append(sanitized)
            if len(result) >= 5:
                return result
    return result


def _has_daily_quota(violations: list[dict[str, str]]) -> bool:
    return any("perday" in violation.get("quotaId", "").casefold() for violation in violations)


def _rpc_details(error: errors.APIError) -> list[dict[str, object]]:
    payload = error.details
    if not isinstance(payload, dict):
        return []
    error_payload = payload.get("error", payload)
    if not isinstance(error_payload, dict):
        return []
    details = error_payload.get("details", [])
    if not isinstance(details, list):
        return []
    return [detail for detail in details if isinstance(detail, dict)]


def _safe_quota_text(value: object) -> str | None:
    if not isinstance(value, (str, int, float)) or isinstance(value, bool):
        return None
    normalized = re.sub(r"[^A-Za-z0-9_./:-]", "_", str(value).strip())[:200]
    return normalized or None


def _seconds(value: object) -> float | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return float(value) if math.isfinite(value) and value >= 0 else None
    if not isinstance(value, str):
        return None
    if value.strip().replace(".", "", 1).isdigit():
        return float(value)
    match = _RETRY_DELAY.fullmatch(value)
    return float(match.group(1)) if match else None


def _retry_after_header_seconds(
    value: object,
    *,
    now: datetime | None = None,
) -> float | None:
    parsed_seconds = _seconds(value)
    if parsed_seconds is not None:
        return parsed_seconds
    if not isinstance(value, str):
        return None
    try:
        parsed_date = parsedate_to_datetime(value)
    except (TypeError, ValueError, OverflowError):
        return None
    if parsed_date.tzinfo is None:
        parsed_date = parsed_date.replace(tzinfo=UTC)
    current = now or datetime.now(UTC)
    return max(0.0, (parsed_date.astimezone(UTC) - current.astimezone(UTC)).total_seconds())
