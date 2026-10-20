"""Mandatory recovery precedes optional polish, within the original request budget."""

import pytest
from test_report_insight_assessment import request
from test_report_insight_explanation_recovery import generate
from test_report_insight_review_time_admission import assert_usage
from test_report_insight_v4_pipeline import V4Provider, stages

from app.core.errors import AgentError
from app.llm import report_insight_service as service

TOPICS = {
    "CHIP_MAKER": (
        "제조사는 공정 검증 계획의 변경을 발표했다.",
        "발표된 공정 검증 계획의 변경은 공정 검증 판단에 연결된다.",
    ),
    "EQUIPMENT_MAKER": (
        "장비 공급사는 신규 장비의 공정 검증 계획의 변경을 발표했다.",
        "장비 공정 검증 계획의 변경은 공정 검증 판단에 연결된다.",
    ),
    "IT_INFRA": (
        "운영사는 서버 조달 계획의 변경을 발표했다.",
        "서버 조달 계획의 변경은 시스템 조달 판단에 연결된다.",
    ),
    "MARKET_INVESTOR": (
        "기업은 내년 매출 전망을 하향 조정했다고 밝혔다.",
        "내년 매출 전망의 하향 조정은 실적 전망 판단에 연결된다.",
    ),
}


def timed_case(
    monkeypatch,
    *,
    audience="MARKET_INVESTOR",
    count=1,
    multiple_claims=True,
    map_elapsed=80.0,
    invalid_map=True,
    review_ends=None,
    invalid_review=lambda _stage, _attempt: False,
    invalid_explanation=False,
    explanation_end=None,
    reduce_end=None,
):
    text, reason = TOPICS[audience]
    source = request(ids=tuple(range(100 + count, 100, -1)), audiences=(audience,), text=text)
    if multiple_claims:
        for finding in source.findings:
            finding.claims.append(finding.claims[0].model_copy(update={"id": f"{finding.id}:1"}))
    clock = [0.0]
    pipelines, review_deadlines = [], []
    pipeline_class = service.ReportInsightPipelineProvider

    def capture_pipeline(*args, **kwargs):
        pipeline = pipeline_class(*args, **kwargs)
        pipelines.append(pipeline)
        return pipeline

    monkeypatch.setattr("app.llm.report_insight_pipeline.monotonic", lambda: clock[0])
    monkeypatch.setattr(service, "ReportInsightPipelineProvider", capture_pipeline)

    def hook(stage, occurrence, _data, value):
        if stage.startswith("REDUCE"):
            if reduce_end is not None:
                clock[0] = reduce_end
            insight = value["insights"][0]
            insight["headline"] = "원문에 제시된 업무 판단을 확인한다."
            insight["overview"][0].update(text=reason, assumption="해당 내용을 검토하는 경우")
            return value
        if stage.startswith("MAP"):
            clock[0] = map_elapsed
            invalid = invalid_map
        elif stage.startswith("REVIEW"):
            review_deadlines.append((stage, pipelines[0].deadline))
            clock[0] = (review_ends or {}).get((stage, occurrence), clock[0])
            invalid = invalid_review(stage, occurrence)
        else:
            if explanation_end is not None:
                clock[0] = explanation_end
            invalid = invalid_explanation
        for record in value["assessments"][audience].values():
            record.update(
                impactScope="UNDETERMINED",
                impactBasis=None,
                urgencyState="UNDETERMINED",
                urgencyBasis=None,
                reason=reason + (" 9999개의 변경을 확인한다." if invalid else ""),
            )
        return value

    return source, V4Provider(source, hook=hook), pipelines, review_deadlines


@pytest.mark.parametrize("audience", TOPICS)
@pytest.mark.parametrize("map_elapsed", [60.001, 80.0, 90.0])
def test_required_review_does_not_reserve_optional_explanation_time(
    monkeypatch, audience, map_elapsed
):
    source, provider, pipelines, deadlines = timed_case(
        monkeypatch, audience=audience, map_elapsed=map_elapsed
    )
    original = source.model_dump_json()

    result = generate(provider, source, AGENT_REPORT_INSIGHT_TIMEOUT_SECONDS=180)

    assert stages(provider) == ["MAP-001", "MAP-001", "REVIEW-001", "EXPLAIN-001", "REDUCE-001"]
    assert deadlines == [("REVIEW-001", 120.0)]
    assert pipelines[0].deadline == 180.0
    assert all(provider.schema_validity)
    assessment = result.insights[0].assessments[0]
    assert assessment.axes.directness == 3
    assert assessment.axes.impact is assessment.axes.urgency is None
    assert TOPICS[audience][1] in assessment.reason
    assert "9999" not in assessment.reason
    assert_usage(result.meta.model_dump(by_alias=True), 5)
    assert source.model_dump_json() == original


@pytest.mark.parametrize(
    "request_seconds,map_elapsed", [(180, 90.001), (120, 50.001), (60, 20.001)]
)
def test_required_review_still_needs_reduce_and_minimum_review_headroom(
    monkeypatch, request_seconds, map_elapsed
):
    source, provider, pipelines, deadlines = timed_case(monkeypatch, map_elapsed=map_elapsed)
    with pytest.raises(AgentError) as caught:
        generate(provider, source, AGENT_REPORT_INSIGHT_TIMEOUT_SECONDS=request_seconds)
    assert stages(provider) == ["MAP-001", "MAP-001"]
    assert deadlines == []
    assert caught.value.code == "SCHEMA_VIOLATION"
    assert caught.value.details["validationFailure"]["stage"] == "MAP-001"
    assert caught.value.details["validationFailure"]["attempt"] == 2
    assert pipelines[0].deadline == request_seconds
    assert_usage(caught.value.details["usage"], 2)


@pytest.mark.parametrize("repair_end,succeeds", [(119.999, True), (120.0, False)])
def test_required_review_and_its_repair_share_unchanged_reduce_boundary(
    monkeypatch, repair_end, succeeds
):
    source, provider, pipelines, deadlines = timed_case(
        monkeypatch,
        review_ends={("REVIEW-001", 1): 115.0, ("REVIEW-001", 2): repair_end},
        invalid_review=lambda _stage, attempt: attempt == 1,
    )
    if succeeds:
        result = generate(provider, source, AGENT_REPORT_INSIGHT_TIMEOUT_SECONDS=180)
        # The repaired REVIEW prose is valid. Its optional EXPLAIN is skipped
        # at this late boundary, leaving the original REDUCE reserve intact.
        assert_usage(result.meta.model_dump(by_alias=True), 5)
        assert stages(provider)[-2:] == ["REVIEW-001", "REDUCE-001"]
        assert provider.stages["EXPLAIN-001"] == 0
    else:
        with pytest.raises(AgentError) as caught:
            generate(provider, source, AGENT_REPORT_INSIGHT_TIMEOUT_SECONDS=180)
        assert caught.value.code == "PROVIDER_UNAVAILABLE"
        assert caught.value.details["requestDeadlineExceeded"] is True
        assert stages(provider)[-2:] == ["REVIEW-001", "REVIEW-001"]
        assert_usage(caught.value.details["usage"], 4)
    assert deadlines == [("REVIEW-001", 120.0), ("REVIEW-001", 120.0)]
    assert pipelines[0].deadline == 180.0


@pytest.mark.parametrize("multiple_claims", [False, True])
@pytest.mark.parametrize("review_end,second_admitted", [(59.999, True), (60.001, False)])
def test_pending_required_explanation_reserves_time_before_later_required_review(
    monkeypatch, multiple_claims, review_end, second_admitted
):
    source, provider, _, deadlines = timed_case(
        monkeypatch,
        count=7,
        multiple_claims=multiple_claims,
        map_elapsed=50.0,
        review_ends={("REVIEW-001", 1): review_end, ("REVIEW-001", 2): review_end},
        invalid_review=lambda stage, _attempt: stage == "REVIEW-001",
    )
    if second_admitted:
        result = generate(provider, source, AGENT_REPORT_INSIGHT_TIMEOUT_SECONDS=180)
        assert ("REVIEW-002", 90.0) in deadlines
        assert "EXPLAIN-001" in stages(provider)
        assert stages(provider)[-1] == "REDUCE-001"
        assert len(result.insights[0].assessments) == 7
        assert all("9999" not in row.reason for row in result.insights[0].assessments)
        assert_usage(result.meta.model_dump(by_alias=True), len(provider.calls))
    else:
        with pytest.raises(AgentError) as caught:
            generate(provider, source, AGENT_REPORT_INSIGHT_TIMEOUT_SECONDS=180)
        assert caught.value.code == "SCHEMA_VIOLATION"
        assert all(stage != "REVIEW-002" for stage, _ in deadlines)
        assert stages(provider) == [
            "MAP-001",
            "MAP-001",
            "MAP-002",
            "MAP-002",
            "REVIEW-001",
            "REVIEW-001",
        ]
        assert_usage(caught.value.details["usage"], 6)


def test_pending_single_claim_explanation_also_precedes_later_optional_review(monkeypatch):
    source, provider, _, _ = timed_case(
        monkeypatch,
        count=7,
        multiple_claims=False,
        invalid_map=False,
        map_elapsed=50.0,
        review_ends={("REVIEW-001", 1): 61.0, ("REVIEW-001", 2): 61.0},
        invalid_review=lambda stage, _attempt: stage == "REVIEW-001",
    )
    result = generate(provider, source, AGENT_REPORT_INSIGHT_TIMEOUT_SECONDS=180)
    assert stages(provider) == [
        "MAP-001",
        "MAP-002",
        "REVIEW-001",
        "REVIEW-001",
        "EXPLAIN-001",
        "REDUCE-001",
    ]
    assert len(result.insights[0].assessments) == 7
    assert all("9999" not in row.reason for row in result.insights[0].assessments)
    assert_usage(result.meta.model_dump(by_alias=True), 6)


def test_optional_review_keeps_its_existing_explanation_reserve(monkeypatch):
    source, provider, _, deadlines = timed_case(monkeypatch, invalid_map=False)
    result = generate(provider, source, AGENT_REPORT_INSIGHT_TIMEOUT_SECONDS=180)
    assert deadlines == []
    assert stages(provider) == ["MAP-001", "EXPLAIN-001", "REDUCE-001"]
    assert_usage(result.meta.model_dump(by_alias=True), 3)


@pytest.mark.parametrize("audience", TOPICS)
def test_required_explanation_failure_still_rejects_invalid_prose(monkeypatch, audience):
    source, provider, _, _ = timed_case(
        monkeypatch,
        audience=audience,
        invalid_review=lambda _stage, _attempt: True,
        invalid_explanation=True,
    )
    with pytest.raises(AgentError) as caught:
        generate(provider, source, AGENT_REPORT_INSIGHT_TIMEOUT_SECONDS=180)
    assert stages(provider) == [
        "MAP-001",
        "MAP-001",
        "REVIEW-001",
        "REVIEW-001",
        "EXPLAIN-001",
        "EXPLAIN-001",
    ]
    assert caught.value.code == "SCHEMA_VIOLATION"
    assert caught.value.details["validationFailure"]["stage"] == "EXPLAIN-001"
    assert caught.value.details["validationFailure"]["attempt"] == 2
    assert_usage(caught.value.details["usage"], 6)


@pytest.mark.parametrize("configured_seconds", [180, 240])
def test_late_recovery_does_not_extend_total_deadline(monkeypatch, configured_seconds):
    source, provider, pipelines, _ = timed_case(monkeypatch, reduce_end=180.0)
    with pytest.raises(AgentError) as caught:
        generate(provider, source, AGENT_REPORT_INSIGHT_TIMEOUT_SECONDS=configured_seconds)
    assert stages(provider)[-1] == "REDUCE-001"
    assert pipelines[0].deadline == 180.0
    assert caught.value.code == "PROVIDER_UNAVAILABLE"
    assert caught.value.details["requestDeadlineExceeded"] is True
    assert_usage(caught.value.details["usage"], 5)


def test_admitted_required_review_does_not_retry_timeout_or_discard_known_usage(monkeypatch):
    source, provider, _, _ = timed_case(monkeypatch)

    def timeout(stage, _attempt, _data, raw):
        if stage == "REVIEW-001":
            raise AgentError(
                503, "PROVIDER_UNAVAILABLE", "synthetic timeout", {"timeoutPhase": "READ"}
            )
        return raw

    provider.raw_hook = timeout
    with pytest.raises(AgentError) as caught:
        generate(provider, source, AGENT_REPORT_INSIGHT_TIMEOUT_SECONDS=180)
    assert stages(provider) == ["MAP-001", "MAP-001", "REVIEW-001"]
    assert caught.value.code == "PROVIDER_UNAVAILABLE"
    assert caught.value.details["timeoutPhase"] == "READ"
    assert caught.value.details["executionMetadata"]["usageCompleteness"] == "PARTIAL"
    assert_usage(caught.value.details["usage"], 2)


@pytest.mark.parametrize("cap,observed_calls", [(0.4, 2), (0.5, 3)])
def test_admitted_required_review_keeps_request_credit_cap(monkeypatch, cap, observed_calls):
    source, provider, _, _ = timed_case(monkeypatch)
    with pytest.raises(AgentError) as caught:
        generate(
            provider,
            source,
            AGENT_REPORT_INSIGHT_TIMEOUT_SECONDS=180,
            AGENT_HARD_CAP_CREDITS_PER_REQUEST=cap,
        )
    assert caught.value.code == "BUDGET_EXCEEDED"
    assert caught.value.details["hardCapCredits"] == cap
    assert stages(provider) == ["MAP-001", "MAP-001", "REVIEW-001"][:observed_calls]
    assert_usage(caught.value.details["usage"], observed_calls)


def test_required_review_with_invalid_native_basis_cannot_proceed_to_explanation(monkeypatch):
    source, provider, _, _ = timed_case(monkeypatch)

    def wrong_basis(stage, _attempt, _data, wire):
        if stage.startswith("REVIEW"):
            wire["assessments"]["MARKET_INVESTOR"]["finding101"]["decision"]["connection"]["basis"][
                "sourceSpanId"
            ] = "s999_0_0"
        return wire

    provider.wire_hook = wrong_basis
    provider.validate_wire = False
    with pytest.raises(AgentError) as caught:
        generate(provider, source, AGENT_REPORT_INSIGHT_TIMEOUT_SECONDS=180)
    assert stages(provider) == ["MAP-001", "MAP-001", "REVIEW-001", "REVIEW-001"]
    assert caught.value.code == "SCHEMA_VIOLATION"
    assert caught.value.details["validationFailure"]["stage"] == "REVIEW-001"
    assert caught.value.details["validationFailure"]["attempt"] == 2
    assert_usage(caught.value.details["usage"], 4)


@pytest.mark.parametrize("audience", TOPICS)
@pytest.mark.parametrize(
    "explanation_end,reduce_end,failed_stage",
    [(150.0, 179.999, None), (150.0, 180.0, "REDUCE-001"), (180.0, None, "EXPLAIN-001")],
)
def test_late_required_explanation_uses_shared_request_time_without_extending_it(
    monkeypatch, audience, explanation_end, reduce_end, failed_stage
):
    source, provider, pipelines, deadlines = timed_case(
        monkeypatch,
        audience=audience,
        review_ends={("REVIEW-001", 1): 115.0, ("REVIEW-001", 2): 119.0},
        invalid_review=lambda _stage, _attempt: True,
        explanation_end=explanation_end,
        reduce_end=reduce_end,
    )
    expected = ["MAP-001", "MAP-001", "REVIEW-001", "REVIEW-001", "EXPLAIN-001"]
    if failed_stage != "EXPLAIN-001":
        expected.append("REDUCE-001")
    if failed_stage is None:
        result = generate(provider, source, AGENT_REPORT_INSIGHT_TIMEOUT_SECONDS=180)
        assert TOPICS[audience][1] in result.insights[0].assessments[0].reason
        assert "9999" not in result.insights[0].assessments[0].reason
        assert_usage(result.meta.model_dump(by_alias=True), len(expected))
    else:
        with pytest.raises(AgentError) as caught:
            generate(provider, source, AGENT_REPORT_INSIGHT_TIMEOUT_SECONDS=180)
        assert caught.value.code == "PROVIDER_UNAVAILABLE"
        assert caught.value.details["requestDeadlineExceeded"] is True
        assert caught.value.details["executionMetadata"]["usageCompleteness"] == "COMPLETE"
        assert_usage(caught.value.details["usage"], len(expected))
        assert stages(provider)[-1] == failed_stage
    assert stages(provider) == expected
    assert deadlines == [("REVIEW-001", 120.0), ("REVIEW-001", 120.0)]
    assert pipelines[0].deadline == 180.0
