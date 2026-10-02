"""Offline contracts for separate work connections and unknown effect/timing.

Provider replies below are fixtures, not measurements of model judgment quality.
"""

import pytest
from jsonschema import Draft202012Validator
from test_report_insight_assessment import item, payload, request, response
from test_report_insight_v4_pipeline import V4Provider, generate, stages

from app.llm.report_insight_assessment import draft_schema, draft_to_wire, validate_draft
from app.llm.report_insight_instructions import report_stage_instruction
from app.llm.report_insight_service import _eligible_report_request, importance_grade
from app.schemas.report_insight import CLAIMLESS_ASSESSMENT_REASON


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


@pytest.mark.parametrize(
    "relation,headline,grade",
    [
        (
            "UNDETERMINED",
            "원문 근거는 있으나 이 관점의 업무 관련성을 판단하지 못했습니다.",
            "unavailable",
        ),
        (
            "UNRELATED",
            "원문을 검토했으나 이 관점과 직접 관련된 이슈는 확인되지 않았습니다.",
            "low",
        ),
    ],
)
def test_empty_synthesis_explains_source_backed_unknown_and_unrelated_separately(
    relation, headline, grade
):
    source = request()
    snapshot = source.model_dump_json()
    provider = V4Provider(source, relation=relation)

    result = generate(provider, source)

    insight = result.insights[0]
    assert insight.headline == headline
    assert insight.overview == insight.implications == insight.watch_items == []
    assert importance_grade(insight.assessments[0].axes) == grade
    assert "REDUCE-001" not in stages(provider)
    assert source.model_dump_json() == snapshot


def test_mixed_audiences_explain_unknown_without_overwriting_grounded_synthesis():
    source = request(audiences=("CHIP_MAKER", "IT_INFRA"))

    def hook(stage, _, data, value):
        if stage.startswith(("MAP", "REVIEW")):
            value["assessments"]["IT_INFRA"]["finding101"] = item(
                source.findings[0], audience="IT_INFRA", relation="UNDETERMINED"
            )
        return value

    provider = V4Provider(source, hook=hook)
    result = generate(provider, source)

    known, unknown = result.insights
    assert "REDUCE-001" in stages(provider)
    assert known.headline == "생산 준비의 제약과 확인 조건을 점검한다."
    assert known.overview[0].basis_claim_ids == ["101:0"]
    assert unknown.headline == "원문 근거는 있으나 이 관점의 업무 관련성을 판단하지 못했습니다."
    assert unknown.overview == unknown.implications == unknown.watch_items == []


def test_unknown_impact_keeps_known_work_synthesis_and_unavailable_importance():
    source = request()

    def hook(stage, _, data, value):
        if stage.startswith(("MAP", "REVIEW")):
            value["assessments"]["CHIP_MAKER"]["finding101"].update(
                impactScope="UNDETERMINED", impactBasis=None
            )
        return value

    result = generate(V4Provider(source, hook=hook), source)

    insight = result.insights[0]
    assert insight.headline == "생산 준비의 제약과 확인 조건을 점검한다."
    assert insight.overview[0].basis_claim_ids == ["101:0"]
    assert insight.assessments[0].axes.directness == 3
    assert importance_grade(insight.assessments[0].axes) == "unavailable"


@pytest.mark.parametrize("filtered_ids", [(101,), (101, 102)])
def test_filtered_sources_do_not_turn_unrelated_into_unknown_or_invent_available_evidence(
    filtered_ids,
):
    source = request(ids=(101, 102))
    for finding in source.findings:
        if finding.id in filtered_ids:
            finding.claims[0].text = "2031년에 생산라인 가동이 중단됐다."
    snapshot = source.model_dump_json()

    def hook(stage, _, data, value):
        if stage.startswith(("MAP", "REVIEW")):
            for finding in data["findings"]:
                if finding["id"] in filtered_ids:
                    assert finding["claims"] == []
                    entry = item(source.findings[finding["id"] - 101], relation="UNDETERMINED")
                    entry["reason"] = CLAIMLESS_ASSESSMENT_REASON
                    value["assessments"]["CHIP_MAKER"][f"finding{finding['id']}"] = entry
        return value

    result = generate(V4Provider(source, relation="UNRELATED", hook=hook), source)

    insight = result.insights[0]
    expected = (
        "이 관점의 관련 근거가 부족합니다."
        if len(filtered_ids) == 2
        else "원문을 검토했으나 이 관점과 직접 관련된 이슈는 확인되지 않았습니다."
    )
    assert insight.headline == expected
    for assessment in insight.assessments:
        if assessment.finding_id in filtered_ids:
            assert assessment.reason == CLAIMLESS_ASSESSMENT_REASON
            assert assessment.basis_claim_ids == []
            assert set(assessment.axes.model_dump().values()) == {None}
    assert source.model_dump_json() == snapshot
