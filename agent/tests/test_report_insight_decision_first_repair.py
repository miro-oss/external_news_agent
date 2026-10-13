"""Retry serialization follows final decisions without weakening source guards."""

import json
from copy import deepcopy
from dataclasses import replace

import pytest
from jsonschema import Draft202012Validator
from pydantic_ai.profiles.openai import OpenAIJsonSchemaTransformer
from test_report_insight_assessment import framed, payload, request, response
from test_report_insight_connection_evidence_dependency import full_validate
from test_report_insight_final_basis_repair import (
    AUDIENCE,
    CONNECTION_SOURCE,
    FINAL_REASON,
    ORIGINAL_REASON,
    build_repair,
    cleared_bases,
    final_basis_case,
    selected_basis,
)
from test_report_insight_preserved_decision_repair import (
    repair_for as prose_repair_for,
)
from test_report_insight_preserved_decision_repair import (
    repair_payload,
    wire_validator,
)
from test_report_insight_truncated_repair_preservation import (
    corrected as corrected_truncated,
)
from test_report_insight_truncated_repair_preservation import (
    mixed_case as truncated_case,
)
from test_report_insight_truncated_repair_preservation import truncated
from test_report_insight_v4_pipeline import V4Provider, stages

from app.core.config import Settings
from app.core.errors import OutputValidationError
from app.llm import report_insight_service as service
from app.llm.openai_contract import output_contract
from app.llm.report_insight_assessment import draft_prompt, draft_schema, review_prompt
from app.llm.report_insight_instructions import (
    ASSESSMENT_FINAL_BASIS_RULE,
    ASSESSMENT_REASON_RULE,
    ASSESSMENT_REPAIR_ORDER_RULE,
    report_stage_instruction,
)
from app.schemas.report_insight import CLAIMLESS_ASSESSMENT_REASON


@pytest.mark.parametrize(
    "audience", ["CHIP_MAKER", "EQUIPMENT_MAKER", "MARKET_INVESTOR", "IT_INFRA"]
)
@pytest.mark.parametrize("stage", ["MAP", "REVIEW"])
def test_retry_system_retains_instruction_budget_for_each_audience(audience, stage):
    instruction = report_stage_instruction([audience], stage, repair=True)
    assert len(instruction) <= 3500
    assert instruction.count(ASSESSMENT_REASON_RULE) == 1
    assert instruction.index(ASSESSMENT_REPAIR_ORDER_RULE) < instruction.index(
        ASSESSMENT_REASON_RULE
    )


def _records(schema):
    return [
        record
        for audience in schema["properties"]["assessments"]["properties"].values()
        for record in audience["properties"].values()
    ]


def _assert_decision_first(schema):
    # Inspect the provider contract after its normal SDK conversion too.
    strict = OpenAIJsonSchemaTransformer(output_contract(schema).schema, strict=True).walk()
    for contract in (schema, strict):
        for record in _records(contract):
            assert list(record["properties"]) == ["findingId", "decision", "reason", "sourceQuotes"]
            assert record["required"] == ["findingId", "decision", "reason", "sourceQuotes"]


def _assert_retry_guidance(call, stage):
    _assert_decision_first(call["response_schema"])
    prompt_instructions = call["prompt"].split("<report-insight-input>", 1)[0]
    assert "findingId, decision, reason, sourceQuotes" in prompt_instructions
    assert "findingId, reason, decision, sourceQuotes" not in prompt_instructions
    assert "먼저 원문 대상과 사건 단계에 맞는 업무 연결 근거를 간결하게 설명한 뒤" not in (
        prompt_instructions
    )
    assert ASSESSMENT_FINAL_BASIS_RULE in call["prompt"]
    active_system = call["system_instruction"].split(f"현재 단계는 {stage.split('-')[0]}.", 1)[1]
    assert active_system.index("connection") < active_system.index(ASSESSMENT_REASON_RULE)


def _pipeline_hook(stage, value, failure):
    def hook(current, occurrence, data, result):
        if current.startswith("REDUCE"):
            for insight in result["insights"]:
                insight.update(
                    headline="시스템 조달의 변경과 준비 일정을 살펴본다.",
                    overview=[
                        {
                            "text": "서버 도입 자원과 준비 일정 변경은 시스템 조달에 영향을 준다.",
                            "basisClaimIds": ["202:0"],
                            "assumption": "서버 도입 프로젝트의 자원 배분이 변경되는 경우",
                        }
                    ],
                )
            return result
        proposed = cleared_bases(value)
        if current == stage and occurrence == 1:
            if failure == "axis":
                proposed = deepcopy(value)
            elif failure == "prose":
                proposed["assessments"][AUDIENCE]["finding101"]["reason"] = (
                    "9999년의 가격 전망은 시스템 조달과 연결된다."
                )
        elif stage == "REVIEW-001" and current == "MAP-001":
            proposed = selected_basis(value, "retain_monitor")
        wanted = {f"finding{finding['id']}" for finding in data["findings"]}
        proposed["assessments"][AUDIENCE] = {
            key: entry for key, entry in proposed["assessments"][AUDIENCE].items() if key in wanted
        }
        return proposed

    return hook


@pytest.mark.parametrize("stage", ["MAP-001", "REVIEW-001"])
@pytest.mark.parametrize("failure", ["axis", "prose", "unlocalized_json"])
def test_actual_native_retry_uses_decision_first_contract_and_full_validation(stage, failure):
    source, value = final_basis_case()
    before = source.model_dump_json(), deepcopy(value)

    def raw(current, occurrence, data, text):
        return (
            "{" if failure == "unlocalized_json" and current == stage and occurrence == 1 else text
        )

    def wire(current, occurrence, data, result):
        if current == stage and occurrence == 2:
            records = result["assessments"][AUDIENCE]
            for key, record in records.items():
                records[key] = {
                    field: record[field]
                    for field in ("findingId", "decision", "reason", "sourceQuotes")
                }
        return result

    provider = V4Provider(
        source, hook=_pipeline_hook(stage, value, failure), raw_hook=raw, wire_hook=wire
    )
    engine = service.ReportInsightService(Settings(_env_file=None, AGENT_MOCK=False), provider)
    result = engine.generate(source)
    expected_stages = ["MAP-001", "REVIEW-001", "REDUCE-001"]
    expected_stages.insert(expected_stages.index(stage), stage)
    assert stages(provider) == expected_stages
    initial_index = stages(provider).index(stage)
    retry = provider.calls[initial_index + 1]
    _assert_retry_guidance(retry, stage)
    # Initial MAP/REVIEW generation and REDUCE retain their original contract.
    for record in _records(provider.calls[initial_index]["response_schema"]):
        assert list(record["properties"]) == ["findingId", "reason", "decision", "sourceQuotes"]
    initial_system = provider.calls[initial_index]["system_instruction"].split(
        f"현재 단계는 {stage.split('-')[0]}.", 1
    )[1]
    assert initial_system.index(ASSESSMENT_REASON_RULE) < initial_system.index("connection")
    retry_output = json.loads(provider.response_texts[initial_index + 1])["assessments"][AUDIENCE]
    assert all(
        list(record) == ["findingId", "decision", "reason", "sourceQuotes"]
        for record in retry_output.values()
    )
    assert "<validation-error>" not in provider.calls[-1]["prompt"]
    assert "현재 단계는 REDUCE." in provider.calls[-1]["system_instruction"]
    assert result.meta.input_tokens == 44 and result.meta.output_tokens == 28
    assessment = next(item for item in result.insights[0].assessments if item.finding_id == 101)
    assert assessment.reason == f"원문: 「{CONNECTION_SOURCE}」 해석: {FINAL_REASON}"
    assert assessment.basis_claim_ids == ["101:0"]
    assert assessment.axes.impact is None and assessment.axes.urgency is None
    assert all(provider.schema_validity)
    assert before == (source.model_dump_json(), value)


@pytest.mark.parametrize("prompt", [draft_prompt, review_prompt], ids=["MAP", "REVIEW"])
@pytest.mark.parametrize(
    "reason,rule",
    [
        (ORIGINAL_REASON, "source_binding"),
        ("선택 근거(101:1)는 시장 전망이며 시스템 조달과 연결된다.", "unsupported_number"),
        ("삼성전자의 가격 전망은 시스템 조달과 연결된다.", "company"),
    ],
)
def test_decision_first_axis_repair_still_rejects_stale_ids_products_and_new_facts(
    prompt, reason, rule
):
    source, value = final_basis_case()
    repair, raw, _, validated = build_repair(source, value, prompt)
    _assert_decision_first(repair.response_schema)
    proposed = cleared_bases(value)
    proposed["assessments"][AUDIENCE]["finding101"]["reason"] = reason
    bad = repair_payload(repair, source, proposed)
    wire_validator(repair).validate(bad)
    candidate = replace(raw, text=json.dumps(bad, ensure_ascii=False))
    with pytest.raises(service.ReportAssessmentValidationError) as caught:
        repair.validate(candidate)
    assert rule in {issue.rule for issue in caught.value.validation_issues}
    assert all(issue.claim_ids == ("101:0",) for issue in caught.value.validation_issues)
    assert validated[-1]["assessments"][AUDIENCE]["finding101"]["reason"] == reason
    assert json.loads(candidate.text) == bad  # Nothing was stripped or silently replaced.
    fixed = repair_payload(repair, source, cleared_bases(value))
    accepted = repair.validate(replace(raw, text=json.dumps(fixed, ensure_ascii=False)))
    assert accepted.evidence[AUDIENCE][101].reason == FINAL_REASON
    assert accepted.evidence[AUDIENCE][101].relation_basis.claim_id == "101:0"
    assert (
        validated[-1]["assessments"][AUDIENCE]["finding202"]
        == (json.loads(raw.text)["assessments"][AUDIENCE]["finding202"])
    )


def test_prose_only_retry_keeps_const_decision_and_rejects_a_sourced_target_price():
    source = request(
        audiences=("MARKET_INVESTOR",),
        text="제조사는 생산라인 전체의 가동 중단이 현재 계속되며 목표주가를 10만원으로 제시했다.",
    )
    value = payload(source)
    entry = value["assessments"]["MARKET_INVESTOR"]["finding101"]
    entry["reason"] = "9999년의 생산 제약은 시장 전망 업무와 연결된다."
    repair, error, raw, _ = prose_repair_for(source, value)
    assert set(error.native_prose_repairs) == {101}
    _assert_decision_first(repair.response_schema)
    fixed = deepcopy(value)
    fixed["assessments"]["MARKET_INVESTOR"]["finding101"]["reason"] = (
        "생산 제약은 시장 전망 업무와 연결된다."
    )
    wire = repair_payload(repair, source, fixed)
    accepted = repair.validate(replace(raw, text=json.dumps(wire, ensure_ascii=False)))
    original = json.loads(raw.text)["assessments"]["MARKET_INVESTOR"]["finding101"]["decision"]
    assert wire["assessments"]["MARKET_INVESTOR"]["finding101"]["decision"] == original
    assert (
        accepted.evidence["MARKET_INVESTOR"][101].reason
        == fixed["assessments"]["MARKET_INVESTOR"]["finding101"]["reason"]
    )
    drift = deepcopy(wire)
    drift["assessments"]["MARKET_INVESTOR"]["finding101"]["decision"]["timing"] = {
        "urgencyState": "UNDETERMINED",
        "basis": None,
    }
    assert not wire_validator(repair).is_valid(drift)
    with pytest.raises(OutputValidationError, match="변경할 수 없습니다"):
        repair.validate(replace(raw, text=json.dumps(drift, ensure_ascii=False)))
    target = deepcopy(wire)
    target["assessments"]["MARKET_INVESTOR"]["finding101"]["reason"] = (
        "목표가를 10만원으로 제시한다."
    )
    wire_validator(repair).validate(target)
    with pytest.raises(service.ReportAssessmentValidationError) as caught:
        repair.validate(replace(raw, text=json.dumps(target, ensure_ascii=False)))
    assert "report_expression_policy" in caught.value.error_kinds


@pytest.mark.parametrize("prompt", [draft_prompt, review_prompt], ids=["MAP", "REVIEW"])
def test_full_retry_keeps_claimless_fixed_reason_without_erasing_nonempty_evidence(prompt):
    source, value = final_basis_case()
    empty = request(ids=(303,), audiences=(AUDIENCE,)).findings[0]
    empty.claims = []
    source.findings.append(empty)
    fixed = cleared_bases(value)
    fixed["assessments"][AUDIENCE]["finding303"] = {
        **deepcopy(fixed["assessments"][AUDIENCE]["finding101"]),
        "findingId": 303,
        "relation": "UNDETERMINED",
        "work": None,
        "relationBasis": None,
        "reason": CLAIMLESS_ASSESSMENT_REASON,
    }
    schema = draft_schema(source)
    before = json.dumps(schema, ensure_ascii=False), source.model_dump_json()
    repair = object.__new__(service.ReportInsightService)._repair_call(
        prompt(source),
        schema,
        "{",
        ValueError("incomplete JSON"),
        lambda raw: full_validate(raw, source),
    )
    _assert_decision_first(repair.response_schema)
    assert [finding["id"] for finding in framed(repair.prompt)["findings"]] == [101, 202, 303]
    wire = repair_payload(repair, source, fixed)
    Draft202012Validator(repair.response_schema).validate(wire)
    accepted = repair.validate(response(fixed, source))
    assert accepted.evidence[AUDIENCE][303].reason == CLAIMLESS_ASSESSMENT_REASON
    assert accepted.evidence[AUDIENCE][101].reason == FINAL_REASON
    bad = deepcopy(fixed)
    bad["assessments"][AUDIENCE]["finding303"]["reason"] = FINAL_REASON
    assert not wire_validator(repair).is_valid(repair_payload(repair, source, bad))
    with pytest.raises(ValueError):
        repair.validate(response(bad, source))
    assert before == (json.dumps(schema, ensure_ascii=False), source.model_dump_json())


@pytest.mark.parametrize("tampered_raw", [False, True])
def test_closed_prefix_and_unauthenticated_prefix_fallback_both_use_decision_first(tampered_raw):
    source, value = truncated_case()
    raw = truncated(source, value, 3)
    error = service._truncated_assessment_repair_error(raw, source, template_wire=True)
    assert error is not None
    assert set(error.preserved_wire["assessments"]["CHIP_MAKER"]) == {"finding103"}
    schema = draft_schema(source)
    before = source.model_dump_json(), raw.text, json.dumps(schema, ensure_ascii=False)
    repair = object.__new__(service.ReportInsightService)._repair_call(
        draft_prompt(source),
        schema,
        raw.text + " " if tampered_raw else raw.text,
        error,
        lambda candidate: full_validate(candidate, source),
    )
    _assert_decision_first(repair.response_schema)
    fixed = repair_payload(repair, source, corrected_truncated(value))
    assert set(fixed["assessments"]["CHIP_MAKER"]) == {
        "finding101",
        "finding102",
        "finding104",
        *(["finding103"] if tampered_raw else []),
    }
    wire_validator(repair).validate(fixed)
    accepted = repair.validate(
        replace(raw, text=json.dumps(fixed, ensure_ascii=False), truncated=False)
    )
    assert set(accepted.evidence["CHIP_MAKER"]) == {101, 102, 103, 104}
    assert (
        accepted.evidence["CHIP_MAKER"][103].reason
        == value["assessments"]["CHIP_MAKER"]["finding103"]["reason"]
    )
    assert before == (source.model_dump_json(), raw.text, json.dumps(schema, ensure_ascii=False))
