"""Axis-only repairs preserve validated connections without assigning a grade."""

import json
from copy import deepcopy
from dataclasses import replace

import pytest
from test_report_insight_assessment import draft_to_wire, framed, payload, request, response
from test_report_insight_axis_support_repair import case
from test_report_insight_core_forecast_support import CURRENT_CAPACITY, recorded_case
from test_report_insight_preserved_decision_repair import repair_payload, wire_validator
from test_report_insight_work_repair_actions import full_validate

from app.core.errors import OutputValidationError
from app.core.report_importance import score_importance
from app.llm import report_insight_service as service
from app.llm.report_insight_assessment import draft_prompt, draft_schema


def repair_for(source, value):
    raw = response(value, source)
    error = service._native_assessment_repair_errors(raw, source)
    assert error is not None
    schema = draft_schema(source)
    before = deepcopy(schema), source.model_dump_json(), raw.text
    repair = object.__new__(service.ReportInsightService)._repair_call(
        draft_prompt(source), schema, raw.text, error, lambda result: full_validate(result, source)
    )
    assert before == (schema, source.model_dump_json(), raw.text)
    return repair, error, raw


def corrected_capacity(value):
    fixed = deepcopy(value)
    item = fixed["assessments"]["CHIP_MAKER"]["finding101"]
    item.update(
        impactBasis={"claimId": "101:1", "quote": CURRENT_CAPACITY},
        urgencyState="MONITOR",
        reason="메모리 생산능력 제약은 칩 제조의 생산능력 업무에 직접 연결된다.",
    )
    return fixed


@pytest.mark.parametrize("partial", [False, True])
def test_recorded_unknown_repair_cannot_delete_verified_connection(partial):
    source, value = recorded_case()
    if partial:
        other = request(ids=(202,))
        source.findings.extend(other.findings)
        value["assessments"]["CHIP_MAKER"].update(payload(other)["assessments"]["CHIP_MAKER"])
    repair, error, raw = repair_for(source, value)
    assert set(error.native_connection_repairs) == {101}
    assert not error.native_prose_repairs
    assert "connection의 관계·업무·조건·근거는 검증되어" in repair.prompt

    # Recorded v22 failure: an axis repair changed the known relation to unknown.
    bad = deepcopy(value)
    bad["assessments"]["CHIP_MAKER"]["finding101"].update(
        relation="UNDETERMINED",
        work=None,
        relationBasis=None,
        condition=None,
        impactScope="UNDETERMINED",
        impactBasis=None,
        urgencyState="UNDETERMINED",
        urgencyBasis=None,
        reason="기사에는 제조사의 내부 운영계획 자료가 없어 업무 연결을 판단할 수 없다.",
    )
    rejected = repair_payload(repair, source, bad)
    assert not wire_validator(repair).is_valid(rejected)
    with pytest.raises(OutputValidationError, match="connection을 변경할 수 없습니다"):
        repair.validate(replace(raw, text=json.dumps(rejected, ensure_ascii=False)))

    fixed = corrected_capacity(value)
    wire = repair_payload(repair, source, fixed)
    wire_validator(repair).validate(wire)
    accepted = repair.validate(replace(raw, text=json.dumps(wire, ensure_ascii=False)))
    item = accepted.evidence["CHIP_MAKER"][101]
    assert item.relation == "DIRECT" and item.work == "YIELD_CAPACITY"
    assert item.condition is None and item.relation_basis.claim_id == "101:1"
    assert item.impact_scope == "CORE_CONSTRAINT" and item.impact_basis.claim_id == "101:1"
    assert item.urgency_state == "MONITOR"
    if partial:
        assert list(accepted.draft.assessments["CHIP_MAKER"]) == ["finding101", "finding202"]
        assert (
            accepted.evidence["CHIP_MAKER"][202].reason
            == value["assessments"]["CHIP_MAKER"]["finding202"]["reason"]
        )


@pytest.mark.parametrize("field", ["relation", "work", "condition", "basis"])
def test_connection_const_covers_each_field_in_sdk_schema_and_server(field):
    source, value = recorded_case()
    repair, _, raw = repair_for(source, value)
    fixed = repair_payload(repair, source, corrected_capacity(value))
    connection = fixed["assessments"]["CHIP_MAKER"]["finding101"]["decision"]["connection"]
    replacement = {
        "relation": "BACKGROUND",
        "work": "MATERIAL_SUPPLY",
        "condition": "제조사의 생산계획이 실제로 바뀌는 경우",
        "basis": {"claimId": "101:0", "sourceSpanId": "s101_0_0"},
    }
    connection[field] = replacement[field]
    assert not wire_validator(repair).is_valid(fixed)
    with pytest.raises(OutputValidationError, match="connection을 변경할 수 없습니다"):
        repair.validate(replace(raw, text=json.dumps(fixed, ensure_ascii=False)))


def test_forecast_impact_unknown_remains_a_legitimate_hold_with_known_relation():
    source, value = case("forecast")
    repair, _, raw = repair_for(source, value)
    fixed = deepcopy(value)
    fixed["assessments"]["IT_INFRA"]["finding101"].update(
        impactScope="UNDETERMINED",
        impactBasis=None,
        urgencyState="MONITOR",
        reason="시장 가격 전망은 조달 업무와 연결되지만 실제 영향 범위는 미확인이다.",
    )
    wire = repair_payload(repair, source, fixed)
    wire_validator(repair).validate(wire)
    accepted = repair.validate(replace(raw, text=json.dumps(wire, ensure_ascii=False)))
    item = accepted.evidence["IT_INFRA"][101]
    assert item.relation == "DIRECT" and item.impact_scope == "UNDETERMINED"
    assert score_importance(accepted.mapped.insights[0].assessments[0].axes) is None


@pytest.mark.parametrize("defect", ["foreign_effect_basis", "unsupported_reason", "coherence"])
def test_connection_preservation_still_runs_full_validation(defect):
    source, value = recorded_case()
    repair, _, raw = repair_for(source, value)
    fixed = draft_to_wire(corrected_capacity(value), source)
    item = fixed["assessments"]["CHIP_MAKER"]["finding101"]
    if defect == "foreign_effect_basis":
        item["decision"]["effect"]["basis"] = {
            "claimId": "999:0",
            "sourceSpanId": "s999_0_0",
        }
        assert not wire_validator(repair).is_valid(fixed)
    elif defect == "unsupported_reason":
        item["reason"] = "삼성전자는 9999억원을 투자했다."
    else:
        item["reason"] = "해당 업무와 직접 관련이 없다."
    with pytest.raises(ValueError):
        repair.validate(replace(raw, text=json.dumps(fixed, ensure_ascii=False)))


@pytest.mark.parametrize("defect", ["public_fact", "native_coherence", "relation"])
def test_mixed_or_genuine_relation_failure_does_not_preserve_connection(defect):
    source, value = case("relocation" if defect == "relation" else "forecast")
    item = value["assessments"]["IT_INFRA"]["finding101"]
    if defect == "public_fact":
        item["reason"] = "9999년의 가격 전망은 시스템 조달과 연결된다."
    elif defect == "native_coherence":
        item["reason"] = "영향 범위는 미확인이다."
    repair, error, raw = repair_for(source, value)
    assert not error.native_connection_repairs
    assert repair.response_schema == draft_schema(source)
    assert "finding 전체의 근거 부정이 아닙니다" not in repair.prompt
    if defect == "relation":
        fixed = deepcopy(value)
        fixed["assessments"]["IT_INFRA"]["finding101"].update(
            relation="CONDITIONAL",
            condition="본사 이동에 실제 IT 시스템 이전이 수반되는 경우",
            reason="시스템 이전이 수반되는 경우 도입·운영 업무와 연결된다.",
        )
        accepted = repair.validate(response(fixed, source))
        assert accepted.evidence["IT_INFRA"][101].relation == "CONDITIONAL"


@pytest.mark.parametrize("defect", ["legacy", "changed_raw", "changed_source"])
def test_stale_or_untrusted_context_retains_ordinary_repair(defect):
    source, value = recorded_case()
    _, error, raw = repair_for(source, value)
    if defect == "legacy":
        error = service.ReportAssessmentValidationError(
            "nativeFields=decision.effect.impactScope market_forecast_only_core_constraint",
            error_kinds=("report_assessment_draft_invalid",),
            failed_finding_ids=(101,),
        )
    elif defect == "changed_raw":
        changed = json.loads(raw.text)
        changed["assessments"]["CHIP_MAKER"]["finding101"]["reason"] += " 추가"
        raw = replace(raw, text=json.dumps(changed, ensure_ascii=False))
    else:
        source.findings[0].sentences[0].text += " 추가 원문"
    schema = draft_schema(source)
    repair = object.__new__(service.ReportInsightService)._repair_call(
        draft_prompt(source), schema, raw.text, error, lambda result: full_validate(result, source)
    )
    assert repair.response_schema == schema
    assert "finding 전체의 근거 부정이 아닙니다" not in repair.prompt


def test_authenticated_axis_repair_separates_impact_from_urgency_without_changing_sources():
    source, value = recorded_case()
    repair, _, raw = repair_for(source, value)
    assert "finding 전체의 근거 부정이 아닙니다" in repair.prompt
    assert "즉시성이나 독자 회사의 내부 운영자료는 필수 요건이 아닙니다" in repair.prompt
    assert "연결 근거가 영향·시점도 지원한다는 보장은 없습니다" in repair.prompt
    assert "해당 축을 지원하는 근거가 없으면 미확인을 유지합니다" in repair.prompt
    assert framed(repair.prompt) == framed(draft_prompt(source))
    fixed = corrected_capacity(value)
    wire = repair_payload(repair, source, fixed)
    wire_validator(repair).validate(wire)
    accepted = repair.validate(replace(raw, text=json.dumps(wire, ensure_ascii=False)))
    assert accepted.evidence["CHIP_MAKER"][101].impact_scope == "CORE_CONSTRAINT"
    # The wording permits a recheck; it does not populate a missing effect itself.
    fixed["assessments"]["CHIP_MAKER"]["finding101"].update(
        impactScope="UNDETERMINED", impactBasis=None
    )
    unknown = repair_payload(repair, source, fixed)
    wire_validator(repair).validate(unknown)
    accepted = repair.validate(replace(raw, text=json.dumps(unknown, ensure_ascii=False)))
    assert score_importance(accepted.mapped.insights[0].assessments[0].axes) is None
