"""Recorded report prose failures retain source-bound numbers and event states."""

from types import SimpleNamespace

import pytest

from app.llm.report_insight_guard import factual_states
from app.llm.report_insight_service import _validate_prose


def validate(value, source):
    _validate_prose([value], ["501:0"], {"501:0": source}, {"501:0": SimpleNamespace(text=source)})


YEAR_SOURCE = (
    "D램 생산 비중은 2025년 73%에서 2027년 59%로 감소할 것이며, "
    "HBM 생산 확대가 일반 D램 공급 능력을 잠식할 것이라고 설명했다.\n"
    "업계에 따르면 삼성전자 HBM4의 생산 수율은 80% 선까지 올라선 것으로 파악된다.\n"
    "삼성전자는 지난 5월 HBM4E 12단 샘플을 글로벌 고객사에 공급하며 "
    "차세대 HBM 시장 공략에 속도를 냈다."
)
YEAR_PROSE = (
    "D램 생산 비중(연도별) 및 HBM 관련 수율·고객 샘플 공급 보고: "
    "원문은 2025→2027년 D램 비중 73%→59%와 HBM4 수율 약 80%, "
    "HBM4E 샘플 공급을 제시한다. "
    "이들 수치·보고는 생산 배치·양산 전환 판단의 직접적 관측 변수다."
)


def test_recorded_year_percentage_series_keeps_its_independent_yield():
    validate(YEAR_PROSE, YEAR_SOURCE)


@pytest.mark.parametrize(
    "value",
    [
        YEAR_PROSE.replace("73%→59%", "59%→73%"),
        YEAR_PROSE.replace("2025→2027년", "2027→2025년"),
        YEAR_PROSE.replace("80%", "90%"),
        YEAR_PROSE + " 변화 폭은 10%p다.",
        YEAR_PROSE.replace("HBM4E 샘플", "SK하이닉스 HBM4E 샘플"),
    ],
)
def test_ordered_year_series_does_not_erase_wrong_pairs_or_other_facts(value):
    with pytest.raises(ValueError):
        validate(value, YEAR_SOURCE)


def test_series_exception_does_not_erase_other_year_quantity_binding():
    source = YEAR_SOURCE + "\n2028년 시험 설비는 10개이며 2029년에는 20개다."
    with pytest.raises(ValueError, match="숫자"):
        validate(YEAR_PROSE + " 2028년 시험 설비는 20개다.", source)


def test_recorded_english_signed_agreement_supports_korean_contract_state():
    source = (
        "The companies signed an expansive, multi-year agreement to jointly develop "
        "GPT-Synopsys, a pioneering customized capability."
    )
    assert factual_states(source)["contract"] == {True}
    validate("양사는 공동 개발을 위한 다년 협약을 체결했음을 밝혔다.", source)


@pytest.mark.parametrize(
    "source",
    [
        "The companies have not signed an agreement to jointly develop the capability.",
        "The companies have never signed a contract.",
        "The companies signed no agreement.",
        "The companies haven't yet signed a contract.",
        "The companies have not yet formally signed an agreement.",
        "The companies denied that they signed an agreement.",
        "The companies will have signed an agreement by next year.",
        "If the companies signed an agreement, development would begin.",
        "The companies hope to have signed an agreement by next year.",
        "The companies plan to sign an agreement.",
        "The companies signed a letter of intent for a contract.",
        "The companies signed a plan to negotiate a contract.",
        "The companies signed a contract proposal.",
        "The companies signed an agreement draft.",
    ],
)
def test_unexecuted_english_contract_does_not_support_completed_korean_state(source):
    assert True not in factual_states(source).get("contract", set())
    with pytest.raises(ValueError):
        validate("양사는 공동 개발 협약을 체결했다.", source)


def test_signed_contract_does_not_support_a_cancelled_or_missing_contract():
    with pytest.raises(ValueError):
        validate("계약은 무산됐다.", "The companies signed a contract.")
