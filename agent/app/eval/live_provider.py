import logging
import math
import random
from collections.abc import Callable
from dataclasses import dataclass
from threading import Lock
from time import monotonic, sleep

from app.core.errors import AgentError
from app.llm.base import AnalyzeProvider, ProviderResponse

logger = logging.getLogger(__name__)


@dataclass(frozen=True, slots=True)
class LiveProviderPolicy:
    request_interval_seconds: float
    rate_limit_retry_attempts: int = 5
    rate_limit_backoff_seconds: float = 15.0
    rate_limit_max_backoff_seconds: float = 60.0

    def __post_init__(self) -> None:
        if not math.isfinite(self.request_interval_seconds) or self.request_interval_seconds < 0:
            raise ValueError("live 요청 간격은 0 이상이어야 합니다.")
        if self.rate_limit_retry_attempts < 0:
            raise ValueError("rate limit 재시도 횟수는 0 이상이어야 합니다.")
        if (
            not math.isfinite(self.rate_limit_backoff_seconds)
            or self.rate_limit_backoff_seconds <= 0
        ):
            raise ValueError("rate limit backoff는 0보다 커야 합니다.")
        if (
            not math.isfinite(self.rate_limit_max_backoff_seconds)
            or self.rate_limit_max_backoff_seconds < self.rate_limit_backoff_seconds
        ):
            raise ValueError("최대 backoff는 기본 backoff보다 작을 수 없습니다.")

    def to_dict(self) -> dict[str, int | float]:
        return {
            "requestIntervalSeconds": self.request_interval_seconds,
            "rateLimitRetryAttempts": self.rate_limit_retry_attempts,
            "rateLimitBackoffSeconds": self.rate_limit_backoff_seconds,
            "rateLimitMaxBackoffSeconds": self.rate_limit_max_backoff_seconds,
        }


def default_live_policy(plan: str) -> LiveProviderPolicy:
    return LiveProviderPolicy(request_interval_seconds=13.0 if plan == "FREE" else 1.0)


class LiveRequestCoordinator:
    """분석과 보고서 provider가 공유하는 호출 간격 및 대기 상태."""

    def __init__(
        self,
        policy: LiveProviderPolicy,
        *,
        clock: Callable[[], float] = monotonic,
        sleeper: Callable[[float], None] = sleep,
        jitter: Callable[[float], float] | None = None,
    ) -> None:
        self.policy = policy
        self._clock = clock
        self._sleep = sleeper
        self._jitter = jitter or (lambda upper: random.uniform(0, upper))
        self._lock = Lock()
        self._last_call_started_at: float | None = None

    def wait_before_call(self) -> None:
        with self._lock:
            now = self._clock()
            if self._last_call_started_at is not None:
                remaining = (
                    self._last_call_started_at
                    + self.policy.request_interval_seconds
                    - now
                )
                if remaining > 0:
                    self._sleep(remaining)
                    now = self._clock()
            self._last_call_started_at = now

    def wait_after_rate_limit(self, error: AgentError, retry_number: int) -> float:
        retry_after = _retry_after_seconds(error)
        if retry_after is not None:
            delay = min(retry_after, self.policy.rate_limit_max_backoff_seconds)
        else:
            base = min(
                self.policy.rate_limit_backoff_seconds * (2 ** (retry_number - 1)),
                self.policy.rate_limit_max_backoff_seconds,
            )
            delay = min(
                base + self._jitter(min(1.0, base * 0.1)),
                self.policy.rate_limit_max_backoff_seconds,
            )
        self._sleep(delay)
        return delay


class PacedRetryProvider:
    """live eval 호출을 pacing하고 429만 제한적으로 재시도한다."""

    def __init__(
        self,
        delegate: AnalyzeProvider,
        coordinator: LiveRequestCoordinator,
    ) -> None:
        self._delegate = delegate
        self._coordinator = coordinator

    def generate(
        self,
        *,
        system_instruction: str,
        prompt: str,
        response_schema: dict[str, object],
    ) -> ProviderResponse:
        policy = self._coordinator.policy
        for attempt in range(policy.rate_limit_retry_attempts + 1):
            self._coordinator.wait_before_call()
            try:
                return self._delegate.generate(
                    system_instruction=system_instruction,
                    prompt=prompt,
                    response_schema=response_schema,
                )
            except AgentError as error:
                if not _is_rate_limited(error) or attempt >= policy.rate_limit_retry_attempts:
                    raise
                retry_number = attempt + 1
                delay = self._coordinator.wait_after_rate_limit(error, retry_number)
                logger.warning(
                    "Live eval rate limit으로 대기 후 재시도합니다. "
                    "retry=%d/%d delaySeconds=%.3f",
                    retry_number,
                    policy.rate_limit_retry_attempts,
                    delay,
                )
        raise RuntimeError("live provider 재시도 상태가 올바르지 않습니다.")

    def close(self) -> None:
        close = getattr(self._delegate, "close", None)
        if callable(close):
            close()


def _is_rate_limited(error: AgentError) -> bool:
    return isinstance(error.details, dict) and error.details.get("rateLimited") is True


def _retry_after_seconds(error: AgentError) -> float | None:
    if not isinstance(error.details, dict):
        return None
    value = error.details.get("retryAfterSeconds")
    if (
        isinstance(value, bool)
        or not isinstance(value, (int, float))
        or not math.isfinite(value)
        or value < 0
    ):
        return None
    return float(value)
