from types import SimpleNamespace

from google.genai import errors

from app.core.config import Settings
from app.llm import gemini_provider
from app.llm.gemini_provider import GeminiAnalyzeProvider


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
            "additionalProperties": False,
            "properties": {"name": {"type": "string", "minLength": 1}},
        },
    )

    assert models.model == "configured-gemini"
    assert models.config.response_mime_type == "application/json"
    assert models.config.response_json_schema["additionalProperties"] is False
    assert "minLength" not in models.config.response_json_schema["properties"]["name"]
    assert response.usage.input_tokens == 12
    assert response.usage.output_tokens == 4


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
