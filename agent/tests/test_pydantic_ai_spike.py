import asyncio
import json
from decimal import Decimal

import httpx2
import pytest
from pydantic import BaseModel, ConfigDict, Field
from pydantic_ai import Agent, NativeOutput, UnexpectedModelBehavior
from pydantic_ai.models.openai import OpenAIChatModelSettings
from pydantic_ai.providers.openai import OpenAIProvider

from app.llm.mindlogic_provider import MINDLOGIC_UNSUPPORTED_STRICT_SCHEMA_KEYS
from spikes.pydantic_ai_spike import (
    MindlogicUsageOpenAIChatModel,
    extract_mindlogic_cost_usd,
    extract_mindlogic_credits,
    is_mindlogic_truncated,
    mindlogic_model_profile,
    preserve_mindlogic_trailing_slash,
)


class NestedOutput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    label: str = Field(
        min_length=2,
        max_length=10,
        pattern=r"^[a-z]+$",
        description="Existing nested description",
    )
    values: list[int] = Field(min_length=1, max_length=2)


class SpikeOutput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str = Field(min_length=1, max_length=20, pattern=r"^[a-z]+$")
    items: list[int] = Field(min_length=1, max_length=3)
    score: int = Field(ge=0, le=100)
    nested: NestedOutput


_VALID_OUTPUT = (
    '{"name":"valid","items":[1],"score":80,'
    '"nested":{"label":"nested","values":[2]}}'
)


def _completion(
    content: str,
    *,
    usage_extra: dict[str, object] | None = None,
    finish_reason: str = "stop",
) -> dict:
    usage = {
        "prompt_tokens": 12,
        "completion_tokens": 4,
        "total_tokens": 16,
    }
    usage.update(usage_extra or {"credits": "1.750000000000000000123"})
    return {
        "id": "chatcmpl-spike",
        "object": "chat.completion",
        "created": 1_788_316_800,
        "model": "configured-claude",
        "choices": [
            {
                "index": 0,
                "message": {"role": "assistant", "content": content},
                "finish_reason": finish_reason,
            }
        ],
        "usage": usage,
    }


def _agent(client: httpx2.AsyncClient, *, retries: int = 0) -> Agent[None, SpikeOutput]:
    provider = OpenAIProvider(
        base_url="https://gateway.test/v1/gateway",
        api_key="spike-key",
        http_client=client,
    )
    model = MindlogicUsageOpenAIChatModel(
        "configured-claude",
        provider=provider,
        profile=mindlogic_model_profile(),
    )
    return Agent(
        model,
        output_type=NativeOutput(SpikeOutput, strict=True),
        model_settings=OpenAIChatModelSettings(
            max_tokens=256,
            temperature=0,
        ),
        retries=retries,
    )


def _contains_key(value: object, target: str) -> bool:
    if isinstance(value, dict):
        return target in value or any(_contains_key(child, target) for child in value.values())
    if isinstance(value, list):
        return any(_contains_key(child, target) for child in value)
    return False


def test_preserves_trailing_slash_and_gateway_strict_schema() -> None:
    captured: dict[str, object] = {}

    async def handler(request: httpx2.Request) -> httpx2.Response:
        captured["url"] = str(request.url)
        captured["body"] = json.loads(request.content)
        return httpx2.Response(
            200,
            json=_completion(_VALID_OUTPUT),
            request=request,
        )

    async def run() -> None:
        async with httpx2.AsyncClient(
            transport=httpx2.MockTransport(handler),
            event_hooks={"request": [preserve_mindlogic_trailing_slash]},
        ) as client:
            result = await _agent(client).run("Return the fixture output.")
            assert result.output.name == "valid"

    asyncio.run(run())

    assert captured["url"] == "https://gateway.test/v1/gateway/chat/completions/"
    body = captured["body"]
    assert isinstance(body, dict)
    assert body["max_tokens"] == 256
    assert "max_completion_tokens" not in body
    response_format = body["response_format"]
    assert response_format["type"] == "json_schema"
    assert response_format["json_schema"]["strict"] is True

    schema = response_format["json_schema"]["schema"]
    assert schema["additionalProperties"] is False
    assert set(schema["required"]) == {"name", "items", "score", "nested"}
    nested_schema = schema["$defs"]["NestedOutput"]
    assert nested_schema["additionalProperties"] is False
    assert set(nested_schema["required"]) == {"label", "values"}
    assert nested_schema["properties"]["label"]["description"] == (
        "Existing nested description"
    )
    for unsupported in MINDLOGIC_UNSUPPORTED_STRICT_SCHEMA_KEYS:
        assert not _contains_key(schema, unsupported)


@pytest.mark.parametrize(
    ("raw_credits", "expected"),
    [
        ("1.750000000000000000123", Decimal("1.750000000000000000123")),
        (1.750000000000000000123, Decimal("1.75")),
    ],
    ids=("json-string-keeps-decimal", "json-number-is-float-limited"),
)
def test_extracts_credits_with_documented_json_precision(
    raw_credits: object,
    expected: Decimal,
) -> None:
    async def handler(request: httpx2.Request) -> httpx2.Response:
        return httpx2.Response(
            200,
            json=_completion(_VALID_OUTPUT, usage_extra={"credits": raw_credits}),
            request=request,
        )

    async def run() -> Decimal:
        async with httpx2.AsyncClient(
            transport=httpx2.MockTransport(handler),
            event_hooks={"request": [preserve_mindlogic_trailing_slash]},
        ) as client:
            result = await _agent(client).run("Return the fixture output.")
            assert "credits" not in result.usage.details
            return extract_mindlogic_credits(result.response, default=Decimal("1"))

    assert asyncio.run(run()) == expected


def test_preserves_usage_fallback_cost_and_truncation() -> None:
    async def handler(request: httpx2.Request) -> httpx2.Response:
        return httpx2.Response(
            200,
            json=_completion(
                _VALID_OUTPUT,
                usage_extra={
                    "credits": "invalid",
                    "total_credits": "2.500000000000000000123",
                    "cost_usd": -1,
                    "total_cost": "0.025000000000000000456",
                },
                finish_reason="length",
            ),
            request=request,
        )

    async def run() -> tuple[Decimal, Decimal, bool]:
        async with httpx2.AsyncClient(
            transport=httpx2.MockTransport(handler),
            event_hooks={"request": [preserve_mindlogic_trailing_slash]},
        ) as client:
            result = await _agent(client).run("Return the fixture output.")
            return (
                extract_mindlogic_credits(result.response, default=Decimal("99")),
                extract_mindlogic_cost_usd(result.response),
                is_mindlogic_truncated(result.response),
            )

    credits, cost_usd, truncated = asyncio.run(run())
    assert credits == Decimal("2.500000000000000000123")
    assert cost_usd == Decimal("0.025000000000000000456")
    assert truncated is True


@pytest.mark.parametrize(("retries", "expected_calls"), [(0, 1), (2, 3)])
def test_pydantic_ai_output_retry_control(retries: int, expected_calls: int) -> None:
    calls = 0

    async def handler(request: httpx2.Request) -> httpx2.Response:
        nonlocal calls
        calls += 1
        return httpx2.Response(
            200,
            json=_completion("not-json"),
            request=request,
        )

    async def run() -> None:
        async with httpx2.AsyncClient(
            transport=httpx2.MockTransport(handler),
            event_hooks={"request": [preserve_mindlogic_trailing_slash]},
        ) as client:
            with pytest.raises(UnexpectedModelBehavior):
                await _agent(client, retries=retries).run("Return invalid output.")

    asyncio.run(run())
    assert calls == expected_calls
