import logging
import math
import re
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
    details: dict[str, object] = {
        "provider": "gemini",
        "providerStatusCode": code,
        "providerStatus": status,
        "rateLimited": code == 429,
        "retryable": code == 429 or code == 408 or code >= 500,
    }
    if retry_after is not None:
        details["retryAfterSeconds"] = retry_after
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
        parsed = _seconds(value)
        if parsed is not None:
            return parsed

    payload = error.details
    if not isinstance(payload, dict):
        return None
    error_payload = payload.get("error", payload)
    if not isinstance(error_payload, dict):
        return None
    rpc_details = error_payload.get("details", [])
    if not isinstance(rpc_details, list):
        return None
    for detail in rpc_details:
        if not isinstance(detail, dict):
            continue
        parsed = _seconds(detail.get("retryDelay"))
        if parsed is not None:
            return parsed
    return None


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
