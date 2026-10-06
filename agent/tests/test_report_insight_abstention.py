"""Offline denominator and historical-rubric checks, never model quality claims."""

import json
import sys
from copy import deepcopy

import pytest

from app.eval.report_insight_abstention import main, summarize_abstention


def assessment(identifier, directness, impact, *, evidence=True, urgency=None):
    return {
        "findingId": identifier,
        "basisClaimIds": [f"{identifier}:0"] if evidence else [],
        "axes": {"directness": directness, "impact": impact, "urgency": urgency},
    }


def job(variant, case_id="report", status="success", assessments=None, *, audiences=None):
    entries = assessments if assessments is not None else [assessment(1, 3, 3)]
    audience_values = audiences or ["CHIP_MAKER"]
    return {
        "variant": variant,
        "caseId": case_id,
        "status": status,
        "request": {
            "audiences": audience_values,
            "findings": [{"id": entry["findingId"]} for entry in entries],
        },
        "response": {
            "insights": [
                {"audience": audience, "assessments": deepcopy(entries)}
                for audience in audience_values
            ],
        }
        if status == "success"
        else None,
    }


def state(*jobs, baseline_rubric="report-importance.v6"):
    return {
        "checkpointSha256": "saved-checkpoint-identity",
        "provenance": {
            "runtimeVersions": {
                "baseline": {"rubricVersion": baseline_rubric},
                "candidate": {"rubricVersion": "report-importance.v6"},
            }
        },
        "results": list(jobs),
    }


def test_failure_and_incomplete_results_have_no_observed_zero_abstention_rate():
    saved = state(
        job("baseline", status="failed"),
        job("candidate", status="pending"),
        job("candidate", "running-report", "running"),
    )
    summary = summarize_abstention(saved)

    for variant in summary["variants"].values():
        assert variant["successResults"] == 0
        assert variant["successfulResultCoverage"] == 0
        for scope in ("allAssessments", "displayedTopIssues"):
            values = variant[scope]
            assert values["observedCount"] == 0
            assert values["unavailableRate"] is None
            assert values["coverageOfPlanned"] == 0
            assert values["coverageOfSuccessfulResults"] is None
    assert summary["variants"]["baseline"]["failedResults"] == 1
    assert summary["variants"]["candidate"]["incompleteResults"] == 2
    assert summary["pairedSuccessfulCases"]["observedCount"] == 0


def test_full_finding_abstention_is_separate_from_ranked_top_five_and_reasons_partition():
    entries = [
        assessment(1, None, None, evidence=False),
        assessment(2, None, None),
        assessment(3, 3, None),
        assessment(4, 0, None),
        assessment(5, 3, 3),
        assessment(6, 2, 2),
        assessment(7, 1, 1),
        assessment(8, 3, 2),
    ]
    saved = state(job("baseline", assessments=entries), job("candidate", assessments=entries))
    original = deepcopy(saved)
    summary = summarize_abstention(saved)

    values = summary["variants"]["candidate"]
    all_rows, top_rows = values["allAssessments"], values["displayedTopIssues"]
    assert (all_rows["observedCount"], all_rows["unavailableCount"]) == (8, 3)
    assert all_rows["unavailableRate"] == 3 / 8
    assert all_rows["abstentionReasonCounts"] == {
        "uncitedAbstention": 1,
        "directnessUnknown": 1,
        "impactUnknown": 1,
    }
    assert all_rows["unrelatedLowCount"] == 1
    assert all_rows["unrelatedWithUnknownImpactCount"] == 1
    assert top_rows["observedCount"] == top_rows["plannedCount"] == 5
    assert top_rows["unavailableRate"] == 0
    assert summary["displayedIssuesAreProjection"] is True
    assert summary["qualityMeasured"] is summary["qualityImprovementClaimed"] is False
    assert saved == original


def test_historical_v5_unrelated_unknown_impact_stays_unavailable():
    entries = [assessment(1, 0, None)]
    saved = state(
        job("baseline", assessments=entries),
        job("candidate", assessments=entries),
        baseline_rubric="report-importance.v5",
    )
    summary = summarize_abstention(saved)
    baseline, candidate = (
        summary["variants"][name]["allAssessments"] for name in ("baseline", "candidate")
    )
    assert baseline["unavailableRate"] == 1
    assert baseline["abstentionReasonCounts"]["impactUnknown"] == 1
    assert candidate["unavailableRate"] == 0
    assert candidate["unrelatedLowCount"] == 1
    assert summary["qualityImprovementClaimed"] is False


def test_uncited_historical_abstention_does_not_claim_that_source_evidence_is_absent():
    historical = job("baseline", assessments=[assessment(1, None, None, evidence=False)])
    historical["request"]["findings"][0]["claims"] = [
        {"id": "1:0", "text": "제조사는 새로운 공정의 검증을 시작했다."}
    ]
    historical["response"]["insights"][0]["assessments"][0]["reason"] = (
        "원문 근거는 있으나 이 관점의 업무 관련성을 판단하지 못했습니다."
    )
    summary = summarize_abstention(state(historical, baseline_rubric="report-importance.v5"))
    reasons = summary["variants"]["baseline"]["allAssessments"]["abstentionReasonCounts"]
    assert reasons == {"uncitedAbstention": 1, "directnessUnknown": 0, "impactUnknown": 0}
    assert "noUsableEvidence" not in json.dumps(summary)
    assert any("not proof of source absence" in item for item in summary["limitations"])


def test_coverage_counts_all_audiences_and_missing_success_assessments():
    successful = job(
        "candidate",
        assessments=[assessment(1, 3, 3), assessment(2, 3, None)],
        audiences=["CHIP_MAKER", "IT_INFRA"],
    )
    successful["response"]["insights"][1]["assessments"].pop()
    failed = deepcopy(successful)
    failed.update(caseId="failed-report", status="failed", response=None)
    summary = summarize_abstention(state(successful, failed))
    values = summary["variants"]["candidate"]
    assert values["successfulResultCoverage"] == 1 / 2
    all_rows = values["allAssessments"]
    assert all_rows["plannedCount"] == 8
    assert all_rows["expectedInSuccessfulResults"] == 4
    assert all_rows["missingInSuccessfulResults"] == 1
    assert all_rows["coverageOfPlanned"] == 3 / 8
    assert all_rows["coverageOfSuccessfulResults"] == 3 / 4
    assert all_rows["unavailableRate"] == 1 / 3


def test_paired_metrics_exclude_unmatched_success_and_failed_responses():
    saved = state(
        job("baseline"),
        job("candidate"),
        job("baseline", "unmatched", assessments=[assessment(1, None, None)]),
        job("candidate", "unmatched", "failed"),
    )
    # Even a stale response on a failed result must never enter the denominator.
    saved["results"][-1]["response"] = deepcopy(saved["results"][0]["response"])
    summary = summarize_abstention(saved)
    assert summary["variants"]["baseline"]["allAssessments"]["unavailableRate"] == 1 / 2
    paired = summary["pairedSuccessfulCases"]
    assert paired["caseIds"] == ["report"]
    assert paired["coverage"] == 1 / 2
    assert paired["variants"]["baseline"]["allAssessments"]["unavailableRate"] == 0


def test_missing_success_response_cannot_silently_become_a_zero_rate():
    result = job("candidate")
    result["response"] = None
    with pytest.raises(ValueError, match="SUCCESS_RESPONSE_MISSING"):
        summarize_abstention(state(result))


def test_candidate_only_historical_result_does_not_invent_a_baseline():
    saved = state(job("candidate"))
    del saved["provenance"]["runtimeVersions"]["baseline"]
    summary = summarize_abstention(saved)
    baseline = summary["variants"]["baseline"]
    assert baseline["rubricVersion"] is None
    assert baseline["plannedResults"] == 0
    assert baseline["successfulResultCoverage"] is None
    assert baseline["allAssessments"]["coverageOfPlanned"] is None
    assert baseline["allAssessments"]["unavailableRate"] is None
    assert summary["pairedSuccessfulCases"]["observedCount"] == 0


def test_unknown_rubric_is_rejected_instead_of_assuming_current_classification():
    with pytest.raises(ValueError, match="UNSUPPORTED_IMPORTANCE_RUBRIC"):
        summarize_abstention(state(job("baseline"), baseline_rubric="report-importance.v99"))


def test_mismatched_successful_inputs_cannot_be_presented_as_paired():
    baseline, candidate = job("baseline"), job("candidate")
    candidate["request"]["report"] = {"id": 123}
    with pytest.raises(ValueError, match="PAIRED_REQUEST_MISMATCH"):
        summarize_abstention(state(baseline, candidate))


def test_cli_writes_separate_artifact_without_changing_checkpoint(tmp_path, monkeypatch):
    source = tmp_path / "result.json"
    output = tmp_path / "abstention-summary.json"
    source.write_text(json.dumps(state(job("candidate"))))
    original = source.read_bytes()
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "abstention",
            "--checkpoint",
            str(source),
            "--output",
            str(output),
        ],
    )
    main()
    assert source.read_bytes() == original
    assert json.loads(output.read_text())["variants"]["candidate"]["successResults"] == 1
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "abstention",
            "--checkpoint",
            str(source),
            "--output",
            str(source),
        ],
    )
    with pytest.raises(SystemExit):
        main()
    assert source.read_bytes() == original
