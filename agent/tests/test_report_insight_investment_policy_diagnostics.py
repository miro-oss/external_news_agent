"""Repair diagnostics distinguish missing source support without relaxing policy."""

import pytest

from app.llm.report_insight_investment_policy import (
    has_investment_advice,
    investment_policy_violation,
)

SOURCE = (
    "외국인은 10억원을 순매도했다. 삼성전자에 외국인 매도가 집중됐다. "
    "기관 매수세와 순매수 기록이 관찰됐다."
)
VALUE_ANALYSIS = "두 반도체 기업의 밸류에이션 차이를 분석했다."


@pytest.mark.parametrize(
    "value,source",
    [
        ("외국인 매도 등은 밸류에이션 판단에 연결된다.", VALUE_ANALYSIS),
        ("외국인 매도는 수급 관찰 정보다.", ""),
        ("기관 순매수 기록은 수급 관찰 정보다.", "외국인은 순매도했다."),
        ("기관 매수세는 수급 관찰 정보다.", "기관 매수 물량이 늘었다."),
        ("기관 매수 물량은 수급 관찰 정보다.", "기관 매수세가 늘었다."),
        ("삼성전자 매도 집중은 수급 관찰 정보다.", VALUE_ANALYSIS),
        ("현대차에 매도가 집중됐다.", "삼성전자에 매도가 집중됐다. 현대차 실적이 늘었다."),
        ("AMD 중심의 매도 집중은 관찰 정보다.", "삼성전자에 매도가 집중됐다. AMD 실적이 늘었다."),
        ("삼성전자에 매도가 집중됐다.", "삼성전자에 순매도가 집중됐다."),
        ("외국인 순매도(10억원)는 관찰 정보다.", "기관은 10억원을 순매도했다."),
        ("외국인 순매도(10억원)는 관찰 정보다.", "외국인은 10억원을 매도했다."),
        ("외국인 순매도(10억원)는 관찰 정보다.", VALUE_ANALYSIS),
    ],
)
def test_observational_form_without_matching_selected_source_has_specific_diagnostic(value, source):
    assert investment_policy_violation(value, source) == "trading_observation_source"
    assert has_investment_advice(value, source) is True


@pytest.mark.parametrize(
    "value",
    [
        "매수해야 한다.",
        "매도를 권한다.",
        "매수한다.",
        "삼성전자 매도 집중이 좋다.",
        "삼성전자 매도 집중 전략이 필요하다.",
        "삼성전자에 매도가 집중되어야 한다.",
        "삼성전자에 매도가 집중되어 야만 한다.",
        "외국인 순매도(10억원)는 실행하자.",
        "외국인 순매도(10억원)는 필요하다.",
        "외국인 순매도(전략)는 관찰 정보다.",
        "외국인 순매도(10억원 매수)는 관찰 정보다.",
        "목표가격을 10만원으로 제시한다.",
        "독자에 대한 매매 권고나 목표가격 제시는 포함하지 않는다.",
        "원문: 「매수해야 한다.」",
    ],
)
@pytest.mark.parametrize("source", [SOURCE, ""])
def test_advice_targets_unknown_forms_and_meta_denials_keep_wording_diagnostic(value, source):
    assert investment_policy_violation(value, source) == "investment_wording"
    assert has_investment_advice(value, source) is True


@pytest.mark.parametrize("source", [SOURCE, "", "증권사가 목표주가를 하향 조정했다."])
def test_target_observation_without_matching_source_has_its_own_diagnostic(source):
    value = "목표주가 상향 보도가 투자 판단과 연결된다."
    assert investment_policy_violation(value, source) == "target_price_observation_source"
    assert has_investment_advice(value, source) is True


@pytest.mark.parametrize(
    "bad_wording",
    [
        "매수해야 한다.",
        "목표가격을 제시한다.",
        "삼성전자 매도 집중이 좋다.",
        "외국인 순매도(10억원)는 실행하자.",
        "매수한다.",
    ],
)
@pytest.mark.parametrize("position", ["before", "after"])
def test_wording_violation_wins_even_when_another_trade_lacks_source(bad_wording, position):
    source_missing = "기관 순매수 기록은 관찰 정보다."
    value = (
        bad_wording + " " + source_missing
        if position == "before"
        else source_missing + " " + bad_wording
    )
    assert investment_policy_violation(value, VALUE_ANALYSIS) == "investment_wording"
    assert has_investment_advice(value, VALUE_ANALYSIS) is True


@pytest.mark.parametrize(
    "value,source",
    [
        (VALUE_ANALYSIS, ""),
        ("일정 확인을 권고한다.", ""),
        ("양산 목표가 유지된다.", ""),
        ("외국인 순매도(10억원)는 관찰 정보다.", SOURCE),
        ("삼성전자 중심의 매도 집중은 관찰 정보다.", SOURCE),
        ("매도가 삼성전자에 집중되어 수급을 점검한다.", SOURCE),
        ("기관 매수세는 관찰 정보다.", SOURCE),
        ("기관 순매수 기록은 관찰 정보다.", SOURCE),
        ("목표주가 조정 보도가 투자 판단과 연결된다.", "증권사가 목표주가를 상향 조정했다."),
    ],
)
def test_previously_valid_observations_have_no_diagnostic(value, source):
    assert investment_policy_violation(value, source) is None
    assert has_investment_advice(value, source) is False


def test_source_diagnostic_disappears_only_when_selected_source_support_is_added():
    value = "외국인 매도 등은 밸류에이션 판단에 연결된다."
    assert investment_policy_violation(value, VALUE_ANALYSIS) == "trading_observation_source"
    assert investment_policy_violation(value, VALUE_ANALYSIS + " 외국인 매도세가 이어졌다.") is None


def test_policy_meta_clause_is_not_a_required_repair_and_keeps_its_original_verdict():
    analysis = "두 반도체 기업의 밸류에이션 차이는 상대 가치 분석과 연결된다."
    denial = " 독자에 대한 매매 권고나 목표가격 제시는 포함하지 않는다."
    assert investment_policy_violation(analysis + denial, VALUE_ANALYSIS) == "investment_wording"
    assert investment_policy_violation(analysis, VALUE_ANALYSIS) is None
