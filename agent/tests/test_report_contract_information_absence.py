"""Unreported contract facts are local information gaps, not executed contracts."""

from types import SimpleNamespace

import pytest

from app.llm.report_insight_guard import factual_states
from app.llm.report_insight_service import _validate_prose

SOURCE = "정부는 팹 준공을 목표로 전력·용수·인력 공급 기반을 구축하고 있다."


def validate(value, source=SOURCE):
    _validate_prose([value], ["101:0"], {"101:0": source}, {"101:0": SimpleNamespace(text=source)})


@pytest.mark.parametrize(
    "value",
    [
        "팹 조성과 기반 구축은 장비 수요를 유발할 수 있으나, 기사에는 장비 발주·계약 사실이 "
        "명시되지 않아 장비 공급 연결은 미확인 전제가 필요하다.",
        "계약 사실이 원문에 명시되지 않았다.",
        "계약 사실은 기사에 별도로 제시되지 않았습니다.",
        "계약 사실이 아직 확인되지 않아 직접 연결은 미확인이다.",
        "계약 사실이 명시되어 있지 않습니다.",
        "기사는 계약 사실을 언급하지 않는다.",
        "계약 사실이 명시되지 않았고 영향 규모는 미확인이다.",
        "계약 사실이 명시되지 않았으나 영향 규모도 미확인이다.",
    ],
)
def test_unreported_contract_information_does_not_assert_execution_or_nonexistence(value):
    assert "contract" not in factual_states(value)
    validate(value)


@pytest.mark.parametrize(
    "value,expected",
    [
        ("계약 사실이 있다.", True),
        ("계약 사실이 명시됐다.", True),
        ("계약을 체결했다.", True),
        ("계약이 없다.", False),
        ("계약 사실이 없다.", False),
        ("계약 사실은 없다고 밝혔다.", False),
    ],
)
def test_asserted_contract_presence_and_absence_still_need_matching_source(value, expected):
    assert factual_states(value).get("contract") == {expected}
    with pytest.raises(ValueError):
        validate(value)


@pytest.mark.parametrize(
    "value,expected",
    [
        ("계약 사실이 명시되지 않았으나 실제 계약을 체결했다.", True),
        ("계약을 체결했으며 계약 사실이 명시되지 않았다.", True),
        ("계약 사실이 명시되지 않았고 실제 계약은 없다.", False),
        ("계약 사실이 명시되지 않았다는 뜻은 아니다.", True),
        ("계약 사실이 명시되지 않은 것은 아니다.", True),
        ("계약 사실이 명시되지 않았다고 볼 수 없다.", True),
    ],
)
def test_local_information_gap_cannot_hide_other_assertions_or_double_negation(value, expected):
    assert factual_states(value).get("contract") == {expected}
    with pytest.raises(ValueError):
        validate(value)


def test_unsupported_number_survives_information_absence():
    with pytest.raises(ValueError, match="숫자"):
        validate("계약 사실이 명시되지 않았으며 장비 수요는 9999대이다.")


def test_contract_nonexistence_is_distinct_from_an_unreported_contract():
    validate("계약 사실이 없다.", "계약이 없다.")
    with pytest.raises(ValueError, match="부정 표현"):
        validate("계약 사실이 없다.", "회사는 공급 계약을 체결했다.")


@pytest.mark.parametrize(
    "value",
    [
        "계약 사실이 없지는 않다.",
        "계약 사실이 없다는 뜻은 아니다.",
        "계약 사실이 없어지지 않았다.",
        "계약 사실이 없다고 볼 수 없다.",
    ],
)
def test_negated_absence_does_not_become_asserted_nonexistence(value):
    assert False not in factual_states(value).get("contract", set())
    validate(value, "회사는 공급 계약을 체결했다.")
