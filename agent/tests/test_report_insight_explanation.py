"""Final explanation inputs expose selected evidence and freeze native decisions."""

import json
from copy import deepcopy
from dataclasses import replace

import pytest
from jsonschema import Draft202012Validator
from pydantic_ai.profiles.openai import OpenAIJsonSchemaTransformer
from test_report_insight_assessment import framed, payload, request, response
from test_report_insight_corporate_event_live_regressions import source_request

from app.core.errors import OutputValidationError
from app.llm.openai_contract import output_contract
from app.llm.report_insight_assessment import draft_to_wire, validate_draft
from app.llm.report_insight_explanation import (
    prepare_explanation_batches,
    validate_explanation_fields,
)


def captured_case(*, conditional=False):
    source = source_request(8288)
    finding = source.findings[0]
    value = payload(source)
    quote = next(sentence.text for sentence in finding.sentences if sentence.index == 37)
    value["assessments"]["MARKET_INVESTOR"]["finding8288"].update(
        work="CAPEX_EXECUTION",
        relation="CONDITIONAL" if conditional else "DIRECT",
        relationBasis={"claimId": "8288:1", "quote": quote},
        condition="조달 계획이 투자 집행에 적용되는 경우" if conditional else None,
        impactScope="UNDETERMINED",
        impactBasis=None,
        urgencyState="UNDETERMINED",
        urgencyBasis=None,
        reason="상장 추진과 조달 전망은 투자 집행과 관련된다.",
    )
    return source, value


def prepare(source, value, **kwargs):
    raw = response(value, source)
    validated = validate_draft(raw, source)
    return raw, validated, prepare_explanation_batches(source, validated, **kwargs)


def batch_response(raw, batch):
    return replace(raw, text=json.dumps({"assessments": batch.fixed_records}, ensure_ascii=False))


def test_8288_explanation_excludes_unselected_use_of_funds_and_retains_all_linked_sentences():
    source, value = captured_case(conditional=True)
    finding = source.findings[0]
    selected = next(claim for claim in finding.claims if claim.id == "8288:1")
    selected.evidence_sentence_ids.append(38)
    extra = "대표 주관사 선정 관련 추가 원문을 제공한다."
    finding.sentences.append(finding.sentences[0].model_copy(update={"index": 38, "text": extra}))
    before = source.model_dump_json(), deepcopy(value)
    raw, validated, (batch,) = prepare(source, value)
    snapshot = validated.draft.model_dump_json()
    data = framed(batch.prompt)
    assert set(data) == {"reportReferenceDate", "audiences", "findings"}
    focused = data["findings"][0]
    assert [claim["id"] for claim in focused["claims"]] == ["8288:1"]
    assert {sentence["index"] for sentence in focused["sentences"]} == {37, 38}
    assert extra in batch.prompt
    assert "8288:7" not in batch.prompt and "8288:3" not in batch.prompt
    assert "미국 내 생산시설" not in batch.prompt
    assert "sourceQuoteChoices" not in focused
    assert all("text" not in claim for claim in focused["claims"])
    assert focused["selectedEvidence"][0]["claimId"] == "8288:1"
    assert focused["selectedEvidence"][0]["evidenceSentenceIds"] == [37, 38]
    assert "text" not in focused["selectedEvidence"][0]
    assert focused["conditionRequired"] is True
    assert "condition" not in focused["decision"]["connection"]
    original = value["assessments"]["MARKET_INVESTOR"]["finding8288"]
    assert original["reason"] not in batch.prompt
    assert original["condition"] not in batch.prompt
    assert "feedbackExamples" not in batch.prompt and "articleTitle" not in batch.prompt
    assert {claim.id for claim in batch.request.findings[0].claims} == {
        "8288:1",
        "8288:3",
        "8288:7",
    }
    assert "8288:7" not in json.dumps(batch.response_schema, ensure_ascii=False)
    assert "8288:3" not in json.dumps(batch.response_schema, ensure_ascii=False)
    assert validate_explanation_fields(batch_response(raw, batch), batch)
    assert before == (source.model_dump_json(), value)
    assert snapshot == validated.draft.model_dump_json()


@pytest.mark.parametrize("conditional", [False, True])
def test_explanation_schema_and_frozen_values_allow_only_new_prose(conditional):
    source, value = captured_case(conditional=conditional)
    raw, _, (batch,) = prepare(source, value)
    actual = {"assessments": deepcopy(batch.fixed_records)}
    record = actual["assessments"]["MARKET_INVESTOR"]["finding8288"]
    record["reason"] = "솔리다임의 상장·조달 전망은 투자 집행 판단과 연결된다."
    if conditional:
        record["decision"]["connection"]["condition"] = "상장 조달 계획이 실제 이행되는 경우"
    strict = OpenAIJsonSchemaTransformer(
        output_contract(batch.response_schema).schema, strict=True
    ).walk()
    assert batch.response_schema["title"] == "ReportAssessmentDraft"
    for schema in (batch.response_schema, strict):
        Draft202012Validator(schema).validate(actual)
    candidate = replace(raw, text=json.dumps(actual, ensure_ascii=False))
    assert validate_explanation_fields(candidate, batch) == actual
    validate_draft(candidate, batch.request)


@pytest.mark.parametrize(
    "path,new_value",
    [
        (("findingId",), 999),
        (("decision", "connection", "relation"), "BACKGROUND"),
        (("decision", "connection", "work"), "GUIDANCE"),
        (("decision", "connection", "condition"), "조건을 새로 추가하는 경우"),
        (("decision", "connection", "basis", "claimId"), "8288:7"),
        (("decision", "connection", "basis", "sourceSpanId"), "s8288_7_1"),
        (("decision", "effect", "impactScope"), "PROJECT_CHANGE"),
        (("decision", "timing", "urgencyState"), "MONITOR"),
        (("sourceQuotes", "reason"), "source-unselected"),
    ],
)
def test_explanation_rejects_drift_even_without_provider_schema_enforcement(path, new_value):
    source, value = captured_case()
    raw, _, (batch,) = prepare(source, value)
    actual = {"assessments": deepcopy(batch.fixed_records)}
    current = actual["assessments"]["MARKET_INVESTOR"]["finding8288"]
    for part in path[:-1]:
        current = current[part]
    current[path[-1]] = new_value
    assert not Draft202012Validator(batch.response_schema).is_valid(actual)
    with pytest.raises((OutputValidationError, ValueError)):
        validate_explanation_fields(replace(raw, text=json.dumps(actual)), batch)


@pytest.mark.parametrize("shape", ["array", "missing", "extra", "duplicate"])
def test_explanation_rejects_wrong_record_shape_and_duplicate_keys(shape):
    source, value = captured_case()
    raw, _, (batch,) = prepare(source, value)
    actual = {"assessments": deepcopy(batch.fixed_records)}
    records = actual["assessments"]["MARKET_INVESTOR"]
    if shape == "array":
        actual["assessments"]["MARKET_INVESTOR"] = list(records.values())
    elif shape == "missing":
        records.clear()
    elif shape == "extra":
        records["finding999"] = deepcopy(records["finding8288"])
    text = json.dumps(actual)
    if shape == "duplicate":
        text = text.replace('"findingId": 8288', '"findingId": 8288, "findingId": 8288')
    with pytest.raises((OutputValidationError, ValueError)):
        validate_explanation_fields(replace(raw, text=text), batch)


def test_explanation_keeps_nonnull_condition_required():
    source, value = captured_case(conditional=True)
    raw, _, (batch,) = prepare(source, value)
    actual = {"assessments": deepcopy(batch.fixed_records)}
    actual["assessments"]["MARKET_INVESTOR"]["finding8288"]["decision"]["connection"][
        "condition"
    ] = None
    with pytest.raises(OutputValidationError):
        validate_explanation_fields(replace(raw, text=json.dumps(actual)), batch)


@pytest.mark.parametrize("kind", ["all_selected", "unknown", "claimless"])
def test_explanation_does_not_rewrite_all_selected_unknown_or_claimless_findings(kind):
    source, value = captured_case()
    if kind == "all_selected":
        source.findings[0].claims = [
            claim for claim in source.findings[0].claims if claim.id == "8288:1"
        ]
    else:
        value = payload(source, relation="UNDETERMINED")
        if kind == "claimless":
            from app.schemas.report_insight import CLAIMLESS_ASSESSMENT_REASON

            source.findings[0].claims = []
            source.findings[0].sentences = []
            value["assessments"]["MARKET_INVESTOR"]["finding8288"]["reason"] = (
                CLAIMLESS_ASSESSMENT_REASON
            )
    _, _, batches = prepare(source, value)
    assert batches == ()


def test_explanation_batches_preserve_source_order_and_separate_audiences():
    source = request(ids=tuple(range(101, 108)), audiences=("CHIP_MAKER", "EQUIPMENT_MAKER"))
    for finding in source.findings:
        finding.claims.append(finding.claims[0].model_copy(update={"id": f"{finding.id}:1"}))
    value = payload(source)
    _, validated, batches = prepare(source, value)
    assert [(batch.request.audiences, batch.finding_ids) for batch in batches] == [
        (("CHIP_MAKER",), tuple(range(101, 107))),
        (("CHIP_MAKER",), (107,)),
        (("EQUIPMENT_MAKER",), tuple(range(101, 107))),
        (("EQUIPMENT_MAKER",), (107,)),
    ]
    assert draft_to_wire(validated, source)["assessments"].keys() == value["assessments"].keys()


@pytest.mark.parametrize("quote_summary", [False, True])
def test_explanation_8298_summary_identifier_never_reappears_in_raw_only_input(quote_summary):
    raw_sentence = (
        "삼성전자가 메모리 반도체 가격 상승과 고대역폭메모리(HBM) 공급 확대에 힘입어 "
        "남은 4·4분기와 내년에 실적 성장세를 이어갈 것으로 예상된다."
    )
    source = request(ids=(8298,), audiences=("MARKET_INVESTOR",), text=raw_sentence)
    finding = source.findings[0]
    finding.claims[
        0
    ].text = (
        "증권가는 메모리 가격 상승과 HBM4 공급 확대가 삼성전자 이익 성장을 이끌 것으로 전망한다."
    )
    finding.claims[0].claim_type = "FORECAST"
    value = payload(source)
    record = value["assessments"]["MARKET_INVESTOR"]["finding8298"]
    record.update(
        work="PROFITABILITY",
        impactScope="UNDETERMINED",
        impactBasis=None,
        urgencyState="UNDETERMINED",
        urgencyBasis=None,
        reason="HBM4 공급 확대는 삼성전자 수익성과 연결된다.",
    )
    if not quote_summary:
        record["relationBasis"]["quote"] = raw_sentence
    before = source.model_dump_json()
    _, validated, (batch,) = prepare(source, value, required_finding_ids=(8298,))
    focused = framed(batch.prompt)["findings"][0]
    assert "HBM4" not in batch.prompt
    assert raw_sentence in batch.prompt
    assert focused["claims"] == [
        {
            "id": "8298:0",
            "claimType": "FORECAST",
            "attributedTo": None,
            "evidenceSentenceIds": [0],
        }
    ]
    assert focused["selectedEvidence"][0]["sourceSpanId"] == (
        "s8298_0_0" if quote_summary else "s8298_0_1"
    )
    assert "sourceQuoteChoices" not in focused
    assert "HBM4" not in json.dumps(focused["sourceFactIndex"], ensure_ascii=False)
    assert batch.request.findings[0].claims[0].text == finding.claims[0].text
    assert before == source.model_dump_json()
    assert prepare_explanation_batches(source, validated) == ()


def test_required_unknown_row_uses_all_local_linked_sources_and_freezes_null_bases():
    source, _ = captured_case()
    value = payload(source, relation="UNDETERMINED")
    raw, _, (batch,) = prepare(source, value, required_finding_ids=(8288,))
    focused = framed(batch.prompt)["findings"][0]
    assert {claim["id"] for claim in focused["claims"]} == {"8288:1", "8288:3", "8288:7"}
    assert {sentence["index"] for sentence in focused["sentences"]} == {
        index for claim in source.findings[0].claims for index in claim.evidence_sentence_ids
    }
    assert focused["selectedEvidence"] == []
    assert all(axis["basis"] is None for axis in focused["decision"].values())
    assert all("text" not in claim for claim in focused["claims"])
    actual = {"assessments": deepcopy(batch.fixed_records)}
    actual["assessments"]["MARKET_INVESTOR"]["finding8288"]["reason"] = (
        "상장 추진과 자금 조달 전망이 제시됐으나 구체적인 업무 관계 판단은 보류한다."
    )
    Draft202012Validator(batch.response_schema).validate(actual)
    assert validate_explanation_fields(replace(raw, text=json.dumps(actual)), batch) == actual


def test_required_ids_do_not_include_claimless_or_other_all_selected_rows():
    from app.schemas.report_insight import CLAIMLESS_ASSESSMENT_REASON

    source = request(ids=(101, 102, 103))
    value = payload(source, relation="UNDETERMINED")
    source.findings[0].claims = []
    source.findings[0].sentences = []
    value["assessments"]["CHIP_MAKER"]["finding101"]["reason"] = CLAIMLESS_ASSESSMENT_REASON
    _, _, (batch,) = prepare(source, value, required_finding_ids=(101, 102))
    assert batch.finding_ids == (102,)
