"""Separate generation availability from the semantic quality of abstention.

Only terminal generation failures describe unavailable results. A rejected draft
may later be repaired, and a successful null axis is not evidence of a defect.
"""

import re
from collections import Counter

_STAGE = re.compile(r"(?:MAP|REVIEW|EXPLAIN|REDUCE)-[0-9]{3}")
_TECHNICAL_CLASSES = frozenset(
    {
        "REQUEST_DEADLINE",
        "PROVIDER_QUOTA",
        "PROVIDER_RATE_LIMIT",
        "PROVIDER_CONCURRENCY",
        "PIPELINE_CANCELLED",
        "PROVIDER_UNAVAILABLE",
        "PROVIDER_TIMEOUT",
        "BUDGET_EXCEEDED",
        "COST_LIMIT_EXCEEDED",
    }
)
_OUTCOMES = (
    "generated",
    "pending",
    "running",
    "evaluation_limited",
    "validation_rejected",
    "technical_failure",
    "unknown_failure",
    "unknown_status",
)


def generation_outcome(job: dict) -> str:
    status = job.get("status")
    if status == "success":
        return "generated"
    if status in {"pending", "running"}:
        return status
    if status != "failed":
        return "unknown_status"
    failure = job.get("failure") or {}
    if failure.get("class") == "EVALUATION_LIMIT" or failure.get("evaluationFailure"):
        return "evaluation_limited"
    # A timeout while repairing is still a technical failure, even if the
    # earlier draft's validation diagnostics have been retained.
    if (
        failure.get("class") in _TECHNICAL_CLASSES
        or failure.get("code") in _TECHNICAL_CLASSES
        or any(
            failure.get(key)
            for key in (
                "requestDeadlineExceeded",
                "rateLimited",
                "concurrencyLimited",
                "pipelineCancelled",
                "timeoutPhase",
            )
        )
    ):
        return "technical_failure"
    if failure.get("code") == "SCHEMA_VIOLATION" and (
        failure.get("outputValidationExhausted") or failure.get("validationFailure")
    ):
        return "validation_rejected"
    return "unknown_failure"


def _assessment_state(assessment: dict) -> str:
    axes = assessment.get("axes") or {}
    if not {"directness", "impact", "urgency"} <= axes.keys():
        return "unrecorded"
    if axes["directness"] is None:
        return "relevance_unconfirmed"
    if axes["directness"] == 0:
        return "unrelated"
    if axes["impact"] is None:
        return "impact_unconfirmed"
    return "scored"


def _summarize_group(results: list[dict]) -> dict:
    counts = dict.fromkeys(_OUTCOMES, 0)
    assessment_states, terminal_stages = Counter(), Counter()
    successful_with_output = empty_synthesis = 0
    without_selected_basis = 0
    for job in results:
        outcome = generation_outcome(job)
        counts[outcome] += 1
        if outcome == "validation_rejected":
            diagnostic = (job.get("failure") or {}).get("validationFailure") or {}
            stage = diagnostic.get("stage")
            terminal_stages[
                stage if isinstance(stage, str) and _STAGE.fullmatch(stage) else "UNRECORDED"
            ] += 1
        if outcome != "generated":
            continue
        for insight in (job.get("response") or {}).get("insights", []):
            if insight.get("audience") != job.get("audience"):
                continue
            successful_with_output += 1
            empty_synthesis += all(
                key in insight and not insight[key]
                for key in ("overview", "implications", "watchItems")
            )
            assessment_states.update(
                _assessment_state(item) for item in insight.get("assessments", [])
            )
            without_selected_basis += sum(
                not item.get("basisClaimIds") for item in insight.get("assessments", [])
            )
    measured = sum(
        counts[key]
        for key in ("generated", "validation_rejected", "technical_failure", "unknown_failure")
    )
    return {
        "counts": counts,
        "plannedResults": len(results),
        "measuredGenerationResults": measured,
        "generationSuccessRate": counts["generated"] / measured if measured else None,
        "measurementCoverageRate": measured / len(results) if results else None,
        "plannedResultAvailabilityRate": counts["generated"] / len(results) if results else None,
        "terminalValidationFailuresByStage": dict(terminal_stages),
        "successfulInsightsWithOutput": successful_with_output,
        "successfulInsightsWithoutSynthesis": empty_synthesis,
        "observedAssessmentStates": dict(assessment_states),
        "assessmentsWithoutSelectedBasis": without_selected_basis,
        "unnecessaryAbstentionRate": None,
        "falseRejectionRate": None,
        "semanticQualityMeasured": False,
        "requiresHumanLabels": True,
    }


def summarize_outcomes(results: list[dict]) -> dict:
    """Keep every planned job and separate holdout performance from development.

    Older ledgers have no split metadata. They remain unrecorded rather than
    being relabeled from a case name or assumed to be independent validation.
    """
    groups = {split: [] for split in ("development", "holdout", "unrecorded")}
    for job in results:
        split = job.get("split")
        groups[split if split in {"development", "holdout"} else "unrecorded"].append(job)
    return {
        **_summarize_group(results),
        "bySplit": {split: _summarize_group(jobs) for split, jobs in groups.items() if jobs},
    }
