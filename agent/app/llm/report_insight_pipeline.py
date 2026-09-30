"""Request-scoped cumulative budget, usage ledger and deadline for map/reduce."""

from decimal import Decimal, InvalidOperation
from time import monotonic

from app.core.config import Settings
from app.core.errors import AgentError
from app.llm.base import AnalyzeProvider, ProviderResponse, ProviderUsage
from app.llm.guarded_provider import GuardedAnalyzeProvider
from app.llm.mindlogic_provider import MindlogicAnalyzeProvider
from app.llm.openai_provider import OpenAIAnalyzeProvider
from app.llm.rate_limit_provider import run_with_request_policy
from app.llm.router import get_provider_coordinator, get_provider_guard
from app.schemas.analyze import Plan


class ReportInsightPipelineProvider:
    def __init__(self, settings: Settings, plan: Plan, provider: AnalyzeProvider | None = None):
        self.settings = settings
        self.plan = plan
        self.provider = provider
        self.deadline = monotonic() + settings.report_provider_timeout_seconds
        self.cap = Decimal(str(settings.hard_cap_credits_per_request))
        self.usage = ProviderUsage()
        self.last_response: ProviderResponse | None = None
        self.has_observed_usage = False
        self.unknown_failure_usage = False
        self.calls = 0

    def generate(self, **kwargs) -> ProviderResponse:
        self.ensure_time_remaining()
        if self.usage.credits >= self.cap:
            raise self._budget_error(ProviderUsage())
        try:
            response = (
                self.provider.generate(**kwargs)
                if self.provider is not None
                else self._generate_scoped(**kwargs)
            )
        except AgentError as error:
            details = error.details if isinstance(error.details, dict) else {}
            failure_usage = details.get("usage")
            if isinstance(failure_usage, dict):
                self.usage += _known_usage(failure_usage)
                self.has_observed_usage |= any(
                    _valid_usage_field(failure_usage, key)
                    for key in ("inputTokens", "outputTokens", "costUsd", "credits")
                )
                self.unknown_failure_usage |= not _complete_usage(failure_usage)
            else:
                self.unknown_failure_usage |= not details.get("requestDeadlineExceeded", False)
            raise
        self.calls += 1
        self.has_observed_usage = True
        self.usage += response.usage
        if self.last_response is not None and (
            self.last_response.provider != response.provider
            or self.last_response.model != response.model
        ):
            raise AgentError(
                status_code=502,
                code="SCHEMA_VIOLATION",
                message="분석 단계 사이에 Provider 또는 모델이 변경되었습니다.",
                details={"usage": _usage_dict(response.usage)},
            )
        self.last_response = response
        if self.usage.credits > self.cap:
            raise self._budget_error(response.usage)
        if monotonic() >= self.deadline:
            error = self._deadline_error()
            error.details["usage"] = _usage_dict(response.usage)
            raise error
        return response

    def ensure_time_remaining(self) -> None:
        if monotonic() >= self.deadline:
            raise self._deadline_error()

    def _generate_scoped(self, **kwargs) -> ProviderResponse:
        # Share admission/circuit/pacing, but own short-lived HTTP clients. No
        # mutable shared timeout and no cache entry for each remaining duration.
        guard = get_provider_guard(self.settings, self.plan)
        coordinator = get_provider_coordinator(self.settings, self.plan)

        def call():
            remaining = self.deadline - monotonic() - guard.acquire_timeout_seconds
            if remaining <= 0:
                raise self._deadline_error()
            scoped = self.settings.model_copy(
                update={
                    "provider_timeout_seconds": remaining,
                    "provider_retry_attempts": 0,
                }
            )
            if self.plan == "FREE":
                if not scoped.openai_api_key.strip():
                    raise AgentError(503, "API_KEY_MISSING", "OpenAI provider 설정이 없습니다.")
                raw = OpenAIAnalyzeProvider(scoped)
            else:
                if not scoped.mindlogic_api_key.strip():
                    raise AgentError(503, "API_KEY_MISSING", "Mindlogic provider 설정이 없습니다.")
                raw = MindlogicAnalyzeProvider(scoped)
            provider = GuardedAnalyzeProvider(
                raw,
                concurrency=scoped.provider_concurrency,
                acquire_timeout_seconds=scoped.provider_acquire_timeout_seconds,
                failure_threshold=scoped.circuit_failure_threshold,
                cooldown_seconds=scoped.circuit_cooldown_seconds,
                hard_cap_credits=self.cap,
                guard=guard,
            )
            try:
                return provider.generate(**kwargs)
            finally:
                provider.close()

        return run_with_request_policy(
            coordinator,
            call,
            deadline=self.deadline,
            retry_attempts=0,
        )

    def annotate_failure(self, error: AgentError, prompt_version: str) -> None:
        details = dict(error.details) if isinstance(error.details, dict) else {}
        # structured_call has already accumulated each stage's repairs; this
        # request ledger replaces that subtotal rather than adding it twice.
        if self.has_observed_usage:
            details["usage"] = _usage_dict(self.usage)
        metadata = details.get("executionMetadata")
        metadata = dict(metadata) if isinstance(metadata, dict) else {}
        if self.last_response is not None and metadata.get("provider") is None:
            metadata.update(provider=self.last_response.provider, model=self.last_response.model)
        metadata.update(
            promptVersion=prompt_version,
            source="AGENT_ERROR",
            usageCompleteness="UNKNOWN"
            if not self.has_observed_usage
            else ("PARTIAL" if self.unknown_failure_usage else "COMPLETE"),
        )
        details["executionMetadata"] = metadata
        error.details = details

    def _deadline_error(self) -> AgentError:
        return AgentError(
            status_code=503,
            code="PROVIDER_UNAVAILABLE",
            message="리포트 관점 인사이트 요청의 전체 시간 예산을 초과했습니다.",
            details={"requestDeadlineExceeded": True},
        )

    def _budget_error(self, current: ProviderUsage) -> AgentError:
        return AgentError(
            status_code=429,
            code="BUDGET_EXCEEDED",
            message="리포트 관점 인사이트 전체 요청 사용량이 hard cap을 초과했습니다.",
            details={"usage": _usage_dict(current), "hardCapCredits": float(self.cap)},
        )


def _usage_dict(usage: ProviderUsage) -> dict:
    return {
        "inputTokens": usage.input_tokens,
        "outputTokens": usage.output_tokens,
        "costUsd": float(usage.cost_usd),
        "credits": float(usage.credits),
    }


def _known_usage(value: dict) -> ProviderUsage:
    def number(key, integer=False):
        raw = value.get(key)
        if isinstance(raw, bool):
            return 0 if integer else Decimal(0)
        try:
            parsed = Decimal(str(raw))
            if not parsed.is_finite() or parsed < 0 or (integer and parsed != int(parsed)):
                return 0 if integer else Decimal(0)
            return int(parsed) if integer else parsed
        except (InvalidOperation, ValueError, TypeError):
            return 0 if integer else Decimal(0)

    return ProviderUsage(
        input_tokens=number("inputTokens", True),
        output_tokens=number("outputTokens", True),
        cost_usd=number("costUsd"),
        credits=number("credits"),
    )


def _complete_usage(value: dict) -> bool:
    return all(
        _valid_usage_field(value, key)
        for key in ("inputTokens", "outputTokens", "costUsd", "credits")
    )


def _valid_usage_field(value: dict, key: str) -> bool:
    raw = value.get(key)
    if raw is None or isinstance(raw, bool):
        return False
    try:
        parsed = Decimal(str(raw))
        return (
            parsed.is_finite()
            and parsed >= 0
            and (not key.endswith("Tokens") or parsed == int(parsed))
        )
    except (InvalidOperation, ValueError, TypeError):
        return False
