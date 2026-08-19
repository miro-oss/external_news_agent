from typing import Any

from google import genai
from google.genai import types

from app.core.config import Settings
from app.core.errors import AgentError
from app.core.safecast import safe_int
from app.llm.base import ProviderResponse, ProviderUsage


class GeminiAnalyzeProvider:
    def __init__(self, settings: Settings, client: Any | None = None) -> None:
        self._model = settings.gemini_model
        self._max_output_tokens = settings.max_output_tokens
        self._client = client or genai.Client(api_key=settings.gemini_api_key)

    def generate(
        self,
        *,
        system_instruction: str,
        prompt: str,
        response_schema: dict[str, Any],
    ) -> ProviderResponse:
        try:
            response = self._client.models.generate_content(
                model=self._model,
                contents=prompt,
                config=types.GenerateContentConfig(
                    system_instruction=system_instruction,
                    temperature=0,
                    max_output_tokens=self._max_output_tokens,
                    response_mime_type="application/json",
                    response_json_schema=response_schema,
                ),
            )
            text = response.text
            if not isinstance(text, str) or not text.strip():
                raise ValueError("Gemini 응답 본문이 비어 있습니다.")
            usage = getattr(response, "usage_metadata", None)
            return ProviderResponse(
                text=text,
                provider="gemini",
                model=self._model,
                usage=ProviderUsage(
                    input_tokens=safe_int(getattr(usage, "prompt_token_count", 0), 0) or 0,
                    output_tokens=safe_int(getattr(usage, "candidates_token_count", 0), 0) or 0,
                ),
            )
        except AgentError:
            raise
        except Exception as exc:
            raise AgentError(
                status_code=503,
                code="PROVIDER_UNAVAILABLE",
                message="Gemini provider를 호출할 수 없습니다.",
            ) from exc
