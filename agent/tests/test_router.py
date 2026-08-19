import pytest

from app.core.config import Settings
from app.core.errors import AgentError
from app.llm.gemini_provider import GeminiAnalyzeProvider
from app.llm.mindlogic_provider import MindlogicAnalyzeProvider
from app.llm.router import create_analyze_provider


def test_requires_configuration_for_selected_plan_only() -> None:
    with pytest.raises(AgentError) as free_error:
        create_analyze_provider(Settings(), "FREE")
    with pytest.raises(AgentError) as paid_error:
        create_analyze_provider(Settings(), "PAID")

    assert free_error.value.code == "API_KEY_MISSING"
    assert paid_error.value.code == "API_KEY_MISSING"


def test_routes_free_and_paid_to_configured_provider() -> None:
    settings = Settings(
        GEMINI_API_KEY="gemini-key",
        GEMINI_MODEL="gemini-model",
        MINDLOGIC_API_KEY="mindlogic-key",
        MINDLOGIC_CLAUDE_MODEL="claude-model",
    )

    assert isinstance(create_analyze_provider(settings, "FREE"), GeminiAnalyzeProvider)
    assert isinstance(create_analyze_provider(settings, "PAID"), MindlogicAnalyzeProvider)
