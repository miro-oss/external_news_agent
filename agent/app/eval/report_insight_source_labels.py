"""Offline source-label checks; draft AI labels are not measured human quality.

Axis calibration and citation coverage are mechanical checks. The fact/role
connections and watch wording still need blind semantic review. Failed generation
is counted separately, never dropped to inflate an accepted-output average.
"""

import argparse
import hashlib
import json
from pathlib import Path

from pydantic import ValidationError

from app.eval.report_insight_review import review_saved_output
from app.llm.report_insight_service import importance_grade
from app.schemas.report_insight import ReportInsightOutput, ReportInsightRequest

DEFAULT_LABELS = Path(__file__).parent / "golden" / "report-insight.real-source-labels.v1.json"
AXES = ("directness", "impact", "urgency")


def source_fingerprint(request: ReportInsightRequest) -> str:
    """Bind labels to exact grounded source, excluding anonymized transport metadata.

    This is an evaluation identity, not the application inputHash/cache identity.
    Role selection, idempotencyKey, title and URL do not change source evidence.
    """
    payload = {
        "report": request.report.model_dump(mode="json", by_alias=True, exclude={"title"}),
        "findings": [
            finding.model_dump(
                mode="json", by_alias=True, exclude={"article_title", "canonical_url"}
            )
            for finding in request.findings
        ],
    }
    data = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(data.encode()).hexdigest()


def load_source_labels(path: Path = DEFAULT_LABELS) -> dict:
    labels = json.loads(path.read_text(encoding="utf-8"))
    if labels["provenance"]["humanVerified"] is not False:
        raise ValueError("이 평가기는 사람 확인 전 draft 라벨만 다룹니다.")
    reports = labels["reports"]
    ids = []
    for report in reports:
        request = ReportInsightRequest.model_validate(report["request"])
        ids.append(request.report.id)
        expected = {finding.id for finding in request.findings}
        labeled = [item["findingId"] for item in report["assessmentLabels"]]
        if len(labeled) != len(set(labeled)) or set(labeled) != expected:
            raise ValueError("라벨은 모든 finding을 중복 없이 포함해야 합니다.")
        if report["split"] not in {"development", "holdout"}:
            raise ValueError("알 수 없는 평가 split입니다.")
        for finding in report["assessmentLabels"]:
            if set(finding["audiences"]) != set(request.audiences):
                raise ValueError("finding 라벨은 입력 관점 전체를 포함해야 합니다.")
            for allowed in finding["audiences"].values():
                for axis in AXES:
                    if not allowed[axis] or any(
                        value is not None and (type(value) is not int or value not in range(4))
                        for value in allowed[axis]
                    ):
                        raise ValueError("축 라벨은 비어 있지 않은 0~3/null 허용 집합입니다.")
    if len(ids) != len(set(ids)):
        raise ValueError("보고서가 development와 holdout에 중복되었습니다.")
    return labels


def _ratio(matched: int, total: int) -> dict:
    return {"matched": matched, "total": total, "rate": matched / total if total else None}


def review_source_case(report: dict, request: ReportInsightRequest, candidate: dict) -> dict:
    labeled_request = ReportInsightRequest.model_validate(report["request"])
    if source_fingerprint(request) != source_fingerprint(labeled_request):
        raise ValueError("평가 후보의 원문이 라벨 원문과 다릅니다.")
    if len(request.audiences) != 1:
        raise ValueError("저장 후보는 관점 하나씩 평가합니다.")
    audience = request.audiences[0]
    if audience not in labeled_request.audiences:
        raise ValueError("라벨에 없는 관점입니다.")
    result = {
        "reportId": request.report.id,
        "audience": audience,
        "split": report["split"],
        "humanVerified": False,
        "semanticQualityMeasured": False,
        "requiresBlindReview": True,
        "calibrationEligible": False,
        **review_saved_output(request, candidate),
    }
    try:
        output = ReportInsightOutput.model_validate(candidate)
    except ValidationError:
        return {**result, "schemaParsed": False, "labelFlags": ["invalid_output_schema"]}
    if len(output.insights) != 1 or output.insights[0].audience != audience:
        return {**result, "schemaParsed": True, "labelFlags": ["wrong_audience_coverage"]}
    insight = output.insights[0]
    assessments = {item.finding_id: item for item in insight.assessments}
    expected = {item["findingId"] for item in report["assessmentLabels"]}
    flags = []
    if len(assessments) != len(insight.assessments) or set(assessments) != expected:
        flags.append("wrong_finding_coverage")
    metrics = {axis: [0, 0] for axis in (*AXES, "importance")}
    strong_metrics = {axis: [0, 0] for axis in (*AXES, "importance")}
    for item in report["assessmentLabels"]:
        finding_id = item["findingId"]
        allowed = item["audiences"][audience]
        actual = assessments.get(finding_id)
        for axis in (*AXES, "importance"):
            metrics[axis][1] += 1
            strong = allowed.get("labelConfidence") == "strong"
            if strong:
                strong_metrics[axis][1] += 1
            value = (
                importance_grade(actual.axes)
                if actual is not None and axis == "importance"
                else getattr(actual.axes, axis)
                if actual is not None
                else "missing"
            )
            if actual is not None and value in allowed[axis]:
                metrics[axis][0] += 1
                if strong:
                    strong_metrics[axis][0] += 1
            else:
                flags.append(f"{axis}:{finding_id}:expected_{allowed[axis]}:actual_{value}")
    synthesis_label = next(
        item for item in report["audienceSynthesisLabels"] if item["audience"] == audience
    )
    synthesis_refs = {
        ref for item in [*insight.overview, *insight.implications] for ref in item.basis_claim_ids
    }
    watch_refs = {ref for item in insight.watch_items for ref in item.basis_claim_ids}
    groups = synthesis_label["priorityClaimGroups"]
    core_groups = [group for group in groups if group.get("priority") == "core"]
    covered = sum(bool(set(group["claimIds"]) & synthesis_refs) for group in groups)
    core_covered = sum(bool(set(group["claimIds"]) & synthesis_refs) for group in core_groups)
    for group in groups:
        if not set(group["claimIds"]) & synthesis_refs:
            flags.append("missing_synthesis_issue:" + group["issueId"])
    watches = synthesis_label["watchSignals"]
    watched = sum(bool(set(item["basisClaimIds"]) & watch_refs) for item in watches)
    calibration_eligible = result["contractPassed"] and "wrong_finding_coverage" not in flags
    metrics_key = "axisCalibration" if calibration_eligible else "diagnosticAxisCalibration"
    strong_key = "strongAxisCalibration" if calibration_eligible else "diagnosticStrongAxes"
    return {
        **result,
        "schemaParsed": True,
        "labelFlags": flags,
        "calibrationEligible": calibration_eligible,
        metrics_key: {name: _ratio(*counts) for name, counts in metrics.items()},
        strong_key: {name: _ratio(*counts) for name, counts in strong_metrics.items()},
        "importanceAvailability": _ratio(
            sum(
                importance_grade(assessments[finding_id].axes) != "unavailable"
                for finding_id in expected & assessments.keys()
            ),
            len(expected),
        ),
        "priorityCitationCoverage": _ratio(covered, len(groups)),
        "corePriorityCitationCoverage": _ratio(core_covered, len(core_groups)),
        "watchCitationCoverage": _ratio(watched, len(watches)),
        "citationCoverageIsSemanticCoverage": False,
        "semanticReviewGuide": {
            "factConstraints": report["factConstraints"],
            "audienceSynthesis": synthesis_label,
        },
    }


def review_generation_records(labels: dict, records: list[dict]) -> dict:
    reports = {
        ReportInsightRequest.model_validate(report["request"]).report.id: report
        for report in labels["reports"]
    }
    cases, seen = [], set()
    for record in records:
        request = ReportInsightRequest.model_validate(record["request"])
        if len(request.audiences) != 1:
            raise ValueError("생성 결과는 관점 하나의 요청이어야 합니다.")
        identity = (request.report.id, request.audiences[0])
        if identity in seen:
            raise ValueError("같은 보고서·관점 후보가 중복됩니다. 실행 버전을 따로 평가하세요.")
        seen.add(identity)
        report = reports.get(identity[0])
        if (
            report is None
            or identity[1] not in report["request"]["audiences"]
            or source_fingerprint(request)
            != source_fingerprint(ReportInsightRequest.model_validate(report["request"]))
        ):
            raise ValueError("라벨에 없는 보고서 또는 다른 원문입니다.")
        if record["status"] == "success":
            cases.append(
                {
                    "status": "success",
                    **review_source_case(
                        report, request, {"insights": record["response"]["insights"]}
                    ),
                }
            )
        elif record["status"] == "failed":
            cases.append(
                {
                    "status": "failed",
                    "reportId": identity[0],
                    "audience": identity[1],
                    "split": report["split"],
                    "errorCode": record.get("errorCode"),
                    "contractPassed": False,
                    "calibrationEligible": False,
                }
            )
        else:
            raise ValueError("진행 중이거나 알 수 없는 생성 상태는 평가할 수 없습니다.")
    eligible = [case for case in cases if case["status"] == "success"]
    return {
        "datasetVersion": labels["version"],
        "labelProvenance": labels["provenance"],
        "splitPolicy": labels["splitPolicy"],
        "attemptedCases": len(cases),
        "successfulCases": len(eligible),
        "failedCases": len(cases) - len(eligible),
        "contractPassedCases": sum(case["contractPassed"] for case in cases),
        "unattemptedCases": [
            {"reportId": report_id, "audience": audience}
            for report_id, report in reports.items()
            for audience in report["request"]["audiences"]
            if (report_id, audience) not in seen
        ],
        "semanticQualityMeasured": False,
        "humanQualityMeasured": False,
        "qualityImprovementClaimed": False,
        "requiresBlindReview": True,
        "cases": cases,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_LABELS)
    parser.add_argument("--results", type=Path, required=True)
    args = parser.parse_args()
    records = json.loads(args.results.read_text(encoding="utf-8"))["results"]
    print(
        json.dumps(
            review_generation_records(load_source_labels(args.dataset), records),
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
