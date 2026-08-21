from decimal import Decimal
from threading import BoundedSemaphore

from app.core.breaker import CircuitBreaker
from app.core.errors import AgentError
from app.llm.base import AnalyzeProvider, ProviderResponse


class ProviderGuard:
    """같은 provider plan의 호출 경로가 공유하는 보호 상태."""

    def __init__(
        self,
        *,
        concurrency: int,
        acquire_timeout_seconds: float,
        failure_threshold: int,
        cooldown_seconds: float,
        hard_cap_credits: Decimal,
    ) -> None:
        self.semaphore = BoundedSemaphore(concurrency)
        self.acquire_timeout_seconds = acquire_timeout_seconds
        self.breaker = CircuitBreaker(failure_threshold, cooldown_seconds)
        self.hard_cap_credits = hard_cap_credits


class GuardedAnalyzeProvider:
    """모든 provider 호출에 circuit, concurrency, 요청별 hard cap을 적용한다."""

    def __init__(
        self,
        delegate: AnalyzeProvider,
        *,
        concurrency: int,
        acquire_timeout_seconds: float,
        failure_threshold: int,
        cooldown_seconds: float,
        hard_cap_credits: Decimal,
        guard: ProviderGuard | None = None,
    ) -> None:
        self.delegate = delegate
        self._guard = guard or ProviderGuard(
            concurrency=concurrency,
            acquire_timeout_seconds=acquire_timeout_seconds,
            failure_threshold=failure_threshold,
            cooldown_seconds=cooldown_seconds,
            hard_cap_credits=hard_cap_credits,
        )

    @property
    def circuit_breaker(self) -> CircuitBreaker:
        return self._guard.breaker

    @property
    def guard(self) -> ProviderGuard:
        return self._guard

    def generate(
        self,
        *,
        system_instruction: str,
        prompt: str,
        response_schema: dict[str, object],
    ) -> ProviderResponse:
        self._guard.breaker.before_call()
        acquired = self._guard.semaphore.acquire(
            timeout=self._guard.acquire_timeout_seconds
        )
        if not acquired:
            self._guard.breaker.cancel_call()
            raise AgentError(
                status_code=503,
                code="PROVIDER_UNAVAILABLE",
                message="Provider 동시 호출 한도에 도달했습니다.",
                details={"concurrencyLimited": True},
            )

        try:
            response = self.delegate.generate(
                system_instruction=system_instruction,
                prompt=prompt,
                response_schema=response_schema,
            )
        except AgentError as error:
            if error.code == "PROVIDER_UNAVAILABLE":
                self._guard.breaker.record_failure()
            else:
                self._guard.breaker.record_rejected()
            raise
        except Exception:
            self._guard.breaker.record_failure()
            raise
        else:
            self._guard.breaker.record_success()
            if response.usage.credits > self._guard.hard_cap_credits:
                raise AgentError(
                    status_code=429,
                    code="BUDGET_EXCEEDED",
                    message="Provider 응답 사용량이 요청당 hard cap을 초과했습니다.",
                    details={
                        "usage": {
                            "inputTokens": response.usage.input_tokens,
                            "outputTokens": response.usage.output_tokens,
                            "costUsd": float(response.usage.cost_usd),
                            "credits": float(response.usage.credits),
                        },
                        "hardCapCredits": float(self._guard.hard_cap_credits),
                    },
                )
            return response
        finally:
            self._guard.semaphore.release()

    def close(self) -> None:
        close = getattr(self.delegate, "close", None)
        if callable(close):
            close()
