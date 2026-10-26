"""Adjacent unit notation keeps a decimal whole without reading product digits."""

from types import SimpleNamespace

import pytest

from app.core.evidence import _numbers, factual_mismatches
from app.llm.report_insight_service import _validate_prose

SOURCE = (
    "가온전력이 공급사와 20년 간 3.59기가와트 규모의 전력을 구매하는 "
    "장기 계약을 맺었다는 소식이 나왔습니다."
)
PROSE = (
    "가온전력의 20년·{quantity} 장기 전력구매 보도는 대규모 AI 전력 조달 사례로서 "
    "전력 계약·공급 관점의 검토 필요성을 제기한다."
)


@pytest.mark.parametrize("unit", ["GW", "MW", "kW", "W", "nm", "GB", "TB"])
def test_adjacent_unit_keeps_the_complete_decimal(unit):
    assert _numbers(f"3.59{unit}") == {"3.59"}
    assert _numbers(f"3.59 {unit}") == {"3.59"}
    assert factual_mismatches(f"3.95{unit}", f"3.59{unit}")


@pytest.mark.parametrize(
    "token", ["HBM4", "HBM4E", "ACME4", "OpenAI2", "PCIe5.0", "3.59GW2", "3.59GWExtra"]
)
def test_product_identifier_cannot_leak_a_partial_numeric_token(token):
    assert _numbers(token) == set()


@pytest.mark.parametrize(
    ("value", "expected"),
    [
        ("-3.59GW", {"-3.59"}),
        ("+3.59GW", {"3.59"}),
        ("3,590.25GW", {"3590.25"}),
        ("3.59GW와 20년", {"3.59", "20"}),
        ("3.59.", {"3.59"}),
        ("3.", {"3"}),
        ("HBM4E 12단", {"12"}),
    ],
)
def test_complete_numeric_boundaries_keep_signs_and_sentence_punctuation(value, expected):
    assert _numbers(value) == expected


@pytest.mark.parametrize("quantity", ["3.59GW", "3.59 GW", "3.59기가와트"])
def test_report_prose_accepts_supported_power_quantity_notation(quantity):
    value = PROSE.format(quantity=quantity)
    assert factual_mismatches(value, SOURCE) == []
    _validate_prose(
        [value],
        ["501:0"],
        {"501:0": SOURCE},
        {"501:0": SimpleNamespace(text=SOURCE)},
    )


@pytest.mark.parametrize("quantity", ["3.95GW", "35.9GW", "3GW", "3.95 GW"])
def test_report_prose_rejects_changed_digits_even_with_adjacent_unit(quantity):
    value = PROSE.format(quantity=quantity)
    errors = factual_mismatches(value, SOURCE)
    assert any("근거에서 확인되지 않는 숫자" in error for error in errors)
    with pytest.raises(ValueError, match="숫자"):
        _validate_prose(
            [value],
            ["501:0"],
            {"501:0": SOURCE},
            {"501:0": SimpleNamespace(text=SOURCE)},
        )
