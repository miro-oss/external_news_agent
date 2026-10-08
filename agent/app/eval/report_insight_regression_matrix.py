"""Offline planning and measurement for source-preserving regression runs.

Real requests, outputs and supplied human labels belong in private local exports.
Known debugging reports are development data, never a fresh semantic holdout.
Live generation uses report_insight_operational, including its durable call cap.
"""

from __future__ import annotations

import math
from collections import Counter
from decimal import Decimal

from app.eval.report_insight_operational import AUDIENCES, ReportCase
from app.eval.report_insight_run import digest, require
from app.eval.report_insight_semantic_judge import validate_packet
from app.eval.report_insight_semantic_review import _annotations
from app.llm.report_insight_assessment import MAX_REVIEW_FINDINGS
from app.llm.report_insight_service import MAX_ASSESSMENT_BATCH, _prose_validation_errors
from app.schemas.report_insight import ReportInsightRequest


def build_cases(
    snapshots: dict[str, dict],
    sizes: dict[str, list[int]],
    *,
    review_controls: dict[str, int] | None = None,
) -> list[dict]:
    """Use exact source prefixes; never pad an incomplete source snapshot."""
    require(snapshots.keys() == sizes.keys(), "SOURCE_SIZE_KEYS_DIFFER")
    controls = review_controls or {}
    require(controls.keys() <= snapshots.keys(), "UNKNOWN_CONTROL_SOURCE")
    cases = []
    for name, raw in snapshots.items():
        source = ReportInsightRequest.model_validate(raw)
        require(
            bool(sizes[name])
            and len(sizes[name]) == len(set(sizes[name]))
            and all(
                type(size) is int and 1 <= size <= len(source.findings) for size in sizes[name]
            ),
            "INVALID_SNAPSHOT_SUBSET_SIZE",
        )
        require(controls.get(name) is None or controls[name] in sizes[name], "CONTROL_NEEDS_PAIR")
        for size in sizes[name]:
            request = source.model_copy(
                update={"audiences": list(AUDIENCES), "findings": source.findings[:size]}
            )
            case = ReportCase(
                case_id=f"{name}-n{size}", split="development", request=request
            ).model_dump(mode="json", by_alias=True)
            cases.append(case)
            if controls.get(name) == size:
                cases.append(
                    {**case, "case_id": case["case_id"] + "-no-review", "review_policy": "disabled"}
                )
    return cases


def plan_metadata(cases: list[dict], *, original_counts: dict[str, int] | None = None) -> dict:
    """Expose counts and hashes only; nominal calls assume admission and no repair."""
    rows = []
    for value in cases:
        case = ReportCase.model_validate(value)
        count = len(case.request.findings)
        maps = math.ceil(count / MAX_ASSESSMENT_BATCH)
        reviews = (
            math.ceil(min(count, MAX_REVIEW_FINDINGS) / MAX_ASSESSMENT_BATCH)
            if case.review_policy == "production"
            else 0
        )
        rows.append(
            {
                "caseId": case.case_id,
                "reportId": case.request.report.id,
                "findingCount": count,
                "originalReportFindingCount": (original_counts or {}).get(
                    str(case.request.report.id)
                ),
                "sourceHash": digest(case.request.model_dump(mode="json", by_alias=True)),
                "audienceCount": len(case.request.audiences),
                "reviewPolicy": case.review_policy,
                "split": case.split,
                "nominalCalls": (maps + reviews + 1) * len(case.request.audiences),
            }
        )
    return {
        "cases": rows,
        "plannedResults": sum(row["audienceCount"] for row in rows),
        "nominalCalls": sum(row["nominalCalls"] for row in rows),
        "estimatedCostUsd": None,
        "callEstimateAssumptions": (
            "all REVIEW admitted; no retries/repair; hard budget still applies"
        ),
        "qualityHoldout": False,
    }


def _rate(numerator, denominator):
    return numerator / denominator if denominator else None


def measure_run(state: dict) -> dict:
    """Observed engineering outcomes, without inventing semantic quality labels."""
    groups = {}
    completed_stages = {
        (event["jobId"], event["stage"]): event
        for event in state.get("stages", [])
        if event["event"] == "stage"
    }
    for policy in ("production", "disabled"):
        jobs = [job for job in state["results"] if job.get("reviewPolicy", "production") == policy]
        if not jobs:
            continue
        ids = {job["jobId"] for job in jobs}
        attempts = [row for row in state["attempts"] if row["jobId"] in ids]
        finished = [job for job in jobs if job["status"] in {"success", "failed"}]
        limited = [
            job for job in finished if job.get("failure", {}).get("class") == "EVALUATION_LIMIT"
        ]
        measured = [job for job in finished if job not in limited]
        success = sum(job["status"] == "success" for job in measured)
        repairs = [row for row in state.get("repairs", []) if row["jobId"] in ids]
        partial_plans = [row for row in repairs if row["scope"] != "whole_output"]
        partial = [
            row
            for row in partial_plans
            if "afterAttemptId" not in row
            or any(
                attempt["jobId"] == row["jobId"]
                and attempt["stage"] == row["stage"]
                and attempt["attemptId"] > row["afterAttemptId"]
                for attempt in attempts
            )
        ]
        repaired = sum(
            completed_stages.get((row["jobId"], row["stage"]), {}).get("status") == "success"
            for row in partial
        )
        latency = sorted(job["latencyMs"] for job in finished if "latencyMs" in job)
        groups[policy] = {
            "plannedResults": len(jobs),
            "finishedResults": len(finished),
            "measuredGenerationResults": len(measured),
            "evaluationLimitedResults": len(limited),
            "successResults": success,
            "successRate": _rate(success, len(measured)),
            "coverageRate": _rate(len(measured), len(jobs)),
            "generationFailureCodes": dict(
                Counter(job["failure"]["code"] for job in measured if "failure" in job)
            ),
            "partialRepairAttempts": len(partial),
            "partialRepairPlanned": len(partial_plans),
            "partialRepairSuccesses": repaired,
            "partialRepairSuccessRate": _rate(repaired, len(partial)),
            "repairScopes": dict(Counter(row["scope"] for row in repairs)),
            "latencyP95Ms": latency[math.ceil(len(latency) * 0.95) - 1] if latency else None,
            "providerCalls": len(attempts),
            "costEstimatedUsd": str(
                sum((Decimal(row["costEstimatedUsd"]) for row in attempts), Decimal(0))
            ),
            "unsettledReservedUsd": str(
                sum((Decimal(row["unsettledReservedUsd"]) for row in attempts), Decimal(0))
            ),
            "supportedFalseRejectionRate": None,
            "contradictionMissRate": None,
            "semanticQualityMeasured": False,
        }
    reviewed = changed = 0
    for job in state["results"]:
        originals = {}
        events = [event for event in completed_stages.values() if event["jobId"] == job["jobId"]]
        for event in events:
            if event["stage"].startswith("MAP-"):
                originals.update(
                    {
                        (row["audience"], row["findingId"]): row
                        for row in event.get("assessments", [])
                    }
                )
        for event in events:
            if event["stage"].startswith("REVIEW-"):
                for row in event.get("assessments", []):
                    before = originals.get((row["audience"], row["findingId"]))
                    if before is not None:
                        reviewed += 1
                        changed += row["axes"] != before["axes"]
    pairs = []
    jobs_by_key = {(job["caseId"], job["audience"], job["repeat"]): job for job in state["results"]}
    for job in state["results"]:
        if job.get("reviewPolicy") != "disabled":
            continue
        reference = jobs_by_key.get(
            (job["caseId"].removesuffix("-no-review"), job["audience"], job["repeat"])
        )
        if reference is None:
            continue
        measured = all(
            item["status"] in {"success", "failed"}
            and item.get("failure", {}).get("class") != "EVALUATION_LIMIT"
            for item in (job, reference)
        )
        pairs.append(
            {
                "caseId": reference["caseId"],
                "audience": job["audience"],
                "repeat": job["repeat"],
                "productionStatus": reference["status"],
                "disabledStatus": job["status"],
                "productionReviewAdmitted": any(
                    event["jobId"] == reference["jobId"] and event["admitted"]
                    for event in state.get("reviewAdmissions", [])
                ),
                "latencyDeltaMs": reference["latencyMs"] - job["latencyMs"]
                if measured and "latencyMs" in reference and "latencyMs" in job
                else None,
                "semanticQualityDelta": None,
            }
        )
    return {
        "policies": groups,
        "pairedReviewComparison": pairs,
        "budgetDenialsByReason": dict(
            Counter(event["reason"] for event in state.get("admissionDenials", []))
        ),
        "reviewedAssessments": reviewed,
        "reviewChangedAxes": changed,
        "reviewAxisChangeRate": _rate(changed, reviewed),
        "reviewSemanticBenefit": None,
        "reviewPolicyDecision": "retain-production-policy-pending-source-bound-human-comparison",
        "semanticJudgeMode": "shadow",
        "semanticJudgeMayOverrideValidation": False,
    }


def measure_statement_guards(packet: dict, human: dict) -> dict:
    """Compare deterministic prose guards to supplied human truth, not generated scores."""
    validate_packet(packet)
    annotation, labels = _annotations(packet, human, "human")
    require(
        annotation.provenance is not None
        and annotation.provenance.origin == "external_human_review"
        and annotation.provenance.independent_of_judge,
        "HUMAN_PROVENANCE_REQUIRED",
    )
    rows = []
    for unit in packet["units"]:
        label = labels.get(unit["unitId"])
        if label is None:
            continue
        # Each authored unit has one source sentence. Wider packets need their
        # original request projection instead of manufacturing cross-source joins.
        require(
            len(unit["source"]) == 1 and len(unit["source"][0]["sentences"]) == 1,
            "SINGLE_SENTENCE_FIXTURE_REQUIRED",
        )
        text = unit["source"][0]["sentences"][0]["text"]
        request = ReportInsightRequest.model_validate(
            {
                "idempotencyKey": "offline-statement-guard",
                "plan": "FREE",
                "audiences": ["CHIP_MAKER"],
                "report": {"id": 1, "title": "Synthetic statement fixture", "reportScope": "RUN"},
                "findings": [
                    {
                        "id": 1,
                        "articleId": 1,
                        "articleTitle": "Synthetic fixture",
                        "canonicalUrl": "https://example.test/fixture",
                        "topicName": "synthetic",
                        "claims": [
                            {
                                "id": "1:0",
                                "text": text,
                                "claimType": "FACT",
                                "evidenceSentenceIds": [0],
                            }
                        ],
                        "sentences": [{"index": 0, "text": text}],
                    }
                ],
            }
        )
        errors = _prose_validation_errors(
            [unit["statement"]],
            ["1:0"],
            {"1:0": text},
            {"1:0": request.findings[0].claims[0]},
            request=request,
        )
        rows.append({"unitId": unit["unitId"], "humanVerdict": label, "rejected": bool(errors)})
    supported = [row for row in rows if row["humanVerdict"] == "SUPPORTED"]
    contradicted = [row for row in rows if row["humanVerdict"] == "CONTRADICTED"]
    return {
        "scope": "deterministic-prose-guards-on-authored-statements; not generation-quality",
        "truthOrigin": "external-human-labels",
        "measuredUnits": len(rows),
        "unmeasuredUnits": len(packet["units"]) - len(rows),
        "supportedCount": len(supported),
        "contradictedCount": len(contradicted),
        "supportedFalseRejectionRate": _rate(
            sum(row["rejected"] for row in supported), len(supported)
        ),
        "contradictionMissRate": _rate(
            sum(not row["rejected"] for row in contradicted), len(contradicted)
        ),
        "rows": rows,
    }


def judge_budget_after_generation(
    manifest: dict, state: dict, *, max_calls: int, max_usd: str
) -> dict:
    """Sequential continuation of one authorization; unfinished work cannot be reused."""
    require(state["manifestHash"] == digest(manifest), "MANIFEST_CHANGED")
    require(
        all(job["status"] in {"success", "failed"} for job in state["results"]),
        "GENERATION_NOT_FINISHED",
    )
    require(
        all(row["status"] != "running" for row in state["attempts"]), "GENERATION_STILL_IN_FLIGHT"
    )
    require(
        max_calls >= manifest["policy"]["max_calls"]
        and Decimal(max_usd) >= Decimal(manifest["policy"]["max_estimated_usd"]),
        "INVALID_SHARED_AUTHORIZATION",
    )
    spent = Decimal(manifest["policy"].get("previous_estimated_usd", "0")) + sum(
        (
            Decimal(row["costEstimatedUsd"]) + Decimal(row["unsettledReservedUsd"])
            for row in state["attempts"]
        ),
        Decimal(0),
    )
    return {
        "max_calls": max_calls,
        "max_estimated_usd": max_usd,
        "previous_calls": manifest["policy"].get("previous_calls", 0) + len(state["attempts"]),
        "previous_estimated_usd": str(spent),
    }
