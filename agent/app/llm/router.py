from decimal import Decimal
from threading import Lock

from app.core.config import Settings
from app.core.errors import AgentError
from app.llm.base import AnalyzeProvider
from app.llm.gemini_provider import GeminiAnalyzeProvider
from app.llm.guarded_provider import GuardedAnalyzeProvider, ProviderGuard
from app.llm.mindlogic_provider import MindlogicAnalyzeProvider
from app.llm.rate_limit_provider import (
    PacedRetryProvider,
    ProviderRequestCoordinator,
    ProviderRequestPolicy,
)
from app.schemas.analyze import Plan

_PROVIDER_CACHE: dict[tuple[Settings, Plan, bool], AnalyzeProvider] = {}
_GUARD_CACHE: dict[Plan, ProviderGuard] = {}
_COORDINATOR_CACHE: dict[Plan, ProviderRequestCoordinator] = {}
_PROVIDER_LOCK = Lock()


def get_analyze_provider(
    settings: Settings,
    plan: Plan,
    *,
    apply_request_policy: bool = True,
) -> AnalyzeProvider:
    if plan == "FREE":
        _require(settings.gemini_api_key, settings.gemini_model, provider="Gemini")
    else:
        _require(
            settings.mindlogic_api_key,
            settings.mindlogic_base_url,
            settings.mindlogic_claude_model,
            provider="Mindlogic",
        )

    key = (settings, plan, apply_request_policy)
    with _PROVIDER_LOCK:
        provider = _PROVIDER_CACHE.get(key)
        if provider is None:
            delegate = (
                GeminiAnalyzeProvider(settings)
                if plan == "FREE"
                else MindlogicAnalyzeProvider(settings)
            )
            guard = _provider_guard(settings, plan)
            guarded = GuardedAnalyzeProvider(
                delegate,
                concurrency=settings.provider_concurrency,
                acquire_timeout_seconds=settings.provider_acquire_timeout_seconds,
                failure_threshold=settings.circuit_failure_threshold,
                cooldown_seconds=settings.circuit_cooldown_seconds,
                hard_cap_credits=Decimal(str(settings.hard_cap_credits_per_request)),
                guard=guard,
            )
            if apply_request_policy:
                coordinator = _provider_coordinator(settings, plan)
                provider = PacedRetryProvider(guarded, coordinator)
            else:
                provider = guarded
            _PROVIDER_CACHE[key] = provider
        return provider


def get_provider_guard(settings: Settings, plan: Plan) -> ProviderGuard:
    with _PROVIDER_LOCK:
        return _provider_guard(settings, plan)


def get_provider_coordinator(
    settings: Settings,
    plan: Plan,
) -> ProviderRequestCoordinator:
    with _PROVIDER_LOCK:
        return _provider_coordinator(settings, plan)


def _provider_guard(settings: Settings, plan: Plan) -> ProviderGuard:
    guard = _GUARD_CACHE.get(plan)
    if guard is None:
        guard = ProviderGuard(
            concurrency=settings.provider_concurrency,
            acquire_timeout_seconds=settings.provider_acquire_timeout_seconds,
            failure_threshold=settings.circuit_failure_threshold,
            cooldown_seconds=settings.circuit_cooldown_seconds,
            hard_cap_credits=Decimal(str(settings.hard_cap_credits_per_request)),
        )
        _GUARD_CACHE[plan] = guard
    return guard


def _provider_coordinator(
    settings: Settings,
    plan: Plan,
) -> ProviderRequestCoordinator:
    coordinator = _COORDINATOR_CACHE.get(plan)
    if coordinator is None:
        coordinator = ProviderRequestCoordinator(
            ProviderRequestPolicy(
                request_interval_seconds=(
                    settings.gemini_request_interval_seconds if plan == "FREE" else 0.0
                ),
                rate_limit_retry_attempts=settings.rate_limit_retry_attempts,
                rate_limit_backoff_seconds=settings.rate_limit_backoff_seconds,
                rate_limit_max_backoff_seconds=settings.rate_limit_max_backoff_seconds,
                rate_limit_max_wait_seconds=settings.rate_limit_max_wait_seconds,
            )
        )
        _COORDINATOR_CACHE[plan] = coordinator
    return coordinator


def close_analyze_providers() -> None:
    with _PROVIDER_LOCK:
        providers = list(_PROVIDER_CACHE.values())
        _PROVIDER_CACHE.clear()
        _GUARD_CACHE.clear()
        _COORDINATOR_CACHE.clear()
    for provider in providers:
        close = getattr(provider, "close", None)
        if callable(close):
            close()


def _require(*values: str, provider: str) -> None:
    if not all(value.strip() for value in values):
        raise AgentError(
            status_code=503,
            code="API_KEY_MISSING",
            message=f"{provider} provider 설정이 없습니다.",
        )
