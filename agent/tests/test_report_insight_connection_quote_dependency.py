"""Legacy display quotes must stay supported when editable axis bases disappear."""

import json
from copy import deepcopy
from dataclasses import replace

import pytest
from test_report_insight_assessment import response
from test_report_insight_core_forecast_support import recorded_case
from test_report_insight_preserved_decision_repair import wire_validator

from app.llm import report_insight_service as service
from app.llm.report_insight_assessment import (
    ReportAssessmentDraftValidationError,
    draft_prompt,
    draft_schema,
    draft_to_wire,
    validate_source_draft,
)
from app.llm.report_insight_fact_rendering import build_fact_text_catalog, render_source_prose

SHORT_CONDITION = "생산능력 제약이 생산 일정에 영향을 미치는 경우"
LONG_CONDITION = (
    "메모리 생산능력 제약이 자사의 생산 일정 및 생산능력 배분과 관련된 계획에 "
    "실제 영향을 미치는 경우"
)


def legacy_case(claim_id, *, condition=SHORT_CONDITION):
    source, value = recorded_case()
    slots = {slot.claim_ids[0]: slot.slot_id for slot in build_fact_text_catalog(source).slots}
    value["assessments"]["CHIP_MAKER"]["finding101"].update(
        relation="DIRECT" if condition is None else "CONDITIONAL",
        condition=condition,
        reason="메모리 생산능력 제약은 생산능력·배분 업무와 연결된다.",
        sourceQuotes={"reason": None, "condition": slots[claim_id]},
    )
    return source, value, slots


def legacy_response(source, value):
    # The usual response fixture deliberately removes legacy display selectors.
    # Preserve real non-null wire values here to exercise their source dependency.
    return replace(
        response(value, source),
        text=json.dumps(
            draft_to_wire(value, source, legacy_source_quotes=True), ensure_ascii=False
        ),
    )


def full_legacy_validate(raw, source):
    draft = validate_source_draft(raw, source)
    service._validated_map_output(
        replace(raw, text=draft.mapped.model_dump_json(by_alias=True)),
        source,
        native_assessments=draft.evidence,
    )
    return draft


def remove_editable_bases(value):
    fixed = deepcopy(value)
    fixed["assessments"]["CHIP_MAKER"]["finding101"].update(
        impactScope="UNDETERMINED",
        impactBasis=None,
        urgencyState="UNDETERMINED",
        urgencyBasis=None,
    )
    return fixed


@pytest.mark.parametrize("condition", [SHORT_CONDITION, LONG_CONDITION], ids=["shown", "omitted"])
def test_condition_quote_from_editable_basis_prevents_connection_freeze(condition):
    source, value, slots = legacy_case("101:0", condition=condition)
    raw = legacy_response(source, value)
    before = source.model_dump_json(), raw.text, deepcopy(value)
    rendered = render_source_prose(
        condition,
        slots["101:0"],
        build_fact_text_catalog(source),
        ["101:0", "101:1"],
        max_length=120,
        kind="assumption",
    )
    assert rendered.fact_quote_omitted_for_length == (condition == LONG_CONDITION)

    with pytest.raises(ReportAssessmentDraftValidationError) as original_failure:
        validate_source_draft(raw, source)
    assert set(original_failure.value.native_connection_repairs) == {101}
    assert all(
        issue.rule.startswith("report_axis_") for issue in original_failure.value.validation_issues
    )
    error = service._native_assessment_repair_errors(raw, source)
    assert error is not None
    assert not error.native_connection_repairs
    assert not error.native_prose_repairs
    # The original display selector is valid against the union of selected bases.
    # Its dependency is repair guidance, not an invented original-output failure.
    assert {issue.field for issue in error.validation_issues} == {
        "assessments[101].decision.effect.impactScope",
        "assessments[101].decision.timing.urgencyState",
    }
    fixed = remove_editable_bases(value)
    with pytest.raises(ReportAssessmentDraftValidationError) as caught:
        full_legacy_validate(legacy_response(source, fixed), source)
    assert [(issue.field, issue.rule) for issue in caught.value.validation_issues] == [
        ("assessments[101].decision.connection.condition", "report_fact_slot_scope")
    ]

    repair = object.__new__(service.ReportInsightService)._repair_call(
        draft_prompt(source),
        draft_schema(source),
        raw.text,
        error,
        lambda candidate: full_legacy_validate(candidate, source),
    )
    fixed["assessments"]["CHIP_MAKER"]["finding101"]["sourceQuotes"]["condition"] = None
    candidate = legacy_response(source, fixed)
    wire_validator(repair).validate(json.loads(candidate.text))
    result = repair.validate(candidate)
    item = result.evidence["CHIP_MAKER"][101]
    assert item.condition == condition
    assert item.relation_basis.claim_id == "101:1"
    assert item.impact_basis is None and item.urgency_basis is None
    assert before == (source.model_dump_json(), raw.text, value)


@pytest.mark.parametrize("condition", [SHORT_CONDITION, LONG_CONDITION], ids=["short", "long"])
def test_condition_quote_from_fixed_connection_basis_remains_supported(condition):
    source, value, slots = legacy_case("101:1", condition=condition)
    raw = legacy_response(source, value)
    error = service._native_assessment_repair_errors(raw, source)
    assert error is not None
    assert set(error.native_connection_repairs) == {101}
    context = error.native_connection_repairs[101]
    assert context.snapshot.condition == condition
    assert context.snapshot.source_quotes.condition == slots["101:1"]
    with pytest.raises(ReportAssessmentDraftValidationError) as original_failure:
        validate_source_draft(raw, source)
    assert service._connection_condition_is_supported(
        original_failure.value.native_connection_repairs[101], source, "CHIP_MAKER"
    )

    fixed = remove_editable_bases(value)
    candidate = legacy_response(source, fixed)
    assert (
        json.loads(candidate.text)["assessments"]["CHIP_MAKER"]["finding101"]["sourceQuotes"][
            "condition"
        ]
        == slots["101:1"]
    )
    result = full_legacy_validate(candidate, source)
    item = result.evidence["CHIP_MAKER"][101]
    assert item.condition.endswith(condition)
    assert item.source_quotes.condition == slots["101:1"]
    assert item.relation_basis.claim_id == "101:1"
    assert item.impact_basis is None and item.urgency_basis is None


def test_absent_condition_cannot_freeze_a_legacy_nonnull_condition_quote():
    source, value, _ = legacy_case("101:1", condition=None)
    raw = legacy_response(source, value)
    error = service._native_assessment_repair_errors(raw, source)
    assert error is not None
    assert not error.native_connection_repairs

    fixed = remove_editable_bases(value)
    with pytest.raises(ReportAssessmentDraftValidationError) as caught:
        full_legacy_validate(legacy_response(source, fixed), source)
    assert [(issue.field, issue.rule) for issue in caught.value.validation_issues] == [
        ("assessments[101].decision.connection.condition", "report_fact_slot_without_prose")
    ]
    fixed["assessments"]["CHIP_MAKER"]["finding101"]["sourceQuotes"]["condition"] = None
    result = full_legacy_validate(legacy_response(source, fixed), source)
    assert result.evidence["CHIP_MAKER"][101].condition is None
