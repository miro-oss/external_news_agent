import asyncio
import json
from decimal import Decimal

import httpx2
import pytest
from pydantic import BaseModel, ConfigDict, Field
from pydantic_ai import Agent, NativeOutput, UnexpectedModelBehavior
from pydantic_ai.models.openai import OpenAIChatModelSettings
from pydantic_ai.providers.openai import OpenAIProvider

from spikes.pydantic_ai_spike import (
    MindlogicCreditsOpenAIChatModel,
    extract_mindlogic_credits,
    mindlogic_model_profile,
)


class SpikeOutput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str = Field(min_length=1, max_length=20, pattern=r"^[a-z]+$")
    items: list[int] = Field(min_length=1, max_length=3)
    score: int = Field(ge=0, le=100)


def _completion(content: str, *, credits: object = "1.750000000000000000123") -> dict:
    return {
        "id": "chatcmpl-spike",
        "object": "chat.completion",
        "created": 1_788_316_800,
        "model": "configured-claude",
        "choices": [
            {
                "index": 0,
                "message": {"role": "assistant", "content": content},
                "finish_reason": "stop",
            }
        ],
        "usage": {
            "prompt_tokens": 12,
            "completion_tokens": 4,
            "total_tokens": 16,
            "credits": credits,
        },
    }


def _agent(client: httpx2.AsyncClient, *, retries: int = 0) -> Agent[None, SpikeOutput]:
    provider = OpenAIProvider(
        base_url="https://gateway.test/v1/gateway",
        api_key="spike-key",
        http_client=client,
    )
    model = MindlogicCreditsOpenAIChatModel(
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


def test_uses_no_trailing_slash_and_gateway_strict_schema() -> None:
    captured: dict[str, object] = {}

    async def handler(request: httpx2.Request) -> httpx2.Response:
        captured["url"] = str(request.url)
        captured["body"] = json.loads(request.content)
        return httpx2.Response(
            200,
            json=_completion('{"name":"valid","items":[1],"score":80}'),
            request=request,
        )

    async def run() -> None:
        async with httpx2.AsyncClient(transport=httpx2.MockTransport(handler)) as client:
            result = await _agent(client).run("Return the fixture output.")
            assert result.output.name == "valid"

    asyncio.run(run())

    assert captured["url"] == "https://gateway.test/v1/gateway/chat/completions"
    body = captured["body"]
    assert isinstance(body, dict)
    assert body["max_tokens"] == 256
    assert "max_completion_tokens" not in body
    response_format = body["response_format"]
    assert response_format["type"] == "json_schema"
    assert response_format["json_schema"]["strict"] is True

    schema = response_format["json_schema"]["schema"]
    assert schema["additionalProperties"] is False
    assert set(schema["required"]) == {"name", "items", "score"}
    for unsupported in (
        "maxItems",
        "maxLength",
        "maximum",
        "minItems",
        "minLength",
        "minimum",
        "pattern",
    ):
        assert not _contains_key(schema, unsupported)


def test_extracts_fractional_credits_without_precision_loss() -> None:
    async def handler(request: httpx2.Request) -> httpx2.Response:
        return httpx2.Response(
            200,
            json=_completion('{"name":"valid","items":[1],"score":80}'),
            request=request,
        )

    async def run() -> Decimal:
        async with httpx2.AsyncClient(transport=httpx2.MockTransport(handler)) as client:
            result = await _agent(client).run("Return the fixture output.")
            assert "credits" not in result.usage.details
            return extract_mindlogic_credits(result.response, default=Decimal("1"))

    assert asyncio.run(run()) == Decimal("1.750000000000000000123")


def test_disables_pydantic_ai_output_retries() -> None:
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
        async with httpx2.AsyncClient(transport=httpx2.MockTransport(handler)) as client:
            with pytest.raises(UnexpectedModelBehavior):
                await _agent(client, retries=0).run("Return invalid output.")

    asyncio.run(run())
    assert calls == 1
