"""Synthetic repair cases: a frozen condition must retain its evidence."""

import json
from copy import deepcopy
from dataclasses import replace

import pytest
from test_report_insight_assessment import framed, payload, request, response
from test_report_insight_axis_support_repair import case
from test_report_insight_preserved_decision_repair import repair_payload, wire_validator
from test_report_insight_v4_pipeline import V4Provider, generate, stages

from app.core.errors import AgentError, OutputValidationError
from app.llm import report_insight_service as service
from app.llm.report_insight_assessment import draft_prompt, draft_schema, draft_to_wire
from app.llm.report_insight_fact_rendering import build_fact_text_catalog

GOOD_REASON = "메모리 가격 전망은 시스템 조달과 연결된다."
GOOD_CONDITION = "메모리 가격 전망이 시스템 조달에 적용되는 경우"


def dependency_case(kind="company", *, mixed=False, neighbor=False):
    # These are invented source sentences, not a replay of a user's report.
    source, value = case("forecast")
    finding = source.findings[0]
    text, condition = {
        "company": (
            "삼성전자의 메모리 가격은 내년에 상승할 것으로 전망된다.",
            "삼성전자의 메모리 가격 전망이 시스템 조달에 적용되는 경우",
        ),
        "number": (
            "메모리 가격은 내년에 30% 상승할 것으로 전망된다.",
            "메모리 가격의 30% 상승 전망이 시스템 조달에 적용되는 경우",
        ),
    }[kind]
    finding.claims.append(
        finding.claims[0].model_copy(
            update={"id": "101:1", "text": text, "evidence_sentence_ids": [1]}
        )
    )
    finding.sentences.append(finding.sentences[0].model_copy(update={"index": 1, "text": text}))
    value["assessments"]["IT_INFRA"]["finding101"].update(
        relation="CONDITIONAL",
        condition=condition,
        reason="9999년의 메모리 가격 전망은 시스템 조달과 연결된다." if mixed else GOOD_REASON,
        impactBasis={"claimId": "101:1", "quote": text},
        urgencyBasis={"claimId": "101:1", "quote": text},
    )
    if neighbor:
        other = request(
            ids=(202,),
            audiences=("IT_INFRA",),
            text="회사는 서버 도입 프로젝트의 자원 배분과 준비 일정을 변경했다.",
        )
        entries = payload(other)["assessments"]["IT_INFRA"]
        entries["finding202"].update(
            impactScope="PROJECT_CHANGE",
            urgencyState="SCHEDULED_PREPARATION",
            reason="서버 도입 자원과 준비 일정 변경은 시스템 조달 업무에 직접 영향을 준다.",
        )
        source.findings.extend(other.findings)
        value["assessments"]["IT_INFRA"].update(entries)
    return source, value


def corrected(value):
    fixed = deepcopy(value)
    fixed["assessments"]["IT_INFRA"]["finding101"].update(
        impactScope="UNDETERMINED",
        impactBasis=None,
        urgencyState="UNDETERMINED",
        urgencyBasis=None,
        condition=GOOD_CONDITION,
        reason=GOOD_REASON,
    )
    return fixed


def full_validate(raw, source, *, current=True):
    draft = (service.validate_draft if current else service.validate_legacy_draft)(raw, source)
    service._validated_map_output(
        replace(raw, text=draft.mapped.model_dump_json(by_alias=True)),
        source,
        native_assessments=draft.evidence,
    )
    return draft


def build_repair(source, value, *, current=True, raw=None):
    raw = raw or response(value, source)
    error = service._native_assessment_repair_errors(raw, source, template_wire=current)
    assert error is not None
    schema = draft_schema(source)
    before = source.model_dump_json(), raw.text, deepcopy(schema)
    repair = object.__new__(service.ReportInsightService)._repair_call(
        draft_prompt(source),
        schema,
        raw.text,
        error,
        lambda result: full_validate(result, source, current=current),
    )
    assert before == (source.model_dump_json(), raw.text, schema)
    return repair, error, raw


@pytest.mark.parametrize("current", [False, True], ids=["legacy", "current"])
@pytest.mark.parametrize("mixed", [False, True], ids=["axis_only", "axis_and_reason"])
@pytest.mark.parametrize("kind", ["company", "number"])
def test_condition_depending_on_editable_basis_gets_full_record_repair(current, mixed, kind):
    source, value = dependency_case(kind, mixed=mixed)
    before = source.model_dump_json(), deepcopy(value)
    repair, error, raw = build_repair(source, value, current=current)
    # The original condition is valid under the original basis union. Dependency
    # feedback must not invent a new condition validation error.
    assert {issue.field for issue in error.validation_issues} == {
        "assessments[101].decision.effect.impactScope",
        "assessments[101].decision.timing.urgencyState",
        *(["assessments[101].reason"] if mixed else []),
    }
    assert not error.native_connection_repairs
    assert not error.native_prose_repairs
    assert "connection을 고정하지 않습니다." in repair.prompt
    assert "condition과 표시용 인용도 함께 재검토하세요." in repair.prompt
    assert framed(repair.prompt) == framed(draft_prompt(source))
    fixed = repair_payload(repair, source, corrected(value))
    wire_validator(repair).validate(fixed)
    accepted = repair.validate(replace(raw, text=json.dumps(fixed, ensure_ascii=False)))
    item = accepted.evidence["IT_INFRA"][101]
    assert item.relation == "CONDITIONAL" and item.work == "SYSTEM_PROCUREMENT"
    assert item.relation_basis.claim_id == "101:0"
    assert item.condition == GOOD_CONDITION and item.reason == GOOD_REASON
    assert item.impact_scope == item.urgency_state == "UNDETERMINED"
    assert item.impact_basis is item.urgency_basis is None
    assert before == (source.model_dump_json(), value)


def test_pre_fix_freeze_rejected_a_candidate_that_passed_full_validation(monkeypatch):
    source, value = dependency_case(mixed=True)
    fixed = response(corrected(value), source)
    full_validate(fixed, source)
    # Disable only the new eligibility gate to reproduce the old contradiction.
    monkeypatch.setattr(service, "_connection_condition_is_supported", lambda *args: True)
    repair, error, _ = build_repair(source, value)
    assert set(error.native_connection_repairs) == {101}
    assert not wire_validator(repair).is_valid(json.loads(fixed.text))
    with pytest.raises(OutputValidationError, match="connection을 변경할 수 없습니다"):
        repair.validate(fixed)


@pytest.mark.parametrize("defect", ["old_company", "old_number", "new_company", "new_number"])
def test_fallback_keeps_condition_fact_validation_after_axes_are_repaired(defect):
    kind = "number" if defect.endswith("number") else "company"
    source, value = dependency_case(kind)
    repair, _, raw = build_repair(source, value)
    fixed = corrected(value)
    item = fixed["assessments"]["IT_INFRA"]["finding101"]
    item["condition"] = (
        value["assessments"]["IT_INFRA"]["finding101"]["condition"]
        if defect.startswith("old")
        else "TSMC의 메모리 가격 전망이 시스템 조달에 적용되는 경우"
        if kind == "company"
        else "메모리 가격의 999% 상승 전망이 시스템 조달에 적용되는 경우"
    )
    wire = repair_payload(repair, source, fixed)
    wire_validator(repair).validate(wire)
    with pytest.raises(service.ReportAssessmentValidationError) as caught:
        repair.validate(replace(raw, text=json.dumps(wire, ensure_ascii=False)))
    assert {issue.field for issue in caught.value.validation_issues} == {
        "assessments[101].decision.connection.condition"
    }
    assert {issue.rule for issue in caught.value.validation_issues} == {
        "company" if kind == "company" else "unsupported_number"
    }


@pytest.mark.parametrize("support", ["direct", "generic", "linked_sentence"])
def test_connection_stays_frozen_when_its_own_claim_supports_the_condition(support):
    source, value = dependency_case(mixed=True)
    entry = value["assessments"]["IT_INFRA"]["finding101"]
    if support == "direct":
        entry.update(relation="DIRECT", condition=None)
    elif support == "generic":
        entry["condition"] = GOOD_CONDITION
    else:
        # A selected span is not the whole factual scope: all original sentences
        # linked to the fixed claim remain allowed, including sentence 1.
        source.findings[0].claims[0].evidence_sentence_ids.append(1)
    repair, error, raw = build_repair(source, value)
    assert set(error.native_connection_repairs) == {101}
    fixed = corrected(value)
    fixed["assessments"]["IT_INFRA"]["finding101"]["condition"] = entry["condition"]
    wire = repair_payload(repair, source, fixed)
    wire_validator(repair).validate(wire)
    accepted = repair.validate(replace(raw, text=json.dumps(wire, ensure_ascii=False)))
    assert accepted.evidence["IT_INFRA"][101].condition == entry["condition"]
    changed = deepcopy(wire)
    changed["assessments"]["IT_INFRA"]["finding101"]["decision"]["connection"]["work"] = (
        "DEPLOYMENT_OPERATIONS"
    )
    assert not wire_validator(repair).is_valid(changed)
    with pytest.raises(OutputValidationError, match="connection을 변경할 수 없습니다"):
        repair.validate(replace(raw, text=json.dumps(changed, ensure_ascii=False)))


@pytest.mark.parametrize("quote_claim", ["101:0", "101:1"])
def test_legacy_condition_quote_also_requires_fixed_connection_evidence(quote_claim):
    source, value = dependency_case()
    value["assessments"]["IT_INFRA"]["finding101"]["condition"] = GOOD_CONDITION
    raw = response(value, source)
    wire = json.loads(raw.text)
    slot = next(
        slot for slot in build_fact_text_catalog(source).slots if quote_claim in slot.claim_ids
    )
    wire["assessments"]["IT_INFRA"]["finding101"]["sourceQuotes"]["condition"] = slot.slot_id
    legacy = replace(raw, text=json.dumps(wire, ensure_ascii=False))
    error = service._native_assessment_repair_errors(legacy, source, template_wire=False)
    assert error is not None
    assert bool(error.native_connection_repairs) == (quote_claim == "101:0")
    assert {issue.field for issue in error.validation_issues} == {
        "assessments[101].decision.effect.impactScope",
        "assessments[101].decision.timing.urgencyState",
    }


def test_truncated_prefix_reopens_dependent_condition_and_preserves_completed_neighbor():
    source, value = dependency_case(mixed=True, neighbor=True)
    third = source.findings[1].model_copy(deep=True)
    third.id = 303
    third.claims[0].id = "303:0"
    source.findings.append(third)
    entry = deepcopy(value["assessments"]["IT_INFRA"]["finding202"])
    entry["findingId"] = 303
    for field in ("relationBasis", "impactBasis", "urgencyBasis"):
        entry[field]["claimId"] = "303:0"
    value["assessments"]["IT_INFRA"]["finding303"] = entry
    raw = response(value, source)
    records = json.loads(raw.text)["assessments"]["IT_INFRA"]
    prefix = json.dumps(
        {"assessments": {"IT_INFRA": dict(list(records.items())[:2])}}, ensure_ascii=False
    )[:-3]
    raw = replace(raw, text=prefix, truncated=True)
    before = source.model_dump_json(), raw.text
    error = service._truncated_assessment_repair_error(raw, source, template_wire=True)
    assert error is not None and error.failed_finding_ids == (101, 303)
    assert not error.native_connection_repairs
    assert error.preserved_wire["assessments"]["IT_INFRA"] == {"finding202": records["finding202"]}
    validated = []

    def validate(candidate):
        validated.append(json.loads(candidate.text))
        return full_validate(candidate, source)

    repair = object.__new__(service.ReportInsightService)._repair_call(
        draft_prompt(source), draft_schema(source), raw.text, error, validate
    )
    assert "connection을 고정하지 않습니다." in repair.prompt
    assert "condition과 표시용 인용도 함께 재검토하세요." in repair.prompt
    wire = repair_payload(repair, source, corrected(value))
    assert set(wire["assessments"]["IT_INFRA"]) == {"finding101", "finding303"}
    wire_validator(repair).validate(wire)
    accepted = repair.validate(
        replace(raw, text=json.dumps(wire, ensure_ascii=False), truncated=False)
    )
    assert accepted.evidence["IT_INFRA"][101].condition == GOOD_CONDITION
    assert len(validated) == 1
    assert (
        validated[0]["assessments"]["IT_INFRA"]
        == draft_to_wire(corrected(value), source)["assessments"]["IT_INFRA"]
    )
    assert validated[0]["assessments"]["IT_INFRA"]["finding202"] == records["finding202"]
    assert before == (source.model_dump_json(), raw.text)


@pytest.mark.parametrize("retained", [False, True], ids=["corrected", "unsupported_condition"])
def test_map_and_review_dependency_repairs_keep_neighbor_and_one_retry_budget(
    retained, monkeypatch
):
    source, value = dependency_case(mixed=True, neighbor=True)
    before = source.model_dump_json(), deepcopy(value)
    validated = []
    original_validate = service.validate_draft

    def capture(raw, validation_request):
        if len(validation_request.findings) == 2:
            validated.append(json.loads(raw.text))
        return original_validate(raw, validation_request)

    monkeypatch.setattr(service, "validate_draft", capture)

    def hook(stage, occurrence, data, generated):
        if stage.startswith("EXPLAIN"):
            for record in generated["assessments"]["IT_INFRA"].values():
                record.update(reason=GOOD_REASON, condition=GOOD_CONDITION)
            return generated
        if stage.startswith("REDUCE"):
            for insight in generated["insights"]:
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
            return generated
        proposed = deepcopy(value if occurrence == 1 else corrected(value))
        if retained and occurrence > 1:
            proposed["assessments"]["IT_INFRA"]["finding101"]["condition"] = value["assessments"][
                "IT_INFRA"
            ]["finding101"]["condition"]
        wanted = {f"finding{finding['id']}" for finding in data["findings"]}
        proposed["assessments"]["IT_INFRA"] = {
            key: item for key, item in proposed["assessments"]["IT_INFRA"].items() if key in wanted
        }
        return proposed

    provider = V4Provider(source, hook=hook)
    if retained:
        with pytest.raises(AgentError) as caught:
            generate(provider, source)
        assert caught.value.code == "SCHEMA_VIOLATION"
        assert caught.value.details["usage"]["inputTokens"] == 44
        assert stages(provider) == ["MAP-001", "MAP-001", "REVIEW-001", "REVIEW-001"]
        assert caught.value.details["validationFailure"]["stage"] == "REVIEW-001"
    else:
        result = generate(provider, source)
        assert stages(provider) == [
            "MAP-001",
            "MAP-001",
            "REVIEW-001",
            "REVIEW-001",
            "EXPLAIN-001",
            "REDUCE-001",
        ]
        assert result.meta.input_tokens == 66 and result.meta.output_tokens == 42
        assert result.meta.cost_usd == 0.018 and result.meta.credits == 1.2
        assert [item.finding_id for item in result.insights[0].assessments] == [101, 202]
        first = result.insights[0].assessments[0]
        assert first.axes.directness == 2 and first.axes.impact is first.axes.urgency is None
        assert first.basis_claim_ids == ["101:0"]
        expected = draft_to_wire(corrected(value), source)["assessments"]["IT_INFRA"]
        assert any(batch["assessments"]["IT_INFRA"] == expected for batch in validated)
        for index in (1, 3):
            repair_input = framed(provider.calls[index]["prompt"])
            assert "connection을 고정하지 않습니다." in provider.calls[index]["prompt"]
            assert [finding["id"] for finding in repair_input["findings"]] == [101]
            assert "proseRepair" not in repair_input["findings"][0]
            assert provider.wire_payloads[index]["assessments"]["IT_INFRA"] == {
                "finding101": expected["finding101"]
            }
        assert (
            expected["finding202"]
            == provider.wire_payloads[0]["assessments"]["IT_INFRA"]["finding202"]
        )
    assert all(provider.schema_validity)
    assert before == (source.model_dump_json(), value)
