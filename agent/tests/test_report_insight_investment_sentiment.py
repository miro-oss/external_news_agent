"""Investment sentiment is not a firm's capital investment event."""

import pytest
from test_report_insight_assessment import request

from app.llm.report_insight_synthesis_quality import (
    ReportSynthesisQualityValidationError,
    _frames,
    validate_synthesis_quality,
)
from app.schemas.report_insight import ReportInsightReduceAudience


@pytest.mark.parametrize(
    "text",
    [
        "주가 방향성의 변화는 투자심리와 주가에 영향을 줄 수 있다.",
        "삼성전자의 투자 심리는 실적 전망에 따라 변할 수 있다.",
        "Samsung Electronics' investment sentiment can change with earnings expectations.",
    ],
)
def test_sentiment_does_not_create_an_investment_actor_or_event_frame(text):
    assert _frames(text) == []


def insight(text):
    return ReportInsightReduceAudience(
        audience="MARKET_INVESTOR",
        headline="실적 전망과 시장 반응을 확인한다.",
        overview=[
            {
                "text": text,
                "basisClaimIds": ["101:0"],
                "assumption": "실적 전망의 변화가 시장 반응에 연결되는 경우",
            }
        ],
        implications=[],
        watchItems=[],
    )


def test_market_direction_does_not_borrow_a_capex_owner_from_the_same_references():
    source = request(
        audiences=("MARKET_INVESTOR",),
        text=("삼성전자의 설비투자가 확대됐다. TSMC의 주가 방향성은 투자심리와 관련된다."),
    )
    validate_synthesis_quality(
        insight("주가 방향성의 변화는 투자심리와 주가에 영향을 줄 수 있다."),
        source,
        ["101:0"],
    )
    validate_synthesis_quality(insight("삼성전자의 설비투자가 확대됐다."), source, ["101:0"])
    with pytest.raises(ReportSynthesisQualityValidationError) as caught:
        validate_synthesis_quality(insight("TSMC의 설비투자가 확대됐다."), source, ["101:0"])
    assert caught.value.error_kinds == ("report_synthesis_subject_mismatch",)
    assert caught.value.validation_issues[0].field == "overview[0].text"


def test_sentiment_in_a_clause_does_not_erase_a_separate_real_investment_event():
    frames = _frames("삼성전자의 투자심리와 TSMC의 설비투자가 증가했다.")
    assert len(frames) == 1
    assert (frames[0].actor, frames[0].family, frames[0].stage) == (
        "TSMC",
        "investment",
        2,
    )
