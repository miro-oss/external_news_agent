"""Different records' repair causes must not reopen verified native decisions."""

import json
from copy import deepcopy
from dataclasses import replace

import pytest
from test_report_insight_assessment import payload, request, response
from test_report_insight_core_forecast_support import recorded_case
from test_report_insight_map_complete_diagnostics import deadline_record
from test_report_insight_preserved_connection_repair import corrected_capacity
from test_report_insight_preserved_decision_repair import (
    full_validator,
    repair_payload,
    wire_validator,
)

from app.core.errors import OutputValidationError
from app.llm import report_insight_service as service
from app.llm.report_insight_assessment import draft_prompt, draft_schema, review_prompt
from app.llm.report_insight_fact_rendering import build_fact_text_catalog

GOOD_REASON = "공정 검증 업무에 연결되는 원문 사건을 확인한다."
BAD_COMPANY_REASON = "TSMC의 공정 검증 영향을 확인한다."


def mixed_case(*, same_finding=False):
    source = request(ids=(101, 102, 103))
    value = payload(source)
    records = value["assessments"]["CHIP_MAKER"]
    records["finding101"]["reason"] = "영향 범위는 미확인이다. " + (
        BAD_COMPANY_REASON if same_finding else "공정 검증 영향을 확인한다."
    )
    records["finding102"]["reason"] = BAD_COMPANY_REASON
    return source, value


def build_repair(source, value, prompt, *, public_only=False):
    raw = response(value, source)
    calls = []
    validate = full_validator(source, calls)
    if public_only:
        with pytest.raises(service.ReportAssessmentValidationError) as caught:
            validate(raw)
        error = caught.value
        calls.clear()
    else:
        error = service._native_assessment_repair_errors(raw, source)
    assert error is not None
    schema = draft_schema(source)
    before = deepcopy(schema), source.model_dump_json(), raw.text
    repair = object.__new__(service.ReportInsightService)._repair_call(
        prompt(source), schema, raw.text, error, validate
    )
    assert before == (schema, source.model_dump_json(), raw.text)
    return repair, error, raw, calls


def corrected_mixed(value):
    fixed = deepcopy(value)
    records = fixed["assessments"]["CHIP_MAKER"]
    # The coherence error belongs to 101, so its impact decision remains editable.
    records["finding101"].update(
        impactScope="UNDETERMINED",
        impactBasis=None,
        reason="영향 범위는 미확인이다. 공정 검증 영향을 확인한다.",
    )
    records["finding102"]["reason"] = GOOD_REASON
    return fixed


def repaired_response(repair, source, raw, fixed):
    wire = repair_payload(repair, source, fixed)
    return wire, replace(raw, text=json.dumps(wire, ensure_ascii=False))


def assert_normal_record_preserved(raw, calls):
    assert len(calls) == 1
    records = calls[0]["assessments"]["CHIP_MAKER"]
    assert set(records) == {"finding101", "finding102", "finding103"}
    assert records["finding103"] == json.loads(raw.text)["assessments"]["CHIP_MAKER"]["finding103"]


@pytest.mark.parametrize("prompt", [draft_prompt, review_prompt], ids=["MAP", "REVIEW"])
@pytest.mark.parametrize("same_finding", [False, True], ids=["separate", "mixed_on_101"])
def test_native_and_public_failures_preserve_only_the_prose_only_record(prompt, same_finding):
    source, value = mixed_case(same_finding=same_finding)
    repair, error, raw, calls = build_repair(source, value, prompt)
    assert set(error.error_kinds) == {
        "report_assessment_draft_invalid",
        "report_evidence_insufficient",
    }
    assert error.failed_finding_ids == (101, 102)
    assert set(error.native_prose_repairs) == {102}
    assert not error.native_connection_repairs
    entries = repair.response_schema["properties"]["assessments"]["properties"]["CHIP_MAKER"]
    assert set(entries["properties"]) == {"finding101", "finding102"}
    fixed, candidate = repaired_response(repair, source, raw, corrected_mixed(value))
    wire_validator(repair).validate(fixed)
    result = repair.validate(candidate)
    assert result.evidence["CHIP_MAKER"][101].impact_scope == "UNDETERMINED"
    assert result.evidence["CHIP_MAKER"][102].impact_scope == "CORE_CONSTRAINT"
    assert_normal_record_preserved(raw, calls)


@pytest.mark.parametrize("prompt", [draft_prompt, review_prompt], ids=["MAP", "REVIEW"])
@pytest.mark.parametrize("impact_scope", ["UNDETERMINED", "NO_CHANGE"])
def test_mixed_batch_rejects_previously_accepted_decision_drift(prompt, impact_scope):
    source, value = mixed_case()
    repair, _, raw, calls = build_repair(source, value, prompt)
    fixed = corrected_mixed(value)
    record = fixed["assessments"]["CHIP_MAKER"]["finding102"]
    record["impactScope"] = impact_scope
    if impact_scope == "UNDETERMINED":
        record["impactBasis"] = None
    wire, candidate = repaired_response(repair, source, raw, fixed)
    assert not wire_validator(repair).is_valid(wire)
    with pytest.raises(OutputValidationError, match="변경할 수 없습니다"):
        repair.validate(candidate)
    assert calls == []


def mixed_connection_case():
    source, value = recorded_case()
    other = request(ids=(102, 103))
    source.findings.extend(other.findings)
    value["assessments"]["CHIP_MAKER"].update(payload(other)["assessments"]["CHIP_MAKER"])
    value["assessments"]["CHIP_MAKER"]["finding102"]["reason"] = BAD_COMPANY_REASON
    return source, value


@pytest.mark.parametrize("prompt", [draft_prompt, review_prompt], ids=["MAP", "REVIEW"])
def test_connection_and_prose_preservation_apply_to_different_records_in_one_repair(prompt):
    source, value = mixed_connection_case()
    repair, error, raw, calls = build_repair(source, value, prompt)
    assert set(error.native_connection_repairs) == {101}
    assert set(error.native_prose_repairs) == {102}
    fixed = corrected_capacity(value)
    fixed["assessments"]["CHIP_MAKER"]["finding102"]["reason"] = GOOD_REASON
    wire, candidate = repaired_response(repair, source, raw, fixed)
    wire_validator(repair).validate(wire)
    result = repair.validate(candidate)
    assert result.evidence["CHIP_MAKER"][101].relation == "DIRECT"
    assert result.evidence["CHIP_MAKER"][101].impact_basis.claim_id == "101:1"
    assert result.evidence["CHIP_MAKER"][102].impact_scope == "CORE_CONSTRAINT"
    assert_normal_record_preserved(raw, calls)

    changed_connection = deepcopy(wire)
    changed_connection["assessments"]["CHIP_MAKER"]["finding101"]["decision"]["connection"][
        "relation"
    ] = "BACKGROUND"
    assert not wire_validator(repair).is_valid(changed_connection)
    with pytest.raises(OutputValidationError, match="connection을 변경할 수 없습니다"):
        repair.validate(replace(raw, text=json.dumps(changed_connection, ensure_ascii=False)))

    changed_effect = deepcopy(wire)
    changed_effect["assessments"]["CHIP_MAKER"]["finding102"]["decision"]["effect"] = {
        "impactScope": "UNDETERMINED",
        "basis": None,
    }
    assert not wire_validator(repair).is_valid(changed_effect)
    with pytest.raises(OutputValidationError, match="변경할 수 없습니다"):
        repair.validate(replace(raw, text=json.dumps(changed_effect, ensure_ascii=False)))
    assert len(calls) == 1


@pytest.mark.parametrize("retained_defect", ["company", "coherence"])
def test_mixed_repair_still_revalidates_the_complete_original_output(retained_defect):
    source, value = mixed_case()
    repair, _, raw, calls = build_repair(source, value, draft_prompt)
    fixed = corrected_mixed(value)
    fixed["assessments"]["CHIP_MAKER"]["finding102"]["reason"] = (
        BAD_COMPANY_REASON if retained_defect == "company" else "영향 범위는 미확인이다."
    )
    wire, candidate = repaired_response(repair, source, raw, fixed)
    wire_validator(repair).validate(wire)
    with pytest.raises(ValueError):
        repair.validate(candidate)
    assert_normal_record_preserved(raw, calls)


@pytest.mark.parametrize("same_finding", [False, True], ids=["separate", "mixed_on_101"])
@pytest.mark.parametrize("both_prose_fields", [False, True], ids=["reason", "reason_condition"])
def test_direct_public_batch_keeps_another_findings_prose_context(same_finding, both_prose_fields):
    source = request(ids=(101, 102, 103), text="검증 장비 도입의 마감은 2026년 9월 20일이다.")
    value = payload(source, relation="CONDITIONAL" if both_prose_fields else "DIRECT")
    for record in value["assessments"]["CHIP_MAKER"].values():
        deadline_record(
            record,
            bad_company=record["findingId"] == 102 or (same_finding and record["findingId"] == 101),
            expired_urgency=record["findingId"] == 101,
        )
    if both_prose_fields:
        value["assessments"]["CHIP_MAKER"]["finding102"]["condition"] = (
            "TSMC의 공정 검증 일정에 필요한 경우"
        )
    repair, error, raw, calls = build_repair(source, value, draft_prompt, public_only=True)
    assert set(error.error_kinds) == {"report_assessment_invalid", "report_evidence_insufficient"}
    assert set(error.native_prose_repairs) == {102}
    assert set(error.native_prose_repairs[102][1]) == (
        {"reason", "decision.connection.condition"} if both_prose_fields else {"reason"}
    )
    fixed = deepcopy(value)
    for identifier in (101, 102):
        deadline_record(fixed["assessments"]["CHIP_MAKER"][f"finding{identifier}"])
    if both_prose_fields:
        fixed["assessments"]["CHIP_MAKER"]["finding102"]["condition"] = (
            "공정 검증 일정에 필요한 경우"
        )
    wire, candidate = repaired_response(repair, source, raw, fixed)
    wire_validator(repair).validate(wire)
    result = repair.validate(candidate)
    assert result.evidence["CHIP_MAKER"][101].urgency_state == "UNDETERMINED"
    assert_normal_record_preserved(raw, calls)

    changed = deepcopy(wire)
    changed["assessments"]["CHIP_MAKER"]["finding102"]["decision"]["timing"] = {
        "urgencyState": "MONITOR",
        "basis": changed["assessments"]["CHIP_MAKER"]["finding102"]["decision"]["connection"][
            "basis"
        ],
    }
    assert not wire_validator(repair).is_valid(changed)
    with pytest.raises(OutputValidationError, match="변경할 수 없습니다"):
        repair.validate(replace(raw, text=json.dumps(changed, ensure_ascii=False)))
    assert len(calls) == 1


def templated_connection_case(*, condition=False):
    source, value = mixed_connection_case()
    record = value["assessments"]["CHIP_MAKER"]["finding101"]
    slot = next(slot for slot in build_fact_text_catalog(source).slots if "101:1" in slot.claim_ids)
    record["sourceQuotes"] = {"reason": slot.slot_id, "condition": None}
    record["reason"] = "메모리 생산능력 제약은 생산능력·배분 업무와 연결된다."
    if condition:
        record.update(
            relation="CONDITIONAL",
            condition="생산능력 제약이 생산 일정에 영향을 미치는 경우",
        )
        record["sourceQuotes"]["condition"] = slot.slot_id
    return source, value, slot.slot_id


def template_validator(source, calls):
    def validate(raw):
        calls.append(json.loads(raw.text))
        draft = service.validate_draft(raw, source)
        service._validated_map_output(
            replace(raw, text=draft.mapped.model_dump_json(by_alias=True)),
            source,
            native_assessments=draft.evidence,
        )
        return draft

    return validate


@pytest.mark.parametrize("prompt", [draft_prompt, review_prompt], ids=["MAP", "REVIEW"])
@pytest.mark.parametrize("condition", [False, True], ids=["reason", "reason_condition"])
def test_valid_template_axis_error_preserves_authenticated_original_connection(prompt, condition):
    source, value, source_id = templated_connection_case(condition=condition)
    raw = response(value, source)
    error = service._native_assessment_repair_errors(raw, source, template_wire=True)
    assert set(error.native_connection_repairs) == {101}
    assert set(error.native_prose_repairs) == {102}
    original = value["assessments"]["CHIP_MAKER"]["finding101"]
    context = error.native_connection_repairs[101]
    assert context.snapshot.reason == original["reason"]
    assert context.snapshot.condition == original["condition"]
    calls = []
    repair = object.__new__(service.ReportInsightService)._repair_call(
        prompt(source), draft_schema(source), raw.text, error, template_validator(source, calls)
    )
    fixed = corrected_capacity(value)
    assert fixed["assessments"]["CHIP_MAKER"]["finding101"]["sourceQuotes"]["reason"] == source_id
    fixed["assessments"]["CHIP_MAKER"]["finding102"]["reason"] = GOOD_REASON
    wire, candidate = repaired_response(repair, source, raw, fixed)
    wire_validator(repair).validate(wire)
    result = repair.validate(candidate)
    assert "{{fact:" not in result.mapped.model_dump_json()
    assert result.evidence["CHIP_MAKER"][101].relation_basis.claim_id == "101:1"
    assert_normal_record_preserved(raw, calls)

    wire["assessments"]["CHIP_MAKER"]["finding101"]["decision"]["connection"]["basis"] = {
        "claimId": "101:0",
        "sourceSpanId": "s101_0_0",
    }
    assert not wire_validator(repair).is_valid(wire)
    with pytest.raises(OutputValidationError, match="connection을 변경할 수 없습니다"):
        repair.validate(replace(raw, text=json.dumps(wire, ensure_ascii=False)))
    assert len(calls) == 1


@pytest.mark.parametrize("mutation", ["reason", "connection", "source", "source_reference"])
def test_rebound_template_connection_does_not_authenticate_modified_input(mutation):
    source, value, _ = templated_connection_case()
    source.findings = source.findings[:1]
    value["assessments"]["CHIP_MAKER"] = {
        "finding101": value["assessments"]["CHIP_MAKER"]["finding101"]
    }
    raw = response(value, source)
    error = service._native_assessment_repair_errors(raw, source, template_wire=True)
    assert set(error.native_connection_repairs) == {101}
    if mutation == "source":
        source.findings[0].sentences[1].text += " 추가 원문"
    else:
        changed = json.loads(raw.text)
        record = changed["assessments"]["CHIP_MAKER"]["finding101"]
        if mutation == "reason":
            record["reason"] += " 추가 설명"
        elif mutation == "source_reference":
            record["sourceQuotes"]["reason"] = "source-" + "0" * 24
        else:
            record["decision"]["connection"]["work"] = "MATERIAL_SUPPLY"
        raw = replace(raw, text=json.dumps(changed, ensure_ascii=False))
    schema = draft_schema(source)
    repair = object.__new__(service.ReportInsightService)._repair_call(
        draft_prompt(source), schema, raw.text, error, template_validator(source, [])
    )
    assert repair.response_schema == schema
