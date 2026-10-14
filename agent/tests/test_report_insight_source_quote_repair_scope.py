"""Current repairs keep display selectors null and preserve authenticated decisions."""

from copy import deepcopy

import pytest
from jsonschema import Draft202012Validator
from pydantic_ai.profiles.openai import OpenAIJsonSchemaTransformer
from test_report_insight_assessment import request
from test_report_insight_review_time_admission import assert_usage
from test_report_insight_v4_pipeline import V4Provider, generate, stages

from app.core.errors import StructuredOutputExhaustedError
from app.llm.openai_contract import output_contract
from app.llm.report_insight_fact_rendering import build_fact_text_catalog


def source_with_two_claims():
    source = request(ids=(101, 102))
    finding = source.findings[0]
    text = "제조사는 두 번째 생산라인 전체의 가동 중단이 현재 계속된다고 밝혔다."
    finding.claims.append(
        finding.claims[0].model_copy(
            update={
                "id": "101:1",
                "text": text,
                "evidence_sentence_ids": [1],
            }
        )
    )
    finding.sentences.append(finding.sentences[0].model_copy(update={"index": 1, "text": text}))
    return source


def quote_scenario(
    field,
    *,
    bad="same_finding",
    repeat=False,
    null_condition=False,
    selector_error=False,
    prose_error=False,
    drift=False,
):
    source = source_with_two_claims()
    slots = {slot.claim_ids[0]: slot.slot_id for slot in build_fact_text_catalog(source).slots}
    invalid = {
        "same_finding": slots["101:0"],
        "foreign_finding": slots["102:0"],
        "unknown": "source-" + "0" * 24,
    }[bad]

    def hook(stage, occurrence, _, value):
        if stage.startswith(("MAP", "REVIEW")):
            item = value["assessments"]["CHIP_MAKER"].get("finding101")
            if item is not None:
                basis = {"claimId": "101:1", "quote": source.findings[0].claims[1].text}
                for name in ("relationBasis", "impactBasis", "urgencyBasis"):
                    item[name] = deepcopy(basis)
        return value

    def wire(stage, occurrence, _, value):
        if stage == "MAP-001" or (repeat and stage.startswith("REVIEW")):
            item = value["assessments"]["CHIP_MAKER"].get("finding101")
            if item is not None:
                if selector_error:
                    item["sourceQuotes"][field] = invalid if occurrence == 1 or repeat else None
                if (not selector_error or prose_error) and (occurrence == 1 or repeat):
                    target = item if field == "reason" else item["decision"]["connection"]
                    target[field] = (
                        "TSMC의 생산 제약에 따른 공정 검증 준비를 확인한다."
                        if field == "reason"
                        else "TSMC의 생산 제약이 공정 검증 일정에 연결되는 경우"
                    )
                if drift and occurrence == 2:
                    preserved_field = "condition" if field == "reason" else "reason"
                    target = item if preserved_field == "reason" else item["decision"]["connection"]
                    target[preserved_field] = "해당 생산 제약이 공정 검증 준비에 연결되는 경우"
        return value

    provider = V4Provider(
        source,
        relation="DIRECT" if null_condition else "CONDITIONAL",
        hook=hook,
        wire_hook=wire,
        validate_wire=not (selector_error or drift),
    )
    return source, slots, invalid, provider


def repair_schema(provider):
    schema = provider.calls[1]["response_schema"]
    return OpenAIJsonSchemaTransformer(output_contract(schema).schema, strict=True).walk()


@pytest.mark.parametrize("field", ["reason", "condition"])
def test_semantic_prose_repair_keeps_null_quotes_and_freezes_original_bases(field):
    source, _, _, provider = quote_scenario(field)
    before = source.model_dump_json()
    result = generate(provider, source)

    assert stages(provider) == ["MAP-001", "MAP-001", "REVIEW-001", "EXPLAIN-001", "REDUCE-001"]
    initial, repaired = provider.wire_payloads[:2]
    first = initial["assessments"]["CHIP_MAKER"]["finding101"]
    second = repaired["assessments"]["CHIP_MAKER"]["finding101"]
    assert provider.schema_validity == [True, True, True, True, True]
    assert set(repaired["assessments"]["CHIP_MAKER"]) == {"finding101"}
    assert first["sourceQuotes"] == second["sourceQuotes"] == {"reason": None, "condition": None}
    for axis in ("connection", "effect", "timing"):
        assert first["decision"][axis]["basis"] == second["decision"][axis]["basis"]
    schema = repair_schema(provider)
    record = schema["properties"]["assessments"]["properties"]["CHIP_MAKER"]["properties"][
        "finding101"
    ]["properties"]
    other = "condition" if field == "reason" else "reason"
    properties = (
        record
        if other == "reason"
        else record["decision"]["properties"]["connection"]["properties"]
    )
    original = first if other == "reason" else first["decision"]["connection"]
    corrected = second if other == "reason" else second["decision"]["connection"]
    assert properties[other]["const"] == original[other] == corrected[other]
    for selector in record["sourceQuotes"]["properties"].values():
        assert selector["type"] == "null"
    assert Draft202012Validator(schema).is_valid(repaired)
    rendered = next(row for row in result.insights[0].assessments if row.finding_id == 101)
    assert rendered.reason.startswith(f"원문: 「{source.findings[0].sentences[1].text}」")
    assert {row.finding_id for row in result.insights[0].assessments} == {101, 102}
    assert source.model_dump_json() == before


@pytest.mark.parametrize("field", ["reason", "condition"])
def test_model_selected_display_quote_is_removed_only_by_a_valid_null_retry(field):
    source, _, invalid, provider = quote_scenario(field, selector_error=True)
    result = generate(provider, source)
    assert provider.schema_validity[:2] == [False, True]
    original = provider.wire_payloads[0]["assessments"]["CHIP_MAKER"]["finding101"]
    corrected = provider.wire_payloads[1]["assessments"]["CHIP_MAKER"]["finding101"]
    assert original["sourceQuotes"][field] == invalid
    assert corrected["sourceQuotes"] == {"reason": None, "condition": None}
    assert {row.finding_id for row in result.insights[0].assessments} == {101, 102}


def test_absent_condition_requires_null_selector_in_initial_and_retry_contracts():
    source, _, invalid, provider = quote_scenario(
        "condition", null_condition=True, selector_error=True
    )
    generate(provider, source)
    assert stages(provider).count("MAP-001") == 2
    assert provider.schema_validity[:2] == [False, True]
    original = provider.wire_payloads[0]["assessments"]["CHIP_MAKER"]["finding101"]
    corrected = provider.wire_payloads[1]["assessments"]["CHIP_MAKER"]["finding101"]
    assert original["sourceQuotes"]["condition"] == invalid
    assert original["decision"]["connection"]["condition"] is None
    assert original["decision"] == corrected["decision"]
    assert corrected["sourceQuotes"]["condition"] is None
    schema = repair_schema(provider)
    selection = schema["properties"]["assessments"]["properties"]["CHIP_MAKER"]["properties"][
        "finding101"
    ]["properties"]["sourceQuotes"]["properties"]["condition"]
    assert selection == {"type": "null"}


@pytest.mark.parametrize("bad", ["same_finding", "foreign_finding", "unknown"])
def test_repeated_invalid_quote_remains_rejected_by_schema_and_full_validator(bad):
    source, _, _, provider = quote_scenario("reason", bad=bad, repeat=True, selector_error=True)
    with pytest.raises(StructuredOutputExhaustedError) as caught:
        generate(provider, source)
    assert stages(provider) == ["MAP-001", "MAP-001"]
    assert provider.schema_validity == [False, False]
    assert caught.value.details["validationFailure"]["errorKinds"] == ["none_required"]
    assert caught.value.details["usage"]["inputTokens"] == 22
    assert not Draft202012Validator(repair_schema(provider)).is_valid(provider.wire_payloads[1])


def test_repeated_nonnull_selector_on_absent_condition_is_not_normalized_to_success():
    source, _, _, provider = quote_scenario(
        "condition", null_condition=True, repeat=True, selector_error=True
    )
    with pytest.raises(StructuredOutputExhaustedError) as caught:
        generate(provider, source)
    assert stages(provider) == ["MAP-001", "MAP-001"]
    assert provider.schema_validity == [False, False]
    assert caught.value.details["validationFailure"]["errorKinds"] == ["none_required"]


@pytest.mark.parametrize("field", ["reason", "condition"])
def test_semantic_repair_cannot_rewrite_the_other_already_validated_prose(field):
    source, _, _, provider = quote_scenario(field, drift=True)
    with pytest.raises(StructuredOutputExhaustedError) as caught:
        generate(provider, source)
    assert stages(provider) == ["MAP-001", "MAP-001"]
    assert provider.schema_validity == [True, False]
    assert caught.value.details["validationFailure"]["errorKinds"] == ["report_assessment_invalid"]


@pytest.mark.parametrize("field", ["reason", "condition"])
def test_null_display_quotes_do_not_waive_factual_prose_validation(field):
    source, _, _, provider = quote_scenario(field, repeat=True)
    with pytest.raises(StructuredOutputExhaustedError) as caught:
        generate(provider, source)
    assert stages(provider) == ["MAP-001", "MAP-001", "REVIEW-001", "REVIEW-001"]
    assert caught.value.details["validationFailure"]["stage"] == "REVIEW-001"
    assert caught.value.details["validationFailure"]["attempt"] == 2
    assert_usage(caught.value.details["usage"], 4)
    assert provider.schema_validity == [True, True, True, True]
    assert caught.value.details["validationFailure"]["errorKinds"] == [
        "report_evidence_insufficient"
    ]
    assert all(
        wire["assessments"]["CHIP_MAKER"]["finding101"]["sourceQuotes"]
        == {"reason": None, "condition": None}
        for wire in provider.wire_payloads
    )


@pytest.mark.parametrize("keep_factual_error", [False, True])
def test_mixed_selector_and_prose_errors_require_both_corrections(keep_factual_error):
    source, _, invalid, provider = quote_scenario("reason", selector_error=True, prose_error=True)
    original_hook = provider.wire_hook

    def wire(stage, occurrence, data, value):
        value = original_hook(stage, occurrence, data, value)
        if keep_factual_error and (
            (stage == "MAP-001" and occurrence == 2) or stage.startswith("REVIEW")
        ):
            value["assessments"]["CHIP_MAKER"]["finding101"]["reason"] = (
                "TSMC의 생산 제약에 따른 공정 검증 준비를 확인한다."
            )
        return value

    provider.wire_hook = wire
    if keep_factual_error:
        with pytest.raises(StructuredOutputExhaustedError) as caught:
            generate(provider, source)
        assert caught.value.details["validationFailure"]["errorKinds"] == [
            "report_evidence_insufficient"
        ]
        assert stages(provider) == ["MAP-001", "MAP-001", "REVIEW-001", "REVIEW-001"]
        assert caught.value.details["validationFailure"]["stage"] == "REVIEW-001"
        assert caught.value.details["validationFailure"]["attempt"] == 2
        assert_usage(caught.value.details["usage"], 4)
    else:
        generate(provider, source)
    assert stages(provider).count("MAP-001") == 2
    assert provider.schema_validity[:2] == [False, True]
    first = provider.wire_payloads[0]["assessments"]["CHIP_MAKER"]["finding101"]
    repaired = provider.wire_payloads[1]["assessments"]["CHIP_MAKER"]["finding101"]
    assert first["sourceQuotes"]["reason"] == invalid
    assert repaired["sourceQuotes"] == {"reason": None, "condition": None}
    assert (first["reason"] == repaired["reason"]) is keep_factual_error
