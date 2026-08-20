from decimal import Decimal
from threading import Lock

from app.core.config import Settings
from app.core.errors import AgentError
from app.llm.base import AnalyzeProvider
from app.llm.gemini_provider import GeminiAnalyzeProvider
from app.llm.guarded_provider import GuardedAnalyzeProvider
from app.llm.mindlogic_provider import MindlogicAnalyzeProvider
from app.schemas.analyze import Plan

_PROVIDER_CACHE: dict[tuple[Settings, Plan], AnalyzeProvider] = {}
_PROVIDER_LOCK = Lock()


def get_analyze_provider(settings: Settings, plan: Plan) -> AnalyzeProvider:
    if plan == "FREE":
        _require(settings.gemini_api_key, settings.gemini_model, provider="Gemini")
    else:
        _require(
            settings.mindlogic_api_key,
            settings.mindlogic_base_url,
            settings.mindlogic_claude_model,
            provider="Mindlogic",
        )

    key = (settings, plan)
    with _PROVIDER_LOCK:
        provider = _PROVIDER_CACHE.get(key)
        if provider is None:
            delegate = (
                GeminiAnalyzeProvider(settings)
                if plan == "FREE"
                else MindlogicAnalyzeProvider(settings)
            )
            provider = GuardedAnalyzeProvider(
                delegate,
                concurrency=settings.provider_concurrency,
                acquire_timeout_seconds=settings.provider_acquire_timeout_seconds,
                failure_threshold=settings.circuit_failure_threshold,
                cooldown_seconds=settings.circuit_cooldown_seconds,
                hard_cap_credits=Decimal(str(settings.hard_cap_credits_per_request)),
            )
            _PROVIDER_CACHE[key] = provider
        return provider


def close_analyze_providers() -> None:
    with _PROVIDER_LOCK:
        providers = list(_PROVIDER_CACHE.values())
        _PROVIDER_CACHE.clear()
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
