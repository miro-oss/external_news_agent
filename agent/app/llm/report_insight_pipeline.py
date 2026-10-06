"""Request-scoped cumulative budget, usage ledger and deadline for map/reduce."""

from contextlib import contextmanager
from decimal import Decimal, InvalidOperation
from threading import RLock
from time import monotonic

from app.core.config import Settings
from app.core.errors import AgentError, StructuredOutputExhaustedError
from app.llm.base import AnalyzeProvider, ProviderResponse, ProviderUsage
from app.llm.guarded_provider import GuardedAnalyzeProvider
from app.llm.mindlogic_provider import MindlogicAnalyzeProvider
from app.llm.openai_provider import OpenAIAnalyzeProvider
from app.llm.rate_limit_provider import run_with_request_policy
from app.llm.router import get_provider_coordinator, get_provider_guard
from app.schemas.analyze import Plan

MAX_REPORT_INSIGHT_DEADLINE_SECONDS = 180.0
# Maximum headroom for optional refinement. Shorter configured requests allocate
# a fixed third to each stage. Synthesis includes its draft and one repair.
REPORT_INSIGHT_REDUCE_RESERVE_SECONDS = 60.0
REPORT_INSIGHT_REVIEW_BUDGET_SECONDS = 60.0
REPORT_INSIGHT_REVIEW_MINIMUM_SECONDS = 30.0


class ReportInsightPipelineProvider:
    def __init__(self, settings: Settings, plan: Plan, provider: AnalyzeProvider | None = None):
        self.settings = settings
        self.plan = plan
        self.provider = provider
        # This whole-request budget is independent of ordinary report provider timeouts.
        # The BE's read timeout covers this maximum plus response serialization/transport time.
        request_budget = min(
            settings.report_insight_timeout_seconds, MAX_REPORT_INSIGHT_DEADLINE_SECONDS
        )
        self.deadline = monotonic() + request_budget
        self._review_budget_seconds = min(REPORT_INSIGHT_REVIEW_BUDGET_SECONDS, request_budget / 3)
        self._review_minimum_seconds = min(
            REPORT_INSIGHT_REVIEW_MINIMUM_SECONDS, request_budget / 6
        )
        self._reduce_reserve_seconds = min(
            REPORT_INSIGHT_REDUCE_RESERVE_SECONDS, request_budget / 3
        )
        self.cap = Decimal(str(settings.hard_cap_credits_per_request))
        self.usage = ProviderUsage()
        self.last_response: ProviderResponse | None = None
        self.has_observed_usage = False
        self.unknown_failure_usage = False
        self.calls = 0
        self._lock = RLock()
        self._cancelled = False
        self._cancellation_notified = False
        self._reserved_credits = Decimal(0)
        self._credit_reservation = self._parallel_credit_reservation()

    def _parallel_credit_reservation(self) -> Decimal | None:
        if self.provider is None:
            # OpenAIAnalyzeProvider._usage always leaves credits at zero. USD
            # accounting remains separate, including an evaluator's USD cap.
            return Decimal(0) if self.plan == "FREE" else None
        workers = getattr(self.provider, "report_insight_max_concurrency", None)
        bound = getattr(self.provider, "report_insight_credit_reservation", None)
        if type(workers) is not int or workers < 1 or isinstance(bound, bool):
            return None
        try:
            parsed = Decimal(str(bound))
        except (InvalidOperation, ValueError, TypeError):
            return None
        return parsed if parsed.is_finite() and parsed >= 0 else None

    @property
    def map_concurrency(self) -> int:
        if self._credit_reservation is None:
            return 1
        explicit = 3 if self.provider is None else self.provider.report_insight_max_concurrency
        return min(3, self.settings.provider_concurrency, explicit)

    def cancel_pending_calls(self) -> None:
        """Stop admission/repairs; already issued calls still settle their usage."""
        with self._lock:
            self._cancelled = True
            notify = not self._cancellation_notified
            self._cancellation_notified = True
        # Do not invert a provider's ledger lock with this pipeline's lock.
        callback = getattr(self.provider, "cancel_pending_calls", None)
        if notify and callable(callback):
            try:
                callback()
            except Exception:
                # Local admission is already closed. A callback must not replace
                # the original failure or prevent draining issued requests.
                pass

    def _ensure_not_cancelled(self) -> None:
        with self._lock:
            if self._cancelled:
                raise AgentError(
                    503,
                    "PROVIDER_UNAVAILABLE",
                    "동일 리포트 요청의 실패로 추가 Provider 호출을 중단했습니다.",
                    {"pipelineCancelled": True, "requestNotStarted": True},
                )

    def generate(self, **kwargs) -> ProviderResponse:
        try:
            with self._lock:
                self._ensure_not_cancelled()
                self.ensure_time_remaining()
                reserved = self._credit_reservation or Decimal(0)
                if self.usage.credits >= self.cap or (
                    self.usage.credits + self._reserved_credits + reserved > self.cap
                ):
                    self._cancelled = True
                    raise self._budget_error(ProviderUsage())
                self._reserved_credits += reserved
        except AgentError:
            self.cancel_pending_calls()
            raise
        try:
            response = (
                self.provider.generate(**kwargs)
                if self.provider is not None
                else self._generate_scoped(**kwargs)
            )
        except AgentError as error:
            details = error.details if isinstance(error.details, dict) else {}
            failure_usage = details.get("usage")
            with self._lock:
                self._reserved_credits -= reserved
                self._cancelled = True
                if isinstance(failure_usage, dict):
                    self.usage += _known_usage(failure_usage)
                    self.has_observed_usage |= any(
                        _valid_usage_field(failure_usage, key)
                        for key in ("inputTokens", "outputTokens", "costUsd", "credits")
                    )
                    self.unknown_failure_usage |= not _complete_usage(failure_usage)
                else:
                    self.unknown_failure_usage |= not (
                        details.get("requestDeadlineExceeded", False)
                        or details.get("requestNotStarted", False)
                    )
            self.cancel_pending_calls()
            raise
        except BaseException:
            with self._lock:
                self._reserved_credits -= reserved
                self.unknown_failure_usage = True
                self._cancelled = True
            self.cancel_pending_calls()
            raise
        error = None
        with self._lock:
            self._reserved_credits -= reserved
            self.calls += 1
            self.has_observed_usage = True
            self.usage += response.usage
            if self.last_response is not None and (
                self.last_response.provider != response.provider
                or self.last_response.model != response.model
            ):
                error = AgentError(
                    status_code=502,
                    code="SCHEMA_VIOLATION",
                    message="분석 단계 사이에 Provider 또는 모델이 변경되었습니다.",
                    details={"usage": _usage_dict(response.usage)},
                )
            else:
                self.last_response = response
            if self.usage.credits > self.cap:
                error = self._budget_error(response.usage)
            elif self._credit_reservation is not None and response.usage.credits > reserved:
                error = AgentError(
                    502,
                    "PROVIDER_UNAVAILABLE",
                    "Provider 사용량이 사전에 보장한 호출별 credit 상한을 초과했습니다.",
                    {"usage": _usage_dict(response.usage), "creditReservationExceeded": True},
                )
            elif monotonic() >= self.deadline:
                error = self._deadline_error()
                error.details["usage"] = _usage_dict(response.usage)
            if error is not None:
                self._cancelled = True
        if error is not None:
            self.cancel_pending_calls()
            raise error
        return response

    def ensure_time_remaining(self) -> None:
        if monotonic() >= self.deadline:
            raise self._deadline_error()

    def can_start_optional_review(self) -> bool:
        """Require a useful review window while preserving synthesis headroom.

        This is admission only. Once started, every provider/deadline failure
        still propagates normally and all calls share the original deadline.
        The draft and its repair may use the available window up to the usual
        cap; starting does not require that entire maximum to remain.
        """
        return self.deadline - monotonic() >= (
            self._reduce_reserve_seconds + self._review_minimum_seconds
        )

    @contextmanager
    def optional_review_deadline(self):
        """Share one bounded window across a REVIEW draft and its repair.

        Scoped production providers inherit this deadline for their HTTP call.
        The response check also applies it to injected providers. Exceptions
        leave the pipeline normally; this context never converts them to success.
        """
        request_deadline = self.deadline
        self.deadline = min(
            monotonic() + self._review_budget_seconds,
            request_deadline - self._reduce_reserve_seconds,
        )
        try:
            yield
        except StructuredOutputExhaustedError:
            # A late validation failure cannot bypass the REVIEW window via
            # the service's ordinary validated-MAP fallback.
            self.ensure_time_remaining()
            raise
        else:
            self.ensure_time_remaining()
        finally:
            self.deadline = request_deadline

    def _generate_scoped(self, **kwargs) -> ProviderResponse:
        # Share admission/circuit/pacing, but own short-lived HTTP clients. No
        # mutable shared timeout and no cache entry for each remaining duration.
        guard = get_provider_guard(self.settings, self.plan)
        coordinator = get_provider_coordinator(self.settings, self.plan)

        def call():
            self._ensure_not_cancelled()
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
                raw = OpenAIAnalyzeProvider(scoped, request_deadline=self.deadline)
            else:
                if not scoped.mindlogic_api_key.strip():
                    raise AgentError(503, "API_KEY_MISSING", "Mindlogic provider 설정이 없습니다.")
                raw = MindlogicAnalyzeProvider(scoped, request_deadline=self.deadline)
            provider = GuardedAnalyzeProvider(
                _PendingScopedProvider(self, raw),
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
        with self._lock:
            self._annotate_failure_locked(error, prompt_version)

    def _annotate_failure_locked(self, error: AgentError, prompt_version: str) -> None:
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


class _PendingScopedProvider:
    """Recheck cancellation after shared pacing and semaphore admission."""

    def __init__(self, pipeline, delegate):
        self.pipeline = pipeline
        self.delegate = delegate

    def generate(self, **kwargs):
        self.pipeline._ensure_not_cancelled()
        return self.delegate.generate(**kwargs)

    def close(self):
        self.delegate.close()


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
