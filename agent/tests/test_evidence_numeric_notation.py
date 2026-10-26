"""Source-local quantity spelling changes never waive other numeric facts."""

import pytest
from test_report_insight_assessment import request

from app.core.evidence import factual_mismatches
from app.llm.report_insight_service import (
    _eligible_report_request,
    _prose_validation_errors,
    _source_context,
)

AUDIENCES = ("CHIP_MAKER", "EQUIPMENT_MAKER", "MARKET_INVESTOR", "IT_INFRA")


@pytest.mark.parametrize("counter", ["장", "대", "곳", "평"])
@pytest.mark.parametrize("amount", ["2만", "1억 7천만", "6조 7800억"])
@pytest.mark.parametrize("reverse", [False, True])
def test_opaque_counter_whitespace_is_proved_by_the_complete_source_token(counter, amount, reverse):
    source = f"검토 수량은 {amount} {counter}이다."
    claim = f"검토 수량은 {amount}{counter}이다."
    if reverse:
        source, claim = claim, source
    assert factual_mismatches(claim, source) == []


@pytest.mark.parametrize(
    ("source", "claim"),
    [
        ("오로라전자의 생산량은 1만개다.", "오로라전자의 생산량은 10000개다."),
        ("한빛소재의 공급량은 10000대다.", "한빛소재의 공급량은 1만대다."),
        ("푸른연구원의 고용 인원은 1만명이다.", "푸른연구원의 고용 인원은 10000명이다."),
    ],
)
@pytest.mark.parametrize("audience", AUDIENCES)
def test_equivalent_quantity_survives_input_projection_and_report_prose(source, claim, audience):
    original = request(text=source, audiences=(audience,))
    claims, evidence = _source_context(original)
    assert not _prose_validation_errors([claim], ["101:0"], evidence, claims, request=original)
    original.findings[0].claims[0].text = claim
    projected = _eligible_report_request(original)
    assert len(projected.findings[0].claims) == 1
    assert projected.findings[0].sentences[0].text == source
    assert projected.findings[0].claims[0].text == claim


@pytest.mark.parametrize(
    ("source", "claim"),
    [
        ("생산량은 1만개다.", "생산량은 10000개다. 추가 수량은 1개다."),
        ("생산량은 10000개다.", "생산량은 1만개다. 추가 수량은 1개다."),
        ("설비는 2만 장이다.", "설비는 2만장이다. 추가 수량은 2장이다."),
        ("설비는 2만장이다.", "설비는 2만 장이다. 추가 수량은 2장이다."),
        ("설비는 2만장이다.", "설비는 2만 장이다. 추가 수량은 2곳이다."),
        ("전력 용량은 1GW다.", "전력 용량은 1000MW다. 추가 용량은 1MW다."),
        ("전력 용량은 1000MW다.", "전력 용량은 1GW다. 추가 용량은 1000GW다."),
        ("생산량은 1만개다.", "생산량은 10000개다. 투자액은 1만원이다."),
        ("생산량은 1만개다.", "생산량은 10000개다. 추가 수량은 999개다."),
    ],
)
def test_one_supported_span_cannot_conceal_an_additional_quantity(source, claim):
    assert factual_mismatches(claim, source)


@pytest.mark.parametrize(
    ("source", "claim"),
    [
        ("오로라전자의 생산량은 1만개다.", "오로라전자의 생산량은 12000개다."),
        ("오로라전자의 생산량은 1만개다.", "오로라전자의 생산량은 1개다."),
        ("한빛소재의 냉각 대응용량은 1GW다.", "한빛소재의 냉각 대응용량은 1MW다."),
        ("한빛소재의 냉각 대응용량은 1GW다.", "한빛소재의 냉각 대응용량은 -1000MW다."),
        ("오로라전자의 생산량은 1만개다.", "오로라전자의 생산량은 10000명이다."),
        ("오로라전자의 생산량은 1만개다.", "오로라전자의 생산량은 10000원이다."),
        ("오로라전자의 생산량은 1만개다.", "한빛소재의 생산량은 10000개다."),
        ("오로라전자의 생산량은 2027년 1만개다.", "오로라전자의 생산량은 2028년 10000개다."),
        (
            "오로라전자의 생산량은 1만개다. 한빛소재의 생산량은 20000개다.",
            "한빛소재의 생산량은 10000개다.",
        ),
        (
            "오로라전자의 생산량은 2027년 1만개다. 오로라전자의 생산량은 2028년 20000개다.",
            "오로라전자의 생산량은 2028년 10000개다.",
        ),
    ],
)
@pytest.mark.parametrize("audience", AUDIENCES)
def test_conversion_keeps_value_unit_currency_sign_subject_and_date_guards(source, claim, audience):
    original = request(text=source, audiences=(audience,))
    claims, evidence = _source_context(original)
    assert _prose_validation_errors([claim], ["101:0"], evidence, claims, request=original)


def test_conversion_cannot_borrow_an_unselected_claim_quantity():
    original = request(text="오로라전자의 생산량은 1만개다.")
    finding = original.findings[0]
    finding.claims.append(
        finding.claims[0].model_copy(
            update={
                "id": "101:1",
                "text": "오로라전자의 생산량은 2만개다.",
                "evidence_sentence_ids": [1],
            }
        )
    )
    finding.sentences.append(
        finding.sentences[0].model_copy(
            update={
                "index": 1,
                "text": "오로라전자의 생산량은 2만개다.",
            }
        )
    )
    claims, evidence = _source_context(original)
    assert _prose_validation_errors(
        ["오로라전자의 생산량은 20000개다."], ["101:0"], evidence, claims, request=original
    )


@pytest.mark.parametrize("reverse", [False, True])
def test_spacing_equivalence_does_not_exempt_an_unknown_different_owner(reverse):
    source = "오로라전자의 생산량은 2만 장이다."
    claim = "한빛소재의 생산량은 2만장이다."
    if reverse:
        source = source.replace("2만 장", "2만장")
        claim = claim.replace("2만장", "2만 장")
    assert factual_mismatches(claim, source)


def test_existing_supported_numbers_keep_their_particle_paraphrases():
    source = "생산량은 1만개이다. 패키지는 12단 구조다."
    claim = "생산량은 1만 개이다. 패키지는 12단의 구조다."
    assert factual_mismatches(claim, source) == []


def test_unproved_extra_numeric_paraphrase_keeps_original_conversion_error():
    source = "생산량은 1만개이다. 패키지는 12단 구조다."
    claim = "생산량은 10000개이다. 패키지는 12단의 구조다."
    # This extra clause is plausible, but the strict conversion proof cannot
    # establish its complete identity. Keep the original 10000 error; never
    # invent an unsupported 12 diagnostic or broaden the accepted paraphrases.
    assert factual_mismatches(claim, source) == ["근거에서 확인되지 않는 숫자: 10000"]


def test_supported_other_numeric_clause_survives():
    source = "생산량은 1만개이다. 패키지는 12단 구조다."
    claim = "생산량은 10000개이다. 패키지는 12단 구조다."
    assert factual_mismatches(claim, source) == []


def test_same_owner_different_metric_is_not_a_quantity_spelling_change():
    source = "오로라전자의 생산량은 1만개다."
    claim = "오로라전자의 판매량은 10000개다."
    assert factual_mismatches(claim, source)


def test_unicode_minus_is_deferred_until_all_source_parsers_agree():
    assert factual_mismatches("수익률은 -3%다.", "수익률은 −3%다.")


@pytest.mark.parametrize(
    "extra",
    ["한빛소재의 계획이다.", "다른 공장의 실적이다.", "작년 완료 실적이다."],
)
@pytest.mark.parametrize("before", [False, True])
def test_conversion_cannot_hide_a_different_context_in_a_number_free_clause(extra, before):
    source = "오로라전자의 계획이다. 생산량은 1만개다."
    claim = f"{extra} 생산량은 10000개다." if before else f"생산량은 10000개다. {extra}"
    original = request(text=source)
    claims, evidence = _source_context(original)
    assert _prose_validation_errors([claim], ["101:0"], evidence, claims, request=original)


@pytest.mark.parametrize("audience", AUDIENCES)
@pytest.mark.parametrize(
    ("source", "claim"),
    [
        (
            "오로라전자 계획이다. 생산량은 1만개다.",
            "한빛소재 계획이다. 생산량은 10000개다.",
        ),
        (
            "오로라전자 계획이다. 생산량은 2만 장이다.",
            "한빛소재 계획이다. 생산량은 2만장이다.",
        ),
    ],
)
def test_unknown_cross_sentence_owner_remains_rejected(source, claim, audience):
    original = request(text=source, audiences=(audience,))
    claims, evidence = _source_context(original)
    assert _prose_validation_errors([claim], ["101:0"], evidence, claims, request=original)


def test_supported_source_clauses_cannot_be_reordered_or_joined_across_another_context():
    source = "오로라전자 계획이다. 생산량은 1만개다. 한빛소재 계획이다."
    assert factual_mismatches("한빛소재 계획이다. 생산량은 10000개다.", source)


@pytest.mark.parametrize("audience", AUDIENCES)
@pytest.mark.parametrize("index_context", ["", " 나스닥100 지수를 확인한다."])
def test_summary_cannot_supply_the_owner_for_a_new_numeric_equivalence(audience, index_context):
    raw = "오로라전자의 생산량은 1만개다." + index_context
    original = request(text=raw, audiences=(audience,))
    original.findings[0].claims[0].text = "한빛소재의 생산량은 1만개다." + index_context
    projected = _eligible_report_request(original)
    # A currently unresolved summary binding is not newly promoted to proof.
    assert len(projected.findings[0].claims) == 1
    claims, evidence = _source_context(projected)
    generated = "한빛소재의 생산량은 10000개다." + index_context
    assert _prose_validation_errors([generated], ["101:0"], evidence, claims, request=projected)


@pytest.mark.parametrize("audience", AUDIENCES)
@pytest.mark.parametrize(
    ("source", "claim"),
    [
        (
            "이는 실제 수치가 아닌 가정이다. 생산량은 1만개다.",
            "생산량은 10000개다.",
        ),
        ("생산량은 1만개다?", "생산량은 10000개다."),
        ("생산량은 2만 장인가?", "생산량은 2만장인가."),
    ],
)
def test_conversion_preserves_complete_context_and_sentence_punctuation(source, claim, audience):
    original = request(text=source, audiences=(audience,))
    claims, evidence = _source_context(original)
    assert _prose_validation_errors([claim], ["101:0"], evidence, claims, request=original)


@pytest.mark.parametrize("audience", AUDIENCES)
@pytest.mark.parametrize(
    ("source", "claim"),
    [
        ("가온전자의 냉각 대응용량은 1GW다.", "가온전자의 냉각 대응용량은 1000MW다."),
        ("가온전자의 냉각 대응용량은 1000MW다.", "가온전자의 냉각 대응용량은 1GW다."),
        ("한빛소재의 전력 소비량은 1mW다.", "한빛소재의 전력 소비량은 1000kW다."),
        ("한빛소재의 전력 소비량은 1000kW다.", "한빛소재의 전력 소비량은 1mW다."),
        ("항로 길이는 1만NM다.", "항로 길이는 10000nm다."),
        ("장비1만개를 검토한다.", "장비10000개를 검토한다."),
        ("장비-1만개를 검토한다.", "장비-10000개를 검토한다."),
        ("장비_1만개를 검토한다.", "장비_10000개를 검토한다."),
        ("용량은 1MW다. 수량은 2만 장이다.", "용량은 1mW다. 수량은 2만장이다."),
        ("용량은 1mW다. 수량은 2만 장이다.", "용량은 1MW다. 수량은 2만장이다."),
    ],
)
def test_physical_symbol_case_and_adjacent_identifiers_are_not_new_quantity_proof(
    source, claim, audience
):
    original = request(text=source, audiences=(audience,))
    claims, evidence = _source_context(original)
    assert _prose_validation_errors([claim], ["101:0"], evidence, claims, request=original)
