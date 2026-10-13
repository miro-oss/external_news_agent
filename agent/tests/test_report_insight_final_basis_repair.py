"""A retry must ground its prose in the bases that survive the final decision."""

import json
from copy import deepcopy
from dataclasses import replace

import pytest
from report_insight_schema_assertions import assert_same_native_constraints
from test_report_insight_assessment import framed, response
from test_report_insight_connection_evidence_dependency import full_validate
from test_report_insight_mixed_connection_repair import mixed_case
from test_report_insight_preserved_decision_repair import repair_payload, wire_validator
from test_report_insight_v4_pipeline import V4Provider, stages

from app.core.config import Settings
from app.core.errors import AgentError, OutputValidationError
from app.llm import report_insight_service as service
from app.llm.report_insight_assessment import draft_prompt, draft_schema, review_prompt
from app.llm.report_insight_instructions import (
    ASSESSMENT_FINAL_BASIS_RULE,
    ASSESSMENT_REASON_RULE,
)

AUDIENCE = "IT_INFRA"
CONNECTION_SOURCE = "HBM 메모리 가격은 내년에 상승할 것으로 전망된다."
FORECAST_SOURCE = "HBM4 메모리 가격은 내년에 상승할 것으로 전망된다."
PROJECT_SOURCE = "회사는 HBM4 서버 도입 프로젝트의 자원 배분과 준비 일정을 변경했다."
ORIGINAL_REASON = "HBM4 가격 전망은 시스템 조달과 연결된다."
FINAL_REASON = "HBM 가격 전망은 시스템 조달과 연결된다."
CONDITION = "HBM 가격 전망이 시스템 조달에 적용되는 경우"


def final_basis_case(relation="DIRECT", *, known_timing=True):
    # Invented evidence preserves the dependency shape without copying a report.
    source, value = mixed_case(relation, neighbor=True)
    finding = source.findings[0]
    finding.claims[0].text = CONNECTION_SOURCE
    finding.sentences[0].text = CONNECTION_SOURCE
    finding.claims[1].text = FORECAST_SOURCE
    finding.claims[1].evidence_sentence_ids = [1]
    finding.sentences.append(
        finding.sentences[0].model_copy(update={"index": 1, "text": FORECAST_SOURCE})
    )
    finding.claims.append(
        finding.claims[0].model_copy(
            update={"id": "101:2", "text": PROJECT_SOURCE, "evidence_sentence_ids": [2]}
        )
    )
    finding.sentences.append(
        finding.sentences[0].model_copy(update={"index": 2, "text": PROJECT_SOURCE})
    )
    entry = value["assessments"][AUDIENCE]["finding101"]
    entry.update(
        relationBasis={"claimId": "101:0", "quote": CONNECTION_SOURCE},
        impactBasis={"claimId": "101:1", "quote": FORECAST_SOURCE},
        urgencyBasis={"claimId": "101:1", "quote": FORECAST_SOURCE} if known_timing else None,
        urgencyState="SCHEDULED_PREPARATION" if known_timing else "UNDETERMINED",
        reason=ORIGINAL_REASON,
        condition=CONDITION if relation == "CONDITIONAL" else None,
    )
    return source, value


def cleared_bases(value, *, stale_reason=False):
    fixed = deepcopy(value)
    fixed["assessments"][AUDIENCE]["finding101"].update(
        impactScope="UNDETERMINED",
        impactBasis=None,
        urgencyState="UNDETERMINED",
        urgencyBasis=None,
        reason=ORIGINAL_REASON if stale_reason else FINAL_REASON,
    )
    return fixed


def selected_basis(value, mode):
    fixed = cleared_bases(value)
    entry = fixed["assessments"][AUDIENCE]["finding101"]
    project = {"claimId": "101:2", "quote": PROJECT_SOURCE}
    if mode == "replace_effect":
        entry.update(impactScope="PROJECT_CHANGE", impactBasis=project)
    elif mode == "add_timing":
        entry.update(urgencyState="SCHEDULED_PREPARATION", urgencyBasis=project)
    else:
        assert mode == "retain_monitor"
        entry.update(
            urgencyState="MONITOR",
            urgencyBasis={"claimId": "101:1", "quote": FORECAST_SOURCE},
            reason=ORIGINAL_REASON,
        )
        return fixed
    entry["reason"] = "HBM 가격 전망과 HBM4 서버 도입 자원·일정 변경은 시스템 조달과 연결된다."
    return fixed


def build_repair(source, value, prompt):
    raw = response(value, source)
    error = service._native_assessment_repair_errors(raw, source, template_wire=True)
    assert error is not None
    assert set(error.native_connection_repairs) == {101}
    assert not error.native_prose_repairs
    assert all(issue.field != "assessments[101].reason" for issue in error.validation_issues)
    validated = []

    def validate(candidate):
        validated.append(json.loads(candidate.text))
        return full_validate(candidate, source)

    repair = object.__new__(service.ReportInsightService)._repair_call(
        prompt(source), draft_schema(source), raw.text, error, validate
    )
    return repair, raw, error, validated


@pytest.mark.parametrize("prompt", [draft_prompt, review_prompt], ids=["MAP", "REVIEW"])
@pytest.mark.parametrize("relation", ["DIRECT", "CONDITIONAL"])
def test_initially_grounded_reason_must_change_when_its_only_selected_basis_is_removed(
    prompt, relation
):
    source, value = final_basis_case(relation)
    before = source.model_dump_json(), deepcopy(value)
    repair, raw, error, validated = build_repair(source, value, prompt)
    assert {issue.rule for issue in error.validation_issues} == {
        "report_axis_market_forecast_only_project_change",
        "report_axis_market_forecast_only_scheduled_preparation",
    }
    # The old reason is supported before the erroneous categories are fixed.
    supported = selected_basis(value, "retain_monitor")
    assert full_validate(response(supported, source), source).evidence[AUDIENCE][101].reason == (
        ORIGINAL_REASON
    )
    stale = repair_payload(repair, source, cleared_bases(value, stale_reason=True))
    wire_validator(repair).validate(stale)
    with pytest.raises(service.ReportAssessmentValidationError) as caught:
        repair.validate(replace(raw, text=json.dumps(stale, ensure_ascii=False)))
    assert {
        (issue.field, issue.rule, issue.claim_ids) for issue in caught.value.validation_issues
    } == {("assessments[101].reason", "source_binding", ("101:0",))}
    # Available but unselected HBM4 claims do not legalize the stale prose.
    assert [claim["id"] for claim in framed(repair.prompt)["findings"][0]["claims"]] == [
        "101:0",
        "101:1",
        "101:2",
    ]
    fixed = repair_payload(repair, source, cleared_bases(value))
    wire_validator(repair).validate(fixed)
    accepted = repair.validate(replace(raw, text=json.dumps(fixed, ensure_ascii=False)))
    item = accepted.evidence[AUDIENCE][101]
    assert item.reason == FINAL_REASON
    assert item.impact_basis is item.urgency_basis is None
    original = json.loads(raw.text)["assessments"][AUDIENCE]
    assert (
        fixed["assessments"][AUDIENCE]["finding101"]["decision"]["connection"]
        == (original["finding101"]["decision"]["connection"])
    )
    assert set(fixed["assessments"][AUDIENCE]) == {"finding101"}
    assert validated[-1]["assessments"][AUDIENCE]["finding202"] == original["finding202"]
    assert before == (source.model_dump_json(), value)


@pytest.mark.parametrize("prompt", [draft_prompt, review_prompt], ids=["MAP", "REVIEW"])
@pytest.mark.parametrize("mode", ["replace_effect", "add_timing", "retain_monitor"])
def test_repair_can_select_new_supported_bases_or_keep_a_valid_observation_basis(prompt, mode):
    source, value = final_basis_case(known_timing=mode != "add_timing")
    repair, raw, _, _ = build_repair(source, value, prompt)
    fixed = repair_payload(repair, source, selected_basis(value, mode))
    wire_validator(repair).validate(fixed)
    accepted = repair.validate(replace(raw, text=json.dumps(fixed, ensure_ascii=False)))
    item = accepted.evidence[AUDIENCE][101]
    assert "HBM4" in item.reason
    selected = item.impact_basis if mode == "replace_effect" else item.urgency_basis
    assert selected.claim_id == ("101:1" if mode == "retain_monitor" else "101:2")
    assert item.relation_basis.claim_id == "101:0"
    assert (
        accepted.evidence[AUDIENCE][202].reason
        == (value["assessments"][AUDIENCE]["finding202"]["reason"])
    )


@pytest.mark.parametrize("prompt", [draft_prompt, review_prompt], ids=["MAP", "REVIEW"])
def test_final_basis_instruction_does_not_authorize_rewriting_a_protected_condition(prompt):
    source, value = final_basis_case("CONDITIONAL")
    repair, raw, _, validated = build_repair(source, value, prompt)
    changed = cleared_bases(value)
    changed["assessments"][AUDIENCE]["finding101"]["condition"] = (
        "HBM 가격 전망이 실제 시스템 조달에 적용되는 경우"
    )
    full_validate(response(changed, source), source)
    wire = repair_payload(repair, source, changed)
    assert not wire_validator(repair).is_valid(wire)
    with pytest.raises(OutputValidationError, match="connection을 변경할 수 없습니다"):
        repair.validate(replace(raw, text=json.dumps(wire, ensure_ascii=False)))
    assert not validated


def test_reason_cannot_keep_its_basis_by_retaining_the_unsupported_axis_category():
    source, value = final_basis_case()
    repair, raw, _, _ = build_repair(source, value, draft_prompt)
    invalid = repair_payload(repair, source, value)
    wire_validator(repair).validate(invalid)
    with pytest.raises(ValueError):
        repair.validate(replace(raw, text=json.dumps(invalid, ensure_ascii=False)))


@pytest.mark.parametrize("prompt", [draft_prompt, review_prompt], ids=["MAP", "REVIEW"])
def test_final_basis_check_follows_repair_data_without_changing_sources_or_const_scope(prompt):
    source, value = final_basis_case("CONDITIONAL")
    repair, _, _, _ = build_repair(source, value, prompt)
    tail = repair.prompt.split("</validation-error>", 1)[1]
    assert tail.index(ASSESSMENT_FINAL_BASIS_RULE) < tail.index(ASSESSMENT_REASON_RULE)
    full_finding = framed(prompt(source))["findings"][0]
    assert framed(repair.prompt)["findings"] == [full_finding]


@pytest.mark.parametrize("kind", ["native_fallback", "legacy", "reduce"])
def test_final_basis_tail_is_only_applied_to_native_map_review_repairs(kind):
    source, value = final_basis_case()
    native = full_validate(response(cleared_bases(value), source), source)
    if kind == "native_fallback":
        engine = object.__new__(service.ReportInsightService)
        prompt, schema = draft_prompt(source), draft_schema(source)
    elif kind == "legacy":
        engine = object.__new__(service.ReportInsightLegacyService)
        prompt, schema = (
            service._report_insight_prompt(source),
            service.report_insight_map_schema(source),
        )
    else:
        engine = object.__new__(service.ReportInsightService)
        retrieved = {
            item.audience: service.retrieve_report_insight_evidence(
                source, item.audience, item.assessments, preserve_assessment_bases=True
            )
            for item in native.mapped.insights
        }
        allowed = {audience: items.claim_ids for audience, items in retrieved.items()}
        prompt = service._reduce_v4_prompt(source, native, retrieved, allowed)
        schema = service.report_insight_reduce_schema(source, allowed, structured=True)
    repair = engine._repair_call(prompt, schema, "{", ValueError("incomplete"), lambda raw: raw)
    tail = repair.prompt.split("</validation-error>", 1)[1]
    assert (ASSESSMENT_FINAL_BASIS_RULE in tail) == (kind == "native_fallback")
    if kind == "native_fallback":
        # Required-field order follows the retry's decision-first serialization;
        # all actual schema constraints must still equal the initial contract.
        assert_same_native_constraints(repair.response_schema, schema)
    else:
        assert repair.response_schema == schema


@pytest.mark.parametrize("stage", ["MAP-001", "REVIEW-001"])
@pytest.mark.parametrize("stale_reason", [False, True])
def test_pipeline_revalidates_final_bases_with_one_repair_and_original_usage_budget(
    stage, stale_reason
):
    source, value = final_basis_case()
    snapshot = source.model_dump_json()

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
        proposed = deepcopy(
            value
            if current == stage and occurrence == 1
            else cleared_bases(value, stale_reason=stale_reason)
            if current == stage
            else selected_basis(value, "retain_monitor")
            if stage == "REVIEW-001"
            else cleared_bases(value)
        )
        wanted = {f"finding{finding['id']}" for finding in data["findings"]}
        proposed["assessments"][AUDIENCE] = {
            key: entry for key, entry in proposed["assessments"][AUDIENCE].items() if key in wanted
        }
        return proposed

    provider = V4Provider(source, hook=hook)
    engine = service.ReportInsightService(Settings(_env_file=None, AGENT_MOCK=False), provider)
    if stage == "MAP-001" and stale_reason:
        with pytest.raises(AgentError) as caught:
            engine.generate(source)
        assert caught.value.code == "SCHEMA_VIOLATION"
        assert stages(provider) == [stage, stage]
        assert caught.value.details["usage"]["inputTokens"] == 22
        assert caught.value.details["usage"]["outputTokens"] == 14
    else:
        result = engine.generate(source)
        expected = ["MAP-001", "REVIEW-001", "REDUCE-001"]
        expected.insert(expected.index(stage), stage)
        assert stages(provider) == expected
        assert result.meta.input_tokens == 44 and result.meta.output_tokens == 28
        assessment = next(item for item in result.insights[0].assessments if item.finding_id == 101)
        if stale_reason:
            # Optional REVIEW failure keeps a valid MAP whose selected timing
            # still supports HBM4; it never publishes the rejected revision.
            assert "HBM4" in assessment.reason
            assert "101:1" in assessment.basis_claim_ids
        else:
            assert "HBM4" not in assessment.reason
            assert assessment.basis_claim_ids == ["101:0"]
    repair_index = stages(provider).index(stage) + 1
    repaired = provider.wire_payloads[repair_index]["assessments"][AUDIENCE]
    assert set(repaired) == {"finding101"}
    assert all(provider.schema_validity)
    assert source.model_dump_json() == snapshot
