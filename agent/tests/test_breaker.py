import pytest

from app.core.breaker import CircuitBreaker, CircuitState
from app.core.errors import AgentError


def test_opens_half_opens_and_recovers() -> None:
    now = [10.0]
    breaker = CircuitBreaker(2, 5.0, clock=lambda: now[0])

    breaker.before_call()
    breaker.record_failure()
    breaker.before_call()
    breaker.record_failure()

    assert breaker.state is CircuitState.OPEN
    with pytest.raises(AgentError) as open_error:
        breaker.before_call()
    assert open_error.value.code == "PROVIDER_UNAVAILABLE"

    now[0] = 15.0
    assert breaker.state is CircuitState.HALF_OPEN
    breaker.before_call()
    with pytest.raises(AgentError):
        breaker.before_call()

    breaker.record_success()
    assert breaker.state is CircuitState.CLOSED


def test_failed_half_open_probe_reopens_for_full_cooldown() -> None:
    now = [0.0]
    breaker = CircuitBreaker(1, 10.0, clock=lambda: now[0])

    breaker.before_call()
    breaker.record_failure()
    now[0] = 10.0
    breaker.before_call()
    breaker.record_failure()

    now[0] = 19.9
    assert breaker.state is CircuitState.OPEN
    now[0] = 20.0
    assert breaker.state is CircuitState.HALF_OPEN


def test_rejected_half_open_probe_reopens_without_clearing_failure_history() -> None:
    now = [0.0]
    breaker = CircuitBreaker(2, 10.0, clock=lambda: now[0])

    breaker.before_call()
    breaker.record_failure()
    breaker.before_call()
    breaker.record_rejected()
    breaker.before_call()
    breaker.record_failure()

    assert breaker.state is CircuitState.OPEN
    now[0] = 10.0
    breaker.before_call()
    breaker.record_rejected()
    assert breaker.state is CircuitState.OPEN
