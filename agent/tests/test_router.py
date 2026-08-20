import pytest

from app.core.config import Settings
from app.core.errors import AgentError
from app.llm.gemini_provider import GeminiAnalyzeProvider
from app.llm.guarded_provider import GuardedAnalyzeProvider
from app.llm.mindlogic_provider import MindlogicAnalyzeProvider
from app.llm.router import close_analyze_providers, get_analyze_provider


def test_requires_configuration_for_selected_plan_only() -> None:
    with pytest.raises(AgentError) as free_error:
        get_analyze_provider(Settings(), "FREE")
    with pytest.raises(AgentError) as paid_error:
        get_analyze_provider(Settings(), "PAID")

    assert free_error.value.code == "API_KEY_MISSING"
    assert paid_error.value.code == "API_KEY_MISSING"


def test_routes_free_and_paid_to_configured_provider() -> None:
    settings = Settings(
        GEMINI_API_KEY="gemini-key",
        GEMINI_MODEL="gemini-model",
        MINDLOGIC_API_KEY="mindlogic-key",
        MINDLOGIC_CLAUDE_MODEL="claude-model",
    )

    try:
        free = get_analyze_provider(settings, "FREE")
        paid = get_analyze_provider(settings, "PAID")

        assert isinstance(free, GuardedAnalyzeProvider)
        assert isinstance(paid, GuardedAnalyzeProvider)
        assert isinstance(free.delegate, GeminiAnalyzeProvider)
        assert isinstance(paid.delegate, MindlogicAnalyzeProvider)
        assert get_analyze_provider(settings, "FREE") is free
        assert get_analyze_provider(settings, "PAID") is paid
    finally:
        close_analyze_providers()
