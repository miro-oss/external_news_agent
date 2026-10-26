"""Read-only abstention accounting for saved frozen-version comparisons.

No application runtime, credentials, or model calls are loaded. Historical
checkpoints remain untouched; their recorded rubric controls grade semantics.
"""

from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path

VERSIONS = ("baseline", "candidate")
STATUSES = {"success", "failed", "pending", "running"}
REASONS = ("uncitedAbstention", "directnessUnknown", "impactUnknown")
GRADES = ("high", "medium", "low", "unavailable")
RUBRICS = {f"report-importance.v{version}" for version in range(1, 7)}


def _ratio(numerator: int, denominator: int) -> float | None:
    return numerator / denominator if denominator else None


def _score(assessment: dict, rubric: str) -> float | None:
    axes = assessment["axes"]
    directness, impact, urgency = (axes[key] for key in ("directness", "impact", "urgency"))
    if any(
        value is not None and (type(value) is not int or not 0 <= value <= 3)
        for value in (directness, impact, urgency)
    ):
        raise ValueError("INVALID_ASSESSMENT_AXES")
    # This matches the blind renderer's historical rubric handling. In v6,
    # unrelated evidence becomes low without asserting that its impact is known.
    if directness == 0 and rubric == "report-importance.v6":
        return 0.0
    if directness is None or impact is None:
        return None
    if directness == 0:
        return 0.0
    weighted = directness * 0.4 + impact * 0.4
    return weighted / 0.8 if urgency is None else weighted + urgency * 0.2


def _observations(result: dict, rubric: str, limit: int) -> tuple[list[dict], list[dict]]:
    request = result["request"]
    order = {finding["id"]: index for index, finding in enumerate(request["findings"])}
    audiences = request["audiences"]
    response = result.get("response")
    insights = response.get("insights") if isinstance(response, dict) else None
    if not isinstance(insights, list):
        raise ValueError("SUCCESS_RESPONSE_MISSING")
    all_rows, displayed_rows, seen_audiences = [], [], set()
    for insight in insights:
        audience = insight["audience"]
        if audience not in audiences or audience in seen_audiences:
            raise ValueError("INVALID_RESPONSE_AUDIENCE")
        seen_audiences.add(audience)
        rows, seen_findings = [], set()
        for assessment in insight["assessments"]:
            finding_id = assessment["findingId"]
            if finding_id not in order or finding_id in seen_findings:
                raise ValueError("INVALID_ASSESSMENT_FINDING")
            seen_findings.add(finding_id)
            score = _score(assessment, rubric)
            axes = assessment["axes"]
            reason = None
            if score is None:
                if not assessment["basisClaimIds"]:
                    reason = "uncitedAbstention"
                elif axes["directness"] is None:
                    reason = "directnessUnknown"
                else:
                    reason = "impactUnknown"
            rows.append(
                {
                    "findingId": finding_id,
                    "score": score,
                    "grade": "unavailable"
                    if score is None
                    else ("high" if score >= 2.25 else "medium" if score >= 1.25 else "low"),
                    "reason": reason,
                    "unrelated": axes["directness"] == 0,
                    "unknownImpact": axes["impact"] is None,
                }
            )
        all_rows.extend(rows)
        # The public payload ranks known grades first, then descending score,
        # then original snapshot order, and persists at most five per audience.
        displayed_rows.extend(
            sorted(
                rows,
                key=lambda row: (
                    row["score"] is None,
                    -(row["score"] or 0),
                    order[row["findingId"]],
                ),
            )[:limit]
        )
    return all_rows, displayed_rows


def _counts(rows: list[dict], planned: int, successful_expected: int) -> dict:
    grades = Counter(row["grade"] for row in rows)
    reasons = Counter(row["reason"] for row in rows if row["reason"] is not None)
    return {
        "plannedCount": planned,
        "expectedInSuccessfulResults": successful_expected,
        "observedCount": len(rows),
        "missingInSuccessfulResults": successful_expected - len(rows),
        "coverageOfPlanned": _ratio(len(rows), planned),
        "coverageOfSuccessfulResults": _ratio(len(rows), successful_expected),
        "unavailableCount": grades["unavailable"],
        "unavailableRate": _ratio(grades["unavailable"], len(rows)),
        "gradeCounts": {grade: grades[grade] for grade in GRADES},
        "abstentionReasonCounts": {reason: reasons[reason] for reason in REASONS},
        "unrelatedCount": sum(row["unrelated"] for row in rows),
        "unrelatedLowCount": sum(row["unrelated"] and row["grade"] == "low" for row in rows),
        "unrelatedWithUnknownImpactCount": sum(
            row["unrelated"] and row["unknownImpact"] for row in rows
        ),
    }


def _variant_metrics(results: list[dict], rubric: str | None, limit: int) -> dict:
    status_counts = Counter(result["status"] for result in results)
    planned, successful_expected = [0, 0], [0, 0]
    all_rows, displayed_rows = [], []
    for result in results:
        request = result["request"]
        expected = [
            len(request["findings"]) * len(request["audiences"]),
            min(limit, len(request["findings"])) * len(request["audiences"]),
        ]
        planned = [total + count for total, count in zip(planned, expected, strict=True)]
        if result["status"] != "success":
            continue
        successful_expected = [
            total + count for total, count in zip(successful_expected, expected, strict=True)
        ]
        observations, displayed = _observations(result, rubric, limit)
        all_rows.extend(observations)
        displayed_rows.extend(displayed)
    return {
        "rubricVersion": rubric,
        "plannedResults": len(results),
        "successResults": status_counts["success"],
        "failedResults": status_counts["failed"],
        "incompleteResults": status_counts["pending"] + status_counts["running"],
        "successfulResultCoverage": _ratio(status_counts["success"], len(results)),
        "allAssessments": _counts(all_rows, planned[0], successful_expected[0]),
        "displayedTopIssues": _counts(displayed_rows, planned[1], successful_expected[1]),
    }


def summarize_abstention(state: dict, *, displayed_issue_limit: int = 5) -> dict:
    """Describe observed classifications; a lower abstention rate is not quality."""
    if type(displayed_issue_limit) is not int or displayed_issue_limit < 1:
        raise ValueError("INVALID_DISPLAYED_ISSUE_LIMIT")
    results = state["results"]
    by_version = {version: [] for version in VERSIONS}
    indexed = {}
    for result in results:
        if result["variant"] not in VERSIONS or result["status"] not in STATUSES:
            raise ValueError("INVALID_COMPARISON_RESULT")
        key = (result["caseId"], result["variant"])
        if key in indexed:
            raise ValueError("DUPLICATE_COMPARISON_RESULT")
        indexed[key] = result
        by_version[result["variant"]].append(result)
    rubrics = {}
    for version in VERSIONS:
        rubric = state["provenance"]["runtimeVersions"].get(version, {}).get("rubricVersion")
        if (by_version[version] or rubric is not None) and rubric not in RUBRICS:
            raise ValueError("UNSUPPORTED_IMPORTANCE_RUBRIC")
        rubrics[version] = rubric
    case_ids = sorted({result["caseId"] for result in results})
    paired = []
    for case_id in case_ids:
        pair = [indexed.get((case_id, version)) for version in VERSIONS]
        if all(result is not None and result["status"] == "success" for result in pair):
            if pair[0]["request"] != pair[1]["request"]:
                raise ValueError("PAIRED_REQUEST_MISMATCH")
            paired.append(case_id)
    return {
        "schemaVersion": 1,
        "sourceCheckpointSha256": state.get("checkpointSha256"),
        "qualityMeasured": False,
        "qualityImprovementClaimed": False,
        "displayedIssueLimitPerAudience": displayed_issue_limit,
        "displayedIssuesAreProjection": True,
        "abstentionReasonSource": "response basisClaimIds and axes",
        "abstentionReasonPrecedence": list(REASONS),
        "variants": {
            version: _variant_metrics(by_version[version], rubrics[version], displayed_issue_limit)
            for version in VERSIONS
        },
        "pairedSuccessfulCases": {
            "plannedCount": len(case_ids),
            "observedCount": len(paired),
            "coverage": _ratio(len(paired), len(case_ids)),
            "caseIds": paired,
            "variants": {
                version: _variant_metrics(
                    [result for result in by_version[version] if result["caseId"] in paired],
                    rubrics[version],
                    displayed_issue_limit,
                )
                for version in VERSIONS
            },
        },
        "limitations": [
            "Rates use observed assessments from successful results; failures are not zero rates.",
            "Top issues are a ranked projection, not a measurement of persisted production UI.",
            "Exclusive observations: uncited abstention, unknown directness, then unknown impact.",
            "Empty basis is not proof of source absence; "
            "may reflect abstention or source filtering.",
            "Directness zero is unrelated and low under v6 even when impact remains unknown.",
            "Lower abstention, including unrelated low classifications, does not prove quality.",
            "Checkpoint structure is inspected; frozen runtime provenance is not revalidated.",
        ],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--displayed-issue-limit", type=int, default=5)
    args = parser.parse_args()
    if args.output.resolve() == args.checkpoint.resolve():
        parser.error("output must not replace the checkpoint")
    state = json.loads(args.checkpoint.read_text(encoding="utf-8"))
    summary = summarize_abstention(state, displayed_issue_limit=args.displayed_issue_limit)
    args.output.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


if __name__ == "__main__":
    main()
