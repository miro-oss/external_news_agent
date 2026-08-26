import pytest

from app.core.config import Settings
from app.core.errors import AgentError
from app.llm.gemini_provider import GeminiAnalyzeProvider
from app.llm.guarded_provider import GuardedAnalyzeProvider
from app.llm.mindlogic_provider import MindlogicAnalyzeProvider
from app.llm.rate_limit_provider import PacedRetryProvider
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

        assert isinstance(free, PacedRetryProvider)
        assert isinstance(paid, PacedRetryProvider)
        assert isinstance(free.delegate, GuardedAnalyzeProvider)
        assert isinstance(paid.delegate, GuardedAnalyzeProvider)
        assert isinstance(free.delegate.delegate, GeminiAnalyzeProvider)
        assert isinstance(paid.delegate.delegate, MindlogicAnalyzeProvider)
        assert free.coordinator.policy.request_interval_seconds == 2.0
        assert paid.coordinator.policy.request_interval_seconds == 0.0
        assert get_analyze_provider(settings, "FREE") is free
        assert get_analyze_provider(settings, "PAID") is paid
    finally:
        close_analyze_providers()


def test_analyze_and_report_settings_share_plan_guard() -> None:
    settings = Settings(
        GEMINI_API_KEY="gemini-key",
        GEMINI_MODEL="gemini-model",
    )
    report_settings = settings.model_copy(
        update={
            "max_output_tokens": settings.report_max_output_tokens,
            "provider_timeout_seconds": settings.report_provider_timeout_seconds,
        }
    )

    try:
        analyze = get_analyze_provider(settings, "FREE")
        report = get_analyze_provider(report_settings, "FREE")

        assert isinstance(analyze, PacedRetryProvider)
        assert isinstance(report, PacedRetryProvider)
        assert analyze is not report
        assert isinstance(analyze.delegate, GuardedAnalyzeProvider)
        assert isinstance(report.delegate, GuardedAnalyzeProvider)
        assert analyze.delegate.guard is report.delegate.guard
        assert analyze.coordinator is report.coordinator
    finally:
        close_analyze_providers()
