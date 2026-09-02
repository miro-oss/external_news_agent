"""P2-4 adapters used to verify PydanticAI against the Mindlogic contract."""

from decimal import Decimal
from typing import Any

import httpx2
from openai.types import chat
from pydantic_ai.messages import ModelResponse
from pydantic_ai.models.openai import OpenAIChatModel
from pydantic_ai.profiles.openai import OpenAIJsonSchemaTransformer, OpenAIModelProfile

from app.llm.mindlogic_provider import (
    MINDLOGIC_COST_USAGE_KEYS,
    MINDLOGIC_CREDIT_USAGE_KEYS,
    parse_usage_decimal,
    sanitize_mindlogic_strict_schema,
)

_PROVIDER_USAGE_PREFIX = "mindlogic_usage_"
_PROVIDER_CREDIT_KEYS = tuple(
    f"{_PROVIDER_USAGE_PREFIX}{key}" for key in MINDLOGIC_CREDIT_USAGE_KEYS
)
_PROVIDER_COST_KEYS = tuple(
    f"{_PROVIDER_USAGE_PREFIX}{key}" for key in MINDLOGIC_COST_USAGE_KEYS
)


class MindlogicStrictJsonSchemaTransformer(OpenAIJsonSchemaTransformer):
    """Apply OpenAI strict rules plus the gateway's narrower schema subset."""

    def transform(self, schema: dict[str, Any]) -> dict[str, Any]:
        # Remove gateway-unsupported constraints before the parent can copy them into
        # descriptions as prompt hints. The production provider drops them outright.
        return super().transform(sanitize_mindlogic_strict_schema(schema))


class MindlogicUsageOpenAIChatModel(OpenAIChatModel):
    """Preserve non-standard usage values on PydanticAI's non-streaming path."""

    def _process_provider_details(self, response: chat.ChatCompletion) -> dict[str, Any] | None:
        details = super()._process_provider_details(response) or {}
        response_usage = response.usage
        if response_usage is not None:
            for key in (*MINDLOGIC_CREDIT_USAGE_KEYS, *MINDLOGIC_COST_USAGE_KEYS):
                raw = getattr(response_usage, key, None)
                if raw is not None and not isinstance(raw, bool):
                    details[f"{_PROVIDER_USAGE_PREFIX}{key}"] = str(raw)
        return details or None


async def preserve_mindlogic_trailing_slash(request: httpx2.Request) -> None:
    """Keep the established `/chat/completions/` endpoint on OpenAI SDK requests."""
    if request.url.path.endswith("/chat/completions"):
        request.url = request.url.copy_with(path=f"{request.url.path}/")


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
    return parse_usage_decimal(
        details,
        *_PROVIDER_CREDIT_KEYS,
        default=default,
    )


def extract_mindlogic_cost_usd(
    response: ModelResponse,
    *,
    default: Decimal = Decimal("0"),
) -> Decimal:
    """Convert preserved cost metadata into the existing Decimal usage type."""
    details = response.provider_details or {}
    return parse_usage_decimal(
        details,
        *_PROVIDER_COST_KEYS,
        default=default,
    )


def is_mindlogic_truncated(response: ModelResponse) -> bool:
    """Map PydanticAI's normalized finish reason to the provider contract."""
    return response.finish_reason == "length"
