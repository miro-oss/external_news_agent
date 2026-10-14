"""Captured flow reporting and monitoring suggestions are not trade advice."""

import pytest
from test_report_insight_assessment import request

from app.llm.report_insight_investment_policy import has_investment_advice
from app.llm.report_insight_service import _assessment_field_errors, _source_context

# report2005 run17 REVIEW-001 draft/repair, selected 8308:2 and 8308:4.
SOURCE = (
    "미국 반도체 기업에 분산 투자하는 ‘아이셰어스 반도체 ETF’(SOXX)는 같은 기간 "
    "5516만달러가 순매수되며 전체 3위에 올랐다. "
    "개별 반도체 기업에 대한 매수세도 두드러졌다. "
    "AMD는 4529만달러로 순매수 6위에 올랐고 인텔은 3436만달러로 9위, "
    "대만 TSMC는 3322만달러로 10위를 차지했다."
)
CAPTURED = (
    "해당 기사 문장은 SOXX 등 반도체 관련 ETF·개별 종목으로의 순매수 유입을 보도하며, "
    "이는 투자 수요 흐름 관점에서 투자지침(GUIDANCE)과 시장 수급 관찰에 직접 관련된다.",
    "보도된 SOXX의 5516만달러 순매수와 AMD·인텔·TSMC의 개별 순매수 규모는 해당 기간 "
    "반도체 관련 자산에 집중된 매수 흐름을 시사하나, 지속성·영향 범위에 관한 추가 정보가 "
    "없어 모니터링을 권하는 근거로 판단된다.",
)


@pytest.mark.parametrize("value", CAPTURED)
def test_captured_trade_flows_and_monitoring_keep_selected_source_validation(value):
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


def test_a_monitoring_suggestion_cannot_exempt_an_added_buy_recommendation():
    assert has_investment_advice(CAPTURED[1] + " 지금 매수를 권한다.", SOURCE)
