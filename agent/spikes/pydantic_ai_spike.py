"""P2-4 adapters used to verify PydanticAI against the Mindlogic contract."""

from decimal import Decimal
from typing import Any

from openai.types import chat
from pydantic_ai.messages import ModelResponse
from pydantic_ai.models.openai import OpenAIChatModel
from pydantic_ai.profiles.openai import OpenAIJsonSchemaTransformer, OpenAIModelProfile

from app.llm.mindlogic_provider import (
    _UNSUPPORTED_STRICT_SCHEMA_KEYS,
    _usage_decimal,
)

_CREDIT_KEYS = ("credits", "credits_used", "credit_usage", "total_credits")
_PROVIDER_CREDITS_KEY = "mindlogic_credits"


class MindlogicStrictJsonSchemaTransformer(OpenAIJsonSchemaTransformer):
    """Apply OpenAI strict rules plus the gateway's narrower schema subset."""

    def transform(self, schema: dict[str, Any]) -> dict[str, Any]:
        transformed = super().transform(schema)
        for key in _UNSUPPORTED_STRICT_SCHEMA_KEYS:
            transformed.pop(key, None)
        return transformed


class MindlogicCreditsOpenAIChatModel(OpenAIChatModel):
    """Preserve the gateway's non-standard credit usage without decimal loss."""

    def _process_provider_details(self, response: chat.ChatCompletion) -> dict[str, Any] | None:
        details = super()._process_provider_details(response) or {}
        response_usage = response.usage
        if response_usage is not None:
            for key in _CREDIT_KEYS:
                raw = getattr(response_usage, key, None)
                if raw is not None and not isinstance(raw, bool):
                    details[_PROVIDER_CREDITS_KEY] = str(raw)
                    break
        return details or None


def mindlogic_model_profile() -> OpenAIModelProfile:
    """Return the PydanticAI profile matching the current gateway wire contract."""
    return OpenAIModelProfile(
        json_schema_transformer=MindlogicStrictJsonSchemaTransformer,
        supports_json_schema_output=True,
        openai_chat_supports_max_completion_tokens=False,
    )


def extract_mindlogic_credits(
    response: ModelResponse,
    *,
    default: Decimal,
) -> Decimal:
    """Convert preserved provider metadata into the existing Decimal budget type."""
    details = response.provider_details or {}
    return _usage_decimal(
        details,
        _PROVIDER_CREDITS_KEY,
        default=default,
    )
