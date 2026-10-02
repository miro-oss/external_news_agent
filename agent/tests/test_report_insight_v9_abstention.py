"""Offline contracts for separate work connections and unknown effect/timing.

Provider replies below are fixtures, not measurements of model judgment quality.
"""

import pytest
from jsonschema import Draft202012Validator
from test_report_insight_assessment import payload, request, response

from app.llm.report_insight_assessment import draft_schema, draft_to_wire, validate_draft
from app.llm.report_insight_instructions import report_stage_instruction
from app.llm.report_insight_service import _eligible_report_request, importance_grade


@pytest.mark.parametrize(
    "audience,work,text,impact,grade",
    [
        (
            "CHIP_MAKER",
            "PROCESS_QUALIFICATION",
            "설계 업체는 해당 공정 인증을 완료하고 그 공정에 사용할 설계 IP를 도입했다.",
            "PROJECT_CHANGE",
            "high",
        ),
        (
            "CHIP_MAKER",
            "YIELD_CAPACITY",
            "메모리 제조사는 기존 메모리 생산라인을 고대역폭 메모리 생산으로 전환했다.",
            "PROJECT_CHANGE",
            "high",
        ),
        (
            "EQUIPMENT_MAKER",
            "PROCESS_VALIDATION",
            "장비 업체는 새 공정용 계측 장비의 검증 준비를 시작했다.",
            "LIMITED_PREPARATION",
            "medium",
        ),
        (
            "IT_INFRA",
            "SYSTEM_PROCUREMENT",
            "서버 메모리의 판매 가격이 올랐다. 구매 물량과 실제 조달 비용은 미정이다.",
            "UNDETERMINED",
            "unavailable",
        ),
    ],
)
def test_known_work_does_not_require_other_work_contracts_or_known_deadlines(
    audience, work, text, impact, grade
):
    source = request(ids=(9101,), audiences=(audience,), text=text)
    source = _eligible_report_request(source)
    assert len(source.findings[0].claims) == 1
    value = payload(source)
    entry = value["assessments"][audience]["finding9101"]
    entry.update(
        work=work,
        impactScope=impact,
        urgencyState="UNDETERMINED",
        urgencyBasis=None,
        reason=text,
    )
    if impact == "UNDETERMINED":
        entry["impactBasis"] = None
    wire = draft_to_wire(value, source)
    Draft202012Validator(draft_schema(source)).validate(wire)
    result = validate_draft(response(value, source), source)
    assessment = result.mapped.insights[0].assessments[0]
    assert assessment.axes.directness == 3
    assert assessment.axes.urgency is None
    assert assessment.axes.novelty is None
    assert assessment.basis_claim_ids == ["9101:0"]
    assert importance_grade(assessment.axes) == grade
    assert result.evidence[audience][9101].condition is None


@pytest.mark.parametrize("stage", ["MAP", "REVIEW"])
def test_assessment_instructions_distinguish_work_scope_from_evidence_absence(stage):
    instruction = report_stage_instruction(["CHIP_MAKER"], stage)
    assert "공정 인증·설계 적용은 고객 계약이 없어도" in instruction
    assert "정량 금액·비율 없이도 정성적으로 판정" in instruction
    assert "미확인을 NO_CHANGE로 바꾸지 않는다" in instruction
    assert "전망·목표를 현재 집행·수주·납품·효과로 바꾸지 않는다" in instruction
    assert "기사에 독자의 직무명이나 대응 지시가 그대로 적혀 있을 필요는 없다" in instruction
