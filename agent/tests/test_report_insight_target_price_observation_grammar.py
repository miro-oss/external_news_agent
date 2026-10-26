"""Completed target observations keep ordinary Korean and currency grammar."""

import pytest
from test_report_insight_target_price_conversion import CAPTURED_SOURCE, field_errors

from app.llm.report_insight_investment_policy import investment_policy_violation


@pytest.mark.parametrize(
    "verb,direction",
    [("높인", "상향"), ("올린", "상향"), ("낮춘", "하향"), ("내린", "하향")],
)
@pytest.mark.parametrize("nominal", ["것은", "사실은"])
def test_completed_relative_clauses_are_source_scoped_observations(verb, direction, nominal):
    source = f"증권사는 목표주가를 {direction} 조정했다."
    value = f"증권사가 목표주가를 {verb} {nominal} 기업가치 전망을 검토할 관찰 정보다."
    assert investment_policy_violation(value, source) is None
    assert field_errors(value, source) == []
    opposite = "하향" if direction == "상향" else "상향"
    assert investment_policy_violation(value, f"증권사는 목표주가를 {opposite} 조정했다.") == (
        "target_price_observation_source"
    )


def test_natural_completed_relative_clause_uses_the_actual_selected_article_source():
    value = "HSBC가 어도비의 목표주가를 낮춘 것은 기업가치 전망을 검토할 관찰 정보다."
    assert investment_policy_violation(value, CAPTURED_SOURCE) is None
    assert field_errors(value, CAPTURED_SOURCE) == []


@pytest.mark.parametrize(
    "verb,direction",
    [
        ("높이었다", "상향"),
        ("올리었다", "상향"),
        ("낮추었다", "하향"),
        ("내리었다", "하향"),
    ],
)
def test_uncontracted_past_predicates_keep_their_source_direction(verb, direction):
    source = f"증권사는 목표주가를 {verb}."
    value = f"증권사의 목표주가 {direction}은 기업가치 전망의 관찰 정보다."
    assert investment_policy_violation(value, source) is None
    assert field_errors(value, source) == []
    completed = f"증권사가 목표주가를 {verb}는 사실은 관찰 정보다."
    assert investment_policy_violation(completed, source) is None


@pytest.mark.parametrize(
    "start,end",
    [
        ("USD 310", "USD 267"),
        ("310 USD", "267 USD"),
        ("EUR 220", "EUR 180"),
        ("220 euros", "180 euros"),
        ("KRW 180000", "KRW 150000"),
        ("180000 KRW", "150000 KRW"),
        ("USD 310(약 41만원)", "USD 267(약 35만원)"),
        ("310 USD(약 41만원)", "267 USD(약 35만원)"),
    ],
)
def test_original_currency_forms_use_shared_money_parsing_without_copying_the_price(start, end):
    source = f"증권사는 목표주가를 {start}에서 {end}로 낮추었다."
    value = "증권사의 목표주가 하향은 기업가치 전망의 관찰 정보다."
    assert investment_policy_violation(value, source) is None
    assert field_errors(value, source) == []
    for price in (f"목표주가 하향({end})", f"{end}인 목표주가", f"{end}인 목표가"):
        assert investment_policy_violation(price + "는 관찰 정보다.", source) == (
            "investment_wording"
        )


@pytest.mark.parametrize(
    "ending",
    [
        "낮출 예정이다.",
        "낮추지 않았다.",
        "낮추었다는 보도를 부인했다.",
        "낮추었다는 보도는 허위다.",
        "낮추었다면 전망이 달라진다.",
        "낮추었다고 가정하면 전망이 달라진다.",
        "낮춘 경우에만 전망을 바꾼다.",
        "낮춘 사실은 아니다.",
        "낮춘 사실을 부인했다.",
        "낮춘 것으로 가정한다.",
        "낮춘 것으로 예상된다.",
        "낮춘 것으로 추정된다.",
        "높이었다.",
    ],
)
def test_new_source_forms_do_not_authorize_unfinished_unasserted_or_opposite_changes(ending):
    source = f"증권사는 목표주가를 USD 310에서 USD 267로 {ending}"
    value = "증권사의 목표주가 하향은 기업가치 전망의 관찰 정보다."
    assert investment_policy_violation(value, source) == "target_price_observation_source"


@pytest.mark.parametrize(
    "value",
    [
        "증권사가 목표주가를 낮춘다면 전망이 달라진다.",
        "증권사가 목표주가를 낮춘 경우에는 전망을 바꾼다.",
        "증권사가 목표주가를 낮춘 사실은 아니다.",
        "증권사가 목표주가를 낮춘 것으로 가정한다.",
        "증권사가 목표주가를 낮춘 것으로 예상된다.",
        "증권사가 목표주가를 낮춘 사실을 부인했다.",
        "목표주가를 낮추어야 한다.",
        "목표주가를 낮춘 사실은 관찰 정보다. 지금 매수해야 한다.",
        "목표주가를 낮춘 사실은 관찰 정보다. 비중을 늘리세요.",
    ],
)
def test_output_conditions_denials_and_advice_never_become_completed_observations(value):
    source = "증권사는 목표주가를 USD 310에서 USD 267로 낮추었다."
    assert investment_policy_violation(value, source) == "investment_wording"


@pytest.mark.parametrize(
    "predicate",
    [
        "낮춘 것이라는 가정이다",
        "낮춘 사실이라는 전제다",
        "낮춘 것으로 보인다",
        "낮춘 것처럼 보인다",
        "낮춘 듯하다",
        "낮춘 듯이 보인다",
        "낮춘 듯 보인다",
        "낮춘 모양이다",
        "낮춘 것일 수 있다",
        "낮춘 적이 없다",
        "낮춘 사실이 없다",
        "낮춘 게 아니다",
        "낮춘 것 같다는 추측이다",
        "낮추었다는 추측이다",
        "낮춘 사실인 것이라는 전제다",
        "낮춘 것으로 볼 수 없다",
    ],
)
def test_attached_nominal_modality_or_denial_is_not_a_completed_source_or_output(predicate):
    uncertain = f"증권사는 목표주가를 {predicate}."
    observation = "증권사의 목표주가 하향은 기업가치 전망의 관찰 정보다."
    completed = "증권사는 목표주가를 낮추었다."
    assert investment_policy_violation(observation, uncertain) == "target_price_observation_source"
    assert field_errors(observation, uncertain)
    assert investment_policy_violation(uncertain, completed) == "investment_wording"
    assert field_errors(uncertain, completed)


@pytest.mark.parametrize(
    "value",
    [
        "목표주가를 낮춘 것은 추정치 변경에 따른 결과다.",
        "목표주가 하향은 전망에 영향을 미친다.",
        "목표주가 하향 보도는 전망을 검토할 관찰 정보다.",
        "목표주가를 낮춘 사실은 전망을 검토할 관찰 정보다.",
        "목표주가 하향은 전망 해석에 참고할 관찰 정보다.",
    ],
)
def test_a_completed_event_can_still_explain_an_estimate_change_without_claiming_uncertainty(value):
    source = "증권사는 목표주가를 낮추었다. 이 조정은 추정치 변경에 따른 결과다."
    assert investment_policy_violation(value, source) is None
    assert field_errors(value, source) == []


@pytest.mark.parametrize("bad", ["목표주가를 제시한다.", "목표주가는 USD 120이다.", "매수한다."])
@pytest.mark.parametrize("first", [True, False])
def test_wording_errors_win_over_target_source_gaps_regardless_of_field_order(bad, first):
    observation = "목표주가를 낮춘 사실은 관찰 정보다."
    value = bad + " " + observation if first else observation + " " + bad
    assert investment_policy_violation(value, "기관 순매수 기록이 관찰됐다.") == (
        "investment_wording"
    )
