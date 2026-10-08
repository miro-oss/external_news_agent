import json
import re
from copy import deepcopy
from decimal import Decimal
from pathlib import Path

import pytest
from test_report_insight import output, request_body

from app.core.config import Settings
from app.core.errors import AgentError
from app.llm.base import ProviderResponse, ProviderUsage
from app.llm.guarded_provider import ProviderGuard
from app.llm.rate_limit_provider import ProviderRequestCoordinator, ProviderRequestPolicy
from app.llm.report_insight_pipeline import (
    MAX_REPORT_INSIGHT_DEADLINE_SECONDS,
    ReportInsightPipelineProvider,
)
from app.llm.report_insight_service import ReportInsightLegacyService as ReportInsightService
from app.schemas.report_insight import ReportInsightRequest


def map_output(candidate=None):
    return {
        "insights": [
            {key: value for key, value in insight.items() if key in {"audience", "assessments"}}
            for insight in (candidate or output())["insights"]
        ]
    }


def reduce_output(candidate=None):
    return {
        "insights": [
            {key: value for key, value in insight.items() if key != "assessments"}
            for insight in (candidate or output())["insights"]
        ]
    }


def response(payload, *, credits="1", tokens=100):
    return ProviderResponse(
        text=json.dumps(payload, ensure_ascii=False),
        provider="openai",
        model="offline-map-reduce",
        usage=ProviderUsage(
            input_tokens=tokens,
            output_tokens=tokens // 2,
            cost_usd=Decimal("0.01"),
            credits=Decimal(credits),
        ),
    )


class StageProvider:
    def __init__(self, *results):
        self.results = list(results)
        self.calls = []

    def generate(self, **kwargs):
        self.calls.append(kwargs)
        result = self.results.pop(0)
        if isinstance(result, AgentError):
            raise result
        return result


def generate(provider, body=None, **settings):
    return ReportInsightService(Settings(AGENT_MOCK=False, **settings), provider).generate(
        ReportInsightRequest.model_validate(body or request_body())
    )


def test_map_reduce_usage_is_summed_and_map_scores_are_preserved():
    provider = StageProvider(
        response(map_output(), credits="1.2"), response(reduce_output(), credits="1.7", tokens=200)
    )
    result = generate(provider)
    assert len(provider.calls) == 2
    assert result.meta.input_tokens == 300
    assert result.meta.output_tokens == 150
    assert result.meta.cost_usd == 0.02
    assert result.meta.credits == 2.9
    assert (
        result.insights[0].assessments[0].model_dump(by_alias=True)
        == (output()["insights"][0]["assessments"][0])
    )
    assert "MAP" in provider.calls[0]["system_instruction"]
    assert "REDUCE" in provider.calls[1]["system_instruction"]
    assert "scoped-bm25.v1" in provider.calls[1]["prompt"]
    assert "retrievedEvidence" in provider.calls[1]["prompt"]


def test_map_repair_and_reduce_costs_accumulate_without_double_counting():
    invalid = map_output()
    invalid["insights"][0]["assessments"] = []
    provider = StageProvider(
        response(invalid, credits="0.5"),
        response(map_output(), credits="0.7"),
        response(reduce_output(), credits="0.8"),
    )
    result = generate(provider)
    assert len(provider.calls) == 3
    assert result.meta.input_tokens == 300
    assert result.meta.credits == 2


def test_reduce_cannot_rewrite_map_axes_and_repair_cost_is_recorded():
    invalid = reduce_output()
    invalid["insights"][0]["assessments"] = [{"findingId": 501, "axes": {"directness": 3}}]
    provider = StageProvider(
        response(map_output(), credits="0.5"),
        response(invalid, credits="0.5"),
        response(reduce_output(), credits="0.5"),
    )
    result = generate(provider)
    assert len(provider.calls) == 3
    assert result.meta.credits == 1.5
    assert result.insights[0].assessments[0].axes.directness == 2


def test_cumulative_cap_includes_both_stages_and_failed_repair_charges():
    provider = StageProvider(
        response(map_output(), credits="3"), response(reduce_output(), credits="3")
    )
    with pytest.raises(AgentError) as error:
        generate(provider, AGENT_HARD_CAP_CREDITS_PER_REQUEST=5)
    assert error.value.code == "BUDGET_EXCEEDED"
    assert error.value.details["usage"]["credits"] == 6
    assert error.value.details["usage"]["inputTokens"] == 200
    assert len(provider.calls) == 2


def test_reduce_not_called_when_map_has_already_consumed_entire_cap():
    provider = StageProvider(response(map_output(), credits="5"))
    with pytest.raises(AgentError) as error:
        generate(provider, AGENT_HARD_CAP_CREDITS_PER_REQUEST=5)
    assert error.value.code == "BUDGET_EXCEEDED"
    assert error.value.details["usage"]["credits"] == 5
    assert len(provider.calls) == 1


def test_reduce_failure_preserves_map_usage_and_partial_failure_usage():
    provider = StageProvider(
        response(map_output(), credits="1"),
        AgentError(
            status_code=503,
            code="PROVIDER_UNAVAILABLE",
            message="offline failure",
            details={"usage": {"credits": 2}},
        ),
    )
    with pytest.raises(AgentError) as error:
        generate(provider)
    assert error.value.details["usage"]["credits"] == 3
    assert error.value.details["usage"]["inputTokens"] == 100
    assert error.value.details["executionMetadata"]["usageCompleteness"] == "PARTIAL"


@pytest.mark.parametrize("credits", [0, 6])
def test_first_map_failure_with_complete_usage_is_observed_even_without_response(credits):
    usage = {"inputTokens": 100, "outputTokens": 50, "costUsd": 0.01, "credits": credits}
    provider = StageProvider(AgentError(429, "BUDGET_EXCEEDED", "offline cap", {"usage": usage}))
    with pytest.raises(AgentError) as error:
        generate(provider)
    assert error.value.details["usage"] == usage
    assert error.value.details["executionMetadata"]["usageCompleteness"] == "COMPLETE"
    assert len(provider.calls) == 1


@pytest.mark.parametrize("details", [None, {"usage": {}}, {"usage": {"credits": True}}])
def test_first_map_failure_without_observed_usage_is_unknown(details):
    provider = StageProvider(AgentError(503, "PROVIDER_UNAVAILABLE", "offline failure", details))
    with pytest.raises(AgentError) as error:
        generate(provider)
    assert error.value.details["executionMetadata"]["usageCompleteness"] == "UNKNOWN"


def test_first_map_failure_with_partially_observed_zero_usage_is_partial():
    provider = StageProvider(
        AgentError(503, "PROVIDER_UNAVAILABLE", "offline failure", {"usage": {"credits": 0}})
    )
    with pytest.raises(AgentError) as error:
        generate(provider)
    assert error.value.details["usage"]["credits"] == 0
    assert error.value.details["executionMetadata"]["usageCompleteness"] == "PARTIAL"


def test_unknown_reduce_failure_is_partial_and_keeps_known_map_usage():
    provider = StageProvider(
        response(map_output()), AgentError(503, "PROVIDER_UNAVAILABLE", "offline timeout")
    )
    with pytest.raises(AgentError) as error:
        generate(provider)
    assert error.value.details["usage"]["credits"] == 1
    assert error.value.details["executionMetadata"]["usageCompleteness"] == "PARTIAL"


def test_reduce_schema_failure_preserves_all_calls_including_map():
    invalid = reduce_output()
    invalid["insights"][0]["overview"][0]["basisClaimIds"] = ["999:0"]
    provider = StageProvider(response(map_output()), response(invalid), response(invalid))
    with pytest.raises(AgentError) as error:
        generate(provider)
    assert error.value.code == "SCHEMA_VIOLATION"
    assert error.value.details["usage"]["credits"] == 3
    assert error.value.details["usage"]["inputTokens"] == 300
    assert error.value.details["validationFailure"]["stage"] == "REDUCE"
    assert error.value.details["validationFailure"]["attempt"] == 2
    assert error.value.details["executionMetadata"]["usageCompleteness"] == "COMPLETE"


@pytest.mark.parametrize(
    "configured_timeout,expected_budget,review_seconds",
    [(None, 180, 60), (90, 90, 30), (120, 120, 40), (300, 180, 60)],
)
def test_insight_deadline_is_independent_and_bounds_review_and_synthesis(
    monkeypatch, configured_timeout, expected_budget, review_seconds
):
    monkeypatch.delenv("AGENT_REPORT_INSIGHT_TIMEOUT_SECONDS", raising=False)
    clock = [0.0]
    monkeypatch.setattr("app.llm.report_insight_pipeline.monotonic", lambda: clock[0])
    values = {"AGENT_REPORT_PROVIDER_TIMEOUT_SECONDS": 9}
    if configured_timeout is not None:
        values["AGENT_REPORT_INSIGHT_TIMEOUT_SECONDS"] = configured_timeout
    pipeline = ReportInsightPipelineProvider(Settings(**values), "FREE")

    assert pipeline.deadline == expected_budget
    # Starting REVIEW needs room for its whole window and the same REDUCE reserve.
    clock[0] = expected_budget - 2 * review_seconds
    assert pipeline.can_start_optional_review()
    with pipeline.optional_review_deadline():
        assert pipeline.deadline == expected_budget - review_seconds
    assert pipeline.deadline == expected_budget
    clock[0] += 0.001
    assert not pipeline.can_start_optional_review()


def test_overall_deadline_stops_reduce_and_keeps_observed_charge(monkeypatch):
    clock = [0.0]
    monkeypatch.setattr("app.llm.report_insight_pipeline.monotonic", lambda: clock[0])

    class SlowProvider(StageProvider):
        def generate(self, **kwargs):
            result = super().generate(**kwargs)
            clock[0] = 10
            return result

    provider = SlowProvider(response(map_output()))
    with pytest.raises(AgentError) as error:
        generate(provider, AGENT_REPORT_INSIGHT_TIMEOUT_SECONDS=9)
    assert error.value.details["requestDeadlineExceeded"] is True
    assert error.value.details["usage"]["credits"] == 1
    assert len(provider.calls) == 1


def test_shared_cooldown_beyond_deadline_does_not_sleep_or_invoke_provider():
    clock = [0.0]
    sleeps = []
    coordinator = ProviderRequestCoordinator(
        ProviderRequestPolicy(1),
        clock=lambda: clock[0],
        sleeper=sleeps.append,
    )
    coordinator.wait_after_rate_limit(
        AgentError(
            503,
            "PROVIDER_UNAVAILABLE",
            "limited",
            {"retryAfterSeconds": 60},
        ),
        1,
    )
    with pytest.raises(AgentError) as error:
        coordinator.wait_before_call(deadline=10)
    assert error.value.details["requestDeadlineExceeded"] is True
    assert sleeps == []


@pytest.mark.parametrize(
    "configured_timeout,expected_timeouts", [(90, [90, 80]), (120, [120, 110]), (300, [180, 170])]
)
@pytest.mark.parametrize("plan", ["FREE", "PAID"])
def test_native_clients_are_scoped_closed_and_get_remaining_deadline(
    monkeypatch, configured_timeout, expected_timeouts, plan
):
    clock = [0.0]
    monkeypatch.setattr("app.llm.report_insight_pipeline.monotonic", lambda: clock[0])
    captured_settings = []
    captured_deadlines = []
    closed = []
    # Native OpenAI reports monetary cost but always zero Mindlogic credits.
    credit = "0" if plan == "FREE" else "1"
    results = [response(map_output(), credits=credit), response(reduce_output(), credits=credit)]

    class Transport:
        def __init__(self, config, *, request_deadline=None):
            captured_settings.append(config)
            captured_deadlines.append(request_deadline)

        def generate(self, **kwargs):
            clock[0] += 10
            return results.pop(0)

        def close(self):
            closed.append(True)

    guard = ProviderGuard(
        concurrency=1,
        acquire_timeout_seconds=1,
        failure_threshold=3,
        cooldown_seconds=30,
        hard_cap_credits=Decimal(5),
    )
    coordinator = ProviderRequestCoordinator(ProviderRequestPolicy(0), clock=lambda: clock[0])
    monkeypatch.setattr("app.llm.report_insight_pipeline.OpenAIAnalyzeProvider", Transport)
    monkeypatch.setattr("app.llm.report_insight_pipeline.MindlogicAnalyzeProvider", Transport)
    monkeypatch.setattr("app.llm.report_insight_pipeline.get_provider_guard", lambda *_: guard)
    monkeypatch.setattr(
        "app.llm.report_insight_pipeline.get_provider_coordinator", lambda *_: coordinator
    )
    settings = Settings(
        AGENT_MOCK=False,
        OPENAI_API_KEY="offline-test-only",
        MINDLOGIC_API_KEY="offline-test-only",
        MINDLOGIC_CLAUDE_MODEL="offline-model",
        OPENAI_REQUEST_INTERVAL_SECONDS=0,
        AGENT_REPORT_PROVIDER_TIMEOUT_SECONDS=configured_timeout,
        AGENT_REPORT_INSIGHT_TIMEOUT_SECONDS=configured_timeout,
    )
    body = request_body()
    body["plan"] = plan
    ReportInsightService(settings).generate(ReportInsightRequest.model_validate(body))
    assert [config.provider_timeout_seconds for config in captured_settings] == expected_timeouts
    assert captured_deadlines == [min(configured_timeout, MAX_REPORT_INSIGHT_DEADLINE_SECONDS)] * 2
    assert all(config.provider_retry_attempts == 0 for config in captured_settings)
    assert len(closed) == 2

    # Ordinary reports using the same immutable Settings retain their configured timeout.
    from test_report_service import FakeProvider, provider_response, request, valid_output

    from app.llm.report_service import ReportWriterService
    from app.llm.router import close_analyze_providers

    ordinary_configs = []

    class OrdinaryTransport:
        def __init__(self, config, *, request_deadline=None):
            ordinary_configs.append((config.provider_timeout_seconds, request_deadline))
            self.delegate = FakeProvider(provider_response(valid_output()))

        def generate(self, **kwargs):
            return self.delegate.generate(**kwargs)

    monkeypatch.setattr("app.llm.router.OpenAIAnalyzeProvider", OrdinaryTransport)
    monkeypatch.setattr("app.llm.router.MindlogicAnalyzeProvider", OrdinaryTransport)
    close_analyze_providers()
    try:
        ReportWriterService(settings).write(request().model_copy(update={"plan": plan}))
        assert ordinary_configs == [(configured_timeout, None)]
    finally:
        close_analyze_providers()


@pytest.mark.parametrize("configured_timeout,blocking_interval", [(90, 91), (300, 181)])
def test_pipeline_coordinator_rejects_wait_beyond_its_bounded_deadline(
    monkeypatch, configured_timeout, blocking_interval
):
    clock = [0.0]
    sleeps = []
    monkeypatch.setattr("app.llm.report_insight_pipeline.monotonic", lambda: clock[0])
    coordinator = ProviderRequestCoordinator(
        ProviderRequestPolicy(blocking_interval), clock=lambda: clock[0], sleeper=sleeps.append
    )
    coordinator.wait_before_call()
    guard = ProviderGuard(
        concurrency=1,
        acquire_timeout_seconds=1,
        failure_threshold=3,
        cooldown_seconds=30,
        hard_cap_credits=Decimal(5),
    )
    monkeypatch.setattr("app.llm.report_insight_pipeline.get_provider_guard", lambda *_: guard)
    monkeypatch.setattr(
        "app.llm.report_insight_pipeline.get_provider_coordinator", lambda *_: coordinator
    )
    settings = Settings(
        AGENT_MOCK=False,
        OPENAI_API_KEY="offline-test-only",
        AGENT_REPORT_INSIGHT_TIMEOUT_SECONDS=configured_timeout,
    )
    pipeline = ReportInsightPipelineProvider(settings, "FREE")

    with pytest.raises(AgentError) as error:
        pipeline.generate(system_instruction="offline", prompt="offline", response_schema={})

    assert error.value.details["requestDeadlineExceeded"] is True
    assert pipeline.calls == 0
    assert sleeps == []


def test_backend_default_wait_covers_maximum_insight_deadline_and_response_margin():
    application = Path(__file__).resolve().parents[2] / "BE/src/main/resources/application.yml"
    match = re.search(
        r"report-insight-timeout: \$\{AGENT_REPORT_INSIGHT_TIMEOUT:(\d+)s\}",
        application.read_text(encoding="utf-8"),
    )
    assert match is not None
    assert int(match.group(1)) >= MAX_REPORT_INSIGHT_DEADLINE_SECONDS + 30


def test_reduce_refs_bound_to_retrieved_subset_not_all_map_findings(monkeypatch):
    from app.llm.report_insight_retrieval import retrieve_report_insight_evidence

    body = request_body(second=True)
    candidate = output(second=True)
    real_retrieval = retrieve_report_insight_evidence

    def retrieve(*args, **kwargs):
        result = real_retrieval(*args, **kwargs)
        return type(result)(
            result.report_id,
            result.audience,
            tuple(item for item in result.evidence if item.claim_id == "501:0"),
        )

    monkeypatch.setattr("app.llm.report_insight_service.retrieve_report_insight_evidence", retrieve)
    invalid = deepcopy(reduce_output(candidate))
    invalid["insights"][0]["overview"][0]["basisClaimIds"] = ["502:0"]
    fixed = reduce_output(candidate)
    provider = StageProvider(response(map_output(candidate)), response(invalid), response(fixed))
    result = generate(provider, body)
    assert len(result.insights[0].assessments) == 2
    assert len(provider.calls) == 3
    assert result.insights[0].overview[0].basis_claim_ids == ["501:0"]


def test_reduce_headline_cannot_use_company_from_unretrieved_finding(monkeypatch):
    from app.llm.report_insight_retrieval import retrieve_report_insight_evidence

    body = request_body(second=True)
    second = body["findings"][1]
    second["claims"][0]["text"] = "SK하이닉스는 검증 장비 도입을 추진한다."
    second["sentences"][0]["text"] = second["claims"][0]["text"]
    real_retrieval = retrieve_report_insight_evidence

    def retrieve(*args, **kwargs):
        result = real_retrieval(*args, **kwargs)
        return type(result)(
            result.report_id,
            result.audience,
            tuple(item for item in result.evidence if item.claim_id == "501:0"),
        )

    monkeypatch.setattr("app.llm.report_insight_service.retrieve_report_insight_evidence", retrieve)
    candidate = output(second=True)
    invalid = deepcopy(reduce_output(candidate))
    invalid["insights"][0]["headline"] = "SK하이닉스의 공정 검증 준비 조건을 확인해야 한다."
    provider = StageProvider(
        response(map_output(candidate)), response(invalid), response(reduce_output(candidate))
    )
    result = generate(provider, body)
    assert len(provider.calls) == 3
    assert "SK하이닉스" not in result.insights[0].headline
