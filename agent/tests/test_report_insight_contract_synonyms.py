"""Recorded contract synonyms keep fact state distinct from audience relevance."""

from types import SimpleNamespace

import pytest

from app.llm.report_insight_guard import factual_states
from app.llm.report_insight_service import _validate_prose

KOREAN_SOURCE = (
    "아이쓰리시스템은 해외 방산업체와 618억원(4560만달러) 규모 적외선 검출기 "
    "조립체 공급계약을 맺었다고 공시했다."
)
ENGLISH_SOURCE = (
    "Hammond Power Solutions Inc., a provider of magnetic and power electronic solutions, "
    "announced that it has signed a long-term lease for a new manufacturing facility "
    "in Fort Worth, Texas."
)


def validate(value, source):
    _validate_prose(
        [value], ["7874:2"], {"7874:2": source}, {"7874:2": SimpleNamespace(text=source)}
    )


@pytest.mark.parametrize(
    ("reason", "source"),
    [
        (
            "공시된 적외선 검출기 공급계약은 특정 방산업체와의 계약 사실로, 칩 제조사의 "
            "공정·수율·생산 일정과 직접적인 업무 연관성이 기사상에서 확인되지 않는다.",
            KOREAN_SOURCE,
        ),
        (
            "원문은 특정 기업의 적외선 검출기 조립체 공급계약 사실을 보도하나, 조립·방산 "
            "장비의 공급계약은 칩 제조의 공정·수율·생산일정 등 CHIP_MAKER 관점의 업무와 "
            "직접적 연관이 없어 무관하다.",
            KOREAN_SOURCE,
        ),
        (
            "원문은 해당 기업의 임대 계약 사실을 보도하지만, 칩 제조사의 공정·생산·자재 측면과의 "
            "직접적 연관성은 기사 내용만으로 확인되지 않는다.",
            ENGLISH_SOURCE,
        ),
        ("공급계약을 맺었다.", "공급계약을 맺었다고 공시했다."),
        (
            "삼성전자의 공급 계약 사실을 보도한다.",
            "삼성전자는 1.5억 달러 규모의 공급계약을 맺었다.",
        ),
    ],
)
def test_recorded_contract_facts_do_not_inherit_relevance_negation(reason, source):
    assert factual_states(source).get("contract") == {True}
    validate(reason, source)


@pytest.mark.parametrize(
    "source",
    [
        "공급계약을 맺을 계획이다.",
        "공급계약을 맺지 않았다.",
        "공급계약을 맺었다면 추가 공급을 검토한다.",
        "만약 공급계약을 맺었다고 가정하면 규모를 검토한다.",
        "공급계약을 맺었다는 보도를 부인했다.",
        "공급계약을 맺었다는 주장은 사실이 아니다.",
        '"공급계약을 맺었다"는 가상의 예시다.',
        "The company plans to sign a long-term lease.",
        "The company has not yet formally signed a long-term lease.",
        "If the company signed a long-term lease, it would open a facility.",
        "The company denied that it signed a long-term lease.",
        "The company signed a long-term lease proposal.",
        "The company signed a letter of intent for a long-term lease.",
        'The claim "the company signed a long-term lease" is false.',
        "The company signed a long-term lease but denied that statement.",
        "Has the company signed a lease?",
        "Suppose the company signed a lease.",
        "Assume the company signed a lease.",
        "공급계약을 맺었다고 주장했으나 아직 미체결이다.",
        "공급계약을 맺었다고 보도되지 않았다.",
        "공급계약을 맺었다고 알려지지 않았다.",
    ],
)
def test_plans_negation_proposals_and_denied_quotes_do_not_establish_a_contract(source):
    assert True not in factual_states(source).get("contract", set())
    with pytest.raises(ValueError):
        validate("공급 계약 사실을 보도한다.", source)


@pytest.mark.parametrize(
    ("reason", "source"),
    [
        ("공급계약을 맺지 않았다.", "공급계약을 맺었다."),
        ("계약이 취소됐다.", "공급계약을 맺었다."),
        ("삼성전자의 공급 계약 사실을 확인한다.", "SK하이닉스는 공급계약을 맺었다."),
        (
            "삼성전자의 공급 계약 사실을 확인한다.",
            "삼성전자는 공급 제안서를 받았다. SK하이닉스는 공급계약을 맺었다.",
        ),
        ("삼성전자는 공급계약을 맺었다.", "SK하이닉스는 공급계약을 맺었다."),
    ],
)
def test_reversed_states_and_different_contract_actors_still_fail(reason, source):
    with pytest.raises(ValueError):
        validate(reason, source)
