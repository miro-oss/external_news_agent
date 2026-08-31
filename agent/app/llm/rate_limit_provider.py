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
class ProviderRequestPolicy:
    request_interval_seconds: float
    rate_limit_retry_attempts: int = 2
    rate_limit_backoff_seconds: float = 4.0
    rate_limit_max_backoff_seconds: float = 10.0
    # provider가 직접 알려준 대기 시간에 적용하는 별도 상한. 추측한 backoff보다 크게 둔다.
    # Gemini 무료 티어는 RPM 초과 시 retryDelay로 20~60초를 돌려주는데, 이걸 backoff 상한으로
    # 깎으면 provider가 기다리라고 한 시간보다 먼저 다시 불러서 429를 한 번 더 받는다.
    rate_limit_max_wait_seconds: float = 60.0

    def __post_init__(self) -> None:
        if not math.isfinite(self.request_interval_seconds) or self.request_interval_seconds < 0:
            raise ValueError("provider 요청 간격은 0 이상이어야 합니다.")
        if (
            isinstance(self.rate_limit_retry_attempts, bool)
            or not isinstance(self.rate_limit_retry_attempts, int)
            or self.rate_limit_retry_attempts < 0
        ):
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
        if (
            not math.isfinite(self.rate_limit_max_wait_seconds)
            or self.rate_limit_max_wait_seconds < self.rate_limit_max_backoff_seconds
        ):
            raise ValueError("provider 지정 대기 상한은 최대 backoff보다 작을 수 없습니다.")

    def to_dict(self) -> dict[str, int | float]:
        return {
            "requestIntervalSeconds": self.request_interval_seconds,
            "rateLimitRetryAttempts": self.rate_limit_retry_attempts,
            "rateLimitBackoffSeconds": self.rate_limit_backoff_seconds,
            "rateLimitMaxBackoffSeconds": self.rate_limit_max_backoff_seconds,
            "rateLimitMaxWaitSeconds": self.rate_limit_max_wait_seconds,
        }


class ProviderRequestCoordinator:
    """분석·근거 검증·보고서가 공유하는 호출 간격과 429 대기 상태."""

    def __init__(
        self,
        policy: ProviderRequestPolicy,
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
        self._blocked_until = 0.0

    def wait_before_call(self) -> None:
        with self._lock:
            now = self._clock()
            next_call_at = self._blocked_until
            if self._last_call_started_at is not None:
                next_call_at = max(
                    next_call_at,
                    self._last_call_started_at + self.policy.request_interval_seconds,
                )
            remaining = next_call_at - now
            if remaining > 0:
                self._sleep(remaining)
                now = self._clock()
            self._last_call_started_at = now

    def wait_after_rate_limit(self, error: AgentError, retry_number: int) -> float:
        """429를 만나면 공유 대기 상태를 늘린다. 재시도 여부와 무관하게 항상 부른다.

        provider가 `retryDelay`로 대기 시간을 알려준 경우에는 그 값을 따른다. 이때는
        추측한 exponential backoff 상한이 아니라 `rate_limit_max_wait_seconds`로 자른다.
        둘을 같은 상한으로 묶으면 provider가 30초를 기다리라고 해도 10초 만에 다시 불러
        429를 한 번 더 받는다.
        """
        retry_after = _retry_after_seconds(error)
        if retry_after is not None:
            delay = min(retry_after, self.policy.rate_limit_max_wait_seconds)
        else:
            base = min(
                self.policy.rate_limit_backoff_seconds * (2 ** (max(retry_number, 1) - 1)),
                self.policy.rate_limit_max_backoff_seconds,
            )
            delay = min(
                base + self._jitter(min(1.0, base * 0.1)),
                self.policy.rate_limit_max_backoff_seconds,
            )
        with self._lock:
            self._blocked_until = max(self._blocked_until, self._clock() + delay)
        return delay


class PacedRetryProvider:
    """일반 provider 호출을 pacing하고 재시도 가능한 429만 제한적으로 재시도한다."""

    def __init__(
        self,
        delegate: AnalyzeProvider,
        coordinator: ProviderRequestCoordinator,
    ) -> None:
        self._delegate = delegate
        self._coordinator = coordinator

    @property
    def delegate(self) -> AnalyzeProvider:
        return self._delegate

    @property
    def coordinator(self) -> ProviderRequestCoordinator:
        return self._coordinator

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
                if not _is_rate_limited(error):
                    raise
                retry_number = attempt + 1
                # 일 quota 소진처럼 기다려도 소용없는 429는 대기를 늘리지 않는다.
                if not _is_retryable(error):
                    raise
                # 재시도를 포기하는 경우에도 공유 대기 상태는 늘린다. 이걸 빼면 이 호출만
                # 실패하고 뒤따르는 기사·검증 요청이 곧바로 같은 429를 다시 받는다.
                delay = self._coordinator.wait_after_rate_limit(error, retry_number)
                if attempt >= policy.rate_limit_retry_attempts:
                    logger.warning(
                        "Provider rate limit 재시도를 모두 소진했습니다. "
                        "이후 호출을 %.3f초 동안 함께 미룹니다.",
                        delay,
                    )
                    raise
                logger.warning(
                    "Provider rate limit 대기를 공유하고 재시도합니다. "
                    "retry=%d/%d delaySeconds=%.3f",
                    retry_number,
                    policy.rate_limit_retry_attempts,
                    delay,
                )
        raise RuntimeError("provider 재시도 상태가 올바르지 않습니다.")

    def close(self) -> None:
        close = getattr(self._delegate, "close", None)
        if callable(close):
            close()


def _is_rate_limited(error: AgentError) -> bool:
    return isinstance(error.details, dict) and error.details.get("rateLimited") is True


def _is_retryable(error: AgentError) -> bool:
    return not isinstance(error.details, dict) or error.details.get("retryable") is not False


def _retry_after_seconds(error: AgentError) -> float | None:
    if not isinstance(error.details, dict):
        return None
    value = error.details.get("retryAfterSeconds")
    if (
        isinstance(value, bool)
        or not isinstance(value, (int, float))
        or not math.isfinite(value)
        or value <= 0
    ):
        return None
    return float(value)
