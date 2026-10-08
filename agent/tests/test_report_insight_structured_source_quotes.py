"""Private source references are independent of prose and never escape publicly."""

from copy import deepcopy

import pytest
from jsonschema import Draft202012Validator
from pydantic import ValidationError
from test_report_insight_assessment import payload, request
from test_report_insight_fact_rendering import reduce_wire, response

from app.llm.report_insight_assessment import (
    ReportAssessmentDraftValidationError,
    draft_schema,
    draft_to_wire,
    parse_wire_draft,
    validate_draft,
    validate_source_draft,
)
from app.llm.report_insight_fact_rendering import (
    FactTemplateError,
    build_fact_text_catalog,
    render_fact_template,
    render_reduce_source_quotes,
    render_source_prose,
)
from app.llm.report_insight_reduce_shape_scan import build_reduce_shape_scan
from app.llm.report_validation_diagnostics import ReportValidationIssue
from app.schemas.report_insight import ReportInsightReduceOutput
from app.schemas.report_insight_source_quotes import ReportInsightStructuredReduceOutput


def structured_reduce():
    value = reduce_wire()
    record = value["insights"][0]
    record["sourceQuotes"] = {"headline": None}
    for group, fields in (
        ("overview", ("text", "assumption")),
        ("implications", ("text", "mechanism", "assumption", "falsifiedBy")),
        ("watchItems", ("topic", "indicator", "trigger")),
    ):
        for unit in record[group]:
            unit["sourceQuotes"] = dict.fromkeys(fields)
    return value


def test_new_map_requires_structured_selection_while_explicit_legacy_reader_keeps_old_wire():
    source = request()
    wire = draft_to_wire(payload(source), source)
    del wire["assessments"]["CHIP_MAKER"]["finding101"]["sourceQuotes"]
    raw = response(wire)
    assert validate_draft(raw, source).mapped.insights[0].assessments[0].finding_id == 101
    with pytest.raises(ValidationError):
        validate_source_draft(raw, source)
    assert not Draft202012Validator(draft_schema(source)).is_valid(wire)


def test_map_source_choice_and_prose_survive_authenticated_flattening_without_public_metadata():
    source = request()
    wire = draft_to_wire(payload(source), source)
    record = wire["assessments"]["CHIP_MAKER"]["finding101"]
    slot = build_fact_text_catalog(source).slots[0]
    record["sourceQuotes"]["reason"] = slot.slot_id
    original = deepcopy(wire)
    Draft202012Validator(draft_schema(source)).validate(wire)
    validated = validate_source_draft(response(wire), source)
    assert (
        validated.draft.assessments["CHIP_MAKER"]["finding101"].source_quotes.reason == slot.slot_id
    )
    assert validated.mapped.insights[0].assessments[0].reason == (
        f"원문: 「{slot.source_text}」 해석: {record['reason']}"
    )
    assert "sourceQuotes" not in validated.mapped.model_dump_json()
    assert wire == original
    exported = draft_to_wire(validated)
    assert exported == wire
    assert validate_source_draft(response(exported), source).mapped == validated.mapped
    # An arbitrary flat dict is not authenticated just because it has a quote label.
    untrusted = validated.draft.model_dump(by_alias=True)
    assert draft_to_wire(untrusted, source)["assessments"]["CHIP_MAKER"]["finding101"][
        "reason"
    ] == (validated.draft.assessments["CHIP_MAKER"]["finding101"].reason)


@pytest.mark.parametrize("field", ["reason", "condition"])
def test_new_map_rejects_legacy_template_grammar_even_with_valid_separate_metadata(field):
    source = request()
    wire = draft_to_wire(payload(source, relation="CONDITIONAL"), source)
    record = wire["assessments"]["CHIP_MAKER"]["finding101"]
    target = record if field == "reason" else record["decision"]["connection"]
    slot = build_fact_text_catalog(source).slots[0]
    target[field] = "{{fact:" + slot.slot_id + "}} " + target[field]
    with pytest.raises(ReportAssessmentDraftValidationError) as caught:
        validate_source_draft(response(wire), source)
    selected = [
        issue
        for issue in caught.value.validation_issues
        if issue.rule_id == "report_fact_slot_position"
    ]
    assert selected
    assert all(
        issue.error_kind == "report_expression_policy" and issue.category == "EXPRESSION_POLICY"
        for issue in selected
    )


def test_null_condition_cannot_carry_an_unchecked_source_selection():
    source = request()
    wire = draft_to_wire(payload(source), source)
    wire["assessments"]["CHIP_MAKER"]["finding101"]["sourceQuotes"]["condition"] = (
        build_fact_text_catalog(source).slots[0].slot_id
    )
    with pytest.raises(ReportAssessmentDraftValidationError) as caught:
        validate_source_draft(response(wire), source)
    assert caught.value.validation_issues[0].rule_id == "report_fact_slot_without_prose"
    assert caught.value.validation_issues[0].error_kind == "report_expression_policy"


@pytest.mark.parametrize("source_id", ["unknown", "foreign"])
def test_structured_source_selection_checks_identity_and_scope_before_length_fallback(source_id):
    source = request(ids=(101, 102), text="생산 준비 상황을 확인한다. " * 15)
    catalog = build_fact_text_catalog(source)
    chosen = (
        "source-" + "0" * 24
        if source_id == "unknown"
        else next(slot.slot_id for slot in catalog.slots if slot.finding_id == 102)
    )
    with pytest.raises(
        FactTemplateError,
        match="report_fact_slot_" + ("unknown" if source_id == "unknown" else "scope"),
    ):
        render_source_prose(
            "검증 준비 영향을 확인한다.", chosen, catalog, ["101:0"], max_length=180
        )


def test_display_omission_preserves_selection_and_plain_prose_length_is_independent_of_handle():
    source = request(text="제조사는 생산 준비 상황을 확인한다고 밝혔다. " * 15)
    catalog = build_fact_text_catalog(source)
    slot = catalog.slots[0]
    prose = "검증 준비 영향을 확인한다."
    rendered = render_source_prose(prose, slot.slot_id, catalog, ["101:0"], max_length=len(prose))
    assert rendered.text == prose
    assert rendered.slot == slot
    assert rendered.fact_quote_omitted_for_length
    with pytest.raises(FactTemplateError, match="report_fact_rendered_length"):
        render_source_prose(
            prose + " 추가", slot.slot_id, catalog, ["101:0"], max_length=len(prose)
        )


def test_legacy_marker_is_available_only_through_explicit_legacy_renderer():
    source = request()
    catalog = build_fact_text_catalog(source)
    slot = catalog.slots[0]
    value = "{{fact:" + slot.slot_id + "}} 공정 준비 영향을 확인한다."
    assert render_fact_template(value, catalog, ["101:0"], max_length=180).slot == slot
    with pytest.raises(FactTemplateError, match="report_fact_slot_position"):
        render_source_prose(value, None, catalog, ["101:0"], max_length=180)


@pytest.mark.parametrize(
    ("group", "field"),
    [
        (None, "headline"),
        ("overview", "text"),
        ("overview", "assumption"),
        ("implications", "text"),
        ("implications", "mechanism"),
        ("implications", "assumption"),
        ("implications", "falsifiedBy"),
        ("watchItems", "topic"),
        ("watchItems", "indicator"),
        ("watchItems", "trigger"),
    ],
)
def test_every_reduce_field_selects_source_separately_and_projects_unchanged_public_shape(
    group, field
):
    source = request()
    slot = build_fact_text_catalog(source).slots[0]
    wire = structured_reduce()
    record = wire["insights"][0] if group is None else wire["insights"][0][group][0]
    record["sourceQuotes"][field] = slot.slot_id
    original = deepcopy(wire)
    parsed = ReportInsightStructuredReduceOutput.model_validate(wire)
    value, issues = render_reduce_source_quotes(
        parsed.model_dump(by_alias=True), source, {"CHIP_MAKER": ["101:0"]}
    )
    assert not issues
    public = ReportInsightReduceOutput.model_validate(value)
    rendered = value["insights"][0] if group is None else value["insights"][0][group][0]
    assert rendered[field].startswith(f"원문: 「{slot.source_text}」")
    assert "sourceQuotes" not in public.model_dump_json()
    assert "source-" not in public.model_dump_json()
    assert wire == original


@pytest.mark.parametrize("defect", ["missing", "extra", "wrong_type"])
def test_reduce_private_selection_shape_is_required_closed_and_typed(defect):
    value = structured_reduce()
    refs = value["insights"][0]["overview"][0]["sourceQuotes"]
    if defect == "missing":
        del refs["text"]
    elif defect == "extra":
        refs["ignored"] = None
    else:
        refs["text"] = {"slotId": "source-" + "0" * 24}
    with pytest.raises(ValidationError):
        ReportInsightStructuredReduceOutput.model_validate(value)


def test_reduce_cannot_borrow_a_source_outside_its_unit_or_audience():
    source = request(ids=(101, 102))
    value = structured_reduce()
    value["insights"][0]["overview"][0]["sourceQuotes"]["text"] = next(
        slot.slot_id for slot in build_fact_text_catalog(source).slots if slot.finding_id == 102
    )
    _, issues = render_reduce_source_quotes(value, source, {"CHIP_MAKER": ["101:0", "102:0"]})
    assert issues[0].field == "overview[0].text"
    assert issues[0].rule_id == "report_fact_slot_scope"
    assert issues[0].error_kind == "report_evidence_reference_invalid"
    assert issues[0].category == "REFERENCE"


def test_reduce_does_not_decode_markers_in_new_provider_prose():
    source = request()
    value = structured_reduce()
    value["insights"][0]["headline"] = (
        "{{fact:" + build_fact_text_catalog(source).slots[0].slot_id + "}} 업무 영향을 확인한다."
    )
    _, issues = render_reduce_source_quotes(value, source, {"CHIP_MAKER": ["101:0"]})
    assert issues[0].rule_id == "report_fact_slot_position"
    assert issues[0].error_kind == "report_expression_policy"
    assert issues[0].category == "EXPRESSION_POLICY"


def test_structured_incomplete_unit_diagnostics_keep_original_scalar_source_selections():
    source = request()
    value = structured_reduce()
    unit = value["insights"][0]["implications"][0]
    source_id = build_fact_text_catalog(source).slots[0].slot_id
    unit["sourceQuotes"]["text"] = source_id
    del unit["assumption"]
    before = deepcopy(value)
    scan = build_reduce_shape_scan(
        value,
        (
            ReportValidationIssue(
                "CHIP_MAKER", "implications[0].assumption", "report_output_shape", ()
            ),
        ),
        ["CHIP_MAKER"],
        structured=True,
    )
    assert scan is not None
    assert dict(scan.incomplete_units[0].source_quotes)["text"] == source_id
    assert scan.candidate.insights[0].implications == []
    assert value == before


def test_missing_selection_remains_missing_in_diagnostic_metadata():
    value = structured_reduce()
    del value["insights"][0]["overview"][0]["sourceQuotes"]["text"]
    scan = build_reduce_shape_scan(
        value,
        (ReportValidationIssue("CHIP_MAKER", "overview[0].text", "report_output_shape", ()),),
        ["CHIP_MAKER"],
        structured=True,
    )
    assert scan is not None
    assert "text" not in dict(scan.incomplete_units[0].source_quotes)


def test_new_wire_parser_authenticates_source_metadata_in_record_snapshots():
    source = request()
    value = draft_to_wire(payload(source), source)
    parsed = parse_wire_draft(response(value).text, structured=True)
    before = parsed.assessments["CHIP_MAKER"]["finding101"]
    mutated = before.model_copy(deep=True)
    mutated.source_quotes.reason = build_fact_text_catalog(source).slots[0].slot_id
    assert before != mutated
