import logging
import math
import re
from copy import deepcopy
from datetime import UTC, datetime
from decimal import Decimal
from email.utils import parsedate_to_datetime
from typing import Any

from openai import APIStatusError, OpenAI
from pydantic_ai.profiles.openai import OpenAIJsonSchemaTransformer

from app.core.config import Settings
from app.core.errors import AgentError
from app.core.safecast import safe_int
from app.llm.base import ProviderResponse, ProviderUsage
from app.llm.openai_contract import output_contract

logger = logging.getLogger(__name__)

# Standard USD per million tokens (input, cached input, output), checked 2026-09-07.
# https://developers.openai.com/api/docs/models/gpt-4.1-nano
# https://developers.openai.com/api/docs/models/gpt-4o-mini
_MODEL_PRICES = {
    "gpt-4.1-nano": (Decimal("0.10"), Decimal("0.025"), Decimal("0.40")),
    "gpt-4o-mini": (Decimal("0.15"), Decimal("0.075"), Decimal("0.60")),
}
_MODEL_ALIASES = {
    "gpt-4.1-nano-2025-04-14": "gpt-4.1-nano",
    "gpt-4o-mini-2024-07-18": "gpt-4o-mini",
}
_ERROR_CODES = {
    "insufficient_quota",
    "rate_limit_exceeded",
    "invalid_api_key",
    "invalid_json_schema",
    "invalid_request_error",
}


class OpenAIAnalyzeProvider:
    def __init__(self, settings: Settings, client: OpenAI | None = None) -> None:
        self._model = settings.openai_model
        self._max_output_tokens = settings.max_output_tokens
        self._retry_attempts = settings.provider_retry_attempts
        self._prices = _MODEL_PRICES.get(_MODEL_ALIASES.get(self._model, self._model))
        if settings.openai_input_cost_per_million is not None:
            self._prices = (
                settings.openai_input_cost_per_million,
                settings.openai_cached_input_cost_per_million,
                settings.openai_output_cost_per_million,
            )
        if self._prices is None:
            logger.warning(
                "OpenAI 모델 단가 미설정: costUsd는 0으로 기록됩니다. model=%s", self._model
            )
        self._owns_client = client is None
        self._client = client or OpenAI(
            api_key=settings.openai_api_key,
            base_url="https://api.openai.com/v1",
            timeout=settings.provider_timeout_seconds,
            # 429 is retried by the shared coordinator; avoid hidden SDK retries.
            max_retries=0,
        )

    def generate(
        self,
        *,
        system_instruction: str,
        prompt: str,
        response_schema: dict[str, Any],
    ) -> ProviderResponse:
        try:
            contract = output_contract(response_schema)
            response = self._create_response(
                system_instruction=contract.instructions(system_instruction),
                prompt=prompt,
                response_schema=contract.schema,
            )
            usage = self._usage(response.usage)
            truncated = (
                response.status == "incomplete"
                and getattr(response.incomplete_details, "reason", None) == "max_output_tokens"
            )
            refused = any(
                part.type == "refusal"
                for item in response.output
                if item.type == "message"
                for part in item.content
            )
            if refused or (response.status != "completed" and not truncated):
                raise _output_error(usage, truncated)
            text = response.output_text
            if not text.strip() and not truncated:
                raise _output_error(usage, truncated)
            return ProviderResponse(
                text=contract.public_text(text),
                provider="openai",
                model=self._model,
                usage=usage,
                truncated=truncated,
            )
        except AgentError:
            raise
        except Exception as exc:
            details = _provider_error_details(exc)
            logger.warning(
                "OpenAI provider 호출에 실패했습니다. model=%s statusCode=%s "
                "providerStatus=%s errorType=%s",
                self._model,
                details["providerStatusCode"],
                details["providerStatus"],
                type(exc).__name__,
            )
            raise AgentError(
                status_code=503,
                code="PROVIDER_UNAVAILABLE",
                message="OpenAI provider를 호출할 수 없습니다.",
                details=details,
            ) from exc

    def _create_response(
        self,
        *,
        system_instruction: str,
        prompt: str,
        response_schema: dict[str, Any],
    ) -> Any:
        schema = OpenAIJsonSchemaTransformer(deepcopy(response_schema), strict=True).walk()
        name = re.sub(r"[^a-zA-Z0-9_-]", "_", str(response_schema.get("title") or "output"))
        for attempt in range(self._retry_attempts + 1):
            try:
                return self._client.responses.create(
                    model=self._model,
                    instructions=system_instruction,
                    input=prompt,
                    temperature=0,
                    max_output_tokens=self._max_output_tokens,
                    store=False,
                    text={
                        "format": {
                            "type": "json_schema",
                            "name": name[:64],
                            "strict": True,
                            "schema": schema,
                        }
                    },
                )
            except APIStatusError as exc:
                if exc.status_code < 500 or attempt >= self._retry_attempts:
                    raise
                logger.warning(
                    "OpenAI provider 일시 오류로 재시도합니다. model=%s attempt=%s/%s",
                    self._model,
                    attempt + 2,
                    self._retry_attempts + 1,
                )
        raise RuntimeError("OpenAI provider 재시도 상태가 올바르지 않습니다.")

    def _usage(self, usage: Any) -> ProviderUsage:
        input_tokens = max(0, safe_int(getattr(usage, "input_tokens", 0), 0) or 0)
        output_tokens = max(0, safe_int(getattr(usage, "output_tokens", 0), 0) or 0)
        cached_tokens = min(
            input_tokens,
            max(
                0,
                safe_int(
                    getattr(getattr(usage, "input_tokens_details", None), "cached_tokens", 0), 0
                )
                or 0,
            ),
        )
        cost = Decimal("0")
        if self._prices is not None:
            input_price, cached_price, output_price = self._prices
            cost = (
                (input_tokens - cached_tokens) * input_price
                + cached_tokens * cached_price
                + output_tokens * output_price
            ) / Decimal("1000000")
        return ProviderUsage(input_tokens=input_tokens, output_tokens=output_tokens, cost_usd=cost)

    def close(self) -> None:
        if self._owns_client:
            self._client.close()


def _output_error(usage: ProviderUsage, truncated: bool) -> AgentError:
    return AgentError(
        status_code=503,
        code="PROVIDER_UNAVAILABLE",
        message="OpenAI provider가 사용할 수 있는 응답을 반환하지 않았습니다.",
        details={
            "provider": "openai",
            "retryable": False,
            "truncated": truncated,
            "usage": {
                "inputTokens": usage.input_tokens,
                "outputTokens": usage.output_tokens,
                "costUsd": float(usage.cost_usd),
                "credits": 0,
            },
        },
    )


def _provider_error_details(error: Exception) -> dict[str, object]:
    status = error.status_code if isinstance(error, APIStatusError) else 0
    code = getattr(error, "code", None)
    if code is None:
        code = getattr(error, "type", None)
    safe_code = code if isinstance(code, str) and code in _ERROR_CODES else "UNKNOWN"
    details: dict[str, object] = {
        "provider": "openai",
        "providerStatusCode": status,
        "providerStatus": safe_code,
        "rateLimited": status == 429,
        "retryable": (status in {408, 429} or status >= 500) and safe_code != "insufficient_quota",
    }
    if isinstance(error, APIStatusError):
        retry_after = _retry_after_header_seconds(error.response.headers.get("retry-after"))
        if retry_after is not None:
            details["retryAfterSeconds"] = retry_after
    return details


def _retry_after_header_seconds(value: str | None) -> float | None:
    if value is None:
        return None
    try:
        seconds = float(value)
        return seconds if math.isfinite(seconds) and seconds >= 0 else None
    except ValueError:
        pass
    try:
        date = parsedate_to_datetime(value)
        if date.tzinfo is None:
            date = date.replace(tzinfo=UTC)
        return max(0.0, (date - datetime.now(UTC)).total_seconds())
    except (TypeError, ValueError, OverflowError):
        return None
