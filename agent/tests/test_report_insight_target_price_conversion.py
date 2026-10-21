"""Money conversion notes do not hide a source's completed target-price change."""

import pytest
from test_report_insight_assessment import request

from app.llm.report_insight_investment_policy import has_investment_advice
from app.llm.report_insight_service import _assessment_field_errors, _source_context

CAPTURED_SOURCE = (
    "영국계 금융회사 HSBC는 지난 13일 어도비의 투자의견을 '매수'에서 '보유'로 낮추고 "
    "목표주가를 310달러(약 41만원)에서 267달러(약 35만원)로 하향 조정했다."
)
CAPTURED_OBSERVATION = (
    "HSBC의 어도비 투자의견 하향과 목표주가 하향 조정은 기업가치 전망의 관찰 정보다."
)


def field_errors(value, source):
    data = request(audiences=("MARKET_INVESTOR",), text=source)
    original = data.model_dump_json()
    claims, evidence = _source_context(data)
    errors = _assessment_field_errors(
        value, ["101:0"], evidence, claims, data, audience="MARKET_INVESTOR"
    )
    assert data.model_dump_json() == original
    return errors


def test_completed_change_in_recorded_source_survives_currency_conversion_notes():
    assert not has_investment_advice(CAPTURED_OBSERVATION, CAPTURED_SOURCE)
    assert field_errors(CAPTURED_OBSERVATION, CAPTURED_SOURCE) == []


@pytest.mark.parametrize(
    "source,observation",
    [
        (
            "미즈호는 엔비디아 목표주가를 100유로(약 15만원)에서 "
            "120유로(약 18만원)로 상향 조정했다.",
            "미즈호의 엔비디아 목표주가 상향은 기업가치 전망의 관찰 정보다.",
        ),
        (
            "증권사는 목표주가를 50달러($50)에서 40달러(약 €35)로 낮췄다.",
            "증권사의 목표주가 하향 조정은 기업가치 전망의 관찰 정보다.",
        ),
        (
            "증권사는 목표주가를 90유로 (약 USD 100)에서 "
            "110유로 (약 KRW 170000)로 높였다.",
            "증권사의 목표주가 상향 조정은 기업가치 전망의 관찰 정보다.",
        ),
        (
            "증권사는 목표주가를 100달러（약 14만원）에서 80달러（약 11만원）로 낮췄다.",
            "증권사의 목표주가 하향 조정은 기업가치 전망의 관찰 정보다.",
        ),
    ],
)
def test_currency_notes_follow_general_money_forms_and_other_reporting_subjects(
    source, observation
):
    assert not has_investment_advice(observation, source)


@pytest.mark.parametrize(
    "value",
    [
        CAPTURED_SOURCE,
        "HSBC는 어도비 투자의견을 '매수'에서 '보유'로 낮췄다.",
        "HSBC는 어도비 목표주가를 310달러에서 267달러로 하향 조정했다.",
        "목표주가를 267달러(약 35만원)로 제시한다.",
        CAPTURED_OBSERVATION + " 지금 매수해야 한다.",
        CAPTURED_OBSERVATION + " 비중을 늘리세요.",
        "목표주가 하향을 권고한다.",
        "매수·매도 권고나 목표가 제시는 하지 않는다.",
    ],
)
def test_source_projection_never_permits_generated_price_amounts_ratings_or_advice(value):
    assert has_investment_advice(value, CAPTURED_SOURCE)
    assert field_errors(value, CAPTURED_SOURCE)


@pytest.mark.parametrize("amount", ["USD 120", "KRW 170000", "EUR 110"])
@pytest.mark.parametrize("conversion", ["(약 USD 120)", ""])
@pytest.mark.parametrize(
    "wording",
    ["목표주가 상향({amount})", "{amount}인 목표주가 상향", "{amount}인 목표가"],
)
def test_completed_change_does_not_authorize_an_iso_currency_price_in_interpretation(
    amount, conversion, wording
):
    source = f"증권사는 목표주가를 90유로에서 110유로{conversion}로 높였다."
    observation = "증권사의 목표주가 상향은 기업가치 전망의 관찰 정보다."
    assert not has_investment_advice(observation, source)
    value = f"증권사의 {wording.format(amount=amount)}는 기업가치 전망의 관찰 정보다."
    assert has_investment_advice(value, source)
    assert any(
        "report_expression_policy" in error.error_kinds for error in field_errors(value, source)
    )


def test_a_separate_monetary_fact_is_not_treated_as_a_target_price():
    source = (
        "증권사는 매출 전망을 USD 120으로 발표했다. "
        "목표주가를 90유로(약 USD 100)에서 110유로(약 USD 120)로 높였다."
    )
    value = "매출 전망은 USD 120이다. 목표주가 상향은 기업가치 전망의 관찰 정보다."
    assert not has_investment_advice(value, source)
    assert field_errors(value, source) == []


@pytest.mark.parametrize(
    "ending",
    [
        "상향 조정했다.",
        "하향 조정할 예정이다.",
        "하향 조정하지 않았다.",
        "하향 조정했다는 보도를 부인했다.",
        "하향 조정했다는 보도는 허위다.",
        "하향 조정했다고 볼 수 없다.",
        "하향 조정했다면 기업가치 전망이 달라질 수 있다.",
        "하향 조정했다고 가정하면 기업가치 전망이 달라진다.",
    ],
)
def test_conversion_notes_do_not_erase_direction_stage_or_denial(ending):
    source = "HSBC는 어도비 목표주가를 310달러(약 41만원)에서 267달러(약 35만원)로 " + ending
    assert has_investment_advice(CAPTURED_OBSERVATION, source)


@pytest.mark.parametrize(
    "note",
    [
        "약 35만원 권고",
        "약 35만원이라는 전망",
        "하향을 부인",
        "약 35만원; 지금 매수해야 한다",
        "약 35%",
        "약 35만원\n",
        "약 35만원(환산)",
        "약 35",
    ],
)
def test_arbitrary_parentheses_are_not_removed_from_the_source_change(note):
    source = f"HSBC는 어도비 목표주가를 310달러에서 267달러({note})로 하향 조정했다."
    assert has_investment_advice(CAPTURED_OBSERVATION, source)


def test_source_scope_and_factual_subject_checks_still_apply_to_the_observation():
    source = request(ids=(101, 202), audiences=("MARKET_INVESTOR",), text=CAPTURED_SOURCE)
    other = "도서관은 독서 모임의 참가 신청을 받는다."
    source.findings[1].claims[0].text = other
    source.findings[1].sentences[0].text = other
    original = source.model_dump_json()
    claims, evidence = _source_context(source)
    errors = _assessment_field_errors(
        CAPTURED_OBSERVATION,
        ["202:0"],
        evidence,
        claims,
        source,
        audience="MARKET_INVESTOR",
    )
    assert any("report_expression_policy" in error.error_kinds for error in errors)
    assert field_errors(CAPTURED_OBSERVATION.replace("HSBC", "삼성전자"), CAPTURED_SOURCE)
    assert source.model_dump_json() == original
