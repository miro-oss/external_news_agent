import json
from decimal import Decimal

import httpx

from app.core.config import Settings
from app.llm.mindlogic_provider import MindlogicAnalyzeProvider


def test_uses_gateway_strict_json_schema_contract() -> None:
    captured: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["url"] = str(request.url)
        captured["authorization"] = request.headers["Authorization"]
        captured["body"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "choices": [{"message": {"content": '{"ok":true}'}}],
                "usage": {"prompt_tokens": 12, "completion_tokens": 4},
            },
        )

    client = httpx.Client(transport=httpx.MockTransport(handler))
    settings = Settings(
        MINDLOGIC_API_KEY="gateway-key",
        MINDLOGIC_CLAUDE_MODEL="configured-claude",
    )

    response = MindlogicAnalyzeProvider(settings, client).generate(
        system_instruction="system",
        prompt="prompt",
        response_schema={
            "type": "object",
            "additionalProperties": False,
            "properties": {
                "items": {
                    "type": "array",
                    "minItems": 1,
                    "items": {"type": "integer", "minimum": 1},
                },
                "name": {"type": "string", "minLength": 1},
            },
        },
    )

    body = captured["body"]
    assert isinstance(body, dict)
    assert captured["url"].endswith("/v1/gateway/chat/completions/")
    assert captured["authorization"] == "Bearer gateway-key"
    assert body["model"] == "configured-claude"
    assert body["response_format"]["json_schema"]["name"] == "structured_output"
    assert body["response_format"]["json_schema"]["strict"] is True
    schema = body["response_format"]["json_schema"]["schema"]
    assert schema["additionalProperties"] is False
    assert "minItems" not in schema["properties"]["items"]
    assert "minimum" not in schema["properties"]["items"]["items"]
    assert "minLength" not in schema["properties"]["name"]
    assert response.usage.input_tokens == 12
    assert response.usage.output_tokens == 4
    assert float(response.usage.credits) == 1

    MindlogicAnalyzeProvider(settings, client).close()
    assert client.is_closed is False
    client.close()


def test_closes_owned_http_client() -> None:
    settings = Settings(
        MINDLOGIC_API_KEY="gateway-key",
        MINDLOGIC_CLAUDE_MODEL="configured-claude",
    )
    provider = MindlogicAnalyzeProvider(settings)
    client = provider._client

    provider.close()

    assert client.is_closed is True


def test_uses_gateway_usage_metrics_when_present() -> None:
    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "choices": [{"message": {"content": '{"ok":true}'}}],
                "usage": {
                    "prompt_tokens": 8,
                    "completion_tokens": 2,
                    "credits": "invalid",
                    "credits_used": "1.750000000000000000123",
                    "cost_usd": -1,
                    "total_cost": "0.025000000000000000456",
                },
            },
        )

    client = httpx.Client(transport=httpx.MockTransport(handler))
    provider = MindlogicAnalyzeProvider(
        Settings(
            MINDLOGIC_API_KEY="gateway-key",
            MINDLOGIC_CLAUDE_MODEL="configured-claude",
            MINDLOGIC_CREDITS_PER_REQUEST=2,
        ),
        client,
    )

    response = provider.generate(
        system_instruction="system",
        prompt="prompt",
        response_schema={"type": "object"},
    )

    assert response.usage.credits == Decimal("1.750000000000000000123")
    assert response.usage.cost_usd == Decimal("0.025000000000000000456")
    client.close()
