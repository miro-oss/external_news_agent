"""Report requests honor bounded 429 recovery inside their original deadline."""

from decimal import Decimal

import pytest

from app.core.breaker import CircuitState
from app.core.config import Settings
from app.core.errors import AgentError
from app.llm.base import ProviderResponse, ProviderUsage
from app.llm.guarded_provider import ProviderGuard
from app.llm.rate_limit_provider import ProviderRequestCoordinator, ProviderRequestPolicy
from app.llm.report_insight_pipeline import ReportInsightPipelineProvider


def limited(*, retryable=True, retry_after=4):
    return AgentError(
        503,
        "PROVIDER_UNAVAILABLE",
        "offline rate limit",
        {
            "providerStatusCode": 429,
            "rateLimited": True,
            "retryable": retryable,
            "retryAfterSeconds": retry_after,
        },
    )


def answer():
    return ProviderResponse(
        "{}", "openai", "offline", ProviderUsage(10, 5, Decimal("0.01"), Decimal(0))
    )


def harness(monkeypatch, outcomes, *, retries=2, timeout=180, on_sleep=None):
    clock, sleeps, created, closed = [0.0], [], [], []
    values = list(outcomes)
    monkeypatch.setattr("app.llm.report_insight_pipeline.monotonic", lambda: clock[0])

    def advance(seconds):
        sleeps.append(seconds)
        clock[0] += seconds
        if on_sleep is not None:
            on_sleep(pipeline)

    class Transport:
        def __init__(self, settings, *, request_deadline):
            created.append(
                (
                    settings.provider_timeout_seconds,
                    request_deadline,
                    settings.provider_retry_attempts,
                )
            )

        def generate(self, **kwargs):
            elapsed, value = values.pop(0)
            clock[0] += elapsed
            if isinstance(value, Exception):
                raise value
            return value

        def close(self):
            closed.append(True)

    guard = ProviderGuard(
        concurrency=1,
        acquire_timeout_seconds=1,
        failure_threshold=1,
        cooldown_seconds=30,
        hard_cap_credits=Decimal(5),
    )
    coordinator = ProviderRequestCoordinator(
        ProviderRequestPolicy(0, rate_limit_retry_attempts=retries),
        clock=lambda: clock[0],
        sleeper=advance,
        jitter=lambda _: 0,
    )
    monkeypatch.setattr("app.llm.report_insight_pipeline.OpenAIAnalyzeProvider", Transport)
    monkeypatch.setattr("app.llm.report_insight_pipeline.get_provider_guard", lambda *_: guard)
    monkeypatch.setattr(
        "app.llm.report_insight_pipeline.get_provider_coordinator", lambda *_: coordinator
    )
    settings = Settings(
        _env_file=None,
        AGENT_MOCK=False,
        OPENAI_API_KEY="offline-test-only",
        AGENT_REPORT_INSIGHT_TIMEOUT_SECONDS=timeout,
        AGENT_RATE_LIMIT_RETRY_ATTEMPTS=retries,
    )
    pipeline = ReportInsightPipelineProvider(settings, "FREE")
    return pipeline, guard, clock, sleeps, created, closed


def call(pipeline):
    return pipeline.generate(system_instruction="offline", prompt="offline", response_schema={})


def test_retryable_429_recovers_with_remaining_time_and_only_successful_response_usage(monkeypatch):
    expected = answer()
    pipeline, guard, clock, sleeps, created, closed = harness(
        monkeypatch, [(50, limited()), (10, expected)]
    )
    assert call(pipeline) is expected
    assert sleeps == [4]
    assert created == [(180, 180, 0), (126, 180, 0)]
    assert clock[0] == 64 and pipeline.deadline == 180
    assert pipeline.calls == 1 and pipeline.usage == expected.usage
    assert not pipeline.unknown_failure_usage
    assert pipeline._reserved_credits == 0
    assert len(closed) == 2 and guard.breaker.state is CircuitState.CLOSED


@pytest.mark.parametrize("retries", [0, 1, 2])
def test_configured_rate_limit_retry_ceiling_is_exact_and_never_retries_transport(
    monkeypatch, retries
):
    pipeline, guard, _, sleeps, created, closed = harness(
        monkeypatch, [(1, limited()) for _ in range(retries + 1)], retries=retries
    )
    with pytest.raises(AgentError) as caught:
        call(pipeline)
    assert caught.value.details["rateLimited"]
    assert len(created) == len(closed) == retries + 1
    assert sleeps == [4] * retries
    assert all(sdk_retries == 0 for _, _, sdk_retries in created)
    assert pipeline.calls == 0 and pipeline.usage == ProviderUsage()
    assert guard.breaker.state is CircuitState.CLOSED
    with pytest.raises(AgentError) as cancelled:
        call(pipeline)
    assert cancelled.value.details["requestNotStarted"]
    assert len(created) == retries + 1


@pytest.mark.parametrize(
    "error",
    [
        limited(retryable=False),
        AgentError(
            503,
            "PROVIDER_UNAVAILABLE",
            "offline timeout",
            {"providerStatusCode": 0, "rateLimited": False},
        ),
        AgentError(
            503,
            "PROVIDER_UNAVAILABLE",
            "offline server error",
            {"providerStatusCode": 500, "rateLimited": False, "retryable": True},
        ),
        AgentError(502, "SCHEMA_VIOLATION", "offline invalid output"),
    ],
)
def test_nonretryable_quota_timeout_server_and_schema_failures_are_not_retried(monkeypatch, error):
    pipeline, _, _, sleeps, created, closed = harness(monkeypatch, [(1, error)])
    with pytest.raises(AgentError) as caught:
        call(pipeline)
    assert caught.value is error
    assert sleeps == [] and len(created) == len(closed) == 1


def test_429_wait_beyond_original_deadline_does_not_sleep_or_issue_another_request(monkeypatch):
    pipeline, guard, clock, sleeps, created, closed = harness(
        monkeypatch, [(50, limited(retry_after=60))], timeout=90
    )
    with pytest.raises(AgentError) as caught:
        call(pipeline)
    assert caught.value.details["requestDeadlineExceeded"]
    assert caught.value.details["requestNotStarted"]
    assert sleeps == [] and clock[0] == 50
    assert len(created) == len(closed) == 1
    assert pipeline.usage == ProviderUsage() and not pipeline.unknown_failure_usage
    assert guard.breaker.state is CircuitState.CLOSED


def test_sibling_cancellation_during_rate_limit_wait_prevents_the_retry(monkeypatch):
    pipeline, guard, _, sleeps, created, closed = harness(
        monkeypatch, [(1, limited())], on_sleep=lambda current: current.cancel_pending_calls()
    )
    with pytest.raises(AgentError) as caught:
        call(pipeline)
    assert caught.value.details["pipelineCancelled"] and caught.value.details["requestNotStarted"]
    assert sleeps == [4] and len(created) == len(closed) == 1
    assert pipeline.usage == ProviderUsage() and not pipeline.unknown_failure_usage
    assert pipeline._reserved_credits == 0
    assert guard.breaker.state is CircuitState.CLOSED
