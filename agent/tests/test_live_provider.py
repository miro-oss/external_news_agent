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


def test_zero_retry_after_uses_exponential_backoff() -> None:
    clock = FakeClock()
    policy = LiveProviderPolicy(
        request_interval_seconds=0,
        rate_limit_retry_attempts=1,
        rate_limit_backoff_seconds=10,
        rate_limit_max_backoff_seconds=60,
    )
    coordinator = LiveRequestCoordinator(
        policy,
        clock=clock,
        sleeper=clock.sleep,
        jitter=lambda _: 0,
    )
    delegate = SequenceProvider([lambda: rate_limit(0), response])

    PacedRetryProvider(delegate, coordinator).generate(
        system_instruction="system",
        prompt="prompt",
        response_schema={},
    )

    assert clock.sleeps == [10.0]


def test_does_not_retry_non_retryable_daily_rate_limit() -> None:
    clock = FakeClock()
    coordinator = LiveRequestCoordinator(
        LiveProviderPolicy(request_interval_seconds=0),
        clock=clock,
        sleeper=clock.sleep,
    )

    def daily_quota_exhausted() -> ProviderResponse:
        raise AgentError(
            status_code=503,
            code="PROVIDER_UNAVAILABLE",
            message="daily quota exhausted",
            details={
                "rateLimited": True,
                "retryable": False,
                "providerStatusCode": 429,
                "quotaViolations": [
                    {"quotaId": "GenerateRequestsPerDayPerProjectPerModel-FreeTier"}
                ],
            },
        )

    delegate = SequenceProvider([daily_quota_exhausted])

    with pytest.raises(AgentError) as error:
        PacedRetryProvider(delegate, coordinator).generate(
            system_instruction="system",
            prompt="prompt",
            response_schema={},
        )

    assert error.value.details["retryable"] is False
    assert delegate.call_count == 1
    assert clock.sleeps == []


@pytest.mark.parametrize("attempts", [True, 1.5])
def test_policy_rejects_non_integer_retry_attempts(attempts: object) -> None:
    with pytest.raises(ValueError, match="재시도 횟수"):
        LiveProviderPolicy(
            request_interval_seconds=0,
            rate_limit_retry_attempts=attempts,  # type: ignore[arg-type]
        )


def test_honours_provider_retry_delay_beyond_backoff_ceiling() -> None:
    """provider가 알려준 대기 시간을 exponential backoff 상한으로 깎지 않는다.

    Gemini 무료 티어는 RPM 초과 시 retryDelay로 30초 안팎을 돌려준다. 이걸 backoff 상한
    10초로 자르면 provider가 기다리라고 한 시간보다 먼저 다시 불러 429를 한 번 더 받는다.
    """
    clock = FakeClock()
    policy = LiveProviderPolicy(
        request_interval_seconds=0,
        rate_limit_retry_attempts=1,
        rate_limit_backoff_seconds=4,
        rate_limit_max_backoff_seconds=10,
    )
    coordinator = LiveRequestCoordinator(
        policy, clock=clock, sleeper=clock.sleep, jitter=lambda _: 0
    )
    delegate = SequenceProvider([lambda: rate_limit(30), response])

    PacedRetryProvider(delegate, coordinator).generate(
        system_instruction="system", prompt="prompt", response_schema={}
    )

    assert clock.sleeps == [30.0]


def test_caps_provider_retry_delay_at_max_wait() -> None:
    """provider가 비상식적으로 긴 대기를 요구해도 상한에서 멈춘다."""
    clock = FakeClock()
    policy = LiveProviderPolicy(
        request_interval_seconds=0,
        rate_limit_retry_attempts=1,
        rate_limit_backoff_seconds=4,
        rate_limit_max_backoff_seconds=10,
    )
    coordinator = LiveRequestCoordinator(
        policy, clock=clock, sleeper=clock.sleep, jitter=lambda _: 0
    )
    delegate = SequenceProvider([lambda: rate_limit(3600), response])

    PacedRetryProvider(delegate, coordinator).generate(
        system_instruction="system", prompt="prompt", response_schema={}
    )

    assert clock.sleeps == [60.0]


def test_blocks_following_calls_after_giving_up_on_rate_limit() -> None:
    """재시도를 포기해도 공유 대기 상태는 늘린다.

    이게 없으면 이 호출만 실패하고 뒤따르는 기사·검증 요청이 곧바로 같은 429를 다시 받는다.
    실측(run 3859)에서 30건 중 23건이 이렇게 연쇄로 무너졌다.
    """
    clock = FakeClock()
    policy = LiveProviderPolicy(
        request_interval_seconds=0,
        rate_limit_retry_attempts=0,
        rate_limit_backoff_seconds=4,
        rate_limit_max_backoff_seconds=10,
    )
    coordinator = LiveRequestCoordinator(
        policy, clock=clock, sleeper=clock.sleep, jitter=lambda _: 0
    )
    delegate = SequenceProvider([lambda: rate_limit(25), response])
    provider = PacedRetryProvider(delegate, coordinator)

    with pytest.raises(AgentError):
        provider.generate(system_instruction="system", prompt="first", response_schema={})

    assert clock.sleeps == []
    provider.generate(system_instruction="system", prompt="second", response_schema={})
    # 두 번째 호출이 첫 429가 남긴 대기 시간을 그대로 물려받는다.
    assert clock.sleeps == [25.0]


def test_daily_quota_exhaustion_does_not_block_following_calls() -> None:
    """기다려도 소용없는 일 quota 소진은 공유 대기를 늘리지 않는다."""
    clock = FakeClock()
    coordinator = LiveRequestCoordinator(
        LiveProviderPolicy(request_interval_seconds=0),
        clock=clock,
        sleeper=clock.sleep,
        jitter=lambda _: 0,
    )

    def daily_quota_exhausted() -> ProviderResponse:
        raise AgentError(
            status_code=503,
            code="PROVIDER_UNAVAILABLE",
            message="daily quota exhausted",
            details={
                "rateLimited": True,
                "retryable": False,
                "retryAfterSeconds": 45,
                "providerStatusCode": 429,
            },
        )

    delegate = SequenceProvider([daily_quota_exhausted, response])
    provider = PacedRetryProvider(delegate, coordinator)

    with pytest.raises(AgentError):
        provider.generate(system_instruction="system", prompt="first", response_schema={})
    provider.generate(system_instruction="system", prompt="second", response_schema={})

    assert clock.sleeps == []
