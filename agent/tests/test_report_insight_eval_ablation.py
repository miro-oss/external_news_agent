"""Offline ablations must preserve mandatory review and explanation recovery."""

import pytest
from test_report_insight_explanation_recovery import source_report
from test_report_insight_operational import FakeSDK
from test_report_insight_operational import prepared as operational_prepared

from app.eval import report_insight_operational as operational
from app.eval.report_insight_paired_compare import stage_metrics

prepared = operational_prepared


def setup_run(
    prepared,
    monkeypatch,
    *,
    review="production",
    explanation="production",
    hook=None,
    raw_hook=None,
    source=None,
):
    output, _, policy, cases = prepared
    source = source or source_report()
    cases[0].update(
        request=source.model_dump(mode="json", by_alias=True),
        review_policy=review,
        explanation_policy=explanation,
        event_families={finding.id: ["synthetic-production-halt"] for finding in source.findings},
    )
    sdk = FakeSDK(source, monkeypatch)
    sdk.provider.hook = hook
    sdk.provider.raw_hook = raw_hook
    operational.prepare(cases, policy, output)
    return operational.run(output, api_key="offline-test"), sdk


def test_optional_ablation_skips_selected_stages_without_disabling_admission(prepared, monkeypatch):
    state, sdk = setup_run(prepared, monkeypatch, review="disabled", explanation="disabled")
    assert state["results"][0]["status"] == "success"
    assert sdk.provider.stages["REVIEW-001"] == 0
    assert sdk.provider.stages["EXPLAIN-001"] == 0
    assert sdk.provider.stages["REDUCE-001"] == 1
    assert state["stagePolicies"][0]["optionalDisabled"]
    assert any(row["omittedBatches"] for row in state["stagePolicies"] if row["stage"] == "EXPLAIN")


@pytest.mark.parametrize("broken_stage", ["MAP", "REVIEW"])
def test_required_recovery_is_retained_under_optional_ablation(prepared, monkeypatch, broken_stage):
    def stale(stage, _occurrence, _data, value):
        if stage.startswith(broken_stage + "-"):
            value["assessments"]["CHIP_MAKER"]["finding101"]["reason"] = (
                "2037년 생산 중단에 따른 검증 준비를 확인한다."
            )
        return value

    state, sdk = setup_run(
        prepared,
        monkeypatch,
        review="disabled" if broken_stage == "MAP" else "production",
        explanation="disabled",
        hook=stale,
    )
    assert state["results"][0]["status"] == "success", state["results"]
    assert sdk.provider.stages["REVIEW-001"] >= 1
    if broken_stage == "REVIEW":
        assert sdk.provider.stages["EXPLAIN-001"] == 1
        assert any(row.get("requiredFindingIds") == [101] for row in state["stagePolicies"])
    else:
        assert any(row["admitted"] for row in state["reviewAdmissions"])


def test_recovery_is_recorded_separately_from_fresh_stage_success(prepared, monkeypatch):
    state, _ = setup_run(
        prepared,
        monkeypatch,
        raw_hook=lambda stage, _n, _data, raw: "{" if stage.startswith("EXPLAIN-") else raw,
    )
    assert state["results"][0]["status"] == "success"
    assert state["recoveries"] == [
        {
            "jobId": "0:0:CHIP_MAKER",
            "stage": "EXPLAIN-001",
            "outcome": "validated_prior_retained",
            "findingCount": 1,
        }
    ]
    assert any(
        row["stage"] == "EXPLAIN-001" and row["status"] == "failed"
        for row in state["stages"]
        if row["event"] == "stage"
    )
    snapshots = [row for row in state["stages"] if "validatedSnapshot" in row]
    assert snapshots and all(row["requestHash"] for row in snapshots)
    metrics = stage_metrics(state)
    assert metrics["EXPLAIN"]["validatedPriorRetained"] == 1
    assert metrics["EXPLAIN"]["providerAttempts"] == 2
    assert not state["summary"]["semanticQualityMeasured"]


@pytest.mark.parametrize("count", [2, 7])
def test_explanation_ablation_records_mixed_batches_and_only_omits_optional_only_batches(
    prepared, monkeypatch, count
):
    source = source_report(count)
    monkeypatch.setattr(
        operational.report_insight_service,
        "select_review",
        lambda request, _: tuple(finding.id for finding in request.findings),
    )

    def stale(stage, _occurrence, _data, value):
        if stage.startswith("REVIEW-"):
            record = value["assessments"]["CHIP_MAKER"].get("finding101")
            if record:
                record["reason"] = "2037년 생산 중단에 따른 검증 준비를 확인한다."
        return value

    state, sdk = setup_run(prepared, monkeypatch, source=source, explanation="disabled", hook=stale)
    assert state["results"][0]["status"] == "success", state["results"]
    policy = next(row for row in state["stagePolicies"] if row["stage"] == "EXPLAIN")
    assert policy["ablationUnit"] == "batch"
    assert policy["requiredFindingIds"] == [101]
    assert policy["retainedOptionalFindingIds"] == ([102] if count == 2 else [])
    assert policy["omittedFindingIds"] == ([] if count == 2 else list(range(102, 108)))
    assert policy["omittedBatches"] == (0 if count == 2 else 1)
    assert sdk.provider.stages["EXPLAIN-001"] == 1
    assert sdk.provider.stages["EXPLAIN-002"] == 0
    assert state["summary"]["optionalExplanationAblationScope"] == (
        "optional-only batches omitted; mandatory-mixed batches retained"
    )
