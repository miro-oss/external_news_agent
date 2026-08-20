import logging
import re
from decimal import Decimal
from typing import Any

import httpx

from app.core.config import Settings
from app.core.errors import AgentError
from app.core.safecast import safe_float, safe_int
from app.llm.base import ProviderResponse, ProviderUsage

logger = logging.getLogger(__name__)

_UNSUPPORTED_STRICT_SCHEMA_KEYS = frozenset(
    {
        "maxItems",
        "maxLength",
        "maximum",
        "minItems",
        "minLength",
        "minimum",
        "pattern",
    }
)


class MindlogicAnalyzeProvider:
    def __init__(self, settings: Settings, client: httpx.Client | None = None) -> None:
        self._model = settings.mindlogic_claude_model
        self._max_output_tokens = settings.max_output_tokens
        self._credits_per_request = Decimal(str(settings.mindlogic_credits_per_request))
        self._endpoint = (
            settings.mindlogic_base_url.rstrip("/") + "/chat/completions/"
        )
        self._headers = {
            "Authorization": f"Bearer {settings.mindlogic_api_key}",
            "Content-Type": "application/json",
        }
        self._owns_client = client is None
        self._client = client or httpx.Client(
            timeout=settings.provider_timeout_seconds,
        )

    def generate(
        self,
        *,
        system_instruction: str,
        prompt: str,
        response_schema: dict[str, Any],
    ) -> ProviderResponse:
        try:
            response = self._client.post(
                self._endpoint,
                headers=self._headers,
                json={
                    "model": self._model,
                    "messages": [
                        {"role": "system", "content": system_instruction},
                        {"role": "user", "content": prompt},
                    ],
                    "temperature": 0,
                    "max_tokens": self._max_output_tokens,
                    "response_format": {
                        "type": "json_schema",
                        "json_schema": {
                            "name": _schema_name(response_schema),
                            "strict": True,
                            "schema": _strict_schema(response_schema),
                        },
                    },
                },
            )
            response.raise_for_status()
            body = response.json()
            choice = body["choices"][0]
            text = choice["message"]["content"]
            if not isinstance(text, str) or not text.strip():
                raise ValueError("Mindlogic 응답 본문이 비어 있습니다.")
            usage = body.get("usage") or {}
            credits = _usage_decimal(
                usage,
                "credits",
                "credits_used",
                "credit_usage",
                "total_credits",
                default=self._credits_per_request,
            )
            cost_usd = _usage_decimal(
                usage,
                "cost_usd",
                "costUsd",
                "total_cost",
                default=Decimal("0"),
            )
            return ProviderResponse(
                text=text,
                provider="mindlogic-claude",
                model=self._model,
                usage=ProviderUsage(
                    input_tokens=safe_int(usage.get("prompt_tokens"), 0) or 0,
                    output_tokens=safe_int(usage.get("completion_tokens"), 0) or 0,
                    cost_usd=cost_usd,
                    credits=credits,
                ),
                truncated=choice.get("finish_reason") == "length",
            )
        except AgentError:
            raise
        except Exception as exc:
            status_code = (
                exc.response.status_code
                if isinstance(exc, httpx.HTTPStatusError)
                else None
            )
            logger.warning(
                "Mindlogic provider 호출에 실패했습니다. model=%s status=%s errorType=%s",
                self._model,
                status_code,
                type(exc).__name__,
            )
            raise AgentError(
                status_code=503,
                code="PROVIDER_UNAVAILABLE",
                message="Mindlogic provider를 호출할 수 없습니다.",
            ) from exc

    def close(self) -> None:
        if self._owns_client:
            self._client.close()


def _strict_schema(value: Any) -> Any:
    """OpenAI 호환 strict 모드가 거부하는 검증 키워드는 서버 검증에 맡긴다."""
    if isinstance(value, dict):
        return {
            key: _strict_schema(child)
            for key, child in value.items()
            if key not in _UNSUPPORTED_STRICT_SCHEMA_KEYS
        }
    if isinstance(value, list):
        return [_strict_schema(child) for child in value]
    return value


def _schema_name(schema: dict[str, Any]) -> str:
    title = str(schema.get("title") or "structured_output")
    normalized = re.sub(r"[^a-zA-Z0-9_-]", "_", title)
    return normalized[:64] or "structured_output"


def _usage_decimal(
    usage: dict[str, Any],
    *keys: str,
    default: Decimal,
) -> Decimal:
    for key in keys:
        converted = safe_float(usage.get(key))
        if converted is not None and converted >= 0:
            return Decimal(str(converted))
    return default
