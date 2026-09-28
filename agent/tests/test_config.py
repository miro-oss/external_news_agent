import pytest
from pydantic import ValidationError

from app.core.config import Settings


def test_relevance_model_default_is_independent_of_general_model(monkeypatch):
    monkeypatch.delenv("TOPIC_RELEVANCE_OPENAI_MODEL", raising=False)
    settings = Settings(OPENAI_MODEL="custom-analysis-model")

    assert settings.topic_relevance_openai_model == "gpt-5.6-terra"
    assert settings.openai_model == "custom-analysis-model"


@pytest.mark.parametrize("model", [
    "gpt-4.1-nano", "gpt-4.1-nano-2025-04-14",
    "gpt-4o-mini", "gpt-4o-mini-2024-07-18",
    "gpt-5-mini", "gpt-5-mini-2025-08-07",
    "gpt-5.6-terra",
])
def test_relevance_model_accepts_only_explicit_supported_models(model):
    settings = Settings(OPENAI_MODEL="custom-analysis-model", TOPIC_RELEVANCE_OPENAI_MODEL=model)

    assert settings.topic_relevance_openai_model == model
    assert settings.openai_model == "custom-analysis-model"


@pytest.mark.parametrize("model", [
    "", "gpt-5-mini-experimental", "gpt-5.6-terra-experimental", "gpt-5.2", "unknown",
])
def test_relevance_model_rejects_unknown_models_before_provider_construction(model):
    with pytest.raises(ValidationError, match="TOPIC_RELEVANCE_OPENAI_MODEL"):
        Settings(TOPIC_RELEVANCE_OPENAI_MODEL=model)
