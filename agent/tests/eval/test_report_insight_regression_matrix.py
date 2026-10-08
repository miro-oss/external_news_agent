"""Offline measurements cannot confuse missing truth or budget with success."""

from copy import deepcopy

import pytest
from test_report_insight_assessment import request
from test_report_insight_operational import FakeSDK, prepared  # noqa: F401

from app.eval import report_insight_operational as operational
from app.eval.report_insight_regression_matrix import (
    build_cases,
    judge_budget_after_generation,
    measure_run,
    measure_statement_guards,
    plan_metadata,
)
from app.eval.report_insight_run import EvaluationStopped, digest
from app.eval.report_insight_semantic_cases import followup_packet, pilot_packet


def test_source_prefixes_controls_and_metadata_preserve_originals():
    raw = request(ids=tuple(range(101, 137))).model_dump(mode="json", by_alias=True)
    original = deepcopy(raw)
    cases = build_cases({"known": raw}, {"known": [1, 6, 12, 36]}, review_controls={"known": 6})
    assert raw == original
    assert len(cases) == 5
    assert all(case["split"] == "development" for case in cases)
    assert all(len(case["request"]["audiences"]) == 4 for case in cases)
    assert cases[1]["request"] == cases[2]["request"]
    assert cases[2]["review_policy"] == "disabled"
    assert cases[0]["request"]["findings"] == original["findings"][:1]
    metadata = plan_metadata(cases, original_counts={"77": 36})
    assert metadata["nominalCalls"] == 88
    assert metadata["estimatedCostUsd"] is None
    assert raw["findings"][0]["sentences"][0]["text"] not in str(metadata)


@pytest.mark.parametrize("sizes", [[0], [True], [2], [1, 1], []])
def test_incomplete_source_is_never_padded(sizes):
    raw = request().model_dump(mode="json", by_alias=True)
    with pytest.raises(EvaluationStopped, match="INVALID_SNAPSHOT_SUBSET_SIZE"):
        build_cases({"incomplete": raw}, {"incomplete": sizes})


def test_authored_packet_keeps_human_answers_out_and_original_hash_stable():
    assert (
        pilot_packet()["packetHash"]
        == "256d5f87eb3af2c61f7e8459b52df64ca5bfb4150dc9840c8e4048c078b7fd62"
    )
    packet = followup_packet()
    assert len(packet["units"]) == 10
    assert all("verdict" not in unit for unit in packet["units"])
    assert packet["units"][0]["sourceHash"] == packet["units"][1]["sourceHash"]
    human = {
        "packet_hash": packet["packetHash"],
        "kind": "human",
        "annotator": "test-fixture",
        "judgments": [],
        "provenance": {
            "origin": "external_human_review",
            "reference": "synthetic-test",
            "reviewed_at": "2026-01-01",
            "independent_of_judge": True,
        },
    }
    measured = measure_statement_guards(packet, human)
    assert measured["measuredUnits"] == 0
    assert measured["supportedFalseRejectionRate"] is None
    assert measured["contradictionMissRate"] is None
    packet["units"][0]["statement"] = "mutated after human annotation"
    with pytest.raises(EvaluationStopped, match="REVIEW_PACKET_CHANGED"):
        measure_statement_guards(packet, human)


def test_pending_generation_quality_and_repairs_are_not_counted_as_success():
    state = {
        "results": [
            {
                "jobId": "a",
                "caseId": "case",
                "audience": "CHIP_MAKER",
                "repeat": 1,
                "status": "pending",
            }
        ],
        "attempts": [],
        "stages": [],
        "repairs": [],
    }
    measured = measure_run(state)
    assert measured["policies"]["production"]["successRate"] is None
    assert measured["policies"]["production"]["partialRepairSuccessRate"] is None
    assert measured["policies"]["production"]["supportedFalseRejectionRate"] is None
    assert measured["semanticJudgeMode"] == "shadow"
    assert not measured["semanticJudgeMayOverrideValidation"]


def test_budget_continuation_preserves_unknown_reservation_and_rejects_inflight():
    manifest = {"policy": {"max_calls": 189, "max_estimated_usd": "4.8"}}
    state = {
        "manifestHash": digest(manifest),
        "results": [{"status": "success"}],
        "attempts": [
            {"status": "failed", "costEstimatedUsd": "0.03", "unsettledReservedUsd": "0.4"}
        ],
    }
    budget = judge_budget_after_generation(manifest, state, max_calls=192, max_usd="5")
    assert budget["previous_calls"] == 1
    assert budget["previous_estimated_usd"] == "0.43"
    state["attempts"][0]["status"] = "running"
    with pytest.raises(EvaluationStopped, match="GENERATION_STILL_IN_FLIGHT"):
        judge_budget_after_generation(manifest, state, max_calls=192, max_usd="5")


def test_evaluation_limit_is_not_measured_as_model_failure():
    job = {"caseId": "case", "audience": "CHIP_MAKER", "repeat": 1}
    state = {
        "results": [
            {**job, "jobId": "a", "status": "success"},
            {
                **job,
                "jobId": "b",
                "status": "failed",
                "failure": {"class": "EVALUATION_LIMIT", "code": "PROVIDER_UNAVAILABLE"},
            },
        ],
        "attempts": [],
        "stages": [],
        "repairs": [
            {"jobId": "b", "stage": "MAP-001", "scope": "finding_or_field", "afterAttemptId": 1}
        ],
        "admissionDenials": [{"jobId": "b", "reason": "CALL_LIMIT"}],
    }
    measured = measure_run(state)
    production = measured["policies"]["production"]
    assert production["measuredGenerationResults"] == 1
    assert production["evaluationLimitedResults"] == 1
    assert production["successRate"] == 1
    assert production["coverageRate"] == 0.5
    assert production["generationFailureCodes"] == {}
    assert production["partialRepairPlanned"] == 1
    assert production["partialRepairAttempts"] == 0
    assert measured["budgetDenialsByReason"] == {"CALL_LIMIT": 1}


def test_smoke_and_resume_share_ledger_and_keep_paired_review_control(prepared, monkeypatch):  # noqa: F811
    output, source, policy, cases = prepared
    sdk = FakeSDK(source, monkeypatch)
    cases.append(
        {**cases[0], "case_id": cases[0]["case_id"] + "-no-review", "review_policy": "disabled"}
    )
    operational.prepare(cases, policy, output)
    smoke = operational.run(output, api_key="test-key", job_limit=1)
    assert smoke["results"][0]["status"] == "success", smoke["results"][0]
    assert len(sdk.calls) == 3
    assert sum(job["status"] == "pending" for job in smoke["results"]) == 7
    result = operational.run(output, api_key="test-key", resume=True)
    assert len(sdk.calls) == 20
    assert result["summary"]["successResults"] == 8
    controls = [event for event in result["reviewAdmissions"] if event["policy"] == "disabled"]
    assert len(controls) == 4 and all(not event["admitted"] for event in controls)
    assert len(measure_run(result)["pairedReviewComparison"]) == 4
    assert measure_run(result)["reviewedAssessments"] == 4
    with pytest.raises(EvaluationStopped, match="NO_PENDING_JOBS"):
        operational.run(output, api_key="test-key", resume=True)


@pytest.mark.parametrize("audiences", [["CHIP_MAKER"], ["MARKET_INVESTOR", "IT_INFRA"]])
def test_targeted_remeasurement_never_schedules_unrequested_audiences(
    prepared,  # noqa: F811
    monkeypatch,
    audiences,
):
    output, source, policy, cases = prepared
    cases[0]["request"]["audiences"] = audiences
    sdk = FakeSDK(source, monkeypatch)
    manifest = operational.prepare(cases, policy, output)
    expected = [audience for audience in operational.AUDIENCES if audience in audiences]
    assert [job["audience"] for job in manifest["jobs"]] == expected
    result = operational.run(output, api_key="test-key")
    assert [job["audience"] for job in result["results"]] == expected
    assert result["summary"]["successResults"] == len(expected)
    assert result["summary"]["fourAudienceSuccessReports"] == 0
    assert len(sdk.calls) == 3 * len(expected)
    assert len(result["attempts"]) == len(sdk.calls)


def test_resume_does_not_reset_global_call_cap(prepared, monkeypatch):  # noqa: F811
    output, source, policy, cases = prepared
    policy["max_calls"] = 3
    sdk = FakeSDK(source, monkeypatch)
    operational.prepare(cases, policy, output)
    operational.run(output, api_key="test-key", job_limit=1)
    result = operational.run(output, api_key="test-key", resume=True)
    assert len(sdk.calls) == 3
    assert len(result["attempts"]) == 3
    assert result["admissionDenials"]


def test_fresh_runtime_baseline_cannot_reset_authorized_budget(prepared, monkeypatch):  # noqa: F811
    output, source, policy, cases = prepared
    policy.update(max_calls=5, previous_calls=4, previous_estimated_usd="0.01")
    sdk = FakeSDK(source, monkeypatch)
    operational.prepare(cases, policy, output)
    result = operational.run(output, api_key="test-key")
    assert len(sdk.calls) == 1
    assert len(result["attempts"]) == 1
    assert result["admissionDenials"]
