"""Offline frozen baseline/candidate comparison; never generates or resumes runs."""

import argparse
import json
from collections import Counter, defaultdict
from copy import deepcopy
from decimal import Decimal
from pathlib import Path

from app.eval.report_insight_outcomes import generation_outcome, summarize_outcomes
from app.eval.report_insight_run import atomic_save, digest, require
from app.eval.report_insight_semantic_judge import validate_packet
from app.eval.report_insight_semantic_review import _annotations, export_packet


def _key(job):
    return (job.get("pairId", job["caseId"]), job["audience"], job["repeat"])


def _jobs(manifest, state=None):
    jobs = manifest["jobs"] if state is None else state["results"]
    indexed = {_key(job): job for job in jobs}
    require(len(indexed) == len(jobs), "DUPLICATE_COMPARISON_PAIR")
    if state is not None:
        require(state["manifestHash"] == digest(manifest), "MANIFEST_CHANGED")
        require(
            len(jobs) == len(manifest["jobs"])
            and {job["jobId"] for job in jobs} == {job["jobId"] for job in manifest["jobs"]},
            "PLANNED_JOBS_CHANGED",
        )
        actual = {job["jobId"]: job for job in jobs}
        require(
            all(
                all(actual[job["jobId"]].get(key) == value for key, value in job.items())
                for job in manifest["jobs"]
            ),
            "PLANNED_JOBS_CHANGED",
        )
    return indexed


def _budget_policy(manifest):
    policy = deepcopy(manifest["policy"])
    policy["max_calls"] -= policy.pop("previous_calls", 0)
    policy["max_estimated_usd"] = str(
        Decimal(policy["max_estimated_usd"]) - Decimal(policy.pop("previous_estimated_usd", "0"))
    )
    return policy


def freeze_comparison(baseline: dict, candidate: dict, *, allow_stage_ablation=False) -> dict:
    from app.eval.report_insight_operational import ReportCase
    from app.eval.report_insight_split import validate_report_splits

    for manifest in (baseline, candidate):
        parsed = [
            ReportCase.model_validate(case).model_dump(by_alias=True) for case in manifest["cases"]
        ]
        require(
            manifest["splitValidation"]
            == validate_report_splits(parsed, exposure_registry=manifest.get("exposureRegistry")),
            "COMPARISON_SPLIT_VALIDATION_CHANGED",
        )
    require(
        baseline.get("exposureRegistry") == candidate.get("exposureRegistry"),
        "COMPARISON_EXPOSURE_HISTORY_CHANGED",
    )
    require(_budget_policy(baseline) == _budget_policy(candidate), "COMPARISON_POLICY_CHANGED")
    require(
        baseline["dependencies"] == candidate["dependencies"], "COMPARISON_DEPENDENCIES_CHANGED"
    )
    left, right = _jobs(baseline), _jobs(candidate)
    require(left.keys() == right.keys(), "COMPARISON_PAIRS_CHANGED")
    pairs = []
    stage_policy_changed = False
    for key, job in left.items():
        other = right[key]
        first = baseline["cases"][job["caseIndex"]]
        second = candidate["cases"][other["caseIndex"]]
        require(first["request"] == second["request"], "COMPARISON_INPUT_CHANGED")
        require(
            first["split"] == second["split"]
            and first.get("event_families", {}) == second.get("event_families", {}),
            "COMPARISON_SPLIT_CHANGED",
        )
        policies = ("reviewPolicy", "explanationPolicy")
        same_stages = all(
            job.get(name, "production") == other.get(name, "production") for name in policies
        )
        stage_policy_changed |= not same_stages
        require(
            allow_stage_ablation or same_stages,
            "UNDECLARED_STAGE_ABLATION",
        )
        pairs.append(
            {
                "key": list(key),
                "split": first["split"],
                "requestHash": digest(first["request"]),
                "baselineJobId": job["jobId"],
                "candidateJobId": other["jobId"],
            }
        )
    require(
        not stage_policy_changed or baseline["runtimeHashes"] == candidate["runtimeHashes"],
        "ABLATION_RUNTIME_CONFOUNDED",
    )
    require(
        not stage_policy_changed or baseline["promptVersion"] == candidate["promptVersion"],
        "ABLATION_PROMPT_CONFOUNDED",
    )
    body = {
        "schemaVersion": 1,
        "baselineManifestHash": digest(baseline),
        "candidateManifestHash": digest(candidate),
        "pairs": pairs,
        "allowStageAblation": allow_stage_ablation,
        "scope": "paired-observations; nondeterministic-model-output-is-not-a-causal-proof",
        "runtimeChanged": baseline["runtimeHashes"] != candidate["runtimeHashes"],
        "promptChanged": baseline["promptVersion"] != candidate["promptVersion"],
    }
    return {**body, "comparisonHash": digest(body)}


def validate_comparison(plan, baseline, candidate):
    require(
        plan
        == freeze_comparison(baseline, candidate, allow_stage_ablation=plan["allowStageAblation"]),
        "FROZEN_COMPARISON_CHANGED",
    )


def stage_metrics(state):
    recovered = {(event["jobId"], event["stage"]) for event in state.get("recoveries", [])}
    rows = defaultdict(
        lambda: {
            "providerAttempts": 0,
            "providerLatencyMs": 0,
            "costEstimatedUsd": Decimal(0),
            "unsettledReservedUsd": Decimal(0),
            "completedStages": 0,
            "stageLatencyMs": 0,
            "validatedPriorRetained": 0,
            "stageSuccesses": 0,
            "stageFailures": 0,
            "effectiveStageOutcomes": Counter(),
        }
    )
    for attempt in state.get("attempts", []):
        row = rows[attempt["stage"].split("-")[0]]
        row["providerAttempts"] += 1
        row["providerLatencyMs"] += attempt.get("latencyMs", 0)
        for name in ("costEstimatedUsd", "unsettledReservedUsd"):
            row[name] += Decimal(attempt.get(name, "0"))
    for event in state.get("stages", []):
        if event["event"] == "stage":
            row = rows[event["stage"].split("-")[0]]
            row["completedStages"] += 1
            row["stageLatencyMs"] += event.get("latencyMs", 0)
            row["stageSuccesses"] += event.get("status") == "success"
            row["stageFailures"] += event.get("status") == "failed"
            outcome = (
                "validated_prior_retained"
                if (event["jobId"], event["stage"]) in recovered
                else event.get("status", "unrecorded")
            )
            row["effectiveStageOutcomes"][outcome] += 1
    for event in state.get("recoveries", []):
        rows[event["stage"].split("-")[0]]["validatedPriorRetained"] += 1
    return {
        stage: {
            key: str(value) if isinstance(value, Decimal) else value for key, value in row.items()
        }
        for stage, row in rows.items()
    }


def stage_effects(state):
    """Count decision/prose changes, without treating change itself as benefit."""
    previous, counts = {}, defaultdict(Counter)
    order = {"MAP": 0, "REVIEW": 1, "EXPLAIN": 2}
    events = [
        event
        for event in state.get("stages", [])
        if event.get("event") == "stage" and event.get("status") == "success"
    ]
    for event in sorted(
        events, key=lambda row: (order.get(row["stage"].split("-")[0], 3), row["stage"])
    ):
        stage = event["stage"].split("-")[0]
        for row in event.get("assessments", []):
            key = event["jobId"], row["audience"], row["findingId"]
            before = previous.get(key)
            if before is not None:
                counts[stage]["comparedAssessments"] += 1
                counts[stage]["axisChanges"] += before["axes"] != row["axes"]
                counts[stage]["basisChanges"] += before["basisClaimIds"] != row["basisClaimIds"]
                counts[stage]["proseChanges"] += before["reasonHash"] != row["reasonHash"]
            previous[key] = row
    return {stage: dict(values) for stage, values in counts.items()}


def compare_runs(plan, baseline_manifest, baseline, candidate_manifest, candidate):
    validate_comparison(plan, baseline_manifest, candidate_manifest)
    left, right = _jobs(baseline_manifest, baseline), _jobs(candidate_manifest, candidate)
    rows = []
    for pair in plan["pairs"]:
        first, second = left[tuple(pair["key"])], right[tuple(pair["key"])]
        observed_models = [
            {
                row["responseModel"]
                for row in state.get("attempts", [])
                if row.get("responseModel") and row["jobId"] == job["jobId"]
            }
            for state, job in ((baseline, first), (candidate, second))
        ]
        require(
            not all(observed_models) or observed_models[0] == observed_models[1],
            "COMPARISON_OBSERVED_MODEL_CHANGED",
        )
        rows.append(
            {
                **pair,
                "baselineOutcome": generation_outcome(first),
                "candidateOutcome": generation_outcome(second),
                "observedModelMatch": observed_models[0] == observed_models[1]
                if all(observed_models)
                else None,
                "semanticQualityDelta": None,
            }
        )
    return {
        "comparisonHash": plan["comparisonHash"],
        "baselineResultHash": digest(baseline),
        "candidateResultHash": digest(candidate),
        "pairs": rows,
        "baseline": {
            "outcomes": summarize_outcomes(baseline["results"]),
            "stages": stage_metrics(baseline),
            "stageEffects": stage_effects(baseline),
        },
        "candidate": {
            "outcomes": summarize_outcomes(candidate["results"]),
            "stages": stage_metrics(candidate),
            "stageEffects": stage_effects(candidate),
        },
        "semanticQualityMeasured": False,
        "observedModelMatch": True
        if rows and all(row["observedModelMatch"] is True for row in rows)
        else None,
        "actualQualityStatus": "UNMEASURED",
        "automaticReleaseDecision": None,
        "providerCalls": 0,
    }


def _output_coverage(state, units, variant):
    """Count missing outputs without guessing which optional batches were planned."""
    jobs = state["results"]
    final_jobs = {
        unit["jobId"] for unit in units if unit["variant"] == variant and unit["stage"] == "FINAL"
    }
    snapshots = {
        (unit["jobId"], unit["stage"])
        for unit in units
        if unit["variant"] == variant and unit["stage"] != "FINAL"
    }
    events = {
        (event["jobId"], event["stage"]): event
        for event in state.get("stages", [])
        if event.get("event") == "stage"
    }
    observed = set(events) | {
        (attempt["jobId"], attempt["stage"]) for attempt in state.get("attempts", [])
    }
    stages = {}
    for stage in sorted(
        {"MAP", "REVIEW", "EXPLAIN", "REDUCE"} | {name.split("-")[0] for _, name in observed}
    ):
        keys = {key for key in observed if key[1].split("-")[0] == stage}
        stages[stage] = {
            "planned": None,
            "observed": len(keys),
            "completed": sum(
                events.get(key, {}).get("status") in {"success", "failed"} for key in keys
            ),
            "failed": sum(events.get(key, {}).get("status") == "failed" for key in keys),
            "reviewableSnapshots": len(keys & snapshots),
            "unmeasured": len(keys - snapshots),
            "jobsWithoutObservedStage": len(jobs) - len({job_id for job_id, _ in keys}),
        }
    return {
        "outcomes": summarize_outcomes(jobs),
        "FINAL": {
            "planned": len(jobs),
            "completed": sum(job.get("status") in {"success", "failed"} for job in jobs),
            "failed": sum(job.get("status") == "failed" for job in jobs),
            "reviewableSnapshots": len(final_jobs),
            "unmeasured": len(jobs) - len(final_jobs),
        },
        "stages": stages,
    }


def export_comparison_packet(plan, baseline_manifest, baseline, candidate_manifest, candidate):
    """Export final outputs and validated stage prose for blind, source-bound review.

    Failed/rejected stage text is handled by the separate guard packet exporter.
    Missing snapshots stay missing; provider text is never called validated.
    """
    comparison = compare_runs(plan, baseline_manifest, baseline, candidate_manifest, candidate)
    units, assessments = [], []
    for variant, manifest, state in (
        ("baseline", baseline_manifest, baseline),
        ("candidate", candidate_manifest, candidate),
    ):
        jobs = {job["jobId"]: job for job in state["results"]}
        snapshots = [
            (
                "FINAL",
                {
                    "manifestHash": state["manifestHash"],
                    "results": [job for job in state["results"] if job.get("response") is not None],
                },
            )
        ]
        for event in state.get("stages", []):
            if event.get("status") != "success" or "validatedSnapshot" not in event:
                continue
            job = deepcopy(jobs[event["jobId"]])
            response = deepcopy(event["validatedSnapshot"])
            for insight in response["insights"]:
                for field, default in (
                    ("headline", ""),
                    ("overview", []),
                    ("implications", []),
                    ("watchItems", []),
                ):
                    insight.setdefault(field, default)
            job.update(status="success", response=response)
            snapshots.append(
                (event["stage"], {"manifestHash": state["manifestHash"], "results": [job]})
            )
        for stage, source_state in snapshots:
            if not any(job["status"] == "success" for job in source_state["results"]):
                continue
            packet = export_packet(manifest, source_state)
            prefix = f"{variant}:{stage}:"
            for unit in packet["units"]:
                if not unit["statement"]:
                    continue
                job = jobs[unit["jobId"]]
                case = manifest["cases"][job["caseIndex"]]
                units.append(
                    {
                        **unit,
                        "unitId": prefix + unit["unitId"],
                        "variant": variant,
                        "stage": stage,
                        "pairKey": list(_key(job)),
                        "split": case["split"],
                        "reportId": case["request"]["report"]["id"],
                        "eventFamilies": case.get("event_families", {}),
                    }
                )
            assessments.extend(
                {**row, "assessmentId": prefix + row["assessmentId"]}
                for row in packet["assessments"]
            )
    body = {
        "schemaVersion": 1,
        "comparisonHash": plan["comparisonHash"],
        "baselineResultHash": digest(baseline),
        "candidateResultHash": digest(candidate),
        "pairs": comparison["pairs"],
        "coverage": {
            variant: _output_coverage(state, units, variant)
            for variant, state in (("baseline", baseline), ("candidate", candidate))
        },
        "coverageDefinitions": {
            "completed": "Recorded terminal successes and failures; not a quality pass.",
            "unmeasured": "No reviewable output snapshot; may overlap failed executions.",
            "stages": "Observed executions only; planned batch totals are unknown. "
            "An absent stage can be optional, not reached, or unrecorded.",
        },
        "units": units,
        "assessments": assessments,
        "limitations": "Human source review required; a stage change does not establish benefit.",
    }
    return {**body, "packetHash": digest(body)}


def summarize_human_comparison(packet, annotations):
    require(
        packet.get("packetHash")
        == digest({key: value for key, value in packet.items() if key != "packetHash"}),
        "REVIEW_PACKET_CHANGED",
    )
    if packet["units"]:
        validate_packet(packet)
    annotation, labels = _annotations(packet, annotations, "human")
    require(
        annotation.provenance is not None
        and annotation.provenance.origin == "external_human_review"
        and annotation.provenance.independent_of_judge,
        "HUMAN_PROVENANCE_REQUIRED",
    )
    groups = defaultdict(Counter)
    units = {unit["unitId"]: unit for unit in packet["units"]}
    for judgment in annotation.judgments:
        require(
            all(
                anchor.claim_id in units[judgment.unit_id]["candidateClaimIds"]
                for anchor in judgment.anchors
            ),
            "ANCHOR_OUTSIDE_SELECTED_REFS",
        )
    for unit in packet["units"]:
        group = groups[f"{unit['variant']}:{unit['stage']}:{unit['split']}"]
        group["plannedUnits"] += 1
        group[labels.get(unit["unitId"], "UNMEASURED")] += 1
    coverage = packet.get("coverage")
    final_coverage = [value["FINAL"] for value in coverage.values()] if coverage else []
    final_units = [unit for unit in packet["units"] if unit["stage"] == "FINAL"]
    final_outputs_complete = bool(final_coverage) and all(
        row["planned"] > 0 and row["unmeasured"] == 0 for row in final_coverage
    )
    return {
        "packetHash": packet["packetHash"],
        "pairs": packet.get("pairs"),
        "coverage": coverage,
        "coverageDefinitions": packet.get("coverageDefinitions"),
        "groups": dict(groups),
        "humanLabeledUnits": len(labels),
        "semanticQualityMeasured": bool(labels),
        "semanticMeasurementScope": "labeled-exported-units-only",
        "allExportedUnitsLabeled": bool(packet["units"]) and len(labels) == len(packet["units"]),
        "generationComparisonComplete": (
            all(row["planned"] > 0 and row["completed"] == row["planned"] for row in final_coverage)
            if final_coverage
            else None
        ),
        "finalOutputComparisonComplete": final_outputs_complete if final_coverage else None,
        "finalSemanticComparisonComplete": (
            final_outputs_complete
            and bool(final_units)
            and all(unit["unitId"] in labels for unit in final_units)
        )
        if final_coverage
        else None,
        "unmeasuredUnits": len(packet["units"]) - len(labels),
        "automaticReleaseDecision": None,
        "providerCalls": 0,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("freeze", "compare", "export"))
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--plan", type=Path)
    parser.add_argument("--allow-stage-ablation", action="store_true")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    def read(directory, name):
        return json.loads((directory / name).read_text())

    baseline, candidate = (
        read(args.baseline, "manifest.json"),
        read(args.candidate, "manifest.json"),
    )
    if args.command == "freeze":
        value = freeze_comparison(
            baseline, candidate, allow_stage_ablation=args.allow_stage_ablation
        )
    else:
        require(args.plan is not None, "COMPARISON_PLAN_REQUIRED")
        plan = json.loads(args.plan.read_text())
        operation = compare_runs if args.command == "compare" else export_comparison_packet
        value = operation(
            plan,
            baseline,
            read(args.baseline, "result.json"),
            candidate,
            read(args.candidate, "result.json"),
        )
    require(not args.output.exists(), "COMPARISON_OUTPUT_EXISTS")
    atomic_save(args.output, value)


if __name__ == "__main__":
    main()
