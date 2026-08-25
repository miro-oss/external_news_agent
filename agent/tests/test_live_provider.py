from collections.abc import Callable

import pytest

from app.core.errors import AgentError
from app.eval.live_provider import (
    LiveProviderPolicy,
    LiveRequestCoordinator,
    PacedRetryProvider,
)
from app.llm.base import ProviderResponse, ProviderUsage


class FakeClock:
    def __init__(self) -> None:
        self.now = 0.0
        self.sleeps: list[float] = []

    def __call__(self) -> float:
        return self.now

    def sleep(self, seconds: float) -> None:
        self.sleeps.append(seconds)
        self.now += seconds


class SequenceProvider:
    def __init__(self, calls: list[Callable[[], ProviderResponse]]) -> None:
        self._calls = iter(calls)
        self.call_count = 0

    def generate(self, **_: object) -> ProviderResponse:
        self.call_count += 1
        return next(self._calls)()


def response() -> ProviderResponse:
    return ProviderResponse(
        text='{"ok":true}',
        provider="gemini",
        model="configured-gemini",
        usage=ProviderUsage(),
    )


def rate_limit(retry_after: float | None = None) -> ProviderResponse:
    details: dict[str, object] = {"rateLimited": True, "providerStatusCode": 429}
    if retry_after is not None:
        details["retryAfterSeconds"] = retry_after
    raise AgentError(
        status_code=503,
        code="PROVIDER_UNAVAILABLE",
        message="rate limited",
        details=details,
    )


def test_paces_calls_and_retries_rate_limit_after_provider_delay() -> None:
    clock = FakeClock()
    policy = LiveProviderPolicy(
        request_interval_seconds=5,
        rate_limit_retry_attempts=2,
        rate_limit_backoff_seconds=10,
        rate_limit_max_backoff_seconds=60,
    )
    coordinator = LiveRequestCoordinator(
        policy,
        clock=clock,
        sleeper=clock.sleep,
        jitter=lambda _: 0,
    )
    delegate = SequenceProvider([lambda: rate_limit(7), response, response])
    provider = PacedRetryProvider(delegate, coordinator)

    first = provider.generate(system_instruction="system", prompt="one", response_schema={})
    second = provider.generate(system_instruction="system", prompt="two", response_schema={})

    assert first.text == second.text == '{"ok":true}'
    assert delegate.call_count == 3
    assert clock.sleeps == [7.0, 5.0]


def test_does_not_retry_non_rate_limit_provider_error() -> None:
    clock = FakeClock()
    coordinator = LiveRequestCoordinator(
        LiveProviderPolicy(request_interval_seconds=0),
        clock=clock,
        sleeper=clock.sleep,
    )

    def rejected() -> ProviderResponse:
        raise AgentError(
            status_code=503,
            code="PROVIDER_UNAVAILABLE",
            message="forbidden",
            details={"rateLimited": False, "providerStatusCode": 403},
        )

    delegate = SequenceProvider([rejected])

    with pytest.raises(AgentError):
        PacedRetryProvider(delegate, coordinator).generate(
            system_instruction="system",
            prompt="prompt",
            response_schema={},
        )

    assert delegate.call_count == 1
    assert clock.sleeps == []
