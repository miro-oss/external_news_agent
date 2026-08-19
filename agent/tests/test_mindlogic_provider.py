import json

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
        response_schema={"type": "object", "additionalProperties": False},
    )

    body = captured["body"]
    assert isinstance(body, dict)
    assert captured["url"].endswith("/v1/gateway/chat/completions/")
    assert captured["authorization"] == "Bearer gateway-key"
    assert body["model"] == "configured-claude"
    assert body["response_format"]["json_schema"]["strict"] is True
    assert body["response_format"]["json_schema"]["schema"]["additionalProperties"] is False
    assert response.usage.input_tokens == 12
    assert response.usage.output_tokens == 4
    assert float(response.usage.credits) == 1
