"""Synthetic quote repairs expose only the sources of authenticated frozen bases."""

from copy import deepcopy

import pytest
from jsonschema import Draft202012Validator
from pydantic_ai.profiles.openai import OpenAIJsonSchemaTransformer
from test_report_insight_assessment import request
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
    field, *, bad="same_finding", repeat=False, null_condition=False, prose_error=False, drift=False
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
        if stage == "MAP-001":
            item = value["assessments"]["CHIP_MAKER"].get("finding101")
            if item is not None:
                if prose_error and occurrence == 1:
                    item["reason"] = "TSMC의 생산 제약에 따른 공정 검증 준비를 확인한다."
                if drift and occurrence == 2:
                    target = item if field == "reason" else item["decision"]["connection"]
                    target[field] = "해당 생산 제약이 공정 검증 준비에 연결되는 경우"
                item["sourceQuotes"][field] = (
                    invalid
                    if occurrence == 1 or repeat
                    else None
                    if null_condition
                    else slots["101:1"]
                )
        return value

    provider = V4Provider(
        source,
        relation="CONDITIONAL" if field == "condition" and not null_condition else "DIRECT",
        hook=hook,
        wire_hook=wire,
        validate_wire=not (repeat or drift),
    )
    return source, slots, invalid, provider


def repair_schema(provider):
    schema = provider.calls[1]["response_schema"]
    return OpenAIJsonSchemaTransformer(output_contract(schema).schema, strict=True).walk()


@pytest.mark.parametrize("field", ["reason", "condition"])
def test_one_quote_repair_allows_supported_nonnull_slot_and_freezes_original_bases(field):
    source, slots, invalid, provider = quote_scenario(field)
    before = source.model_dump_json()
    result = generate(provider, source)

    assert stages(provider) == ["MAP-001", "MAP-001", "REVIEW-001", "REDUCE-001"]
    initial, repaired = provider.wire_payloads[:2]
    first = initial["assessments"]["CHIP_MAKER"]["finding101"]
    second = repaired["assessments"]["CHIP_MAKER"]["finding101"]
    assert first["sourceQuotes"][field] == invalid
    assert second["sourceQuotes"][field] == slots["101:1"]
    assert first["decision"] == second["decision"]
    assert first["reason"] == second["reason"]
    schema = repair_schema(provider)
    record = schema["properties"]["assessments"]["properties"]["CHIP_MAKER"]["properties"][
        "finding101"
    ]["properties"]
    prose = (
        record["reason"]
        if field == "reason"
        else record["decision"]["properties"]["connection"]["properties"]["condition"]
    )
    original_prose = (
        first["reason"] if field == "reason" else first["decision"]["connection"]["condition"]
    )
    assert prose["const"] == original_prose
    selection = record["sourceQuotes"]["properties"][field]
    assert selection == {"anyOf": [{"type": "string", "enum": [slots["101:1"]]}, {"type": "null"}]}
    assert Draft202012Validator(schema).is_valid(repaired)
    null_quote = deepcopy(repaired)
    null_quote["assessments"]["CHIP_MAKER"]["finding101"]["sourceQuotes"][field] = None
    assert Draft202012Validator(schema).is_valid(null_quote)
    assert {row.finding_id for row in result.insights[0].assessments} == {101, 102}
    assert source.model_dump_json() == before


def test_absent_condition_requires_null_selector_only_in_authenticated_repair():
    source, _, invalid, provider = quote_scenario("condition", null_condition=True)
    generate(provider, source)
    assert stages(provider).count("MAP-001") == 2
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
    source, _, _, provider = quote_scenario("reason", bad=bad, repeat=True)
    with pytest.raises(StructuredOutputExhaustedError) as caught:
        generate(provider, source)
    assert stages(provider) == ["MAP-001", "MAP-001"]
    assert provider.schema_validity == [True, False]
    assert caught.value.details["validationFailure"]["errorKinds"] == [
        "report_evidence_reference_invalid"
    ]
    assert caught.value.details["usage"]["inputTokens"] == 22
    assert not Draft202012Validator(repair_schema(provider)).is_valid(provider.wire_payloads[1])


def test_repeated_nonnull_selector_on_absent_condition_is_not_normalized_to_success():
    source, _, _, provider = quote_scenario("condition", null_condition=True, repeat=True)
    with pytest.raises(StructuredOutputExhaustedError) as caught:
        generate(provider, source)
    assert stages(provider) == ["MAP-001", "MAP-001"]
    assert provider.schema_validity == [True, False]
    assert caught.value.details["validationFailure"]["errorKinds"] == ["report_expression_policy"]


@pytest.mark.parametrize("field", ["reason", "condition"])
def test_selector_only_repair_cannot_rewrite_the_already_validated_prose(field):
    source, _, _, provider = quote_scenario(field, drift=True)
    with pytest.raises(StructuredOutputExhaustedError) as caught:
        generate(provider, source)
    assert stages(provider) == ["MAP-001", "MAP-001"]
    assert provider.schema_validity == [True, False]
    assert caught.value.details["validationFailure"]["errorKinds"] == ["report_assessment_invalid"]


def test_mixed_selector_and_factual_prose_errors_allow_both_corrections():
    source, slots, _, provider = quote_scenario("reason", prose_error=True)
    generate(provider, source)
    assert stages(provider).count("MAP-001") == 2
    first = provider.wire_payloads[0]["assessments"]["CHIP_MAKER"]["finding101"]
    repaired = provider.wire_payloads[1]["assessments"]["CHIP_MAKER"]["finding101"]
    schema = repair_schema(provider)
    record = schema["properties"]["assessments"]["properties"]["CHIP_MAKER"]["properties"][
        "finding101"
    ]["properties"]
    assert "const" not in record["reason"]
    assert first["reason"] != repaired["reason"]
    assert repaired["sourceQuotes"]["reason"] == slots["101:1"]
    assert first["decision"] == repaired["decision"]
    assert Draft202012Validator(schema).is_valid(provider.wire_payloads[1])
