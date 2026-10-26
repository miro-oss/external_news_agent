"""A prose-only repair must not contradict an unchanged UNRELATED decision."""

import json
from copy import deepcopy
from dataclasses import replace

import pytest
from test_report_insight_assessment import payload, request, response
from test_report_insight_assessment_coherence import assessment
from test_report_insight_preserved_decision_repair import repair_for, repair_payload, wire_validator

from app.llm import report_insight_service as service
from app.llm.report_insight_assessment import ReportAssessmentDraftValidationError, validate_draft
from app.llm.report_insight_assessment_coherence import assessment_coherence_errors

RECORDED_REASON = (
    "해외 방산업체에 적외선 검출기 조립체 공급계약 체결 사실은 칩 제조 관점에서 "
    "고객 요구·공급 약정과 직접 연결된다. 다만 문장에는 부품 조달·공정 인증·생산능력 "
    "배분으로 이어졌다는 정보가 없어 이행 조건은 확인되지 않는다."
)


@pytest.mark.parametrize(
    "reason",
    [
        RECORDED_REASON,
        "해당 업무와 직접 연결된다.",
        "이 관점의 업무와 직접적인 관련이 있다.",
        "해당 업무와 직접적으로 관련됩니다.",
        "칩 제조 관점에서 공정 검증 업무와 직접 연결된다.",
        "출처의 표현은 미확인이다. 다만 해당 업무와 직접 연결된다.",
    ],
)
def test_unrelated_rejects_explicit_current_positive_work_connection(reason):
    item = assessment(reason, relation="UNRELATED")
    before = item.model_dump()
    errors = assessment_coherence_errors(item, audience="CHIP_MAKER")
    assert len(errors) == 1
    assert "nativeFields=reason,decision.connection.relation" in errors[0]
    assert "UNRELATED" in errors[0]
    assert item.model_dump() == before


@pytest.mark.parametrize(
    "reason",
    [
        '원문은 "칩 제조 관점에서 고객 요구·공급 약정과 직접 연결된다."고 설명한다.',
        "‘해당 업무와 직접 연결된다’는 원문 표현이다.",
        '"해당 업무와 직접 연결된다.',
        "해당 업무와 직접 연결된다고 원문은 설명한다.",
        "해당 업무와 직접 연결된다는 뜻은 아니다.",
        "해당 업무와 직접 연결되지 않는다.",
        "해당 업무와 직접 관련이 없다.",
        "해당 업무와 직접 연결되는지는 확인되지 않는다.",
        "해당 업무와 직접 연결될 수 있다.",
        "해당 업무와 직접 연결된다면 업무를 재검토한다.",
        "해당 업무와 직접 연결된다?",
        "원문에서 이행이 확인되면, 해당 업무와 직접 연결된다.",
        "향후 이행이 확인될 경우 칩 제조 관점에서 고객 요구·공급 약정과 직접 연결된다.",
        "가정상 칩 제조 관점에서 고객 요구·공급 약정과 직접 연결된다.",
        "공급 계약이 실제 체결될 때, 칩 제조 관점에서 고객 요구·공급 약정과 직접 연결된다.",
        "실제 공급 계약 체결을 전제로, 칩 제조 관점에서 고객 요구·공급 약정과 직접 연결된다.",
        "실제 공급 계약이 있어야 칩 제조 관점에서 고객 요구·공급 약정과 직접 연결된다.",
        "과거에는 칩 제조 관점에서 고객 요구·공급 약정과 직접 연결된다.",
        "과거에는 해당 업무와 직접 연결됐지만 현재는 무관하다.",
        "IT 인프라 관점에서 시스템 조달과 직접 연결된다.",
        "IT 인프라 관점에서는 해당 업무와 직접 연결된다.",
        "다른 관점에서는 해당 업무와 직접 연결된다.",
        "투자자 관점에서는, 해당 업무와 직접 연결된다.",
        "장비업체 관점에서는, 해당 업무와 직접 연결된다.",
        "통신사업자 관점에서는, 해당 업무와 직접 연결된다.",
        "원문은 다음과 같이 주장한다: 칩 제조 관점에서 고객 요구·공급 약정과 직접 연결된다.",
        "다른 업무와 직접 연결된다.",
        "공급업체와 직접 계약한다.",
        "칩 제조 관점에서 고객 요구·공급 약정과 직접 연결되는 대상은 미확인이다.",
    ],
)
def test_unrelated_preserves_nonasserted_or_different_perspective_connections(reason):
    assert (
        assessment_coherence_errors(assessment(reason, relation="UNRELATED"), audience="CHIP_MAKER")
        == []
    )


@pytest.mark.parametrize("relation", ["DIRECT", "CONDITIONAL", "BACKGROUND", "UNDETERMINED"])
def test_reverse_guard_does_not_classify_other_relations(relation):
    assert (
        assessment_coherence_errors(
            assessment(RECORDED_REASON, relation=relation), audience="CHIP_MAKER"
        )
        == []
    )


def test_native_validation_passes_current_audience_and_identifies_both_conflicting_fields():
    source = request()
    value = payload(source, relation="UNRELATED")
    value["assessments"]["CHIP_MAKER"]["finding101"]["reason"] = RECORDED_REASON
    with pytest.raises(ReportAssessmentDraftValidationError) as caught:
        validate_draft(response(value, source), source)
    assert caught.value.failed_finding_ids == (101,)
    assert "nativeFields=reason,decision.connection.relation" in str(caught.value)
    error = service._native_assessment_repair_errors(response(value, source), source)
    assert "report_assessment_draft_invalid" in error.error_kinds
    assert not error.native_prose_repairs
    assert "nativeFields=reason,decision.connection.relation" in str(error)


def test_partial_fact_repair_keeps_decision_but_rejects_contradictory_new_reason():
    source = request(ids=(101, 102))
    value = payload(source, relation="UNRELATED")
    for item in value["assessments"]["CHIP_MAKER"].values():
        item["reason"] = "원문은 생산 사건을 설명하며, 해당 업무와 구분해 판단한다."
    value["assessments"]["CHIP_MAKER"]["finding101"]["reason"] += " 9999년의 사건이다."
    repair, error, raw, calls = repair_for(source, value)
    assert set(error.native_prose_repairs) == {101}
    assert "고정된 판정과 reason의 의미도 일치" in repair.prompt
    contradictory = deepcopy(value)
    contradictory["assessments"]["CHIP_MAKER"]["finding101"]["reason"] = (
        "해당 업무와 직접 연결된다."
    )
    wire = repair_payload(repair, source, contradictory)
    wire_validator(repair).validate(wire)
    with pytest.raises(ReportAssessmentDraftValidationError, match="UNRELATED"):
        repair.validate(replace(raw, text=json.dumps(wire, ensure_ascii=False)))
    consistent = deepcopy(value)
    consistent["assessments"]["CHIP_MAKER"]["finding101"]["reason"] = (
        "원문은 생산 사건을 설명하며, 해당 업무와 구분해 판단한다."
    )
    fixed = repair_payload(repair, source, consistent)
    wire_validator(repair).validate(fixed)
    result = repair.validate(replace(raw, text=json.dumps(fixed, ensure_ascii=False)))
    assert set(result.evidence["CHIP_MAKER"]) == {101, 102}
    assert all(item.relation == "UNRELATED" for item in result.evidence["CHIP_MAKER"].values())
    assert len(calls) == 2  # Both repairs reach the original full-batch validator.
    assert (
        calls[-1]["assessments"]["CHIP_MAKER"]["finding102"]
        == json.loads(raw.text)["assessments"]["CHIP_MAKER"]["finding102"]
    )
