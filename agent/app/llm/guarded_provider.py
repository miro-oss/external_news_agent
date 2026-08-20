from decimal import Decimal
from threading import BoundedSemaphore

from app.core.breaker import CircuitBreaker
from app.core.errors import AgentError
from app.llm.base import AnalyzeProvider, ProviderResponse


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
    ) -> None:
        self.delegate = delegate
        self._semaphore = BoundedSemaphore(concurrency)
        self._acquire_timeout_seconds = acquire_timeout_seconds
        self._breaker = CircuitBreaker(failure_threshold, cooldown_seconds)
        self._hard_cap_credits = hard_cap_credits

    @property
    def circuit_breaker(self) -> CircuitBreaker:
        return self._breaker

    def generate(
        self,
        *,
        system_instruction: str,
        prompt: str,
        response_schema: dict[str, object],
    ) -> ProviderResponse:
        self._breaker.before_call()
        acquired = self._semaphore.acquire(timeout=self._acquire_timeout_seconds)
        if not acquired:
            self._breaker.cancel_call()
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
                self._breaker.record_failure()
            else:
                self._breaker.record_success()
            raise
        except Exception:
            self._breaker.record_failure()
            raise
        else:
            self._breaker.record_success()
            if response.usage.credits > self._hard_cap_credits:
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
                        "hardCapCredits": float(self._hard_cap_credits),
                    },
                )
            return response
        finally:
            self._semaphore.release()

    def close(self) -> None:
        close = getattr(self.delegate, "close", None)
        if callable(close):
            close()
