"""Display quotes come from proven bases without another provider selection."""

import json
from copy import deepcopy
from dataclasses import replace

import pytest
from jsonschema import Draft202012Validator
from pydantic import ValidationError
from pydantic_ai.profiles.openai import OpenAIJsonSchemaTransformer
from test_report_insight_assessment import payload, request
from test_report_insight_fact_rendering import response
from test_report_insight_v4_pipeline import V4Provider, generate, stages

from app.core.errors import StructuredOutputExhaustedError
from app.llm.openai_contract import output_contract
from app.llm.report_insight_assessment import (
    ReportAssessmentDraftValidationError,
    draft_schema,
    draft_to_wire,
    render_basis_quotes,
    validate_basis_draft,
    validate_source_draft,
)
from app.llm.report_insight_fact_rendering import build_fact_text_catalog
from app.llm.report_insight_service import _validated_map_output


def full_validation(source, wire):
    raw = response(wire)
    draft = validate_basis_draft(raw, source)
    _validated_map_output(
        replace(raw, text=draft.mapped.model_dump_json(by_alias=True)),
        source,
        native_assessments=draft.evidence,
    )
    rendered = render_basis_quotes(draft, source)
    _validated_map_output(
        replace(raw, text=rendered.mapped.model_dump_json(by_alias=True)),
        source,
        native_assessments=rendered.evidence,
    )
    return rendered


@pytest.mark.parametrize("quote_field", ["reason", "condition"])
def test_same_finding_unselected_claim_cannot_be_a_second_display_choice(quote_field):
    source = request(ids=(8317, 8325))
    for finding in source.findings:
        finding.sentences.append(
            finding.sentences[0].model_copy(
                update={"index": 1, "text": "제조사는 공급을 검토한다."}
            )
        )
        finding.claims.append(
            finding.claims[0].model_copy(
                update={
                    "id": f"{finding.id}:1",
                    "text": "제조사는 공급을 검토한다.",
                    "evidence_sentence_ids": [1],
                }
            )
        )
    wire = draft_to_wire(payload(source, relation="CONDITIONAL"), source)
    catalog = build_fact_text_catalog(source)
    for finding in source.findings:
        record = wire["assessments"]["CHIP_MAKER"][f"finding{finding.id}"]
        record["sourceQuotes"][quote_field] = next(
            slot.slot_id for slot in catalog.slots if slot.claim_ids == (f"{finding.id}:1",)
        )
    # Historical replay still rejects this genuine scope violation.
    with pytest.raises(ReportAssessmentDraftValidationError) as caught:
        validate_source_draft(response(wire), source)
    assert {issue.rule for issue in caught.value.validation_issues} == {"report_fact_slot_scope"}
    # Current strict SDK schema and runtime both remove this choice altogether.
    schema = OpenAIJsonSchemaTransformer(
        output_contract(draft_schema(source)).schema, strict=True
    ).walk()
    assert not Draft202012Validator(schema).is_valid(wire)
    with pytest.raises(ValidationError):
        validate_basis_draft(response(wire), source)
    for record in wire["assessments"]["CHIP_MAKER"].values():
        record["sourceQuotes"] = {"reason": None, "condition": None}
    Draft202012Validator(schema).validate(wire)
    rendered = full_validation(source, wire)
    for assessment in rendered.mapped.insights[0].assessments:
        assert assessment.basis_claim_ids == [f"{assessment.finding_id}:0"]
        assert "공급을 검토한다" not in assessment.reason
        assert assessment.reason.startswith(f"원문: 「{source.findings[0].sentences[0].text}」")


def test_server_quotes_preserve_raw_prose_bases_and_exact_original_sentence():
    source = request()
    wire = draft_to_wire(payload(source, relation="CONDITIONAL"), source)
    snapshot = deepcopy(wire), source.model_dump_json()
    rendered = full_validation(source, wire)
    original = wire["assessments"]["CHIP_MAKER"]["finding101"]
    item = rendered.evidence["CHIP_MAKER"][101]
    assert (
        item.reason
        == f"원문: 「{source.findings[0].sentences[0].text}」 해석: {original['reason']}"
    )
    assert item.condition.endswith(original["decision"]["connection"]["condition"])
    assert draft_to_wire(rendered) == wire
    assert render_basis_quotes(rendered, source).mapped == rendered.mapped
    assert (wire, source.model_dump_json()) == snapshot
    assert "source-" not in rendered.mapped.model_dump_json()


@pytest.mark.parametrize("ambiguous_long_sentence", [False, True])
def test_repeated_source_literal_has_no_automatic_quote_even_outside_display_catalog(
    ambiguous_long_sentence,
):
    source = request()
    finding = source.findings[0]
    repeated = finding.sentences[0].text
    if ambiguous_long_sentence:
        repeated += " 추가 원문입니다." * 70
    finding.sentences.append(finding.sentences[0].model_copy(update={"index": 1, "text": repeated}))
    finding.claims[0].evidence_sentence_ids = [0, 1]
    wire = draft_to_wire(payload(source), source)
    rendered = full_validation(source, wire)
    assert rendered.evidence["CHIP_MAKER"][101].source_quotes is None
    assert (
        rendered.mapped.insights[0].assessments[0].reason
        == wire["assessments"]["CHIP_MAKER"]["finding101"]["reason"]
    )


def test_claim_summary_is_not_quoted_as_an_original_sentence():
    source = request()
    source.findings[0].claims[0].text = "제조사는 생산 가동을 중단했다고 밝혔다."
    wire = draft_to_wire(payload(source), source)
    rendered = full_validation(source, wire)
    assert rendered.evidence["CHIP_MAKER"][101].source_quotes is None
    assert not rendered.mapped.insights[0].assessments[0].reason.startswith("원문:")


def test_distinct_basis_sentences_do_not_choose_one_reason_quote_or_change_condition_source():
    source = request()
    finding = source.findings[0]
    second = "제조사는 두 번째 생산라인 전체의 가동 중단이 현재 계속된다고 밝혔다."
    finding.sentences.append(finding.sentences[0].model_copy(update={"index": 1, "text": second}))
    finding.claims.append(
        finding.claims[0].model_copy(
            update={"id": "101:1", "text": second, "evidence_sentence_ids": [1]}
        )
    )
    value = payload(source, relation="CONDITIONAL")
    value["assessments"]["CHIP_MAKER"]["finding101"]["impactBasis"] = {
        "claimId": "101:1",
        "quote": second,
    }
    wire = draft_to_wire(value, source)
    rendered = full_validation(source, wire)
    item = rendered.evidence["CHIP_MAKER"][101]
    original = wire["assessments"]["CHIP_MAKER"]["finding101"]
    assert item.reason == original["reason"]
    assert item.source_quotes.reason is None
    assert item.condition == (
        f"원문: 「{finding.sentences[0].text}」 미확인 가정: "
        + original["decision"]["connection"]["condition"]
    )
    assert draft_to_wire(rendered) == wire
    assert rendered.mapped.insights[0].assessments[0].basis_claim_ids == ["101:0", "101:1"]


def test_long_automatic_quote_preserves_interpretation_and_original_basis_without_truncation():
    original_sentence = request().findings[0].sentences[0].text
    source = request(text=original_sentence + " 생산 제약에 따른 공정 검증 준비를 확인한다." * 6)
    # The selected basis is a real source span; the complete display sentence
    # exceeds the prose limit and must be omitted as a whole.
    source.findings[0].claims[0].text = original_sentence
    wire = draft_to_wire(payload(source), source)
    rendered = full_validation(source, wire)
    item = rendered.evidence["CHIP_MAKER"][101]
    assert item.reason == wire["assessments"]["CHIP_MAKER"]["finding101"]["reason"]
    assert item.source_quotes.reason is not None
    assert item.relation_basis.quote == original_sentence
    assert draft_to_wire(rendered) == wire


def test_no_basis_does_not_select_a_claim_or_change_unknown_axes():
    source = request()
    wire = draft_to_wire(payload(source, relation="UNDETERMINED"), source)
    rendered = full_validation(source, wire)
    assessment = rendered.mapped.insights[0].assessments[0]
    assert assessment.basis_claim_ids == []
    assert set(assessment.axes.model_dump().values()) == {None}
    assert rendered.evidence["CHIP_MAKER"][101].source_quotes is None


def test_auto_quotes_do_not_add_calls_or_model_choices_to_map_and_review():
    source = request()
    provider = V4Provider(source)
    output = generate(provider, source)
    assert stages(provider) == ["MAP-001", "REVIEW-001", "REDUCE-001"]
    for call, wire in zip(provider.calls[:2], provider.wire_payloads[:2], strict=True):
        assert "factTextSlots" not in call["prompt"]
        assert wire["assessments"]["CHIP_MAKER"]["finding101"]["sourceQuotes"] == {
            "reason": None,
            "condition": None,
        }
    assert output.insights[0].assessments[0].reason.startswith("원문:")


@pytest.mark.parametrize("defect", ["company", "foreign_basis", "unknown_span", "false_impact"])
def test_existing_grounding_and_decision_guards_still_reject_invalid_map(defect):
    source = request(ids=(101, 102))

    def hook(stage, occurrence, data, wire):
        if not stage.startswith(("MAP", "REVIEW")):
            return wire
        record = wire["assessments"]["CHIP_MAKER"]["finding101"]
        if defect == "company":
            record["reason"] = "NVIDIA의 생산 제약에 맞춰 공정 검증 준비의 영향을 확인한다."
        elif defect == "foreign_basis":
            record["decision"]["connection"]["basis"] = {
                "claimId": "102:0",
                "sourceSpanId": "s102_0_0",
            }
        elif defect == "unknown_span":
            record["decision"]["connection"]["basis"]["sourceSpanId"] = "s101_0_999"
        else:
            record["reason"] = "영향 범위는 미확인이다."
        return wire

    provider = V4Provider(source, wire_hook=hook, validate_wire=False)
    with pytest.raises(StructuredOutputExhaustedError):
        generate(provider, source)
    expected = ["MAP-001", "MAP-001"]
    if defect == "company":
        expected += ["REVIEW-001", "REVIEW-001"]
    assert stages(provider) == expected
    assert all("sourceQuotes" in json.dumps(wire) for wire in provider.wire_payloads)
