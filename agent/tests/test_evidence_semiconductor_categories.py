"""Industry categories must not become invented company assertions."""

from types import SimpleNamespace

import pytest

from app.core.evidence import factual_mismatches
from app.llm.report_insight_service import _validate_prose


@pytest.mark.parametrize("prefix", ["전력", "시스템", "화합물", "메모리", "비메모리"])
def test_semiconductor_category_spacing_does_not_create_a_company(prefix):
    source = f"{prefix} 반도체 소자의 설계를 다룬다."
    assert factual_mismatches(f"{prefix}반도체 소자의 설계를 다룬다.", source) == []


@pytest.mark.parametrize(
    ("company", "expected"),
    [
        ("서울반도체", "서울반도체"),
        ("매그나칩반도체", "매그나칩반도체"),
        ("대덕전자", "대덕전자"),
        ("삼성전자", "삼성전자"),
        ("대만반도체", "TSMC"),
        ("가온전력반도체", "가온전력반도체"),
    ],
)
def test_category_exclusion_preserves_known_and_suffix_detected_companies(company, expected):
    value = f"{company}의 전력반도체 소자 설계를 검토한다."
    mismatches = factual_mismatches(value, "전력 반도체 소자의 설계를 검토한다.")
    assert mismatches == [f"근거에서 확인되지 않는 기업명: {expected}"]


POWER_SOURCE = "The supplier presented low-voltage GaN discrete power transistors."


def validate_report(value):
    _validate_prose(
        [value],
        ["101:0"],
        {"101:0": POWER_SOURCE},
        {"101:0": SimpleNamespace(text=POWER_SOURCE)},
    )


def test_power_transistor_report_interpretation_does_not_invent_a_company():
    validate_report("저전압 GaN 소자는 전력반도체·시스템 설계의 기술 검토 대상이다.")


@pytest.mark.parametrize(
    ("value", "kind"),
    [
        ("서울반도체의 전력반도체 소자를 검토한다.", "기업명: 서울반도체"),
        ("전력반도체 소자 200개를 검토한다.", "숫자: 200"),
        ("내년 전력반도체 소자 설계를 검토한다.", "날짜 표현: 내년"),
    ],
)
def test_category_does_not_suppress_other_report_fact_errors(value, kind):
    assert any(kind in error for error in factual_mismatches(value, POWER_SOURCE))
    with pytest.raises(ValueError, match=kind):
        validate_report(value)
