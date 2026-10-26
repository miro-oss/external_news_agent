"""Listing objects and money modifiers must not replace the actual issuer."""

from copy import deepcopy

import pytest
from test_report_insight_assessment import payload, request
from test_report_insight_grounded_prose import _checked_map

from app.llm.report_insight_corporate_event_binding import corporate_event_mismatches
from app.llm.report_insight_service import _prose_validation_errors, _source_context


def prose_errors(source, value):
    claims, evidence = _source_context(source)
    return _prose_validation_errors([value], ["101:0"], evidence, claims, request=source)


@pytest.mark.parametrize("issuer", ["노을테크", "새길바이오"])
@pytest.mark.parametrize("instrument", ["ADR", "GDR", "보통주", "우선주", "주식예탁증서"])
@pytest.mark.parametrize(
    "financing", ["12억 원의 자금을 조달했다", "7억 달러의 자금을 조달했다", "자금을 조달했다"]
)
def test_selected_listing_and_funding_keep_the_issuer_through_all_prose_guards(
    issuer, instrument, financing
):
    raw = f"{issuer}는 {instrument}를 상장해 {financing}."
    source = request(text=raw)
    before = source.model_dump_json()
    values = [
        f"{issuer}의 {instrument} 상장은 투자 여건 검토와 연결된다.",
        f"{issuer}의 자금 조달은 생산 투자 검토와 연결된다.",
        f"{issuer}는 {instrument} 상장을 통해 자금을 조달했다.",
    ]

    for value in values:
        assert prose_errors(source, value) == []
        assert corporate_event_mismatches(value.replace(issuer, "다른제조사"), [raw])
    assert source.model_dump_json() == before


@pytest.mark.parametrize("connective", ["상장해", "상장하여", "상장하고", "상장했으며", "상장했고"])
def test_listing_predicate_connectives_are_not_funding_owners(connective):
    source = f"노을테크는 보통주를 {connective} 자금을 조달했다."
    value = "노을테크의 자금 조달은 투자 판단에 연결된다."
    assert corporate_event_mismatches(value, [source]) == []
    assert corporate_event_mismatches(value.replace("노을테크", "다른제조사"), [source])


@pytest.mark.parametrize("issuer", ["ADR", "GDR", "원테크", "달러시스템", "ADR테크"])
def test_security_and_currency_vocabulary_inside_an_explicit_company_remains_bound(issuer):
    source = f"{issuer}는 보통주를 상장해 12억 원의 자금을 조달했다."
    assert corporate_event_mismatches(f"{issuer}의 상장은 투자 판단에 연결된다.", [source]) == []
    assert (
        corporate_event_mismatches(f"{issuer}의 자금 조달은 투자 판단에 연결된다.", [source]) == []
    )
    assert corporate_event_mismatches("다른제조사의 자금 조달은 투자 판단에 연결된다.", [source])


@pytest.mark.parametrize(
    "descriptor", ["자회사", "모회사", "계열사", "관계사", "기업", "회사", "법인"]
)
@pytest.mark.parametrize("issuer", ["ADR", "GDR"])
def test_explicit_company_description_preserves_a_name_that_is_also_a_security(descriptor, issuer):
    source = f"새봄의 {descriptor}인 {issuer} 상장은 투자 판단에 연결된다."
    assert corporate_event_mismatches(f"{issuer}의 상장은 투자 판단에 연결된다.", [source]) == []
    assert corporate_event_mismatches("새봄의 상장은 투자 판단에 연결된다.", [source])


@pytest.mark.parametrize("embedded_money", ["3원", "4달러", "5유로", "6억원"])
def test_money_substrings_inside_distinct_issuer_names_do_not_merge_their_identity(embedded_money):
    issuer, other = f"노을{embedded_money}테크", f"새봄{embedded_money}테크"
    raw = f"{issuer}는 자금을 조달했다. {other}는 실적을 발표했다."
    source = request(text=raw)
    supported = f"{issuer}의 자금 조달은 투자 판단에 연결된다."
    unsupported = f"{other}의 자금 조달은 투자 판단에 연결된다."
    assert corporate_event_mismatches(supported, [raw]) == []
    assert corporate_event_mismatches(unsupported, [raw])
    assert prose_errors(source, supported) == []
    assert prose_errors(source, unsupported)


@pytest.mark.parametrize(
    "financing",
    ["약40조원의 자금을 조달했다", "총12억원을 조달했다", "12억원의 자금을 조달했다"],
)
def test_closed_amount_prefix_and_case_particles_still_keep_the_actual_issuer(financing):
    raw = f"노을테크는 보통주를 상장해 {financing}."
    value = "노을테크의 자금 조달은 투자 판단에 연결된다."
    assert corporate_event_mismatches(value, [raw]) == []
    assert prose_errors(request(text=raw), value) == []
    assert corporate_event_mismatches(value.replace("노을테크", "다른제조사"), [raw])


@pytest.mark.parametrize("event", ["신주 발행", "유상증자", "회사채 발행"])
def test_listing_and_funding_cannot_supply_an_unreported_financing_method(event):
    source = "노을테크는 GDR을 상장해 12억 원의 자금을 조달했다."
    value = f"노을테크의 {event}은 투자 판단에 연결된다."
    assert corporate_event_mismatches(value, [source])


@pytest.mark.parametrize("role", ["은", "는", "의"])
def test_another_explicit_issuer_after_the_listing_keeps_its_own_funding(role):
    source = f"노을테크는 ADR을 상장했고 다른제조사{role} 자금 조달이 이어졌다."
    assert (
        corporate_event_mismatches("다른제조사의 자금 조달은 투자 판단에 연결된다.", [source]) == []
    )
    assert corporate_event_mismatches("노을테크의 자금 조달은 투자 판단에 연결된다.", [source])


def test_unselected_or_summary_only_financing_still_cannot_support_the_reason():
    raw = "노을테크는 GDR을 상장해 12억 원의 자금을 조달했다."
    value = "노을테크의 자금 조달은 투자 판단에 연결된다."
    source = request(text="노을테크는 실적을 발표했다.")
    finding = source.findings[0]
    finding.claims.append(
        finding.claims[0].model_copy(
            update={"id": "101:1", "text": raw, "evidence_sentence_ids": [1]}
        )
    )
    finding.sentences.append(finding.sentences[0].model_copy(update={"index": 1, "text": raw}))
    assert prose_errors(source, value)
    summary_only = deepcopy(source)
    summary_only.findings[0].claims[0].text = raw
    assert prose_errors(summary_only, value)


@pytest.mark.parametrize(
    "value",
    [
        "노을테크는 15억 원의 자금을 조달했다.",
        "노을테크는 회사채 발행으로 자금을 조달했다.",
        "다른제조사는 GDR 상장을 통해 자금을 조달했다.",
    ],
)
def test_full_guard_still_rejects_wrong_amount_method_or_issuer(value):
    source = request(text="노을테크는 GDR을 상장해 12억 원의 자금을 조달했다.")
    assert prose_errors(source, value)


def test_listing_plan_still_cannot_authenticate_completed_listing():
    source = request(text="노을테크는 GDR을 상장할 계획이다.")
    assert prose_errors(source, "노을테크는 GDR 상장을 완료했다.")


@pytest.mark.parametrize(
    "audience,work", [("CHIP_MAKER", "YIELD_CAPACITY"), ("MARKET_INVESTOR", "CAPEX_EXECUTION")]
)
def test_both_native_audiences_preserve_financing_and_reject_an_issuer_swap(audience, work):
    raw = (
        "노을테크는 GDR을 상장해 12억 원의 자금을 조달했다. "
        "확보한 자금은 반도체 생산라인 증설에 쓰일 예정이다."
    )
    source = request(audiences=(audience,), text=raw)
    value = payload(source, relation="CONDITIONAL")
    entry = value["assessments"][audience]["finding101"]
    entry.update(
        work=work,
        reason="노을테크의 자금 조달은 생산 투자 여건 검토와 연결된다.",
        condition="조달 자금이 계획대로 반도체 생산라인 증설에 투입되는 경우",
        impactScope="UNDETERMINED",
        impactBasis=None,
        urgencyState="UNDETERMINED",
        urgencyBasis=None,
    )
    before = source.model_dump_json(), deepcopy(value)

    native, mapped = _checked_map(source, value)

    assert native.evidence[audience][101].reason == entry["reason"]
    assert native.evidence[audience][101].relation_basis.quote == raw
    assert mapped.insights[0].assessments[0].basis_claim_ids == ["101:0"]
    assert before == (source.model_dump_json(), value)
    entry["reason"] = entry["reason"].replace("노을테크", "다른제조사")
    with pytest.raises(ValueError):
        _checked_map(source, value)
