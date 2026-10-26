"""Optional prose polish preserves valid assessments and time for synthesis."""

import math
from decimal import Decimal

import pytest
from test_report_insight_explanation_recovery import (
    AUDIENCES,
    NEW_REASON,
    ORIGINAL_REASON,
    generate,
    malformed_explanation,
    source_report,
)
from test_report_insight_required_review_time import timed_case
from test_report_insight_review_time_admission import assert_usage
from test_report_insight_v4_pipeline import V4Provider, stages

from app.core.config import Settings
from app.core.errors import AgentError, StructuredOutputExhaustedError
from app.llm import report_insight_service as service
from app.llm.report_insight_pipeline import ReportInsightPipelineProvider


@pytest.mark.parametrize("request_seconds", [60, 90, 120, 180, 300])
def test_explanation_admission_scales_with_request_budget_without_extending_it(
    monkeypatch, request_seconds
):
    clock = [0.0]
    monkeypatch.setattr("app.llm.report_insight_pipeline.monotonic", lambda: clock[0])
    pipeline = ReportInsightPipelineProvider(
        Settings(_env_file=None, AGENT_REPORT_INSIGHT_TIMEOUT_SECONDS=request_seconds), "FREE"
    )
    budget = min(request_seconds, 180)
    clock[0] = budget / 2
    assert pipeline.can_start_optional_explanation()
    clock[0] += 0.001
    assert not pipeline.can_start_optional_explanation()
    assert pipeline.deadline == budget
    assert pipeline.calls == 0


@pytest.mark.parametrize("stopped", ["cancelled", "expired"])
def test_explanation_admission_never_turns_a_stopped_request_into_a_skip(monkeypatch, stopped):
    clock = [0.0]
    monkeypatch.setattr("app.llm.report_insight_pipeline.monotonic", lambda: clock[0])
    pipeline = ReportInsightPipelineProvider(Settings(_env_file=None), "FREE")
    if stopped == "cancelled":
        pipeline.cancel_pending_calls()
    else:
        clock[0] = pipeline.deadline
    with pytest.raises(AgentError) as caught:
        pipeline.can_start_optional_explanation()
    key = "pipelineCancelled" if stopped == "cancelled" else "requestDeadlineExceeded"
    assert caught.value.details[key] is True
    assert pipeline.calls == 0


@pytest.mark.parametrize("audience", AUDIENCES)
@pytest.mark.parametrize("count", [1, 7])
@pytest.mark.parametrize("concurrency", [1, 3])
def test_late_optional_explanations_preserve_all_findings_and_exact_usage(
    monkeypatch, audience, count, concurrency, caplog
):
    source = source_report(count, audiences=(audience,))
    before = source.model_dump_json()
    clock = [0.0]
    monkeypatch.setattr("app.llm.report_insight_pipeline.monotonic", lambda: clock[0])

    def hook(stage, _occurrence, _data, value):
        if stage.startswith("MAP-"):
            clock[0] = 110.0
        elif stage.startswith("REDUCE-"):
            clock[0] = 170.0
        return value

    provider = V4Provider(source, hook=hook)
    provider.report_insight_max_concurrency = concurrency
    provider.report_insight_credit_reservation = Decimal("0.2")
    with caplog.at_level("INFO"):
        result = generate(provider, source, AGENT_REPORT_INSIGHT_TIMEOUT_SECONDS=180)

    map_count = math.ceil(count / 6)
    assert sorted(stages(provider)) == [
        *(f"MAP-{index:03d}" for index in range(1, map_count + 1)),
        "REDUCE-001",
    ]
    rows = result.insights[0].assessments
    assert [row.finding_id for row in rows] == [finding.id for finding in source.findings]
    assert all(ORIGINAL_REASON in row.reason for row in rows)
    assert all(row.basis_claim_ids == [f"{row.finding_id}:0"] for row in rows)
    assert all(row.axes.directness == row.axes.impact == row.axes.urgency == 3 for row in rows)
    skipped = [
        record.getMessage()
        for record in caplog.records
        if "stage=EXPLAIN-" in record.getMessage()
        and "outcome=SKIPPED_TIME_BUDGET" in record.getMessage()
    ]
    assert len(skipped) == map_count
    assert all("fallback=VALIDATED_ASSESSMENTS_RETAINED" in entry for entry in skipped)
    assert source.model_dump_json() == before
    assert_usage(result.meta.model_dump(by_alias=True), map_count + 1)


def test_later_explanation_batches_recheck_remaining_time(monkeypatch):
    source = source_report(7)
    clock = [0.0]
    monkeypatch.setattr("app.llm.report_insight_pipeline.monotonic", lambda: clock[0])
    monkeypatch.setattr(service, "select_review", lambda *_: ())

    def hook(stage, _occurrence, _data, value):
        if stage.startswith("MAP-"):
            clock[0] = 90.0
        elif stage == "EXPLAIN-001":
            clock[0] = 110.0
            for record in value["assessments"]["CHIP_MAKER"].values():
                record["reason"] = NEW_REASON
        return value

    provider = V4Provider(source, hook=hook)
    result = generate(provider, source, AGENT_REPORT_INSIGHT_TIMEOUT_SECONDS=180)
    assert stages(provider) == ["MAP-001", "MAP-002", "EXPLAIN-001", "REDUCE-001"]
    rows = result.insights[0].assessments
    assert [row.finding_id for row in rows] == list(range(107, 100, -1))
    assert all(NEW_REASON in row.reason for row in rows[:6])
    assert ORIGINAL_REASON in rows[-1].reason
    assert_usage(result.meta.model_dump(by_alias=True), 4)


@pytest.mark.parametrize("defect", ["unselected_fact", "unsupported_year", "fingerprint"])
def test_low_remaining_time_does_not_skip_explanation_with_invalid_retained_proof(
    monkeypatch, defect
):
    source = source_report()
    clock = [0.0]
    monkeypatch.setattr("app.llm.report_insight_pipeline.monotonic", lambda: clock[0])
    prepare = service.prepare_explanation_batches

    def corrupt_saved_proof(request, validated, **kwargs):
        batches = prepare(request, validated, **kwargs)
        clock[0] = 110.0
        record = validated.draft.assessments["CHIP_MAKER"]["finding101"]
        if defect == "unselected_fact":
            record.reason = "별도 행사의 장비 937대 전시 계획에 따른 준비 조건을 확인한다."
        elif defect == "unsupported_year":
            record.reason = "2037년 생산 중단에 따른 검증 준비를 확인한다."
        else:
            validated.finding_fingerprints[101] = "changed-source-identity"
        return batches

    monkeypatch.setattr(service, "prepare_explanation_batches", corrupt_saved_proof)
    provider = V4Provider(source, raw_hook=malformed_explanation)
    with pytest.raises(StructuredOutputExhaustedError):
        generate(provider, source, AGENT_REPORT_INSIGHT_TIMEOUT_SECONDS=180)
    assert provider.stages["EXPLAIN-001"] == 2
    assert provider.stages["REDUCE-001"] == 0


@pytest.mark.parametrize("invalid_review", [False, True])
def test_late_required_review_only_skips_explanation_when_final_prose_is_valid(
    monkeypatch, invalid_review
):
    source, provider, _, _ = timed_case(
        monkeypatch,
        review_ends={("REVIEW-001", 1): 110.0, ("REVIEW-001", 2): 115.0},
        invalid_review=lambda *_: invalid_review,
        invalid_explanation=True,
    )
    if invalid_review:
        with pytest.raises(StructuredOutputExhaustedError) as caught:
            generate(provider, source, AGENT_REPORT_INSIGHT_TIMEOUT_SECONDS=180)
        assert caught.value.details["validationFailure"]["stage"] == "EXPLAIN-001"
        assert provider.stages["EXPLAIN-001"] == 2
        assert provider.stages["REDUCE-001"] == 0
        assert_usage(caught.value.details["usage"], len(provider.calls))
    else:
        result = generate(provider, source, AGENT_REPORT_INSIGHT_TIMEOUT_SECONDS=180)
        assert stages(provider) == ["MAP-001", "MAP-001", "REVIEW-001", "REDUCE-001"]
        assert "9999" not in result.insights[0].assessments[0].reason
        assert_usage(result.meta.model_dump(by_alias=True), len(provider.calls))
