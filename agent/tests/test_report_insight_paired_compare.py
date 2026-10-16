from copy import deepcopy

import pytest
from test_report_insight_assessment import payload, response
from test_report_insight_operational import prepared as operational_prepared

from app.eval import report_insight_operational as operational
from app.eval.report_insight_paired_compare import (
    compare_runs,
    export_comparison_packet,
    freeze_comparison,
    stage_metrics,
    summarize_human_comparison,
)
from app.eval.report_insight_run import EvaluationStopped, digest
from app.llm.report_insight_assessment import validate_draft
from app.schemas.report_insight import ReportInsightRequest

prepared = operational_prepared


@pytest.fixture
def frozen_pair(prepared):
    output, _, policy, cases = prepared
    baseline = operational.prepare(cases, policy, output / "baseline")
    candidate = deepcopy(baseline)
    candidate["runtimeHashes"] = {"candidate": "updated-code"}
    return baseline, candidate


def state(manifest, statuses):
    return {
        "manifestHash": digest(manifest),
        "results": [
            {**job, "status": status}
            for job, status in zip(manifest["jobs"], statuses, strict=True)
        ],
        "attempts": [],
        "stages": [],
    }


def with_outputs(manifest, statuses):
    value = state(manifest, statuses)
    for job in value["results"]:
        if job["status"] != "success":
            continue
        source = ReportInsightRequest.model_validate(
            manifest["cases"][job["caseIndex"]]["request"]
        ).model_copy(update={"audiences": [job["audience"]]})
        mapped = validate_draft(response(payload(source), source), source).mapped
        job["response"] = mapped.model_dump(mode="json", by_alias=True)
        for insight in job["response"]["insights"]:
            insight.update(
                headline="생산 제약을 확인한다.", overview=[], implications=[], watchItems=[]
            )
    return value


def human_labels(packet, units):
    return {
        "packet_hash": packet["packetHash"],
        "kind": "human",
        "annotator": "synthetic-reviewer",
        "judgments": [
            {"unit_id": unit["unitId"], "verdict": "INSUFFICIENT", "anchors": []} for unit in units
        ],
        "provenance": {
            "origin": "external_human_review",
            "reference": "synthetic-test-only",
            "reviewed_at": "2026-01-01",
            "independent_of_judge": True,
        },
    }


@pytest.mark.parametrize(
    "change", ["model", "budget", "settings", "request", "split", "event", "audience"]
)
def test_comparison_rejects_confounding_changes(frozen_pair, change):
    baseline, candidate = frozen_pair
    if change == "model":
        candidate["policy"]["model"] = "other"
    elif change == "budget":
        candidate["policy"]["max_calls"] += 1
    elif change == "settings":
        candidate["policy"]["settings"]["schema_repair_attempts"] += 1
    elif change == "request":
        candidate["cases"][0]["request"]["findings"][0]["sentences"][0]["text"] = "different input"
    elif change == "split":
        candidate["cases"][0]["split"] = "development"
    elif change == "event":
        candidate["cases"][0]["event_families"] = {"101": ["other-event"]}
    else:
        candidate["jobs"].pop()
    with pytest.raises(EvaluationStopped, match="COMPARISON_"):
        freeze_comparison(baseline, candidate)


def test_comparison_keeps_missing_and_failed_pairs_without_fabricating_quality(frozen_pair):
    baseline, candidate = frozen_pair
    plan = freeze_comparison(baseline, candidate)
    before = state(baseline, ["pending", "failed", "success", "running"])
    before["results"][1]["failure"] = {"code": "PROVIDER_TIMEOUT"}
    after = state(candidate, ["success", "failed", "failed", "pending"])
    after["results"][1]["failure"] = {"code": "SCHEMA_VIOLATION", "outputValidationExhausted": True}
    after["results"][2]["failure"] = {
        "code": "PROVIDER_UNAVAILABLE",
        "evaluationFailure": "CALL_LIMIT",
    }
    result = compare_runs(plan, baseline, before, candidate, after)
    assert len(result["pairs"]) == 4
    assert result["pairs"][0]["baselineOutcome"] == "pending"
    assert result["pairs"][1]["baselineOutcome"] == "technical_failure"
    assert result["pairs"][1]["candidateOutcome"] == "validation_rejected"
    assert result["pairs"][2]["candidateOutcome"] == "evaluation_limited"
    assert all(row["semanticQualityDelta"] is None for row in result["pairs"])
    assert result["actualQualityStatus"] == "UNMEASURED"
    before["results"].pop()
    with pytest.raises(EvaluationStopped, match="PLANNED_JOBS_CHANGED"):
        compare_runs(plan, baseline, before, candidate, after)


def test_stage_ablation_requires_declaration_and_frozen_plan_cannot_change(frozen_pair):
    baseline, candidate = frozen_pair
    candidate["jobs"][0]["reviewPolicy"] = "disabled"
    with pytest.raises(EvaluationStopped, match="UNDECLARED_STAGE_ABLATION"):
        freeze_comparison(baseline, candidate)
    with pytest.raises(EvaluationStopped, match="ABLATION_RUNTIME_CONFOUNDED"):
        freeze_comparison(baseline, candidate, allow_stage_ablation=True)
    candidate["runtimeHashes"] = deepcopy(baseline["runtimeHashes"])
    plan = freeze_comparison(baseline, candidate, allow_stage_ablation=True)
    plan["pairs"][0]["requestHash"] = "changed"
    with pytest.raises(EvaluationStopped, match="FROZEN_COMPARISON_CHANGED"):
        compare_runs(
            plan,
            baseline,
            state(baseline, ["pending"] * 4),
            candidate,
            state(candidate, ["pending"] * 4),
        )


def test_generic_schema_error_without_output_evidence_is_not_a_guard_rejection():
    from app.core.errors import AgentError
    from app.eval.report_insight_outcomes import generation_outcome

    failure = operational._safe_failure(AgentError(502, "SCHEMA_VIOLATION", "model changed"))
    assert generation_outcome({"status": "failed", "failure": failure}) == "unknown_failure"


def test_observed_models_are_compared_per_pair_and_missing_is_unknown(frozen_pair):
    baseline, candidate = frozen_pair
    plan = freeze_comparison(baseline, candidate)
    before, after = state(baseline, ["pending"] * 4), state(candidate, ["pending"] * 4)

    def attempt(job, model):
        return {"jobId": job["jobId"], "stage": "MAP-001", "responseModel": model}

    before["attempts"] = [attempt(before["results"][0], "A"), attempt(before["results"][1], "B")]
    after["attempts"] = [attempt(after["results"][0], "B"), attempt(after["results"][1], "A")]
    with pytest.raises(EvaluationStopped, match="COMPARISON_OBSERVED_MODEL_CHANGED"):
        compare_runs(plan, baseline, before, candidate, after)
    after["attempts"] = deepcopy(before["attempts"])
    result = compare_runs(plan, baseline, before, candidate, after)
    assert result["pairs"][0]["observedModelMatch"] is True
    assert result["pairs"][2]["observedModelMatch"] is None
    assert result["observedModelMatch"] is None


def test_stage_metrics_separate_failed_calls_from_retained_validated_result():
    value = {
        "stages": [
            {
                "jobId": "a",
                "stage": "EXPLAIN-001",
                "event": "stage",
                "status": "success",
                "latencyMs": 10,
            },
            {
                "jobId": "b",
                "stage": "EXPLAIN-001",
                "event": "stage",
                "status": "failed",
                "latencyMs": 20,
            },
            {
                "jobId": "c",
                "stage": "EXPLAIN-001",
                "event": "stage",
                "status": "failed",
                "latencyMs": 30,
            },
        ],
        "recoveries": [{"jobId": "b", "stage": "EXPLAIN-001"}],
        "attempts": [
            {"jobId": "b", "stage": "EXPLAIN-001", "costEstimatedUsd": "0.2", "latencyMs": 15},
        ],
    }
    measured = stage_metrics(value)["EXPLAIN"]
    assert measured["stageSuccesses"] == 1
    assert measured["stageFailures"] == 2
    assert measured["validatedPriorRetained"] == 1
    assert measured["effectiveStageOutcomes"] == {
        "success": 1,
        "failed": 1,
        "validated_prior_retained": 1,
    }
    assert measured["costEstimatedUsd"] == "0.2"
    assert measured["stageLatencyMs"] == 60


def test_human_packet_preserves_failed_missing_and_unlabeled_results(frozen_pair):
    baseline, candidate = frozen_pair
    plan = freeze_comparison(baseline, candidate)
    before = with_outputs(baseline, ["success"] * 4)
    after = with_outputs(candidate, ["failed", "pending", "running", "success"])
    after["results"][0]["failure"] = {"code": "PROVIDER_TIMEOUT"}
    packet = export_comparison_packet(plan, baseline, before, candidate, after)
    assert len(packet["pairs"]) == 4
    assert [row["candidateOutcome"] for row in packet["pairs"]] == [
        "technical_failure",
        "pending",
        "running",
        "generated",
    ]
    assert packet["coverage"]["candidate"]["FINAL"] == {
        "planned": 4,
        "completed": 2,
        "failed": 1,
        "reviewableSnapshots": 1,
        "unmeasured": 3,
    }
    baseline_units = [unit for unit in packet["units"] if unit["variant"] == "baseline"]
    result = summarize_human_comparison(packet, human_labels(packet, baseline_units))
    assert result["pairs"] == packet["pairs"]
    assert result["coverage"] == packet["coverage"]
    assert result["semanticQualityMeasured"] is True
    assert result["semanticMeasurementScope"] == "labeled-exported-units-only"
    assert result["allExportedUnitsLabeled"] is False
    assert result["generationComparisonComplete"] is False
    assert result["finalOutputComparisonComplete"] is False
    assert result["finalSemanticComparisonComplete"] is False
    assert result["groups"]["candidate:FINAL:holdout"]["UNMEASURED"] > 0
    assert all(row["semanticQualityDelta"] is None for row in result["pairs"])
    assert result["automaticReleaseDecision"] is None


def test_all_failed_comparison_can_be_summarized_without_semantic_units(frozen_pair):
    baseline, candidate = frozen_pair
    plan = freeze_comparison(baseline, candidate)
    before, after = state(baseline, ["failed"] * 4), state(candidate, ["failed"] * 4)
    for run in (before, after):
        for job in run["results"]:
            job["failure"] = {"code": "PROVIDER_TIMEOUT"}
    packet = export_comparison_packet(plan, baseline, before, candidate, after)
    assert packet["units"] == []
    result = summarize_human_comparison(packet, human_labels(packet, []))
    assert len(result["pairs"]) == 4
    for coverage in result["coverage"].values():
        assert coverage["FINAL"] == {
            "planned": 4,
            "completed": 4,
            "failed": 4,
            "reviewableSnapshots": 0,
            "unmeasured": 4,
        }
    assert result["generationComparisonComplete"] is True
    assert result["finalOutputComparisonComplete"] is False
    assert result["finalSemanticComparisonComplete"] is False
    assert result["allExportedUnitsLabeled"] is False
    assert result["semanticQualityMeasured"] is False
    assert result["groups"] == {}
    assert result["automaticReleaseDecision"] is None
    invalid = human_labels(packet, [])
    invalid["packet_hash"] = "other packet"
    with pytest.raises(EvaluationStopped, match="ANNOTATION_PACKET_MISMATCH"):
        summarize_human_comparison(packet, invalid)


def test_stage_coverage_reports_only_observed_batches_without_inventing_plans(frozen_pair):
    baseline, candidate = frozen_pair
    plan = freeze_comparison(baseline, candidate)
    before = with_outputs(baseline, ["success", "running", "pending", "failed"])
    first, second = before["results"][:2]
    before["stages"] = [
        {
            "jobId": first["jobId"],
            "event": "stage",
            "stage": "MAP-001",
            "status": "success",
            "validatedSnapshot": deepcopy(first["response"]),
        },
        {"jobId": first["jobId"], "event": "stage", "stage": "MAP-002", "status": "failed"},
    ]
    before["attempts"] = [{"jobId": second["jobId"], "stage": "MAP-001"}]
    after = state(candidate, ["pending"] * 4)
    packet = export_comparison_packet(plan, baseline, before, candidate, after)
    stages = packet["coverage"]["baseline"]["stages"]
    assert stages["MAP"] == {
        "planned": None,
        "observed": 3,
        "completed": 2,
        "failed": 1,
        "reviewableSnapshots": 1,
        "unmeasured": 2,
        "jobsWithoutObservedStage": 2,
    }
    assert stages["REVIEW"] == {
        "planned": None,
        "observed": 0,
        "completed": 0,
        "failed": 0,
        "reviewableSnapshots": 0,
        "unmeasured": 0,
        "jobsWithoutObservedStage": 4,
    }
    result = summarize_human_comparison(packet, human_labels(packet, packet["units"]))
    assert result["coverage"] == packet["coverage"]
    assert result["allExportedUnitsLabeled"] is True
    assert result["finalSemanticComparisonComplete"] is False


def test_success_status_without_saved_output_remains_unmeasured(frozen_pair):
    baseline, candidate = frozen_pair
    plan = freeze_comparison(baseline, candidate)
    before, after = state(baseline, ["success"] * 4), state(candidate, ["success"] * 4)
    packet = export_comparison_packet(plan, baseline, before, candidate, after)
    result = summarize_human_comparison(packet, human_labels(packet, []))
    assert result["generationComparisonComplete"] is True
    assert result["coverage"]["baseline"]["FINAL"]["unmeasured"] == 4
    assert result["finalOutputComparisonComplete"] is False
    assert result["finalSemanticComparisonComplete"] is False
    assert result["semanticQualityMeasured"] is False


def test_complete_output_and_label_coverage_does_not_imply_quality_pass(frozen_pair):
    baseline, candidate = frozen_pair
    plan = freeze_comparison(baseline, candidate)
    before, after = (
        with_outputs(baseline, ["success"] * 4),
        with_outputs(candidate, ["success"] * 4),
    )
    packet = export_comparison_packet(plan, baseline, before, candidate, after)
    partial = summarize_human_comparison(packet, human_labels(packet, packet["units"][:1]))
    assert partial["finalOutputComparisonComplete"] is True
    assert partial["finalSemanticComparisonComplete"] is False
    result = summarize_human_comparison(packet, human_labels(packet, packet["units"]))
    assert result["generationComparisonComplete"] is True
    assert result["finalOutputComparisonComplete"] is True
    assert result["finalSemanticComparisonComplete"] is True
    assert result["allExportedUnitsLabeled"] is True
    assert all(
        group["INSUFFICIENT"] == group["plannedUnits"] for group in result["groups"].values()
    )
    assert result["automaticReleaseDecision"] is None
    assert all(row["semanticQualityDelta"] is None for row in result["pairs"])


@pytest.mark.parametrize("changed", ["coverage", "pairs"])
def test_packet_hash_binds_generation_outcomes_and_coverage(frozen_pair, changed):
    baseline, candidate = frozen_pair
    plan = freeze_comparison(baseline, candidate)
    before, after = state(baseline, ["failed"] * 4), state(candidate, ["failed"] * 4)
    packet = export_comparison_packet(plan, baseline, before, candidate, after)
    annotation = human_labels(packet, [])
    if changed == "coverage":
        packet["coverage"]["candidate"]["FINAL"]["unmeasured"] = 0
    else:
        packet["pairs"][0]["candidateOutcome"] = "generated"
    with pytest.raises(EvaluationStopped, match="REVIEW_PACKET_CHANGED"):
        summarize_human_comparison(packet, annotation)
