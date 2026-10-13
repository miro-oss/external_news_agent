"""A concise retry keeps decisions and grounding; unfinished provenance still fails."""

import json
from copy import deepcopy
from dataclasses import replace

import pytest
from report_insight_schema_assertions import assert_same_native_constraints
from test_report_insight_assessment import framed, response
from test_report_insight_connection_evidence_dependency import full_validate
from test_report_insight_mixed_connection_repair import corrected, mixed_case
from test_report_insight_preserved_decision_repair import repair_payload, wire_validator
from test_report_insight_v4_pipeline import V4Provider, stages

from app.core.config import Settings
from app.core.errors import AgentError
from app.llm import report_insight_service as service
from app.llm.report_insight_assessment import draft_prompt, draft_schema, review_prompt
from app.llm.report_insight_instructions import ASSESSMENT_REASON_RULE

SOURCE = "HBM4 메모리 가격은 내년에 상승할 것으로 전망된다."
GOOD_REASON = (
    "HBM4 가격 전망은 시스템 조달과 연결된다. "
    "구체적인 조달 변경과 대응 시점은 선택 근거에서 확인되지 않는다."
)
LONG_REASON = (
    "선택된 인용은 HBM4 메모리 가격 상승에 대한 시장 전망으로 시스템 조달과 연결된다. "
    "그러나 해당 문장은 특정 프로젝트의 자원·일정 변경이나 실제 조달 준비 활동을 "
    "명시하지 않아 원문 사건과 관점 업무의 연결은 유지하되 "
    "영향 범위와 대응 시점은 근거로 확인할 수 없다."
)
ID_SUFFIX = " 선택된 근거(claimId=101:0, source"


def reason_case():
    # Invented text and IDs: the production report and provider output stay local.
    source, value = mixed_case(neighbor=True)
    finding = source.findings[0]
    finding.claims[0].text = SOURCE
    finding.sentences[0].text = SOURCE
    other_source = "TSMC는 HBM4 메모리 가격이 내년에 상승할 것으로 전망했다."
    finding.claims[1].text = other_source
    finding.claims[1].evidence_sentence_ids = [1]
    finding.sentences.append(
        finding.sentences[0].model_copy(update={"index": 1, "text": other_source})
    )
    entry = value["assessments"]["IT_INFRA"]["finding101"]
    for field in ("relationBasis", "impactBasis", "urgencyBasis"):
        entry[field]["quote"] = SOURCE
    entry["reason"] = "HBM5의 가격 전망은 시스템 조달과 연결된다."
    return source, value


def corrected_reason(value, defect=None):
    fixed = corrected(value)
    fixed["assessments"]["IT_INFRA"]["finding101"]["reason"] = {
        None: GOOD_REASON,
        "internal_id": LONG_REASON + ID_SUFFIX,
        "unselected_company": "TSMC의 " + GOOD_REASON,
        "wrong_product": GOOD_REASON.replace("HBM4", "HBM5"),
    }[defect]
    return fixed


def build_repair(source, value, prompt):
    raw = response(value, source)
    error = service._native_assessment_repair_errors(raw, source, template_wire=True)
    assert error is not None
    assert set(error.native_connection_repairs) == {101}
    assert not error.native_prose_repairs
    assert {issue.rule for issue in error.validation_issues} == {
        "report_axis_market_forecast_only_project_change",
        "report_axis_market_forecast_only_scheduled_preparation",
        "source_binding",
    }
    validated = []

    def validate(candidate):
        validated.append(json.loads(candidate.text))
        return full_validate(candidate, source)

    repair = object.__new__(service.ReportInsightService)._repair_call(
        prompt(source), draft_schema(source), raw.text, error, validate
    )
    return repair, raw, validated


@pytest.mark.parametrize("prompt", [draft_prompt, review_prompt], ids=["MAP", "REVIEW"])
def test_complete_reason_repairs_mixed_errors_without_rewriting_proven_connection(prompt):
    source, value = reason_case()
    before = source.model_dump_json(), deepcopy(value)
    repair, raw, validated = build_repair(source, value, prompt)
    fixed = repair_payload(repair, source, corrected_reason(value))
    assert set(fixed["assessments"]["IT_INFRA"]) == {"finding101"}
    wire_validator(repair).validate(fixed)
    accepted = repair.validate(replace(raw, text=json.dumps(fixed, ensure_ascii=False)))
    original = json.loads(raw.text)["assessments"]["IT_INFRA"]
    merged = validated[0]["assessments"]["IT_INFRA"]
    assert merged["finding202"] == original["finding202"]
    assert (
        merged["finding101"]["decision"]["connection"]
        == (original["finding101"]["decision"]["connection"])
    )
    item = accepted.evidence["IT_INFRA"][101]
    assert item.reason == GOOD_REASON
    assert item.impact_scope == item.urgency_state == "UNDETERMINED"
    assert item.impact_basis is item.urgency_basis is None
    assert before == (source.model_dump_json(), value)


@pytest.mark.parametrize("prompt", [draft_prompt, review_prompt], ids=["MAP", "REVIEW"])
@pytest.mark.parametrize("defect", ["internal_id", "unselected_company", "wrong_product"])
def test_same_repaired_decisions_still_reject_unsupported_reason_without_sanitizing(prompt, defect):
    source, value = reason_case()
    repair, raw, validated = build_repair(source, value, prompt)
    fixed = repair_payload(repair, source, corrected_reason(value, defect))
    reason = fixed["assessments"]["IT_INFRA"]["finding101"]["reason"]
    if defect == "internal_id":
        assert len(reason) == 180
        # The complete text preceding the invalid appendix independently passes.
        complete = corrected_reason(value)
        complete["assessments"]["IT_INFRA"]["finding101"]["reason"] = LONG_REASON
        full_validate(response(complete, source), source)
    elif defect == "unselected_company":
        assert "TSMC" in source.findings[0].claims[1].text
        assert "TSMC" not in source.findings[0].claims[0].text
    wire_validator(repair).validate(fixed)
    candidate = replace(raw, text=json.dumps(fixed, ensure_ascii=False))
    with pytest.raises(service.ReportAssessmentValidationError) as caught:
        repair.validate(candidate)
    assert {issue.rule for issue in caught.value.validation_issues} == {
        "internal_id": {"internal_reference_in_prose", "unsupported_number"},
        "unselected_company": {"company"},
        "wrong_product": {"source_binding"},
    }[defect]
    assert all(
        issue.field == "assessments[101].reason" and issue.claim_ids == ("101:0",)
        for issue in caught.value.validation_issues
    )
    assert validated[0]["assessments"]["IT_INFRA"]["finding101"]["reason"] == reason
    assert json.loads(candidate.text) == fixed


def test_reason_completion_rule_follows_repair_data_without_changing_its_sources():
    source, value = reason_case()
    repair, _, _ = build_repair(source, value, draft_prompt)
    assert ASSESSMENT_REASON_RULE in repair.prompt.split("</validation-error>", 1)[1]
    assert [finding["id"] for finding in framed(repair.prompt)["findings"]] == [101]
    assert [claim["id"] for claim in framed(repair.prompt)["findings"][0]["claims"]] == [
        "101:0",
        "101:1",
    ]


@pytest.mark.parametrize("prompt", [draft_prompt, review_prompt], ids=["MAP", "REVIEW"])
def test_unlocalized_native_full_repair_still_reapplies_reason_completion_rule(prompt):
    source, value = reason_case()
    schema = draft_schema(source)
    fixed = response(corrected_reason(value), source)
    repair = object.__new__(service.ReportInsightService)._repair_call(
        prompt(source),
        schema,
        '{"assessments":',
        ValueError("The original JSON is incomplete."),
        lambda candidate: full_validate(candidate, source),
    )
    assert_same_native_constraints(repair.response_schema, schema)
    assert ASSESSMENT_REASON_RULE in repair.prompt.split("</validation-error>", 1)[1]
    assert [finding["id"] for finding in framed(repair.prompt)["findings"]] == [101, 202]
    assert repair.validate(fixed).evidence["IT_INFRA"][101].reason == GOOD_REASON


def test_legacy_map_repair_keeps_its_public_contract_without_native_reason_suffix():
    source, value = reason_case()
    original = response(corrected_reason(value), source)
    native = full_validate(original, source)
    fixed = replace(original, text=native.mapped.model_dump_json(by_alias=True))
    schema = service.report_insight_map_schema(source)
    repair = object.__new__(service.ReportInsightLegacyService)._repair_call(
        service._report_insight_prompt(source),
        schema,
        '{"insights":',
        ValueError("The original JSON is incomplete."),
        lambda candidate: service._validated_map_output(candidate, source),
    )
    assert repair.response_schema == schema
    assert repair.prompt.split("</validation-error>", 1)[1].strip() == ""
    assert ASSESSMENT_REASON_RULE not in repair.prompt
    assert repair.validate(fixed) == native.mapped


def test_reduce_repair_does_not_receive_native_assessment_reason_suffix():
    source, value = reason_case()
    native = full_validate(response(corrected_reason(value), source), source)
    retrieved = {
        insight.audience: service.retrieve_report_insight_evidence(
            source, insight.audience, insight.assessments, preserve_assessment_bases=True
        )
        for insight in native.mapped.insights
    }
    allowed = {audience: result.claim_ids for audience, result in retrieved.items()}
    schema = service.report_insight_reduce_schema(source, allowed, structured=True)

    def validate(candidate):
        return service._validated_v4_reduce_output(
            candidate,
            source,
            native.mapped,
            allowed,
            native_assessments=native.evidence,
            template_wire=True,
        )

    repair = object.__new__(service.ReportInsightService)._repair_call(
        service._reduce_v4_prompt(source, native, retrieved, allowed),
        schema,
        '{"insights":',
        ValueError("The original JSON is incomplete."),
        validate,
    )
    assert repair.response_schema == schema
    assert repair.validate is validate
    assert repair.prompt.split("</validation-error>", 1)[1].strip() == ""
    assert ASSESSMENT_REASON_RULE not in repair.prompt
    assert "retrievedEvidence" in framed(repair.prompt)


@pytest.mark.parametrize("defect", [None, "internal_id", "unselected_company"])
def test_pipeline_keeps_one_retry_and_observed_usage_when_reason_remains_invalid(defect):
    source, value = reason_case()

    def hook(stage, occurrence, data, result):
        if stage.startswith("REDUCE"):
            for insight in result["insights"]:
                insight.update(
                    headline="시스템 조달의 변경과 준비 일정을 살펴본다.",
                    overview=[
                        {
                            "text": (
                                "서버 도입 자원과 준비 일정 변경은 시스템 조달 업무에 영향을 준다."
                            ),
                            "basisClaimIds": ["202:0"],
                            "assumption": "서버 도입 프로젝트의 자원 배분이 변경되는 경우",
                        }
                    ],
                )
            return result
        proposed = deepcopy(
            value
            if stage == "MAP-001" and occurrence == 1
            else corrected_reason(value, defect if stage == "MAP-001" else None)
        )
        wanted = {f"finding{finding['id']}" for finding in data["findings"]}
        proposed["assessments"]["IT_INFRA"] = {
            key: entry
            for key, entry in proposed["assessments"]["IT_INFRA"].items()
            if key in wanted
        }
        return proposed

    provider = V4Provider(source, hook=hook)
    engine = service.ReportInsightService(Settings(_env_file=None, AGENT_MOCK=False), provider)
    if defect is None:
        result = engine.generate(source)
        assert stages(provider) == ["MAP-001", "MAP-001", "REVIEW-001", "REDUCE-001"]
        assert result.meta.input_tokens == 44 and result.meta.output_tokens == 28
        assert {item.finding_id for item in result.insights[0].assessments} == {101, 202}
    else:
        with pytest.raises(AgentError) as caught:
            engine.generate(source)
        assert caught.value.code == "SCHEMA_VIOLATION"
        assert stages(provider) == ["MAP-001", "MAP-001"]
        assert caught.value.details["usage"]["inputTokens"] == 22
        assert caught.value.details["usage"]["outputTokens"] == 14
    initial, repaired = provider.wire_payloads[:2]
    assert set(repaired["assessments"]["IT_INFRA"]) == {"finding101"}
    assert (
        repaired["assessments"]["IT_INFRA"]["finding101"]["decision"]["connection"]
        == (initial["assessments"]["IT_INFRA"]["finding101"]["decision"]["connection"])
    )
    assert all(provider.schema_validity)
