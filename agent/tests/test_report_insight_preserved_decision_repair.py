"""Prose-only factual repair cannot silently change validated decision values."""

import json
from copy import deepcopy
from dataclasses import replace

import pytest
from jsonschema import Draft202012Validator
from pydantic_ai.profiles.openai import OpenAIJsonSchemaTransformer
from test_report_insight_assessment import payload, request, response
from test_report_insight_native_field_diagnostics import conditional_payload, recorded_source

from app.core.errors import OutputValidationError
from app.llm import report_insight_service as service
from app.llm.openai_contract import output_contract
from app.llm.report_insight_assessment import (
    draft_prompt,
    draft_schema,
    draft_to_wire,
    validate_draft,
)


def full_validator(source, calls):
    def validate(raw):
        calls.append(json.loads(raw.text))
        draft = validate_draft(raw, source)
        service._validated_map_output(
            replace(raw, text=draft.mapped.model_dump_json(by_alias=True)),
            source,
            native_assessments=draft.evidence,
        )
        return draft

    return validate


def repair_for(source, value):
    raw = response(value, source)
    calls = []
    validate = full_validator(source, calls)
    with pytest.raises(service.ReportAssessmentValidationError) as caught:
        validate(raw)
    calls.clear()
    schema = draft_schema(source)
    before = deepcopy(schema), source.model_dump_json(), raw.text
    # This method only builds a repair contract; no provider/settings are used.
    engine = object.__new__(service.ReportInsightService)
    repair = engine._repair_call(draft_prompt(source), schema, raw.text, caught.value, validate)
    assert (schema, source.model_dump_json(), raw.text) == before
    return repair, caught.value, raw, calls


def wire_validator(repair):
    wire = OpenAIJsonSchemaTransformer(
        output_contract(repair.response_schema).schema, strict=True
    ).walk()
    return Draft202012Validator(wire)


def repair_payload(repair, source, value):
    wire = draft_to_wire(value, source)
    for audience, entries in repair.response_schema["properties"]["assessments"][
        "properties"
    ].items():
        wanted = set(entries["properties"])
        wire["assessments"][audience] = {
            key: item for key, item in wire["assessments"][audience].items() if key in wanted
        }
    return wire


@pytest.mark.parametrize("failed_ids", [(7877,), (7877, 7879, 7880, 7883, 7885, 7886)])
def test_recorded_id_number_failures_preserve_unknown_effect_in_partial_and_full_repair(failed_ids):
    # Actual MAP7 -> MAP8: claim IDs in reason failed the numeric fact guard;
    # unconstrained repair changed all six unknown effects to false NO_CHANGE.
    source = request(ids=(7877, 7879, 7880, 7883, 7885, 7886))
    value = payload(source)
    for finding in source.findings:
        item = value["assessments"]["CHIP_MAKER"][f"finding{finding.id}"]
        item.update(
            impactScope="UNDETERMINED",
            impactBasis=None,
            urgencyState="UNDETERMINED",
            urgencyBasis=None,
        )
        item["reason"] = "공정 검증 업무에 연결되는 원문 사건을 확인한다."
        if finding.id in failed_ids:
            item["reason"] = f"원문 문장(claim {finding.id}:0)이 공정 검증 업무와 관련된다."
    repair, error, raw, calls = repair_for(source, value)
    assert set(error.native_prose_repairs) == set(failed_ids)
    assert set(error.error_kinds) == {"report_fact_mismatch"}
    assert "근거에서 확인되지 않는 숫자" in str(error)
    assert set(
        repair.response_schema["properties"]["assessments"]["properties"]["CHIP_MAKER"][
            "properties"
        ]
    ) == {f"finding{i}" for i in failed_ids}
    repaired = deepcopy(value)
    for identifier in failed_ids:
        repaired["assessments"]["CHIP_MAKER"][f"finding{identifier}"]["reason"] = (
            "공정 검증 업무에 연결되는 원문 사건을 확인한다."
        )
    good = repair_payload(repair, source, repaired)
    wire_validator(repair).validate(good)
    result = repair.validate(replace(raw, text=json.dumps(good, ensure_ascii=False)))
    assert len(calls) == 1
    assert len(calls[0]["assessments"]["CHIP_MAKER"]) == 6
    assert all(
        item.impact_scope == "UNDETERMINED" for item in result.evidence["CHIP_MAKER"].values()
    )
    for finding in source.findings:
        if finding.id not in failed_ids:
            assert (
                calls[0]["assessments"]["CHIP_MAKER"][f"finding{finding.id}"]
                == json.loads(raw.text)["assessments"]["CHIP_MAKER"][f"finding{finding.id}"]
            )
    # A provider ignoring its strict schema must still be rejected server-side.
    bad = deepcopy(good)
    entry = bad["assessments"]["CHIP_MAKER"][f"finding{failed_ids[0]}"]
    entry["decision"]["effect"] = {
        "impactScope": "NO_CHANGE",
        "basis": deepcopy(entry["decision"]["connection"]["basis"]),
    }
    assert not wire_validator(repair).is_valid(bad)
    with pytest.raises(OutputValidationError, match="변경할 수 없습니다"):
        repair.validate(replace(raw, text=json.dumps(bad, ensure_ascii=False)))
    assert len(calls) == 1


def test_condition_only_repair_keeps_relation_and_reason_but_allows_same_finding_basis_correction():
    source = recorded_source()
    value = conditional_payload(source)
    entry = value["assessments"]["IT_INFRA"]["finding7815"]
    entry["condition"] = "삼성전자와 화웨이의 스마트폰 출고가 인상이 해당 업무에 연결되는 경우"
    repair, error, raw, calls = repair_for(source, value)
    assert error.native_prose_repairs[7815][1] == ("decision.connection.condition",)
    corrected = deepcopy(value)
    claim = source.findings[0].claims[1]
    corrected["assessments"]["IT_INFRA"]["finding7815"]["relationBasis"] = {
        "claimId": claim.id,
        "quote": claim.text,
    }
    good = repair_payload(repair, source, corrected)
    wire_validator(repair).validate(good)
    result = repair.validate(replace(raw, text=json.dumps(good, ensure_ascii=False)))
    item = result.evidence["IT_INFRA"][7815]
    assert item.relation == "CONDITIONAL"
    assert item.reason == entry["reason"]
    assert item.relation_basis.claim_id == "7815:0"
    bad = deepcopy(good)
    bad["assessments"]["IT_INFRA"]["finding7815"]["decision"]["connection"].update(
        relation="DIRECT", condition=None
    )
    assert not wire_validator(repair).is_valid(bad)
    with pytest.raises(OutputValidationError, match="변경할 수 없습니다"):
        repair.validate(replace(raw, text=json.dumps(bad, ensure_ascii=False)))
    changed_reason = deepcopy(good)
    changed_reason["assessments"]["IT_INFRA"]["finding7815"]["reason"] = (
        "원문 사건의 조건을 확인한다."
    )
    assert not wire_validator(repair).is_valid(changed_reason)
    with pytest.raises(OutputValidationError, match="변경할 수 없습니다"):
        repair.validate(replace(raw, text=json.dumps(changed_reason, ensure_ascii=False)))
    foreign = deepcopy(good)
    foreign["assessments"]["IT_INFRA"]["finding7815"]["decision"]["connection"]["basis"] = {
        "claimId": "9999:0",
        "sourceSpanId": "s9999_0_0",
    }
    assert not wire_validator(repair).is_valid(foreign)
    with pytest.raises(ValueError, match="원문 선택 계약 위반"):
        repair.validate(replace(raw, text=json.dumps(foreign, ensure_ascii=False)))
    assert len(calls) == 2  # Corrected output and the rejected foreign basis reach full validation.


def test_mixed_native_coherence_and_fact_failures_keep_the_ordinary_repair_contract():
    source = request(ids=(101,))
    value = payload(source)
    value["assessments"]["CHIP_MAKER"]["finding101"]["reason"] = (
        "영향 범위는 미확인이다. 9999년의 업무 조건을 확인한다."
    )
    raw = response(value, source)
    error = service._native_assessment_repair_errors(raw, source)
    assert set(error.error_kinds) == {"report_assessment_draft_invalid", "report_fact_mismatch"}
    assert not error.native_prose_repairs
    schema = draft_schema(source)
    engine = object.__new__(service.ReportInsightService)
    repair = engine._repair_call(draft_prompt(source), schema, raw.text, error, lambda x: x)
    assert repair.response_schema == schema


@pytest.mark.parametrize("defect", ["legacy_error", "changed_raw"])
def test_untrusted_or_stale_attribution_cannot_freeze_decisions(defect):
    source = request(ids=(101,))
    value = payload(source)
    value["assessments"]["CHIP_MAKER"]["finding101"]["reason"] = (
        "원문 문장(claim 101:0)의 검증 조건을 확인한다."
    )
    _, error, raw, _ = repair_for(source, value)
    if defect == "legacy_error":
        error = service.ReportAssessmentValidationError(
            "nativeFields=reason", error_kinds=("report_fact_mismatch",), failed_finding_ids=(101,)
        )
    else:
        altered = json.loads(raw.text)
        altered["assessments"]["CHIP_MAKER"]["finding101"]["reason"] += " 수정"
        raw = replace(raw, text=json.dumps(altered, ensure_ascii=False))
    schema = draft_schema(source)
    engine = object.__new__(service.ReportInsightService)
    repair = engine._repair_call(draft_prompt(source), schema, raw.text, error, lambda x: x)
    assert repair.response_schema == schema
