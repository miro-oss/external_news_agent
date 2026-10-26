"""Nominal target changes remain selected-source reporting, never a proposal."""

import pytest
from test_report_insight_assessment import request

from app.llm.report_insight_investment_policy import has_investment_advice
from app.llm.report_insight_service import _assessment_field_errors, _source_context

SOURCE = (
    "12일(현지 시간) 투자정보 플랫폼 시킹알파에 따르면 비제이 라케시가 이끄는 "
    "미즈호증권 애널리스트팀은 최근 보고서에서 2030년 글로벌 서버 CPU의 "
    "총잠재시장(TAM·Total Addressable Market) 규모가 약 2090억 달러에 이를 것으로 내다봤다. "
    "엔비디아의 서버 CPU 관련 매출은 2025년 약 70억 달러에서 2028년 600억 달러로 "
    "8배 이상 확대될 것으로 전망했다. "
    "이 같은 전망을 토대로 미즈호는 주요 업체의 목표주가를 잇달아 상향 조정했다."
)
CAPTURED = (
    "미즈호의 TAM·엔비디아 매출 전망은 증권사 목표주가 상향의 근거로 작용해 "
    "투자지침·밸류에이션에 즉각적 영향을 미친다.",
    "미즈호의 엔비디아 서버 CPU 매출 확대 전망(2025년 약 70억 달러→2028년 600억 달러)을 "
    "근거로 보고서는 업계 주요사의 목표주가 상향으로 이어져 GUIDANCE 업무에 즉각적 "
    "프로젝트 영향이 발생한 것으로 판단한다; 다만 해당 수치와 향후 실현 여부는 미즈호의 "
    "전망에 근거한 예측이라는 점에서 불확실성이 존재함",
)
CAPTURED_NOMINAL = (
    "미즈호의 TAM·매출 전망과 이를 근거로 한 목표주가 상향은 투자자 의사결정과 투자 "
    "가이던스(증권사·애널리스트 레벨)에 즉각적 영향을 미치는 사실이다."
)
CAPTURED_PASSIVE_REPORT = (
    "미즈호의 분석을 근거로 주요 업체들의 목표주가가 상향 조정된 사실이 원문에 확인되어, "
    "GUIDANCE 관점에서 관련 기업의 투자전망·분석을 즉시 재검토해야 할 근거로 판단된다."
)
# run13 draft/repair, selected claims 8286:4, :8 and :0. The source's
# completed target change, not a fixed analytical phrasing, permits reporting.
CAPTURED_GENERAL_REPORTS = (
    "미즈호의 TAM·엔비디아 서버 CPU 매출 전망과 이에 따른 목표주가 상향은 투자자 관점의 "
    "밸류에이션·가이던스 업데이트 근거로 직접 작용한다.",
    "미즈호의 엔비디아 서버 CPU 매출 전망(2025년 약 70억 달러→2028년 600억 달러)과 "
    "글로벌 TAM 전망을 근거로 분석이 재평가되어 주요 업체들의 목표주가가 상향된 것으로 "
    "판단되며, 해당 전망은 보고서의 예측이라 실현 여부는 불확실하다.",
)
CAPTURED_DISCLAIMER = "원문은 구체적 매매 권고나 목표가격 수치를 제시하지 않는다."


@pytest.mark.parametrize(
    "value", [CAPTURED_NOMINAL, CAPTURED_PASSIVE_REPORT, *CAPTURED_GENERAL_REPORTS]
)
def test_captured_8286_nominal_or_passive_reporting_is_a_supported_observation(value):
    assert not has_investment_advice(value, SOURCE)
    assert has_investment_advice(value)
    source = request(audiences=("MARKET_INVESTOR",), text=SOURCE)
    claims, evidence = _source_context(source)
    assert (
        _assessment_field_errors(
            value, ["101:0"], evidence, claims, source, audience="MARKET_INVESTOR"
        )
        == []
    )


@pytest.mark.parametrize("before", [False, True])
def test_a_supported_report_cannot_exempt_the_captured_no_advice_disclaimer(before):
    value = (
        CAPTURED_DISCLAIMER + " " + CAPTURED_PASSIVE_REPORT
        if before
        else CAPTURED_PASSIVE_REPORT + " " + CAPTURED_DISCLAIMER
    )
    assert has_investment_advice(value, SOURCE)


@pytest.mark.parametrize("value", CAPTURED)
def test_captured_8286_causal_reports_are_not_investment_recommendations(value):
    # Exact run8 MAP-003 draft/repair, with selected claims 8286:0, :4 and :8.
    assert not has_investment_advice(value, SOURCE)
    source = request(audiences=("MARKET_INVESTOR",), text=SOURCE)
    claims, evidence = _source_context(source)
    assert (
        _assessment_field_errors(
            value, ["101:0"], evidence, claims, source, audience="MARKET_INVESTOR"
        )
        == []
    )


@pytest.mark.parametrize("value", [*CAPTURED, CAPTURED_NOMINAL, CAPTURED_PASSIVE_REPORT])
@pytest.mark.parametrize(
    "source",
    [
        "",
        "미즈호는 엔비디아 서버 CPU 매출이 확대될 것으로 전망했다.",
        "미즈호는 목표주가를 상향 조정할 예정이다.",
        "미즈호는 목표주가를 상향 조정하지 않았다.",
        "미즈호가 목표주가를 상향 조정했다는 보도를 부인했다.",
    ],
)
def test_causal_reporting_requires_a_completed_change_in_selected_source(value, source):
    assert has_investment_advice(value, source)


@pytest.mark.parametrize("value", [*CAPTURED, CAPTURED_NOMINAL, CAPTURED_PASSIVE_REPORT])
@pytest.mark.parametrize(
    "advice",
    [
        "지금 매수해야 한다.",
        "지금 매도하세요.",
        "비중을 늘리세요.",
        "목표주가를 10만원으로 제시한다.",
        "목표주가를 상향 조정한다.",
        "이를 100달러로 제시한다.",
    ],
)
@pytest.mark.parametrize("before", [False, True])
def test_causal_report_never_exempts_advice_or_an_independent_price(value, advice, before):
    combined = advice + " " + value if before else value + " " + advice
    assert has_investment_advice(combined, SOURCE)


@pytest.mark.parametrize(
    "value",
    [
        "보고서는 우리 목표주가 상향으로 이어져 GUIDANCE 업무에 영향을 미친다.",
        "보고서는 목표주가 100달러 상향으로 이어져 GUIDANCE 업무에 영향을 미친다.",
        "보고서는 목표주가 상향으로 이어져 GUIDANCE 업무에 100달러를 제시했다.",
        "보고서는 목표주가 상향의 필요성을 제시한다.",
    ],
)
def test_general_reporting_cannot_authorize_own_targets_prices_or_proposals(value):
    assert has_investment_advice(value, SOURCE)


@pytest.mark.parametrize(
    "price",
    [
        "$100",
        "₩100000",
        "€100",
        "¥100",
        "USD 100",
        "usd 100",
        "US$100",
        "KRW 100000",
        "EUR 100",
        "＄１００",
        "주당 $100",
        "약 10만원",
        "약 $100",
        "주당 약 USD 100",
    ],
)
@pytest.mark.parametrize("label", ["목표가: ", "목표가는 ", "목표가 "])
def test_bare_target_price_with_a_currency_prefix_or_approximation_is_still_a_price(price, label):
    target = label + price
    assert has_investment_advice(target, SOURCE)
    for report in (CAPTURED[0], "증권사의 목표주가 조정 보도는 관찰 정보다."):
        assert has_investment_advice(report + " " + target, SOURCE)
        assert has_investment_advice(target + " " + report, SOURCE)


@pytest.mark.parametrize(
    "value",
    [
        "양산 목표가 유지된다.",
        "양산 목표가 약 10만개다.",
        "목표가 약 3개월 앞당겨진다.",
    ],
)
def test_approximate_non_price_goals_are_not_currency_target_prices(value):
    assert not has_investment_advice(value, SOURCE)


@pytest.mark.parametrize("direction", ["상향", "하향", "인상", "인하", "조정", "변경"])
def test_nominal_reporting_keeps_source_and_direct_advice_checks(direction):
    value = f"목표주가 {direction}은 투자 판단에 직접적 영향을 미친다."
    source = f"증권사는 목표주가를 {direction}했다."
    assert not has_investment_advice(value, source)
    assert has_investment_advice(value, "증권사의 실적 전망이 발표됐다.")
    for value in [
        f"목표주가 {direction}은 필요하다.",
        f"우리 목표주가 {direction}은 투자 판단에 영향을 미친다.",
        f"목표주가 10만원 {direction}은 투자 판단에 영향을 미친다.",
    ]:
        assert has_investment_advice(value, SOURCE)


@pytest.mark.parametrize("verb", ["상향 조정된", "하향 조정된", "변경된", "상향한", "하향한"])
def test_completed_adnominal_change_needs_selected_source_and_cannot_propose_a_target(verb):
    value = f"목표주가가 {verb} 사실은 투자 판단의 관찰 정보다."
    direction = "하향" if verb.startswith("하향") else "변경" if verb == "변경된" else "상향"
    source = f"증권사는 목표주가를 {direction}했다."
    assert not has_investment_advice(value, source)
    assert has_investment_advice(value, "목표주가를 상향 조정할 예정이다.")
    assert has_investment_advice(value + " 비중을 늘려라.", SOURCE)
    assert has_investment_advice(f"목표주가가 {verb} 것으로 제시한다.", SOURCE)


@pytest.mark.parametrize(
    "source",
    [
        "미즈호가 목표주가를 상향 조정했다면 투자 판단이 달라질 수 있다.",
        "미즈호가 목표주가를 상향 조정했다고 가정하면 투자 판단이 달라진다.",
        "미즈호가 목표주가를 상향 조정했다는 가정에 따라 분석했다.",
        "미즈호가 목표주가를 상향 조정했다는 보도는 허위다.",
        "미즈호가 목표주가를 상향 조정했다는 보도는 사실무근이다.",
        "미즈호가 목표주가를 상향 조정했다는 것은 아니다.",
        "미즈호가 목표주가를 상향 조정했다고 볼 수 없다.",
        "미즈호가 목표주가를 상향 조정했다는 사실은 확인할 수 없다.",
        '"미즈호가 목표주가를 상향 조정했다."는 보도는 허위다.',
        '미즈호는 "목표주가를 상향 조정했다"는 보도를 부인했다.',
        "만약 미즈호가 목표주가를 상향 조정했다면 이를 재검토한다.",
        "미즈호가 목표주가를 상향 조정할 예정이다.",
    ],
)
@pytest.mark.parametrize("value", [CAPTURED_NOMINAL, CAPTURED_PASSIVE_REPORT])
def test_a_conditional_or_denied_source_cannot_authorize_target_change_reporting(source, value):
    assert has_investment_advice(value, source)


@pytest.mark.parametrize(
    "source",
    [
        "미즈호는 목표주가를 상향 조정했다.",
        "이 같은 전망을 토대로 미즈호는 목표주가를 상향 조정했다.",
        "미즈호는 목표주가를 상향 조정했다고 밝혔다.",
        '미즈호는 "목표주가를 상향 조정했다"고 밝혔다.',
        '미즈호는 "목표주가를 상향 조정했다."고 밝혔다.',
        "미즈호는 목표주가를 상향 조정했다. 다만 향후 실적은 불확실하다.",
        "미즈호는 목표주가를 상향 조정했으며, 향후 실적은 불확실하다.",
    ],
)
def test_affirmative_reports_and_unrelated_forecast_uncertainty_keep_completed_change(source):
    assert not has_investment_advice(CAPTURED_NOMINAL, source)


@pytest.mark.parametrize(
    "value",
    [
        "목표주가가 상향 조정된 사실은 아니다.",
        "목표주가가 상향 조정된 사실을 부인했다.",
        "목표주가가 상향 조정된 보도는 허위다.",
        "목표주가가 상향 조정된 사실은 확인할 수 없다.",
        "목표주가가 상향 조정했다는 사실은 아니다.",
    ],
)
def test_an_attached_denial_is_not_a_positive_change_observation(value):
    assert has_investment_advice(value, SOURCE)


@pytest.mark.parametrize(
    "source_direction,output_direction",
    [("상향", "하향"), ("하향", "상향"), ("인상", "인하"), ("조정", "상향")],
)
def test_observation_cannot_reverse_or_invent_the_selected_change_direction(
    source_direction, output_direction
):
    source = f"증권사는 목표주가를 {source_direction}했다."
    value = f"목표주가 {output_direction}은 투자 판단에 직접적 영향을 미친다."
    assert has_investment_advice(value, source)
    assert not has_investment_advice("목표주가 조정 보도는 관찰 정보다.", source)


def test_an_unrelated_forecast_direction_cannot_become_the_target_change_direction():
    value = "목표주가 조정 보도는 매출 상향 전망을 평가할 근거다."
    assert not has_investment_advice(value, "증권사는 목표주가를 조정했다.")


def test_selected_source_does_not_require_repeating_its_reporting_subject():
    value = "목표주가 상향의 근거로 작용해 투자 판단에 영향을 미친다."
    assert not has_investment_advice(value, SOURCE)
    assert has_investment_advice(value)


@pytest.mark.parametrize("price", ["2원", "10달러", "십만원", "20%", "10퍼센트"])
def test_unit_boundary_still_recognizes_price_particles(price):
    assert has_investment_advice(f"목표주가를 {price}로 제시한다.", SOURCE)


@pytest.mark.parametrize("price", ["2원", "10만원", "십만원", "10달러"])
@pytest.mark.parametrize("ending", ["이다", "인 가격이다", "입니다", "이고", "대로 본다", "선이다"])
def test_reporting_label_cannot_hide_a_price_with_a_nominal_or_copular_ending(price, ending):
    assert has_investment_advice(f"목표주가 조정 보도: {price}{ending}.", SOURCE)


@pytest.mark.parametrize("phrase", ["사실이 원문에 확인됐다", "이 원인이다", "이 원칙을 따른다"])
def test_a_korean_particle_or_compound_noun_is_not_a_won_amount(phrase):
    assert not has_investment_advice(f"목표주가 조정 보도는 {phrase}.", SOURCE)
