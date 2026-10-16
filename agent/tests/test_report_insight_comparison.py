"""Comparison wording never substitutes for a matching raw-source reference."""

import pytest
from test_report_insight_assessment import request

from app.llm.report_insight_comparison import comparison_direction_conflict
from app.llm.report_insight_service import _prose_validation_errors, _source_context

AUDIENCES = ("CHIP_MAKER", "EQUIPMENT_MAKER", "MARKET_INVESTOR", "IT_INFRA")


def errors(source, candidate, *, audience="CHIP_MAKER", summary=None, conditional=False):
    req = request(text=source, audiences=(audience,))
    if summary is not None:
        req.findings[0].claims[0].text = summary
    claims, evidence = _source_context(req)
    return _prose_validation_errors(
        [candidate], ["101:0"], evidence, claims, request=req, conditional=conditional
    )


@pytest.mark.parametrize("audience", AUDIENCES)
@pytest.mark.parametrize("reference", ["지난 주 대비", "지난주보다", "직전 주에 비해", "전주 대비"])
def test_calendar_week_aliases_do_not_require_a_previous_report(reference, audience):
    assert not errors(
        f"생산량은 {reference} 증가했다.", "생산량은 전주 대비 증가했다.", audience=audience
    )


@pytest.mark.parametrize(
    "source,candidate",
    [
        ("생산량이 증가했다.", "생산량은 전주 대비 증가했다."),
        ("생산량은 일주일 전보다 증가했다.", "생산량은 전주 대비 증가했다."),
        ("생산량은 전월 대비 증가했다.", "생산량은 전주 대비 증가했다."),
        ("생산량은 지지난 주 대비 증가했다.", "생산량은 전주 대비 증가했다."),
        ("생산량은 전전주 대비 증가했다.", "생산량은 전주 대비 증가했다."),
        ("생산량은 전주 대비 증가했다.", "생산량은 지지난 주 대비 증가했다."),
        ("생산량은 전주 대비 증가했다.", "지난 보고서보다 생산량이 증가했다."),
        ("생산량은 전주 대비 증가했다.", "생산량 증가가 처음으로 확인됐다."),
        ("지난 보고서에 생산량이 나왔다.", "생산량은 전주 대비 증가했다."),
        ("생산량은 전주 대비 증가했다.", "생산량이 전주 대비 새롭게 부각됐다."),
    ],
)
def test_different_reference_or_newness_cannot_borrow_another_comparison(source, candidate):
    assert any("기준선" in str(error) for error in errors(source, candidate))


def test_summary_alone_cannot_authorize_a_comparison_missing_from_raw_source():
    assert errors(
        "생산량이 증가했다.",
        "생산량은 전주 대비 증가했다.",
        summary="생산량은 전주 대비 증가했다.",
    )


@pytest.mark.parametrize(
    "candidate", ["생산량은 전주 대비 937% 증가했다.", "생산량은 전주 대비 감소했다."]
)
def test_supported_reference_does_not_disable_numeric_or_direction_guards(candidate):
    assert errors("생산량은 지난 주 대비 증가했다.", candidate)


@pytest.mark.parametrize("candidate,source", [
    ("생산량은 전주 대비 감소했다.", "판매량은 지난 주 대비 증가했다."),
    ("생산량은 전주 대비 감소했다.", "생산량은 지난 주 대비 증가하지 않았다."),
    ("생산량은 전주 대비 감소했다.", "생산량은 지난 주 대비 증가할 전망이다."),
    ("생산량은 전주 대비 감소했다.", "생산량은 전월 대비 증가했다."),
    ("생산량은 전주 대비 감소했다.", "생산량은 전주 대비 증가했다. 생산량은 전주 대비 감소했다."),
])
def test_other_context_or_conflicting_sources_are_not_certain_conflicts(candidate, source):
    assert not comparison_direction_conflict(candidate, source)


def test_counterfactual_observation_can_have_opposite_direction_to_source():
    assert not errors(
        "생산량은 지난 주 대비 증가했다.",
        "생산량은 전주 대비 감소했다.",
        conditional=True,
    )
