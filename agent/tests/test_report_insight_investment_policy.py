"""Trading observations remain source-bound without exempting recommendations."""

import pytest

from app.llm.report_insight_investment_policy import has_investment_advice

# Public article excerpts and exact generated reasons from report 2005's v42
# offline replay; the tests perform no network or model calls.
SOURCE_8338 = (
    "외국인의 대규모 매도세에 코스피가 6400선 아래로 밀려났다. "
    "증권가에서는 반도체 업황보다 외국인의 매도 물량을 받아낼 매수 여력이 "
    "부족하다는 점에 주목하고 있다. "
    "외국인 매도세가 주가 급락으로 이어진 배경으로는 이를 받아낼 매수 주체가 "
    "부족하다는 점도 지목된다. "
    "이날 유가증권시장에서 외국인은 2조5089억원(KRX·NXT 합산)을 순매도했다."
)
REASON_8338 = (
    "증권가 지적대로 외국인 대규모 매도와 이를 받아낼 매수 주체의 부족이 시장 수급의 "
    "실질적 제약으로 작용해 코스피 하락의 핵심 요인으로 판단된다."
)
SOURCE_8308 = (
    "미국 반도체 기업에 분산 투자하는 ‘아이셰어스 반도체 ETF’(SOXX)는 같은 기간 "
    "5516만달러가 순매수되며 전체 3위에 올랐다. "
    "개별 반도체 기업에 대한 매수세도 두드러졌다. "
    "AMD는 4529만달러로 순매수 6위에 올랐고 인텔은 3436만달러로 9위, "
    "대만 TSMC는 3322만달러로 10위를 차지했다."
)
REASON_8308 = (
    "SOXX와 개별 반도체 종목의 순매수 기록은 특정 반도체 관련 자금의 유입을 보여주며, "
    "일부 수급 면에서 제한적 준비·대응 범위를 만드는 정황으로 읽힌다."
)


@pytest.mark.parametrize(
    "reason,source",
    [
        (REASON_8338, SOURCE_8338),
        (REASON_8308, SOURCE_8308),
        (
            "기사에서 외국인 대규모 매도와 매수 여력 부족을 연결해 수급(수요·공급) 제약이 "
            "투자 심리에 즉시 영향을 줬다고 서술한다.",
            SOURCE_8338,
        ),
        (
            "ETF 및 개별 반도체 종목에 대한 순매수 관찰은 투자자 수요 측면의 수급 정보로서 "
            "수급 제약·수요 분석 업무와 직접 연결된다.",
            SOURCE_8308,
        ),
    ],
)
def test_recorded_v42_market_observations_are_not_investment_advice(reason, source):
    assert not has_investment_advice(reason, source)
    assert has_investment_advice(reason)


@pytest.mark.parametrize(
    "value,source",
    [
        ("기관 순매수 기록은 수급 관찰과 연결된다.", "기관 순매수가 이어진다고 밝혔다."),
        ("기관 순매수가 대응 조건으로 확인되는 경우", "기관 순매수가 이어진다고 밝혔다."),
        ("외국인의 순매도 규모가 증가했다.", "외국인은 순매도했다."),
        ("기관의 매수세가 이어졌다.", "기관 매수세가 증가했다."),
        ("외국인 매도세가 수급을 제약한다.", SOURCE_8338),
        ("외국인의 대규모 매도는 수급 변화와 연결된다.", SOURCE_8338),
        ("매도 물량을 받아낼 매수 여력이 부족하다.", SOURCE_8338),
        ("외국인 매도와 매수 주체 부족을 살펴본다.", SOURCE_8338),
        ("순매수 흐름을 확인해야 한다.", SOURCE_8308),
        ("순매도 추이의 지속성을 점검할 필요가 있다.", SOURCE_8338),
        ("매수세가 강해졌는지 확인하는 것이 좋다.", SOURCE_8308),
        ("순매수 기록과 매도세를 함께 살펴본다.", SOURCE_8308 + SOURCE_8338),
    ],
)
def test_supported_observations_and_observation_conditions_remain_allowed(value, source):
    assert not has_investment_advice(value, source)


@pytest.mark.parametrize(
    "value,source",
    [
        ("기관 순매수 기록이 증가했다.", "기관은 실적을 발표했다."),
        ("기관 순매수 기록이 증가했다.", "기관 순매도가 증가했다."),
        ("기관 순매도 기록이 증가했다.", "기관 순매수가 증가했다."),
        ("기관 순매수 기록이 증가했다.", "기관 매수세가 증가했다."),
        ("매수 주체가 부족하다.", "매도 주체가 늘었다."),
        ("매수 여력이 부족하다.", "매도 여력이 부족하다."),
        ("외국인 대규모 매도가 나타났다.", "외국인은 실적을 발표했다."),
        ("순매수 기록과 매도세가 나타났다.", "기관 순매수가 증가했다."),
    ],
)
def test_each_trade_mention_requires_corresponding_selected_source_wording(value, source):
    assert has_investment_advice(value, source)


@pytest.mark.parametrize(
    "advice",
    [
        "지금 매수해야 한다.",
        "매도해야 한다.",
        "매수하세요.",
        "매도하라.",
        "매수에 나서자.",
        "매수 검토가 필요하다.",
        "매수 기회다.",
        "매도할 시점이다.",
        "순매수를 추천한다.",
        "순매도를 권합니다.",
        "기관 순매수가 증가해 비중 확대를 권한다.",
        "기관 순매수 흐름에 맞춰 비중을 늘리세요.",
        "매수세가 강해 지금 진입하는 것이 좋다.",
        "순매도세 감소를 확인하면 담아야 한다.",
        "순매수 확대가 필요하다.",
        "순매수를 하는 편이 유리하다.",
        "순매도는 최선의 전략이다.",
        "기관 순매수 이후 지금 사세요.",
        "순매수를 권해 드린다.",
        "순매수 바랍니다.",
        "순매수하길 바란다.",
        "순매수하시는 것이 유리하다.",
        "매수세가 강하니 비중을 늘릴 것을 제안한다.",
        "기관 순매수가 늘었다. 저점에서 담는 편이 좋다.",
        "기관 순매수가 늘었다. 포지션을 키워라.",
        "순매수할 만하다.",
        "순매수해도 좋다.",
        "순매수 해보자.",
        "기관 순매수가 늘었으니 비중 확대가 합리적이다.",
    ],
)
def test_recommendations_remain_rejected_even_with_trade_support(advice):
    source = SOURCE_8338 + SOURCE_8308 + " 기관 순매수 확대와 비중 확대를 추천했다."
    assert has_investment_advice(advice, source)


@pytest.mark.parametrize("value", ["순매수 전략", "순매수하길", "순매도하시는", "순매수 최적화"])
def test_source_support_does_not_whitelist_unknown_net_trading_contexts(value):
    assert has_investment_advice(value, SOURCE_8308 + SOURCE_8338)


@pytest.mark.parametrize("position", ["before", "after", "same_sentence"])
@pytest.mark.parametrize("advice", ["매수해야 한다", "비중 확대를 권한다", "담아야 한다"])
def test_an_observation_does_not_exempt_a_recommendation_elsewhere_in_the_field(position, advice):
    observation = "기관 순매수 기록이 늘었다"
    value = {
        "before": advice + ". " + observation + ".",
        "after": observation + ". " + advice + ".",
        "same_sentence": observation + "며 " + advice + ".",
    }[position]
    assert has_investment_advice(value, SOURCE_8308)


@pytest.mark.parametrize(
    "value",
    [
        "목표가를 10만원으로 제시한다.",
        "목표가는 상향 조정한다.",
        "목표가 하향이 필요하다.",
        "목표가격은 10만원이다.",
        "목표주가를 10만원으로 제시한다.",
        "목표가 １０만원을 제시한다.",
        "목표가: 10만원",
        "목표가는 십만원으로 제시한다.",
        "목표가를 주당 10만원으로 제시한다.",
    ],
)
def test_price_targets_remain_rejected_even_when_the_source_mentions_them(value):
    assert has_investment_advice(value, value)


@pytest.mark.parametrize(
    "value",
    [
        "양산 목표가 유지되는 경우 생산 준비를 살펴본다.",
        "발표한 목표가 유지되고 공정 검증이 필요한 경우",
        "순매수 기록은 관찰하되 양산 목표가 유지되는지 살펴본다.",
    ],
)
def test_a_goal_as_the_subject_of_a_sentence_is_not_a_price_target(value):
    assert not has_investment_advice(value, SOURCE_8308)


@pytest.mark.parametrize(
    "value",
    [
        "원문: 「지금 매수해야 한다.」 해석: 수급을 확인한다.",
        "원문: 「기관 순매수가 늘었다.」 해석: 비중을 늘리세요.",
        "기관 순매수가 늘었다. ‘매도해야 한다’는 판단이다.",
    ],
)
def test_provider_written_quote_labels_do_not_exempt_recommendations(value):
    assert has_investment_advice(value, SOURCE_8338 + SOURCE_8308)


def test_ordinary_business_analysis_remains_unchanged_without_trade_words():
    assert not has_investment_advice("공급 제약이 생산 준비 일정과 연결된다.")
    # Recommendations without any trade/price wording were outside the old
    # lexical guard; this bounded change does not define a new general policy.
    assert not has_investment_advice("일정 확인을 권고한다.")
