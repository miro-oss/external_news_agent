import logging
from decimal import Decimal
from typing import Any

import httpx

from app.core.config import Settings
from app.core.errors import AgentError
from app.core.safecast import safe_int
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
                            "name": "article_analysis",
                            "strict": True,
                            "schema": _strict_schema(response_schema),
                        },
                    },
                },
            )
            response.raise_for_status()
            body = response.json()
            text = body["choices"][0]["message"]["content"]
            if not isinstance(text, str) or not text.strip():
                raise ValueError("Mindlogic 응답 본문이 비어 있습니다.")
            usage = body.get("usage") or {}
            return ProviderResponse(
                text=text,
                provider="mindlogic-claude",
                model=self._model,
                usage=ProviderUsage(
                    input_tokens=safe_int(usage.get("prompt_tokens"), 0) or 0,
                    output_tokens=safe_int(usage.get("completion_tokens"), 0) or 0,
                    credits=Decimal("1"),
                ),
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
