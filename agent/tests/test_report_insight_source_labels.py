from copy import deepcopy

import pytest

from app.eval.report_insight_corpus import load_corpus
from app.eval.report_insight_source_labels import (
    review_generation_records,
    review_source_case,
    source_fingerprint,
)
from app.schemas.report_insight import ReportInsightRequest


@pytest.fixture
def source_case():
    case = load_corpus().cases[0]
    request = case.request
    audience = request.audiences[0]
    refs = [finding.claims[0].id for finding in request.findings]
    report = {
        "split": "development",
        "request": request.model_dump(by_alias=True, mode="json"),
        "assessmentLabels": [
            {
                "findingId": finding.id,
                "audiences": {
                    audience: {
                        "directness": [3],
                        "impact": [None],
                        "urgency": [None],
                        "importance": ["unavailable"],
                    }
                },
            }
            for finding in request.findings
        ],
        "factConstraints": [{"criticalFact": "계획과 실행을 구분한다"}],
        "audienceSynthesisLabels": [
            {
                "audience": audience,
                "priorityClaimGroups": [
                    {"issueId": "production-plan", "claimIds": refs[:1]},
                    {"issueId": "actual-order", "claimIds": refs[1:]},
                ],
                "watchSignals": [{"basisClaimIds": refs[:1]}],
            }
        ],
    }
    candidate = {
        "insights": [
            {
                "audience": audience,
                "headline": "원문 사건의 이행 조건을 확인한다.",
                "assessments": [
                    {
                        "findingId": finding.id,
                        "axes": {"directness": 3, "impact": None, "urgency": None, "novelty": None},
                        "reason": "원문에 명시된 업무와 아직 확인되지 않은 이행 조건을 구분한다.",
                        "basisClaimIds": [finding.claims[0].id],
                    }
                    for finding in request.findings
                ],
                "overview": [
                    {
                        "text": "생산 목표와 실제 발주 이행을 구분해 확인한다.",
                        "assumption": "동일한 대상과 이행 범위를 다루는 경우",
                        "basisClaimIds": refs,
                    }
                ],
                "implications": [],
                "watchItems": [],
            }
        ]
    }
    return report, request, candidate


def test_source_identity_allows_anonymized_metadata_but_rejects_evidence_changes(source_case):
    report, request, candidate = source_case
    anonymized = request.model_dump(by_alias=True, mode="json")
    anonymized["idempotencyKey"] = "anonymous"
    anonymized["report"]["title"] = "익명화된 평가 보고서"
    anonymized["findings"][0]["canonicalUrl"] = "https://example.invalid/article/1"
    anonymous_request = ReportInsightRequest.model_validate(anonymized)
    assert source_fingerprint(anonymous_request) == source_fingerprint(request)
    anonymized["findings"][0]["claims"][0]["text"] += " 수정"
    with pytest.raises(ValueError, match="원문이 라벨"):
        review_source_case(report, ReportInsightRequest.model_validate(anonymized), candidate)


def test_citation_and_axis_pass_never_claim_semantic_or_human_quality(source_case):
    report, request, candidate = source_case
    result = review_source_case(report, request, candidate)
    assert result["axisCalibration"]["directness"]["rate"] == 1
    assert result["axisCalibration"]["importance"]["rate"] == 1
    assert result["priorityCitationCoverage"]["rate"] == 1
    assert result["watchCitationCoverage"]["rate"] == 0
    assert result["citationCoverageIsSemanticCoverage"] is False
    assert result["semanticQualityMeasured"] is False
    assert result["humanVerified"] is False


def test_missing_issue_and_promoted_impact_are_independent_diagnostics(source_case):
    report, request, candidate = source_case
    insight = candidate["insights"][0]
    insight["overview"][0]["basisClaimIds"] = [request.findings[0].claims[0].id]
    insight["assessments"][0]["axes"].update(impact=3, urgency=3)
    result = review_source_case(report, request, candidate)
    assert result["axisCalibration"]["impact"]["matched"] == 1
    assert result["axisCalibration"]["importance"]["matched"] == 1
    assert "missing_synthesis_issue:actual-order" in result["labelFlags"]
    assert any(flag.startswith("importance:") for flag in result["labelFlags"])


def test_failed_generations_and_unattempted_roles_are_not_dropped(source_case):
    report, request, candidate = source_case
    labels = {
        "version": "draft-test",
        "provenance": {"humanVerified": False},
        "splitPolicy": {"reportDisjoint": True},
        "reports": [report],
    }
    report["request"]["audiences"] = ["CHIP_MAKER", "EQUIPMENT_MAKER"]
    record = {
        "request": request.model_dump(by_alias=True, mode="json"),
        "status": "failed",
        "errorCode": "SCHEMA_VIOLATION",
    }
    result = review_generation_records(labels, [record])
    assert result["attemptedCases"] == result["failedCases"] == 1
    assert result["successfulCases"] == result["contractPassedCases"] == 0
    assert len(result["unattemptedCases"]) == 1
    assert result["humanQualityMeasured"] is result["qualityImprovementClaimed"] is False
    with pytest.raises(ValueError, match="중복"):
        review_generation_records(labels, [record, deepcopy(record)])
    record["request"]["report"]["id"] = 999
    with pytest.raises(ValueError, match="라벨에 없는"):
        review_generation_records(labels, [record])


def test_wrong_audience_cannot_score_even_with_correct_axes(source_case):
    report, request, candidate = source_case
    candidate["insights"][0]["audience"] = "IT_INFRA"
    result = review_source_case(report, request, candidate)
    assert result["labelFlags"] == ["wrong_audience_coverage"]
    assert "axisCalibration" not in result


def test_duplicate_findings_cannot_inflate_availability_or_be_quality_scored(source_case):
    report, request, candidate = source_case
    assessments = candidate["insights"][0]["assessments"]
    assessments[0]["axes"]["impact"] = 1
    assessments.append(deepcopy(assessments[0]))
    result = review_source_case(report, request, candidate)
    assert result["contractPassed"] is result["calibrationEligible"] is False
    assert "axisCalibration" not in result
    assert result["importanceAvailability"]["rate"] <= 1
    assert "wrong_finding_coverage" in result["labelFlags"]


def test_failed_unplanned_audience_is_not_counted_as_a_planned_attempt(source_case):
    report, request, _ = source_case
    labels = {"reports": [report]}
    request = request.model_copy(update={"audiences": ["IT_INFRA"]})
    record = {
        "request": request.model_dump(by_alias=True, mode="json"),
        "status": "failed",
    }
    with pytest.raises(ValueError, match="라벨에 없는"):
        review_generation_records(labels, [record])


def test_all_unrelated_accuracy_cannot_hide_missing_a_known_role_connection(source_case):
    report, request, candidate = source_case
    audience = request.audiences[0]
    for index, item in enumerate(report["assessmentLabels"]):
        item["audiences"][audience].update(
            directness=[3] if index == 0 else [0], labelConfidence="strong"
        )
    report["audienceSynthesisLabels"][0]["priorityClaimGroups"][0]["priority"] = "core"
    insight = candidate["insights"][0]
    insight["headline"] = "이 관점의 관련 근거가 부족합니다."
    insight["overview"] = []
    for item in insight["assessments"]:
        item["axes"]["directness"] = 0
    result = review_source_case(report, request, candidate)
    assert result["contractPassed"] is True
    assert result["axisCalibration"]["directness"]["matched"] > 0
    assert result["strongRoleConnectionRecall"] == {"matched": 0, "total": 1, "rate": 0}
    assert result["corePriorityCitationCoverage"]["rate"] == 0
    assert result["semanticQualityMeasured"] is False
