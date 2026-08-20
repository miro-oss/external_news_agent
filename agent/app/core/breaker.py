from __future__ import annotations

from collections.abc import Callable
from enum import StrEnum
from threading import Lock
from time import monotonic

from app.core.errors import AgentError


class CircuitState(StrEnum):
    CLOSED = "closed"
    OPEN = "open"
    HALF_OPEN = "half-open"


class CircuitBreaker:
    """Provider 장애를 빠르게 차단하는 프로세스 내부 회로 차단기."""

    def __init__(
        self,
        failure_threshold: int,
        cooldown_seconds: float,
        *,
        clock: Callable[[], float] = monotonic,
    ) -> None:
        self._failure_threshold = failure_threshold
        self._cooldown_seconds = cooldown_seconds
        self._clock = clock
        self._lock = Lock()
        self._state = CircuitState.CLOSED
        self._failure_count = 0
        self._opened_at = 0.0
        self._half_open_in_flight = False

    @property
    def state(self) -> CircuitState:
        with self._lock:
            self._refresh_state()
            return self._state

    def before_call(self) -> None:
        with self._lock:
            self._refresh_state()
            if self._state is CircuitState.OPEN:
                raise _circuit_open_error()
            if self._state is CircuitState.HALF_OPEN:
                if self._half_open_in_flight:
                    raise _circuit_open_error()
                self._half_open_in_flight = True

    def cancel_call(self) -> None:
        """half-open probe가 provider 호출 전 취소됐을 때 다음 probe를 허용한다."""
        with self._lock:
            if self._state is CircuitState.HALF_OPEN:
                self._half_open_in_flight = False

    def record_success(self) -> None:
        with self._lock:
            self._state = CircuitState.CLOSED
            self._failure_count = 0
            self._opened_at = 0.0
            self._half_open_in_flight = False

    def record_failure(self) -> None:
        with self._lock:
            self._half_open_in_flight = False
            self._failure_count += 1
            if (
                self._state is CircuitState.HALF_OPEN
                or self._failure_count >= self._failure_threshold
            ):
                self._state = CircuitState.OPEN
                self._opened_at = self._clock()

    def _refresh_state(self) -> None:
        if (
            self._state is CircuitState.OPEN
            and self._clock() - self._opened_at >= self._cooldown_seconds
        ):
            self._state = CircuitState.HALF_OPEN
            self._half_open_in_flight = False


def _circuit_open_error() -> AgentError:
    return AgentError(
        status_code=503,
        code="PROVIDER_UNAVAILABLE",
        message="Provider circuit breaker가 열려 있습니다.",
        details={"circuitOpen": True},
    )
