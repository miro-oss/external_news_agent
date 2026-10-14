"""Synthetic market/company boundaries retain source-specific amount checks."""

from copy import deepcopy

import pytest
from test_report_insight_assessment import payload, request, response
from test_report_insight_connection_evidence_dependency import full_validate
from test_report_insight_grounded_prose import _provider_response

from app.core.errors import OutputValidationError
from app.core.evidence import _companies, _normalize, factual_mismatches
from app.llm import report_insight_service as service
from app.schemas.report_insight import ReportInsightReduceOutput

AUDIENCE = "MARKET_INVESTOR"
AMOUNT_SOURCE = "유가증권시장의 거래대금은 100억원이다."
INDEX_SOURCE = "코스피는 6400선 아래로 하락했다."
REASON = "유가증권시장의 거래대금(100억원)과 코스피 6400선 아래 하락은 수급 관찰의 근거다."


def market_case():
    source = request(audiences=(AUDIENCE,), text=AMOUNT_SOURCE)
    finding = source.findings[0]
    finding.claims.append(
        finding.claims[0].model_copy(
            update={"id": "101:1", "text": INDEX_SOURCE, "evidence_sentence_ids": [1]}
        )
    )
    finding.sentences.append(
        finding.sentences[0].model_copy(update={"index": 1, "text": INDEX_SOURCE})
    )
    value = payload(source)
    value["assessments"][AUDIENCE]["finding101"].update(
        reason=REASON,
        impactScope="UNDETERMINED",
        impactBasis=None,
        urgencyState="MONITOR",
        urgencyBasis={"claimId": "101:1", "quote": INDEX_SOURCE},
    )
    return source, value


@pytest.mark.parametrize("market", ["유가증권시장", "유가증권 시장", "유가증권\t시장"])
@pytest.mark.parametrize("particle", ["", "에서", "에서는", "의", "으로"])
def test_only_complete_general_market_noun_is_not_a_company(market, particle):
    assert _companies(_normalize(f"{market}{particle} 거래 동향")) == set()


@pytest.mark.parametrize(
    "name",
    [
        "유가증권",
        "미래에셋증권",
        "유안타증권",
        "가온증권",
        "가온유가증권시장",
        "유가증권시장테크",
        "유가증권시장론",
    ],
)
def test_brokers_and_similarly_spelled_names_still_need_company_evidence(name):
    value = f"{name}의 거래 동향을 검토한다."
    errors = factual_mismatches(value, "유가증권시장의 거래 동향을 검토한다.")
    assert any("기업명" in error for error in errors)
    assert factual_mismatches(value, value) == []


@pytest.mark.parametrize(
    "name",
    [
        "회사 유가증권시장",
        "기업 유가증권 시장",
        "㈜유가증권시장",
        "유가증권시장 주식회사",
        "유가증권시장이라는 회사",
    ],
)
def test_explicit_organization_cannot_borrow_general_market_source(name):
    value = f"{name}의 거래대금은 100억원이다."
    assert any("기업명" in error for error in factual_mismatches(value, AMOUNT_SOURCE))
    assert factual_mismatches(value, value) == []


def test_separate_selected_trade_amount_and_index_level_are_not_one_company_quantity():
    source = (
        "이날 유가증권시장에서 외국인은 100억원을 순매도했다.\n"
        "외국인의 대규모 매도세에 코스피가 6400선 아래로 밀려났다."
    )
    value = "유가증권시장에서 외국인의 순매도(100억원)로 코스피가 6400선 아래로 하락했다."
    assert factual_mismatches(value, source) == []
    errors = factual_mismatches(value.replace("100억원", "101억원"), source)
    assert any("확인되지 않는 숫자" in error for error in errors)


@pytest.mark.parametrize(
    "value",
    [
        "유가증권시장의 외국인 순매도 100억원은 수급을 제약한다.",
        "유가증권시장의 외국인 순매도(100억원)는 수급을 제약한다.",
        "유가증권시장의 외국인 순매도는 수급 관찰의 근거다.",
    ],
    ids=["amount", "parenthesized-amount", "no-quantity"],
)
def test_general_market_identity_still_requires_the_selected_source(value):
    source_text = "코스닥시장에서 외국인은 100억원을 순매도했다."
    assert "근거에서 확인되지 않는 식별자: 유가증권시장" in factual_mismatches(value, source_text)
    source = request(audiences=(AUDIENCE,), text=source_text)
    proposed = payload(source)
    proposed["assessments"][AUDIENCE]["finding101"].update(
        reason=value,
        impactScope="UNDETERMINED",
        impactBasis=None,
        urgencyState="MONITOR",
    )
    with pytest.raises(OutputValidationError) as caught:
        full_validate(response(proposed, source), source)
    assert "source_binding" in {issue.rule for issue in caught.value.validation_issues}


@pytest.mark.parametrize(
    "other_source", ["회사 유가증권시장", "가온유가증권시장", "유가증권시장테크"]
)
def test_named_organizations_do_not_establish_a_general_market_identity(other_source):
    errors = factual_mismatches(
        "유가증권시장의 거래대금은 100억원이다.", f"{other_source}의 거래대금은 100억원이다."
    )
    assert "근거에서 확인되지 않는 식별자: 유가증권시장" in errors


@pytest.mark.parametrize("market", ["유가증권시장", "유가증권 시장"])
@pytest.mark.parametrize("kind", ["거래대금", "외국인 순매도"])
def test_market_amount_cannot_be_borrowed_from_another_market(market, kind):
    source = f"유가증권시장의 {kind}는 100억원이다. 코스닥시장의 {kind}는 200억원이다."
    assert factual_mismatches(f"{market}의 {kind}는 100억원이다.", source) == []
    errors = factual_mismatches(f"{market}의 {kind}는 200억원이다.", source)
    assert any("근거와 연결이 다른 숫자" in error for error in errors)


@pytest.mark.parametrize("quantity", ["억주", "만건", "%", ""])
def test_non_currency_market_quantities_remain_bound_to_the_same_market(quantity):
    source = f"유가증권시장의 거래량은 20{quantity}였다. 코스닥시장의 거래량은 10{quantity}였다."
    value = f"유가증권시장의 거래량은 10{quantity}였다."
    assert any("근거와 연결이 다른 숫자" in error for error in factual_mismatches(value, source))


@pytest.mark.parametrize(
    "source,value",
    [
        (
            AMOUNT_SOURCE + " 코스닥은 6400선 아래다.",
            REASON,
        ),
        (
            AMOUNT_SOURCE + " 코스피는 6300선 아래다. 외국인은 6400주를 매도했다.",
            REASON,
        ),
        (
            "2025년 " + AMOUNT_SOURCE + " 2026년 " + INDEX_SOURCE,
            "2025년 " + REASON,
        ),
        (
            AMOUNT_SOURCE + " " + INDEX_SOURCE,
            "유가증권시장의 거래량은 6400주로 코스피 6400선 하락과 함께 관찰됐다.",
        ),
        (
            AMOUNT_SOURCE + " " + INDEX_SOURCE,
            "유가증권시장에서 코스피 6400선박을 검토했다.",
        ),
    ],
    ids=[
        "different-index",
        "different-level",
        "different-year",
        "same-value-twice",
        "word-boundary",
    ],
)
def test_only_the_independently_sourced_index_occurrence_leaves_market_quantities(source, value):
    assert any("근거와 연결이 다른 숫자" in error for error in factual_mismatches(value, source))


@pytest.mark.parametrize(
    "source,value",
    [
        (
            "미래에셋증권의 거래대금은 100억원이다. 유안타증권의 거래대금은 200억원이다.",
            "유가증권시장에서 미래에셋증권의 거래대금은 200억원이다.",
        ),
        (
            "유가증권시장에서 미래에셋증권의 거래대금은 100억원이다. "
            "유가증권시장에서 유안타증권의 거래대금은 200억원이다.",
            "유가증권시장에서 미래에셋증권의 거래대금은 200억원이다.",
        ),
        (
            "2025년 유가증권시장의 거래대금은 100억원이다. "
            "2026년 유가증권시장의 거래대금은 200억원이다.",
            "2025년 유가증권시장의 거래대금은 200억원이다.",
        ),
        (
            "유가증권시장의 거래대금은 100억원이다. 코스피는 6400선 아래다.",
            "유가증권시장의 거래대금은 6400억원이다.",
        ),
    ],
    ids=["existing-company-context", "market-and-company", "market-and-year", "index-as-money"],
)
def test_market_exception_preserves_actor_date_and_unit_bindings(source, value):
    assert any("숫자" in error for error in factual_mismatches(value, source))


def validate_reduce(source, native, text):
    value = {
        "insights": [
            {
                "audience": AUDIENCE,
                "headline": "시장 거래 동향을 살펴본다.",
                "overview": [
                    {
                        "text": text,
                        "basisClaimIds": ["101:0", "101:1"],
                        "assumption": "거래 동향이 시장 전망의 판단 근거로 적용되는 경우",
                    }
                ],
                "implications": [],
                "watchItems": [],
            }
        ]
    }
    return service._validated_reduce_output(
        _provider_response(value),
        source,
        native.mapped,
        {AUDIENCE: ["101:0", "101:1"]},
        native_assessments=native.evidence,
        native_synthesis=ReportInsightReduceOutput.model_validate(value),
    )


def test_market_and_index_pass_full_native_and_reduce_without_rewriting():
    source, value = market_case()
    before = source.model_dump_json(), deepcopy(value)
    accepted = full_validate(response(value, source), source)
    assert accepted.evidence[AUDIENCE][101].reason == REASON
    assert validate_reduce(source, accepted, REASON).insights[0].overview[0].text == REASON
    assert before == (source.model_dump_json(), value)


@pytest.mark.parametrize(
    "reason,rule",
    [
        (REASON.replace("100억원", "101억원"), "unsupported_number"),
        (REASON.replace("6400선", "6500선"), "unsupported_number"),
        ("미래에셋증권의 " + REASON, "company"),
    ],
    ids=["wrong-amount", "wrong-index", "unsupported-broker"],
)
def test_full_native_and_reduce_keep_other_fact_guards(reason, rule):
    source, value = market_case()
    native = full_validate(response(value, source), source)
    value["assessments"][AUDIENCE]["finding101"]["reason"] = reason
    with pytest.raises(OutputValidationError) as assessment:
        full_validate(response(value, source), source)
    assert rule in {issue.rule for issue in assessment.value.validation_issues}
    with pytest.raises(OutputValidationError) as synthesis:
        validate_reduce(source, native, reason)
    assert rule in synthesis.value.fact_repair_kinds


def test_full_native_retains_market_amount_binding_after_company_exclusion():
    source = request(
        audiences=(AUDIENCE,),
        text="유가증권시장의 거래대금은 100억원이다. 코스닥시장의 거래대금은 200억원이다.",
    )
    value = payload(source)
    value["assessments"][AUDIENCE]["finding101"].update(
        reason="유가증권시장의 거래대금은 200억원이다.",
        impactScope="UNDETERMINED",
        impactBasis=None,
        urgencyState="MONITOR",
    )
    with pytest.raises(OutputValidationError) as caught:
        full_validate(response(value, source), source)
    assert "numeric_context" in {issue.rule for issue in caught.value.validation_issues}


def test_full_reduce_cannot_borrow_market_identity_from_another_finding():
    source, value = market_case()
    source.findings[0].claims[0].text = AMOUNT_SOURCE.replace("유가증권", "코스닥")
    source.findings[0].sentences[0].text = source.findings[0].claims[0].text
    entry = value["assessments"][AUDIENCE]["finding101"]
    entry["relationBasis"]["quote"] = source.findings[0].claims[0].text
    entry["reason"] = REASON.replace("유가증권", "코스닥")
    # The unselected finding must not satisfy the overview's selected refs.
    source.findings.extend(request(ids=(202,), audiences=(AUDIENCE,), text=AMOUNT_SOURCE).findings)
    value["assessments"][AUDIENCE].update(
        payload(source)["assessments"][AUDIENCE] | {"finding101": entry}
    )
    value["assessments"][AUDIENCE]["finding202"].update(
        reason="거래대금은 시장 전망을 검토할 근거다.",
        impactScope="UNDETERMINED",
        impactBasis=None,
        urgencyState="MONITOR",
    )
    native = full_validate(response(value, source), source)
    with pytest.raises(OutputValidationError) as caught:
        validate_reduce(source, native, REASON)
    assert "source_binding" in caught.value.fact_repair_kinds
