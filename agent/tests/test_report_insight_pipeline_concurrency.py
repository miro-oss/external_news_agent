"""Offline concurrency: admission, exactly-once usage and issued-call draining."""

from concurrent.futures import ThreadPoolExecutor
from decimal import Decimal
from threading import Barrier, Event, Lock

import pytest

from app.core.breaker import CircuitState
from app.core.config import Settings
from app.core.errors import AgentError
from app.llm.base import ProviderResponse, ProviderUsage
from app.llm.guarded_provider import ProviderGuard
from app.llm.rate_limit_provider import ProviderRequestCoordinator, ProviderRequestPolicy
from app.llm.report_insight_map_execution import run_report_maps
from app.llm.report_insight_pipeline import ReportInsightPipelineProvider


def config(**kwargs):
    values = {"AGENT_MOCK": False, "AGENT_PROVIDER_CONCURRENCY": 4, **kwargs}
    return Settings(_env_file=None, **values)


def call(pipeline, name="one"):
    return pipeline.generate(system_instruction="offline", prompt=name, response_schema={})


def answer(*, model="offline", credits="0", tokens=10):
    return ProviderResponse(
        "{}", "openai", model, ProviderUsage(tokens, 5, Decimal(".01"), Decimal(credits))
    )


class SafeProvider:
    report_insight_max_concurrency = 3
    report_insight_credit_reservation = Decimal(0)

    def __init__(self, run):
        self.run = run
        self.calls = []
        self.cancel_count = 0
        self.lock = Lock()

    def generate(self, **kwargs):
        with self.lock:
            self.calls.append(kwargs["prompt"])
        return self.run(kwargs["prompt"])

    def cancel_pending_calls(self):
        with self.lock:
            self.cancel_count += 1


def test_only_scoped_openai_or_explicit_bound_and_safe_injection_can_parallelize():
    assert ReportInsightPipelineProvider(config(), "FREE").map_concurrency == 2
    assert ReportInsightPipelineProvider(config(), "PAID").map_concurrency == 1
    assert ReportInsightPipelineProvider(config(), "FREE", object()).map_concurrency == 1
    safe = SafeProvider(lambda _: answer())
    # Evaluation injects OpenAI into a stored PAID case; explicit capability is
    # about the real delegate, not the original request's routing label.
    assert ReportInsightPipelineProvider(config(), "PAID", safe).map_concurrency == 2
    limited = config(AGENT_PROVIDER_CONCURRENCY=2)
    assert ReportInsightPipelineProvider(limited, "FREE", safe).map_concurrency == 1
    safe.report_insight_max_concurrency = 1
    assert ReportInsightPipelineProvider(config(), "FREE", safe).map_concurrency == 1


@pytest.mark.parametrize(
    "slots,expected", [(1, 1), (2, 1), (3, 1), (4, 2), (5, 2), (6, 3), (10, 3)]
)
def test_report_uses_at_most_half_the_shared_slots_with_a_three_worker_ceiling(slots, expected):
    settings = config(AGENT_PROVIDER_CONCURRENCY=slots)
    assert ReportInsightPipelineProvider(settings, "FREE").map_concurrency == expected
    safe = SafeProvider(lambda _: answer())
    safe.report_insight_max_concurrency = 2
    assert ReportInsightPipelineProvider(settings, "FREE", safe).map_concurrency == min(expected, 2)


@pytest.mark.parametrize(
    "workers,bound", [(True, 0), ("3", 0), (0, 0), (3, None), (3, True), (3, -1), (3, "NaN")]
)
def test_missing_or_unusable_parallel_credit_contract_stays_serial(workers, bound):
    provider = SafeProvider(lambda _: answer())
    provider.report_insight_max_concurrency = workers
    provider.report_insight_credit_reservation = bound
    assert ReportInsightPipelineProvider(config(), "FREE", provider).map_concurrency == 1


def test_parallel_success_sums_all_usage_once_and_keeps_request_deadline():
    gate = Barrier(4, timeout=3)

    def run(_):
        gate.wait()
        return answer()

    pipeline = ReportInsightPipelineProvider(config(), "FREE", SafeProvider(run))
    deadline = pipeline.deadline
    with ThreadPoolExecutor(max_workers=3) as pool:
        futures = [pool.submit(call, pipeline, str(i)) for i in range(3)]
        gate.wait()
        assert all(f.result(timeout=3).model == "offline" for f in futures)
    assert pipeline.calls == 3
    assert pipeline.usage == ProviderUsage(30, 15, Decimal(".03"), Decimal(0))
    assert pipeline.has_observed_usage and not pipeline.unknown_failure_usage
    assert pipeline.last_response.model == "offline"
    assert pipeline.deadline == deadline
    assert pipeline._reserved_credits == 0


def test_first_failure_stops_repair_but_drains_issued_success_and_known_failure_usage():
    gate = Barrier(3, timeout=3)
    release = Event()
    usage = {"inputTokens": 20, "outputTokens": 7, "costUsd": 0.02, "credits": 0}
    original = AgentError(503, "PROVIDER_UNAVAILABLE", "offline", {"usage": usage})

    def run(name):
        gate.wait()
        if name == "fail":
            raise original
        assert release.wait(3)
        return answer()

    provider = SafeProvider(run)
    pipeline = ReportInsightPipelineProvider(config(), "FREE", provider)
    with ThreadPoolExecutor(max_workers=2) as pool:
        slow = pool.submit(call, pipeline, "slow")
        failure = pool.submit(call, pipeline, "fail")
        gate.wait()
        with pytest.raises(AgentError) as caught:
            failure.result(timeout=3)
        assert caught.value is original
        try:
            with pytest.raises(AgentError) as cancelled:
                call(pipeline, "repair")
            assert cancelled.value.details["requestNotStarted"]
        finally:
            release.set()
        slow.result(timeout=3)
    assert sorted(provider.calls) == ["fail", "slow"]
    assert provider.cancel_count == 1
    assert pipeline.usage == ProviderUsage(30, 12, Decimal(".03"), Decimal(0))
    pipeline.annotate_failure(original, "offline-version")
    assert original.details["usage"]["inputTokens"] == 30
    assert original.details["executionMetadata"]["usageCompleteness"] == "COMPLETE"
    assert pipeline.calls == 1
    assert pipeline._reserved_credits == 0


def test_terminal_validation_cancel_is_uncharged_but_issued_response_is_recorded():
    started, release = Event(), Event()

    def run(_):
        started.set()
        assert release.wait(3)
        return answer()

    provider = SafeProvider(run)
    pipeline = ReportInsightPipelineProvider(config(), "FREE", provider)
    with ThreadPoolExecutor(max_workers=1) as pool:
        running = pool.submit(call, pipeline)
        assert started.wait(3)
        pipeline.cancel_pending_calls()
        try:
            with pytest.raises(AgentError):
                call(pipeline, "not-issued")
        finally:
            release.set()
        running.result(timeout=3)
    assert pipeline.calls == 1
    assert len(provider.calls) == 1
    assert not pipeline.unknown_failure_usage


def test_credit_bound_is_reserved_before_outbound_call_and_released_once():
    started, release = Event(), Event()

    def run(_):
        started.set()
        assert release.wait(3)
        return answer(credits="2")

    provider = SafeProvider(run)
    provider.report_insight_credit_reservation = Decimal(2)
    pipeline = ReportInsightPipelineProvider(
        config(AGENT_HARD_CAP_CREDITS_PER_REQUEST=3), "FREE", provider
    )
    with ThreadPoolExecutor(max_workers=1) as pool:
        running = pool.submit(call, pipeline)
        assert started.wait(3)
        try:
            with pytest.raises(AgentError) as caught:
                call(pipeline, "over-reservation")
            assert caught.value.code == "BUDGET_EXCEEDED"
            assert len(provider.calls) == 1
        finally:
            release.set()
        running.result(timeout=3)
    assert pipeline.usage.credits == 2
    assert pipeline._reserved_credits == 0
    assert not pipeline.unknown_failure_usage


def test_provider_breaking_declared_credit_bound_is_accounted_and_stops_next_call():
    provider = SafeProvider(lambda _: answer(credits="1"))
    pipeline = ReportInsightPipelineProvider(config(), "FREE", provider)
    with pytest.raises(AgentError) as caught:
        call(pipeline)
    assert caught.value.details["creditReservationExceeded"]
    assert pipeline.usage.credits == 1
    assert pipeline.calls == 1
    with pytest.raises(AgentError):
        call(pipeline, "next")
    assert len(provider.calls) == 1


def test_unknown_exception_closes_admission_without_inventing_usage():
    def run(_):
        raise RuntimeError("offline unexpected failure")

    provider = SafeProvider(run)
    pipeline = ReportInsightPipelineProvider(config(), "FREE", provider)
    with pytest.raises(RuntimeError):
        call(pipeline)
    assert pipeline.unknown_failure_usage
    assert not pipeline.has_observed_usage
    with pytest.raises(AgentError):
        call(pipeline, "next")
    assert len(provider.calls) == 1


def test_two_reports_share_four_guard_slots_without_rejecting_their_map_batches(monkeypatch):
    gate = Barrier(4, timeout=3)
    lock = Lock()
    active = {"first": 0, "second": 0}
    peaks = {"first": 0, "second": 0, "total": 0}
    issued, closed = [], []

    class Transport:
        def __init__(self, *args, **kwargs):
            pass

        def generate(self, **kwargs):
            name = kwargs["prompt"]
            report = name.split(":")[0]
            with lock:
                issued.append(name)
                active[report] += 1
                peaks[report] = max(peaks[report], active[report])
                peaks["total"] = max(peaks["total"], sum(active.values()))
            try:
                gate.wait()
                return answer()
            finally:
                with lock:
                    active[report] -= 1

        def close(self):
            with lock:
                closed.append(self)

    guard = ProviderGuard(
        concurrency=4,
        acquire_timeout_seconds=0.01,
        failure_threshold=1,
        cooldown_seconds=30,
        hard_cap_credits=Decimal(5),
    )
    coordinator = ProviderRequestCoordinator(ProviderRequestPolicy(0))
    monkeypatch.setattr("app.llm.report_insight_pipeline.OpenAIAnalyzeProvider", Transport)
    monkeypatch.setattr("app.llm.report_insight_pipeline.get_provider_guard", lambda *_: guard)
    monkeypatch.setattr(
        "app.llm.report_insight_pipeline.get_provider_coordinator", lambda *_: coordinator
    )
    pipelines = [
        ReportInsightPipelineProvider(config(OPENAI_API_KEY="offline-test-only"), "FREE")
        for _ in range(2)
    ]
    assert all(pipeline.map_concurrency == 2 for pipeline in pipelines)

    def report_maps(pipeline, report):
        def evaluate(index, item):
            call(pipeline, f"{report}:{item}")
            return item

        return run_report_maps(
            list(range(4)),
            evaluate,
            concurrency=pipeline.map_concurrency,
            cancel_pending=pipeline.cancel_pending_calls,
        )

    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [
            pool.submit(report_maps, pipeline, name)
            for pipeline, name in zip(pipelines, ("first", "second"), strict=True)
        ]
        assert [future.result(timeout=5) for future in futures] == [list(range(4))] * 2

    assert peaks == {"first": 2, "second": 2, "total": 4}
    assert len(issued) == len(set(issued)) == len(closed) == 8
    assert guard.breaker.state is CircuitState.CLOSED
    for pipeline in pipelines:
        assert pipeline.calls == 4
        assert pipeline.usage == ProviderUsage(40, 20, Decimal(".04"), Decimal(0))
        assert not pipeline.unknown_failure_usage
        assert pipeline._reserved_credits == 0


def test_four_audiences_wait_for_shared_capacity_without_cancelling_their_maps(monkeypatch):
    release, queued = Event(), Event()
    lock = Lock()
    active = 0
    peak = 0
    issued, closed = [], []

    class Transport:
        def __init__(self, *args, **kwargs):
            pass

        def generate(self, **kwargs):
            nonlocal active, peak
            with lock:
                issued.append(kwargs["prompt"])
                active += 1
                peak = max(peak, active)
            try:
                assert release.wait(3)
                return answer()
            finally:
                with lock:
                    active -= 1

        def close(self):
            with lock:
                closed.append(self)

    guard = ProviderGuard(
        concurrency=4,
        acquire_timeout_seconds=0.01,
        failure_threshold=1,
        cooldown_seconds=30,
        hard_cap_credits=Decimal(5),
    )
    semaphore = guard.semaphore

    class ObservedSemaphore:
        def acquire(self, *, timeout):
            acquired = semaphore.acquire(timeout=timeout)
            if not acquired:
                queued.set()
            return acquired

        def release(self):
            semaphore.release()

    guard.semaphore = ObservedSemaphore()
    coordinator = ProviderRequestCoordinator(ProviderRequestPolicy(0))
    monkeypatch.setattr("app.llm.report_insight_pipeline.OpenAIAnalyzeProvider", Transport)
    monkeypatch.setattr("app.llm.report_insight_pipeline.get_provider_guard", lambda *_: guard)
    monkeypatch.setattr(
        "app.llm.report_insight_pipeline.get_provider_coordinator", lambda *_: coordinator
    )
    pipelines = [
        ReportInsightPipelineProvider(config(OPENAI_API_KEY="offline-test-only"), "FREE")
        for _ in range(4)
    ]

    def report_maps(pipeline, audience):
        def evaluate(index, item):
            call(pipeline, f"{audience}:{item}")
            return item

        return run_report_maps(
            list(range(4)),
            evaluate,
            concurrency=pipeline.map_concurrency,
            cancel_pending=pipeline.cancel_pending_calls,
        )

    with ThreadPoolExecutor(max_workers=4) as pool:
        futures = [
            pool.submit(report_maps, pipeline, index) for index, pipeline in enumerate(pipelines)
        ]
        try:
            # At least one MAP actually exceeds the ordinary admission timeout.
            assert queued.wait(3)
        finally:
            release.set()
        assert [future.result(timeout=5) for future in futures] == [list(range(4))] * 4

    assert peak == 4
    assert len(issued) == len(set(issued)) == len(closed) == 16
    assert guard.breaker.state is CircuitState.CLOSED
    for pipeline in pipelines:
        assert pipeline.calls == 4
        assert pipeline.usage == ProviderUsage(40, 20, Decimal(".04"), Decimal(0))
        assert not pipeline.unknown_failure_usage
        assert pipeline._reserved_credits == 0


def test_queued_deadline_stops_before_provider_and_releases_half_open_probe(monkeypatch):
    clock = [0.0]
    waits, issued, closed = [], [], []
    monkeypatch.setattr("app.llm.report_insight_pipeline.monotonic", lambda: clock[0])

    class BusySemaphore:
        def acquire(self, *, timeout):
            waits.append(timeout)
            clock[0] += timeout
            return False

        def release(self):
            pytest.fail("a queued call never acquired capacity")

    class Transport:
        def __init__(self, *args, **kwargs):
            pass

        def generate(self, **kwargs):
            issued.append(True)
            return answer()

        def close(self):
            closed.append(True)

    guard = ProviderGuard(
        concurrency=1,
        acquire_timeout_seconds=0.4,
        failure_threshold=1,
        cooldown_seconds=0,
        hard_cap_credits=Decimal(5),
    )
    guard.semaphore = BusySemaphore()
    guard.breaker.record_failure()
    assert guard.breaker.state is CircuitState.HALF_OPEN
    coordinator = ProviderRequestCoordinator(ProviderRequestPolicy(0), clock=lambda: clock[0])
    monkeypatch.setattr("app.llm.report_insight_pipeline.OpenAIAnalyzeProvider", Transport)
    monkeypatch.setattr("app.llm.report_insight_pipeline.get_provider_guard", lambda *_: guard)
    monkeypatch.setattr(
        "app.llm.report_insight_pipeline.get_provider_coordinator", lambda *_: coordinator
    )
    pipeline = ReportInsightPipelineProvider(
        config(OPENAI_API_KEY="offline-test-only", AGENT_REPORT_INSIGHT_TIMEOUT_SECONDS=1), "FREE"
    )
    with pytest.raises(AgentError) as caught:
        call(pipeline)

    assert caught.value.details == {"requestDeadlineExceeded": True, "requestNotStarted": True}
    assert waits == pytest.approx([0.4, 0.4, 0.2])
    assert clock[0] == 1
    assert issued == [] and closed == []
    assert pipeline.calls == 0 and pipeline.usage == ProviderUsage()
    assert not pipeline.unknown_failure_usage
    assert pipeline._reserved_credits == 0
    assert guard.breaker.state is CircuitState.HALF_OPEN
    guard.breaker.before_call()
    guard.breaker.cancel_call()


def test_queued_call_uses_latest_pacing_and_constructs_client_with_remaining_time(monkeypatch):
    clock = [0.0]
    sleeps, created, issued, closed = [], [], [], []
    monkeypatch.setattr("app.llm.report_insight_pipeline.monotonic", lambda: clock[0])

    def advance(seconds):
        sleeps.append(seconds)
        clock[0] += seconds

    coordinator = ProviderRequestCoordinator(
        ProviderRequestPolicy(1), clock=lambda: clock[0], sleeper=advance
    )
    coordinator.wait_before_call()
    guard = ProviderGuard(
        concurrency=1,
        acquire_timeout_seconds=1,
        failure_threshold=1,
        cooldown_seconds=30,
        hard_cap_credits=Decimal(5),
    )

    class ContendedSemaphore:
        def acquire(self, *, timeout):
            # Another call receives a cooldown while this request queues.
            clock[0] = 5
            coordinator.wait_after_rate_limit(
                AgentError(503, "PROVIDER_UNAVAILABLE", "limited", {"retryAfterSeconds": 4}), 1
            )
            return True

        def release(self):
            pass

    class Transport:
        def __init__(self, settings, *, request_deadline):
            created.append((settings.provider_timeout_seconds, request_deadline))

        def generate(self, **kwargs):
            issued.append(clock[0])
            return answer()

        def close(self):
            closed.append(True)

    guard.semaphore = ContendedSemaphore()
    monkeypatch.setattr("app.llm.report_insight_pipeline.OpenAIAnalyzeProvider", Transport)
    monkeypatch.setattr("app.llm.report_insight_pipeline.get_provider_guard", lambda *_: guard)
    monkeypatch.setattr(
        "app.llm.report_insight_pipeline.get_provider_coordinator", lambda *_: coordinator
    )
    pipeline = ReportInsightPipelineProvider(
        config(OPENAI_API_KEY="offline-test-only", AGENT_REPORT_INSIGHT_TIMEOUT_SECONDS=20), "FREE"
    )
    call(pipeline)

    assert sleeps == [4]
    assert created == [(11, 20)]
    assert issued == [9] and closed == [True]
    assert pipeline.deadline == 20
    assert pipeline.calls == 1
    assert pipeline.usage == answer().usage


def test_circuit_opened_while_waiting_preserves_observed_usage_without_a_second_call(monkeypatch):
    released, created = [], []
    guard = ProviderGuard(
        concurrency=1,
        acquire_timeout_seconds=0.01,
        failure_threshold=1,
        cooldown_seconds=30,
        hard_cap_credits=Decimal(5),
    )

    class ContendedSemaphore:
        def acquire(self, *, timeout):
            guard.breaker.record_failure()
            return True

        def release(self):
            released.append(True)

    class Transport:
        def __init__(self, *args, **kwargs):
            created.append(True)

        def generate(self, **kwargs):
            return answer()

        def close(self):
            pass

    coordinator = ProviderRequestCoordinator(ProviderRequestPolicy(0))
    monkeypatch.setattr("app.llm.report_insight_pipeline.OpenAIAnalyzeProvider", Transport)
    monkeypatch.setattr("app.llm.report_insight_pipeline.get_provider_guard", lambda *_: guard)
    monkeypatch.setattr(
        "app.llm.report_insight_pipeline.get_provider_coordinator", lambda *_: coordinator
    )
    pipeline = ReportInsightPipelineProvider(config(OPENAI_API_KEY="offline-test-only"), "FREE")
    call(pipeline)
    guard.semaphore = ContendedSemaphore()
    with pytest.raises(AgentError) as caught:
        call(pipeline)

    assert caught.value.details == {"circuitOpen": True, "requestNotStarted": True}
    assert released == [True] and created == [True]
    assert pipeline.calls == 1 and pipeline.usage == answer().usage
    assert not pipeline.unknown_failure_usage
    pipeline.annotate_failure(caught.value, "offline-version")
    assert caught.value.details["executionMetadata"]["usageCompleteness"] == "COMPLETE"
    assert guard.breaker.state is CircuitState.OPEN


def test_waiting_call_cancellation_does_not_make_drained_usage_partial(monkeypatch):
    started, waiting, release = Event(), Event(), Event()
    issued, closed = [], []

    class Transport:
        def __init__(self, *args, **kwargs):
            pass

        def generate(self, **kwargs):
            issued.append(kwargs["prompt"])
            started.set()
            assert release.wait(3)
            return answer()

        def close(self):
            closed.append(self)

    guard = ProviderGuard(
        concurrency=1,
        acquire_timeout_seconds=0.01,
        failure_threshold=1,
        cooldown_seconds=30,
        hard_cap_credits=Decimal(5),
    )
    semaphore = guard.semaphore

    class WaitingSemaphore:
        def acquire(self, *, timeout):
            acquired = semaphore.acquire(timeout=timeout)
            if not acquired:
                waiting.set()
            return acquired

        def release(self):
            semaphore.release()

    guard.semaphore = WaitingSemaphore()
    coordinator = ProviderRequestCoordinator(ProviderRequestPolicy(0))
    monkeypatch.setattr("app.llm.report_insight_pipeline.OpenAIAnalyzeProvider", Transport)
    monkeypatch.setattr("app.llm.report_insight_pipeline.get_provider_guard", lambda *_: guard)
    monkeypatch.setattr(
        "app.llm.report_insight_pipeline.get_provider_coordinator", lambda *_: coordinator
    )
    pipeline = ReportInsightPipelineProvider(config(OPENAI_API_KEY="offline-test-only"), "FREE")
    with ThreadPoolExecutor(max_workers=2) as pool:
        running = pool.submit(call, pipeline, "running")
        assert started.wait(3)
        try:
            queued = pool.submit(call, pipeline, "not-issued")
            assert waiting.wait(3)
            pipeline.cancel_pending_calls()
            with pytest.raises(AgentError) as caught:
                queued.result(timeout=3)
            assert caught.value.details == {
                "pipelineCancelled": True,
                "requestNotStarted": True,
            }
        finally:
            release.set()
        running.result(timeout=3)

    pipeline.annotate_failure(caught.value, "offline-version")
    assert issued == ["running"]
    assert len(closed) == 1
    assert pipeline.calls == 1
    assert pipeline.usage == ProviderUsage(10, 5, Decimal(".01"), Decimal(0))
    assert caught.value.details["usage"] == {
        "inputTokens": 10,
        "outputTokens": 5,
        "costUsd": 0.01,
        "credits": 0.0,
    }
    assert caught.value.details["executionMetadata"]["usageCompleteness"] == "COMPLETE"
    assert not pipeline.unknown_failure_usage
    assert pipeline._reserved_credits == 0
    assert guard.breaker.state is CircuitState.CLOSED


def test_scoped_native_parallel_calls_own_and_close_separate_clients(monkeypatch):
    gate = Barrier(4, timeout=3)
    created, closed = [], []

    class Transport:
        def __init__(self, settings, *, request_deadline):
            self.deadline = request_deadline
            created.append(self)

        def generate(self, **kwargs):
            gate.wait()
            return answer()

        def close(self):
            closed.append(self)

    guard = ProviderGuard(
        concurrency=3,
        acquire_timeout_seconds=1,
        failure_threshold=1,
        cooldown_seconds=30,
        hard_cap_credits=Decimal(5),
    )
    coordinator = ProviderRequestCoordinator(ProviderRequestPolicy(0))
    monkeypatch.setattr("app.llm.report_insight_pipeline.OpenAIAnalyzeProvider", Transport)
    monkeypatch.setattr("app.llm.report_insight_pipeline.get_provider_guard", lambda *_: guard)
    monkeypatch.setattr(
        "app.llm.report_insight_pipeline.get_provider_coordinator", lambda *_: coordinator
    )
    pipeline = ReportInsightPipelineProvider(config(OPENAI_API_KEY="offline-test-only"), "FREE")
    with ThreadPoolExecutor(max_workers=3) as pool:
        futures = [pool.submit(call, pipeline, str(i)) for i in range(3)]
        gate.wait()
        for f in futures:
            f.result(timeout=3)
    assert len(created) == 3
    assert {id(c) for c in created} == {id(c) for c in closed}
    assert all(c.deadline == pipeline.deadline for c in created)
    assert pipeline.usage.input_tokens == 30


def test_cancellation_after_shared_semaphore_wait_never_calls_or_charges_provider(monkeypatch):
    waiting, release = Event(), Event()
    called, closed = [], []

    class PausedSemaphore:
        def acquire(self, *, timeout):
            waiting.set()
            assert release.wait(3)
            return True

        def release(self):
            pass

    class Transport:
        def __init__(self, *args, **kwargs):
            pass

        def generate(self, **kwargs):
            called.append(True)
            return answer()

        def close(self):
            closed.append(True)

    guard = ProviderGuard(
        concurrency=1,
        acquire_timeout_seconds=1,
        failure_threshold=1,
        cooldown_seconds=30,
        hard_cap_credits=Decimal(5),
    )
    guard.semaphore = PausedSemaphore()
    coordinator = ProviderRequestCoordinator(ProviderRequestPolicy(0))
    monkeypatch.setattr("app.llm.report_insight_pipeline.OpenAIAnalyzeProvider", Transport)
    monkeypatch.setattr("app.llm.report_insight_pipeline.get_provider_guard", lambda *_: guard)
    monkeypatch.setattr(
        "app.llm.report_insight_pipeline.get_provider_coordinator", lambda *_: coordinator
    )
    pipeline = ReportInsightPipelineProvider(config(OPENAI_API_KEY="offline-test-only"), "FREE")
    with ThreadPoolExecutor(max_workers=1) as pool:
        future = pool.submit(call, pipeline)
        assert waiting.wait(3)
        pipeline.cancel_pending_calls()
        release.set()
        with pytest.raises(AgentError) as caught:
            future.result(timeout=3)
    assert caught.value.details["pipelineCancelled"]
    assert called == [] and closed == []
    assert pipeline.calls == 0 and pipeline.usage == ProviderUsage()
    assert not pipeline.unknown_failure_usage
    assert guard.breaker.state == CircuitState.CLOSED


def test_concurrent_model_mismatch_accounts_both_responses_before_failure():
    gate = Barrier(3, timeout=3)

    def run(name):
        gate.wait()
        return answer(model=name)

    pipeline = ReportInsightPipelineProvider(config(), "FREE", SafeProvider(run))
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(call, pipeline, name) for name in ("a", "b")]
        gate.wait()
        errors = []
        for f in futures:
            try:
                f.result(timeout=3)
            except AgentError as error:
                errors.append(error)
    assert len(errors) == 1 and errors[0].code == "SCHEMA_VIOLATION"
    assert pipeline.calls == 2 and pipeline.usage.input_tokens == 20
    pipeline.annotate_failure(errors[0], "offline")
    assert errors[0].details["usage"]["inputTokens"] == 20


def test_deadline_failure_after_parallel_responses_still_collects_each_usage(monkeypatch):
    clock = [0.0]
    monkeypatch.setattr("app.llm.report_insight_pipeline.monotonic", lambda: clock[0])
    gate = Barrier(3, timeout=3)
    release = Event()

    def run(_):
        gate.wait()
        assert release.wait(3)
        return answer()

    pipeline = ReportInsightPipelineProvider(config(), "FREE", SafeProvider(run))
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(call, pipeline, str(i)) for i in range(2)]
        gate.wait()
        clock[0] = 181
        release.set()
        for f in futures:
            with pytest.raises(AgentError) as caught:
                f.result(timeout=3)
            assert caught.value.details["requestDeadlineExceeded"]
    assert pipeline.calls == 2 and pipeline.usage.input_tokens == 20
    assert pipeline.deadline == 180
