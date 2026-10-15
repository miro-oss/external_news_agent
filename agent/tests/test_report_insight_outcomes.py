"""Unavailable generation must not masquerade as a semantic abstention."""

import json
import sys

import pytest

from app.eval import report_insight_operational as operational
from app.eval.report_insight_outcomes import generation_outcome, summarize_outcomes
from app.eval.report_insight_regression_matrix import measure_run


def job(status, *, failure=None, assessments=()):
    value = {
        "jobId": status,
        "caseId": "case",
        "caseIndex": 0,
        "repeat": 0,
        "audience": "MARKET_INVESTOR",
        "status": status,
        "variant": "staged",
        "attemptIds": [],
    }
    if failure is not None:
        value["failure"] = failure
    if status == "success":
        value["response"] = {
            "insights": [
                {
                    "audience": "MARKET_INVESTOR",
                    "assessments": list(assessments),
                    "overview": [],
                    "implications": [],
                    "watchItems": [],
                }
            ]
        }
    return value


def assessment(directness, impact, urgency, *, evidence=True):
    return {
        "basisClaimIds": ["101:0"] if evidence else [],
        "axes": {"directness": directness, "impact": impact, "urgency": urgency},
    }


def test_mixed_outcomes_keep_all_planned_results_and_distinct_denominators():
    results = [
        job("success"),
        job("failed", failure={"code": "PROVIDER_UNAVAILABLE", "class": "EVALUATION_LIMIT"}),
        job(
            "failed",
            failure={
                "code": "SCHEMA_VIOLATION",
                "validationFailure": {
                    "stage": "MAP-002",
                },
            },
        ),
        job("failed", failure={"code": "PROVIDER_TIMEOUT"}),
        job("failed", failure={"code": "EVALUATION_ERROR"}),
        job("pending"),
        job("running"),
    ]
    result = summarize_outcomes(results)
    assert result["counts"] == {
        "generated": 1,
        "pending": 1,
        "running": 1,
        "evaluation_limited": 1,
        "validation_rejected": 1,
        "technical_failure": 1,
        "unknown_failure": 1,
        "unknown_status": 0,
    }
    assert result["measuredGenerationResults"] == 4
    assert result["generationSuccessRate"] == 1 / 4
    assert result["measurementCoverageRate"] == 4 / 7
    assert result["plannedResultAvailabilityRate"] == 1 / 7
    assert result["terminalValidationFailuresByStage"] == {"MAP-002": 1}
    assert result["falseRejectionRate"] is None


def test_successful_empty_synthesis_and_unknown_axes_do_not_prove_bad_quality():
    result = summarize_outcomes(
        [
            job(
                "success",
                assessments=[
                    assessment(None, None, None, evidence=False),
                    assessment(0, None, None),
                    assessment(None, None, None),
                    assessment(3, None, 1),
                    assessment(2, 2, None),
                ],
            )
        ]
    )
    assert result["counts"]["generated"] == 1
    assert result["successfulInsightsWithoutSynthesis"] == 1
    assert result["observedAssessmentStates"] == {
        "unrelated": 1,
        "relevance_unconfirmed": 2,
        "impact_unconfirmed": 1,
        "scored": 1,
    }
    # A normal UNDETERMINED assessment has no selected basis even when its
    # input contains source evidence. Output-only metrics cannot prove absence.
    assert result["assessmentsWithoutSelectedBasis"] == 1
    assert result["unnecessaryAbstentionRate"] is None
    assert result["requiresHumanLabels"]
    assert not result["semanticQualityMeasured"]


def test_repaired_draft_failures_do_not_become_terminal_rejections():
    result = job(
        "success",
        failure={
            "code": "SCHEMA_VIOLATION",
            "validationFailure": {"stage": "REVIEW-001"},
        },
    )
    summary = summarize_outcomes([result])
    assert summary["counts"]["generated"] == 1
    assert summary["counts"]["validation_rejected"] == 0
    assert summary["terminalValidationFailuresByStage"] == {}


@pytest.mark.parametrize(
    "failure",
    [
        {"code": "PROVIDER_UNAVAILABLE", "requestDeadlineExceeded": True},
        {"code": "PROVIDER_UNAVAILABLE", "rateLimited": True},
        {"code": "BUDGET_EXCEEDED"},
        {"code": "PROVIDER_TIMEOUT", "timeoutPhase": "read"},
    ],
)
def test_technical_failure_during_repair_is_not_a_validation_verdict(failure):
    failure["validationFailure"] = {"stage": "EXPLAIN-001"}
    assert generation_outcome(job("failed", failure=failure)) == "technical_failure"


def test_absent_or_unknown_state_is_not_success_or_zero_quality():
    assert summarize_outcomes([])["generationSuccessRate"] is None
    assert summarize_outcomes([job("pending")])["generationSuccessRate"] is None
    result = summarize_outcomes([job("interrupted")])
    assert result["counts"]["unknown_status"] == 1
    assert result["measurementCoverageRate"] == 0


def test_untrusted_failure_stage_is_not_copied_into_summary():
    result = summarize_outcomes(
        [
            job(
                "failed",
                failure={
                    "code": "SCHEMA_VIOLATION",
                    "validationFailure": {"stage": "raw-source-or-secret"},
                },
            )
        ]
    )
    assert result["terminalValidationFailuresByStage"] == {"UNRECORDED": 1}
    assert "raw-source-or-secret" not in str(result)


def test_operational_and_regression_summaries_share_classification():
    state = {"results": [job("success"), job("pending")], "attempts": [], "stages": []}
    assert (
        operational.summarize(state)["generationOutcomes"]
        == (measure_run(state)["policies"]["production"]["generationOutcomes"])
    )


def test_development_success_cannot_hide_holdout_failure():
    results = [dict(job("success"), split="development") for _ in range(99)]
    results.append(dict(job("failed", failure={"code": "SCHEMA_VIOLATION"}), split="holdout"))
    result = summarize_outcomes(results)
    assert result["generationSuccessRate"] == 0.99
    assert result["bySplit"]["development"]["generationSuccessRate"] == 1
    assert result["bySplit"]["holdout"]["generationSuccessRate"] == 0
    assert "unrecorded" not in result["bySplit"]


def test_legacy_or_unknown_split_is_explicitly_unrecorded():
    result = summarize_outcomes([job("success"), dict(job("pending"), split="unknown")])
    assert set(result["bySplit"]) == {"unrecorded"}
    assert result["bySplit"]["unrecorded"]["plannedResults"] == 2


def test_offline_summary_reads_historical_results_without_runtime_or_provider(
    tmp_path,
    monkeypatch,
    capsys,
):
    state = {"results": [job("pending")], "attempts": [], "stages": []}
    original = json.dumps(state)
    (tmp_path / "result.json").write_text(original)

    def forbidden(*args, **kwargs):
        pytest.fail("Offline summary must not check runtime, load keys, or generate")

    monkeypatch.setattr(operational, "check", forbidden)
    monkeypatch.setattr(operational, "run", forbidden)
    monkeypatch.setattr(operational.getpass, "getpass", forbidden)
    monkeypatch.setattr(sys, "argv", ["operational", "summarize", "--output", str(tmp_path)])
    operational.main()
    result = json.loads(capsys.readouterr().out)
    assert result["generationOutcomes"]["counts"]["pending"] == 1
    assert (tmp_path / "result.json").read_text() == original
