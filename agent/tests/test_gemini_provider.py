from datetime import UTC, datetime, timedelta
from email.utils import format_datetime
from types import SimpleNamespace

import pytest
from google.genai import errors

from app.core.config import Settings
from app.core.errors import AgentError
from app.llm import gemini_provider
from app.llm.gemini_provider import GeminiAnalyzeProvider, _retry_after_header_seconds


class FakeModels:
    def __init__(self) -> None:
        self.model: str | None = None
        self.config = None

    def generate_content(self, *, model: str, contents: str, config):
        self.model = model
        self.config = config
        assert contents == "prompt"
        return SimpleNamespace(
            text='{"ok":true}',
            usage_metadata=SimpleNamespace(
                prompt_token_count=12,
                candidates_token_count=4,
                thoughts_token_count=6,
            ),
        )


def test_uses_gemini_json_schema_contract() -> None:
    models = FakeModels()
    client = SimpleNamespace(models=models)
    settings = Settings(GEMINI_API_KEY="gemini-key", GEMINI_MODEL="configured-gemini")

    response = GeminiAnalyzeProvider(settings, client).generate(
        system_instruction="system",
        prompt="prompt",
        response_schema={
            "type": "object",
            "title": "AnalyzeOutput",
            "additionalProperties": False,
            "properties": {
                "title": {"type": "string", "title": "Title"},
                "name": {"type": "string", "minLength": 1, "title": "Name"},
                "attributedTo": {
                    "anyOf": [{"type": "string"}, {"type": "null"}],
                    "default": None,
                },
                "sections": {"type": "array", "items": {"type": "string"}, "maxItems": 16},
            },
        },
    )

    assert models.model == "configured-gemini"
    assert models.config.response_mime_type == "application/json"
    assert "title" not in models.config.response_json_schema
    assert models.config.response_json_schema["additionalProperties"] is False
    assert models.config.response_json_schema["properties"]["title"] == {
        "type": "string"
    }
    assert "minLength" not in models.config.response_json_schema["properties"]["name"]
    assert "title" not in models.config.response_json_schema["properties"]["name"]
    assert "default" not in models.config.response_json_schema["properties"]["attributedTo"]
    assert "maxItems" not in models.config.response_json_schema["properties"]["sections"]
    assert models.config.automatic_function_calling.disable is True
    assert models.config.temperature == 0
    assert response.usage.input_tokens == 12
    assert response.usage.output_tokens == 10


def test_configures_timeout_and_closes_only_owned_client(monkeypatch) -> None:
    captured: dict[str, object] = {}

    class FakeClient:
        def __init__(self) -> None:
            self.closed = False

        def close(self) -> None:
            self.closed = True

    client = FakeClient()

    def client_factory(**kwargs):
        captured.update(kwargs)
        return client

    monkeypatch.setattr(gemini_provider.genai, "Client", client_factory)
    settings = Settings(
        GEMINI_API_KEY="gemini-key",
        GEMINI_MODEL="configured-gemini",
        AGENT_PROVIDER_TIMEOUT_SECONDS=12.5,
    )

    provider = GeminiAnalyzeProvider(settings)
    provider.close()

    assert captured["http_options"].timeout == 12_500
    assert client.closed is True


def test_retries_transient_gemini_server_error_once() -> None:
    class FlakyModels(FakeModels):
        def __init__(self) -> None:
            super().__init__()
            self.calls = 0

        def generate_content(self, *, model: str, contents: str, config):
            self.calls += 1
            if self.calls == 1:
                raise errors.ServerError(503, {"error": {"message": "temporary"}})
            return super().generate_content(model=model, contents=contents, config=config)

    models = FlakyModels()
    client = SimpleNamespace(models=models)
    settings = Settings(
        GEMINI_API_KEY="gemini-key",
        GEMINI_MODEL="configured-gemini",
        AGENT_PROVIDER_RETRY_ATTEMPTS=1,
    )

    response = GeminiAnalyzeProvider(settings, client).generate(
        system_instruction="system",
        prompt="prompt",
        response_schema={"type": "object", "additionalProperties": False},
    )

    assert response.text == '{"ok":true}'
    assert models.calls == 2


def test_exposes_sanitized_rate_limit_details_without_api_response_body() -> None:
    class RateLimitedModels(FakeModels):
        def generate_content(self, *, model: str, contents: str, config):
            del model, contents, config
            response = SimpleNamespace(headers={})
            raise errors.ClientError(
                429,
                {
                    "error": {
                        "status": "RESOURCE_EXHAUSTED",
                        "message": "sensitive provider response must not be copied",
                        "details": [
                            {
                                "@type": "type.googleapis.com/google.rpc.QuotaFailure",
                                "violations": [
                                    {
                                        "quotaMetric": "generativelanguage.googleapis.com/requests",
                                        "quotaId": "GenerateRequestsPerDayPerProject-FreeTier",
                                        "quotaValue": "20",
                                    }
                                ],
                            },
                            {
                                "@type": "type.googleapis.com/google.rpc.RetryInfo",
                                "retryDelay": "7s",
                            },
                        ],
                    }
                },
                response,
            )

    settings = Settings(GEMINI_API_KEY="gemini-key", GEMINI_MODEL="configured-gemini")
    provider = GeminiAnalyzeProvider(settings, SimpleNamespace(models=RateLimitedModels()))

    with pytest.raises(AgentError) as error:
        provider.generate(
            system_instruction="system",
            prompt="prompt",
            response_schema={"type": "object"},
        )

    assert error.value.code == "PROVIDER_UNAVAILABLE"
    assert error.value.details == {
        "provider": "gemini",
        "providerStatusCode": 429,
        "providerStatus": "RESOURCE_EXHAUSTED",
        "rateLimited": True,
        "retryable": False,
        "retryAfterSeconds": 7.0,
        "quotaViolations": [
            {
                "quotaMetric": "generativelanguage.googleapis.com/requests",
                "quotaId": "GenerateRequestsPerDayPerProject-FreeTier",
                "quotaValue": "20",
            }
        ],
    }


def test_parses_http_date_retry_after_header() -> None:
    now = datetime(2026, 8, 25, 1, 0, 0, tzinfo=UTC)
    header = format_datetime(now + timedelta(seconds=90), usegmt=True)

    assert _retry_after_header_seconds(header, now=now) == 90.0
