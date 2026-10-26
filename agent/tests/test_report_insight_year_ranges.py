"""Adjacent year ranges retain their shared source target and all factual checks."""

import pytest

from app.core.evidence import factual_mismatches
from app.llm.report_insight_guard import report_prose_mismatches
from app.llm.report_insight_year_ranges import supported_year_range_context

SOURCE = (
    "1일(현지시간) 산제이 메흐로트라(Sanjay Mehrotra) 마이크론 최고경영자(CEO) 겸 회장이 2026 "
    "회계연도 실적 발표 콘퍼런스콜을 통해 인공지능(AI) 인프라 수요 폭증으로 인한 "
    "램(RAM) 공급 부족 사태가 "
    "2028년 이후까지 이어질 것이라고 경고했다.\n"
    "산제이 메흐로트라 CEO는 램 공급 부족이 2028년 이후까지 이어질 것이라고 경고했다.\n"
    '[디지털데일리 김문기기자] "2027년은 물론 2028년에도 수요가 공급을 초과할 것으로 '
    "보고 있으며 실제로 "
    "2026년 대비 2027년과 2028년에 업계 수급 불균형이 더욱 심화될 것이다.\n"
    "2026년 대비 2027년과 2028년에 업계 수급 불균형이 심화될 것으로 보고 있다.\n"
)
TRIGGER = (
    "공급사 또는 시장에서 2027~2028년 내 공급 개선을 시사하는 구체적 증산 계획이나 "
    "장기 공급계약 발표가 나오면 위험 완화로 판단 변경."
)


def test_recorded_trigger_range_is_not_a_quantity_owned_by_2028():
    assert "근거와 연결이 다른 숫자: 2028년→2027" in factual_mismatches(TRIGGER, SOURCE)
    assert supported_year_range_context(TRIGGER, SOURCE)


@pytest.mark.parametrize("separator", ["~", "∼", "–", "-"])
def test_adjacent_shared_years_support_equivalent_range(separator):
    value = f"2027{separator}2028년 공급 전망을 관찰한다."
    assert supported_year_range_context(value, "2027년과 2028년에 공급 부족이 이어질 전망이다.")


@pytest.mark.parametrize(
    "value,source",
    [
        (TRIGGER, "2027년 수요가 증가한다. 2028년 공급이 감소한다."),
        (TRIGGER, "2027년 수요 증가와 2028년 공급 감소를 전망한다."),
        (TRIGGER, "2027년 삼성전자 공급은 늘고 2028년 LG전자 공급은 감소한다."),
        (TRIGGER, "2027년과 2028년에 생산량이 증가한다. 공급 부족은 별도의 전망이다."),
        ("2027~2028년 매출 전망을 관찰한다.", "2027년과 2028년에 공급 부족이 이어진다."),
        (
            "삼성전자는 2027~2028년 공급을 늘릴 전망이다.",
            "2027년과 2028년에 삼성전자와 LG전자가 각각 공급을 늘릴 전망이다.",
        ),
        (
            "2027~2028년 공급 개선을 관찰한다.",
            "2027년과 2028년에 수요와 공급이 각각 늘어날 전망이다.",
        ),
        (
            "LG전자는 2027~2028년 공급을 늘릴 전망이다.",
            "삼성전자는 2027년과 2028년에 공급을 늘릴 전망이다. LG전자는 다른 사업을 운영한다.",
        ),
        ("2027~2029년 공급 전망을 관찰한다.", "2027년과 2029년에 공급 부족이 이어진다."),
        ("2028~2027년 공급 전망을 관찰한다.", SOURCE),
        ("2027~2028년 공급량은 20% 늘어난다.", "2027년과 2028년에 공급량은 10% 늘어난다."),
        ("2027~2028년 공급 비용은 100달러다.", "2027년과 2028년에 공급 비용은 100유로다."),
        ("2027~2028년 공급량은 10%다.", "2027년 공급량은 10%이고 2028년 공급량은 20%다."),
    ],
)
def test_range_never_borrows_years_actors_targets_values_or_event_states(value, source):
    assert not supported_year_range_context(value, source)


@pytest.mark.parametrize(
    "source",
    [
        "2027년과 2028년에 공급 중단 계획을 검토한다.",
        "2027년과 2028년에 공급이 중단되지 않았다.",
    ],
)
def test_range_numeric_exception_preserves_report_event_and_polarity_guards(source):
    value = "2027~2028년 공급이 중단됐다."
    errors = report_prose_mismatches(
        value, source, factual_mismatches(value, source), modality_reason=None
    )
    assert errors
    assert any("부정" in error or "완료·착수·계약·중단" in error for error in errors)
