import json
from copy import deepcopy
from decimal import Decimal

import httpx2
import pytest
from openai import OpenAI
from pydantic import ValidationError

from app.core.config import Settings
from app.core.errors import AgentError
from app.llm.openai_provider import OpenAIAnalyzeProvider, _retry_after_header_seconds
from app.llm.rate_limit_provider import (
    PacedRetryProvider,
    ProviderRequestCoordinator,
    ProviderRequestPolicy,
)
from app.schemas.analyze import AnalyzeOutput, SelfCritiqueOutput
from app.schemas.evidence import EvidenceBatchOutput
from app.schemas.explore import ExploreProposal
from app.schemas.insight import InsightOutput
from app.schemas.keyword_strategy import KeywordStrategyOutput
from app.schemas.report import ReportOutput


def response_body(text='{"ok":true}', *, status="completed", reason=None, refusal=False):
    return {
        "id": "resp_test",
        "object": "response",
        "created_at": 1,
        "model": "gpt-4.1-nano-2025-04-14",
        "status": status,
        "incomplete_details": {"reason": reason} if reason else None,
        "output": [
            {
                "id": "msg_test",
                "type": "message",
                "role": "assistant",
                "status": status,
                "content": [{"type": "refusal", "refusal": "refused"}]
                if refusal
                else [{"type": "output_text", "text": text, "annotations": []}],
            }
        ],
        "usage": {
            "input_tokens": 1000,
            "input_tokens_details": {"cached_tokens": 200},
            "output_tokens": 100,
            "output_tokens_details": {"reasoning_tokens": 0},
            "total_tokens": 1100,
        },
    }


def client_for(handler):
    return OpenAI(
        api_key="test-key",
        max_retries=0,
        http_client=httpx2.Client(transport=httpx2.MockTransport(handler)),
    )


def generate(provider, schema=None):
    return provider.generate(
        system_instruction="Return JSON only.",
        prompt="기사 본문",
        response_schema=schema or {"type": "object", "properties": {"ok": {"type": "boolean"}}},
    )


@pytest.mark.parametrize(
    "output_model",
    [
        AnalyzeOutput,
        SelfCritiqueOutput,
        EvidenceBatchOutput,
        ExploreProposal,
        InsightOutput,
        KeywordStrategyOutput,
        ReportOutput,
    ],
)
def test_all_task_schemas_use_responses_strict_contract(output_model):
    requests = []

    def handler(request):
        requests.append(request)
        return httpx2.Response(200, json=response_body())

    schema = output_model.model_json_schema(by_alias=True)
    original = deepcopy(schema)
    with client_for(handler) as client:
        result = generate(OpenAIAnalyzeProvider(Settings(), client), schema)

    assert str(requests[0].url) == "https://api.openai.com/v1/responses"
    payload = json.loads(requests[0].content)
    assert payload["model"] == "gpt-4.1-nano"
    assert payload["instructions"] == "Return JSON only."
    assert payload["input"] == "기사 본문"
    assert payload["store"] is False
    assert payload["max_output_tokens"] == 4096
    assert payload["temperature"] == 0
    assert payload["text"]["format"]["type"] == "json_schema"
    assert payload["text"]["format"]["strict"] is True
    assert_strict(payload["text"]["format"]["schema"])
    assert schema == original
    assert result.provider == "openai"
    assert result.model == "gpt-4.1-nano"
    assert result.usage.input_tokens == 1000
    assert result.usage.output_tokens == 100
    assert result.usage.cost_usd == Decimal("0.000125")
    assert result.usage.credits == 0


def assert_strict(schema):
    if isinstance(schema, dict):
        assert "default" not in schema
        if schema.get("type") == "object":
            assert schema["additionalProperties"] is False
            assert set(schema["required"]) == set(schema["properties"])
        for key, value in schema.items():
            if key in {"properties", "$defs"}:
                for child in value.values():
                    assert_strict(child)
            else:
                assert_strict(value)
    elif isinstance(schema, list):
        for value in schema:
            assert_strict(value)


@pytest.mark.parametrize(
    ("model", "cost"),
    [
        ("gpt-4.1-nano-2025-04-14", "0.000125"),
        ("gpt-4o-mini", "0.000195"),
        ("gpt-4o-mini-2024-07-18", "0.000195"),
        ("unknown-model", "0"),
    ],
)
def test_model_specific_cost(model, cost):
    with client_for(lambda _: httpx2.Response(200, json=response_body())) as client:
        result = generate(OpenAIAnalyzeProvider(Settings(OPENAI_MODEL=model), client))
    assert result.model == model
    assert result.usage.cost_usd == Decimal(cost)


def test_custom_prices_and_task_limits():
    requests = []

    def handler(request):
        requests.append(json.loads(request.content))
        return httpx2.Response(200, json=response_body())

    settings = Settings(
        OPENAI_MODEL="configured-model",
        AGENT_MAX_OUTPUT_TOKENS=8192,
        OPENAI_INPUT_COST_PER_MILLION="1",
        OPENAI_CACHED_INPUT_COST_PER_MILLION="0.5",
        OPENAI_OUTPUT_COST_PER_MILLION="2",
    )
    with client_for(handler) as client:
        result = generate(OpenAIAnalyzeProvider(settings, client))
    assert result.usage.cost_usd == Decimal("0.0011")
    assert requests[0]["max_output_tokens"] == 8192


def test_requires_all_custom_prices():
    with pytest.raises(ValidationError, match="세 값을 모두"):
        Settings(OPENAI_INPUT_COST_PER_MILLION="1")


def test_owned_client_uses_timeout_disables_sdk_retries_and_closes():
    provider = OpenAIAnalyzeProvider(
        Settings(
            OPENAI_API_KEY="test-key",
            AGENT_PROVIDER_TIMEOUT_SECONDS=12,
        )
    )
    assert provider._client.timeout == 12
    assert provider._client.max_retries == 0
    provider.close()
    assert provider._client.is_closed()


def test_injected_client_remains_open():
    with client_for(lambda _: httpx2.Response(200, json=response_body())) as client:
        OpenAIAnalyzeProvider(Settings(), client).close()
        assert not client.is_closed()


@pytest.mark.parametrize("text", ['{"partial":', ""])
def test_token_limit_preserves_partial_text_and_usage_for_schema_repair(text):
    with client_for(
        lambda _: httpx2.Response(
            200, json=response_body(text, status="incomplete", reason="max_output_tokens")
        )
    ) as client:
        result = generate(OpenAIAnalyzeProvider(Settings(), client))
    assert result.text == text
    assert result.truncated is True
    assert result.usage.output_tokens == 100


@pytest.mark.parametrize(
    "body",
    [
        response_body(refusal=True),
        response_body(text=""),
        response_body(status="incomplete", reason="content_filter"),
        response_body(status="failed"),
    ],
)
def test_unusable_output_is_not_accepted_and_keeps_usage(body):
    with client_for(lambda _: httpx2.Response(200, json=body)) as client:
        with pytest.raises(AgentError) as error:
            generate(OpenAIAnalyzeProvider(Settings(), client))
    assert error.value.code == "PROVIDER_UNAVAILABLE"
    assert error.value.details["retryable"] is False
    assert error.value.details["usage"]["outputTokens"] == 100


@pytest.mark.parametrize(
    ("status", "code", "retryable", "calls"),
    [
        (429, "rate_limit_exceeded", True, 1),
        (429, "insufficient_quota", False, 1),
        (401, "invalid_api_key", False, 1),
        (400, "invalid_json_schema", False, 1),
        (503, "server_error", True, 2),
    ],
)
def test_errors_are_sanitized_and_retries_are_bounded(status, code, retryable, calls, caplog):
    requests = []

    def handler(request):
        requests.append(request)
        return httpx2.Response(
            status,
            headers={"retry-after": "3.5"},
            json={
                "error": {"code": code, "message": "private-upstream-text"},
            },
        )

    with client_for(handler) as client:
        with pytest.raises(AgentError) as error:
            generate(OpenAIAnalyzeProvider(Settings(), client))
    assert len(requests) == calls
    assert error.value.status_code == 503
    assert error.value.details["rateLimited"] is (status == 429)
    assert error.value.details["retryable"] is retryable
    assert error.value.details["retryAfterSeconds"] == 3.5
    assert "private-upstream-text" not in str(error.value.details) + caplog.text


def test_shared_rate_limit_policy_retries_openai_429_then_succeeds():
    requests = []

    def handler(request):
        requests.append(request)
        if len(requests) == 1:
            return httpx2.Response(
                429,
                headers={"retry-after": "2"},
                json={
                    "error": {"code": "rate_limit_exceeded"},
                },
            )
        return httpx2.Response(200, json=response_body())

    waits = []
    coordinator = ProviderRequestCoordinator(
        ProviderRequestPolicy(request_interval_seconds=0),
        clock=lambda: 0,
        sleeper=waits.append,
    )
    with client_for(handler) as client:
        result = generate(
            PacedRetryProvider(OpenAIAnalyzeProvider(Settings(), client), coordinator)
        )
    assert result.provider == "openai"
    assert len(requests) == 2
    assert waits == [2]


def test_quota_exhaustion_is_not_retried_by_shared_policy():
    requests = []

    def handler(request):
        requests.append(request)
        return httpx2.Response(429, json={"error": {"code": "insufficient_quota"}})

    coordinator = ProviderRequestCoordinator(ProviderRequestPolicy(request_interval_seconds=0))
    with client_for(handler) as client:
        with pytest.raises(AgentError):
            generate(PacedRetryProvider(OpenAIAnalyzeProvider(Settings(), client), coordinator))
    assert len(requests) == 1


@pytest.mark.parametrize("header", [None, "", "nan", "inf", "-1", "invalid"])
def test_ignores_invalid_retry_after(header):
    assert _retry_after_header_seconds(header) is None


def test_retry_after_supports_http_date():
    assert _retry_after_header_seconds("Wed, 21 Oct 2015 07:28:00 GMT") == 0
