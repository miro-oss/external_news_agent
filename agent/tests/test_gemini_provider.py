from types import SimpleNamespace

from app.core.config import Settings
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
        response_schema={"type": "object", "additionalProperties": False},
    )

    assert models.model == "configured-gemini"
    assert models.config.response_mime_type == "application/json"
    assert models.config.response_json_schema["additionalProperties"] is False
    assert response.usage.input_tokens == 12
    assert response.usage.output_tokens == 4
