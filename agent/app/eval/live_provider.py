from dataclasses import dataclass

from app.llm.rate_limit_provider import PacedRetryProvider as PacedRetryProvider
from app.llm.rate_limit_provider import (
    ProviderRequestCoordinator as LiveRequestCoordinator,
)
from app.llm.rate_limit_provider import ProviderRequestPolicy

__all__ = [
    "LiveProviderPolicy",
    "LiveRequestCoordinator",
    "PacedRetryProvider",
    "default_live_policy",
]


@dataclass(frozen=True, slots=True)
class LiveProviderPolicy(ProviderRequestPolicy):
    rate_limit_retry_attempts: int = 5
    rate_limit_backoff_seconds: float = 15.0
    rate_limit_max_backoff_seconds: float = 60.0


def default_live_policy(plan: str) -> LiveProviderPolicy:
    return LiveProviderPolicy(request_interval_seconds=30.0 if plan == "FREE" else 1.0)
