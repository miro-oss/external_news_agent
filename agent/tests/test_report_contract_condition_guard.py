"""Future contract conditions must not assert execution or hide other facts."""

import pytest
from test_report_insight_guard import validate_prose

from app.llm.report_insight_guard import factual_states

SOURCE = "가람전자는 생산 설비 건설을 계획하며 고객의 생산시설 증설을 지원할 예정이다."


@pytest.mark.parametrize(
    "predicate",
    [
        "확정될 경우",
        "확정되면",
        "확정되는 경우",
        "확정될 때",
        "성립할 경우",
        "성립하면",
        "성립하는 경우",
    ],
)
def test_nominal_contract_immediate_future_condition_is_not_an_asserted_fact(predicate):
    value = f"계약이 {predicate} 생산시설의 공급 조건을 확인한다."
    assert "contract" not in factual_states(value)
    validate_prose(value, SOURCE)


def test_coordinated_facility_and_contract_condition_remains_hypothetical():
    value = (
        "가람전자의 생산 설비 건설 계획은, 시설이 완공·가동되고 계약이 확정될 경우 "
        "공급 불확실성 완화에 기여할 수 있다."
    )
    assert factual_states(value) == {}
    validate_prose(value, SOURCE)


@pytest.mark.parametrize(
    "value",
    [
        "계약이 확정됐다.",
        "계약이 성립했다.",
        "계약이 확정되면서 공급 조건이 바뀐다.",
        "계약이 성립하면서 공급 조건이 바뀐다.",
        "계약이 확정된 사실에 따라 공급한다.",
        "계약이 확정됐다. 설비가 가동될 경우 공급 조건을 확인한다.",
        "설비가 가동될 경우를 검토하고 계약이 확정됐다.",
        "계약이 확정될 경우를 검토한다. 별도 계약이 성립했다.",
        "계약이 성립한 사실은 설비가 가동될 경우에도 바뀌지 않는다.",
        "시설이 완공됐고 계약이 확정될 경우 공급 조건을 확인한다.",
    ],
)
def test_a_separate_condition_cannot_hide_asserted_contract_or_completion(value):
    with pytest.raises(ValueError, match="사실값"):
        validate_prose(value, SOURCE)


@pytest.mark.parametrize(
    "value",
    [
        "삼성전자의 계약이 확정될 경우 공급 조건을 확인한다.",
        "계약이 확정될 경우 999억원의 공급 조건을 확인한다.",
        "2039년 계약이 확정될 경우 공급 조건을 확인한다.",
    ],
)
def test_contract_condition_keeps_entity_number_and_date_checks(value):
    with pytest.raises(ValueError, match="사실값"):
        validate_prose(value, SOURCE)


def test_hypothetical_source_cannot_ground_asserted_contract_execution():
    with pytest.raises(ValueError, match="사실값"):
        validate_prose("계약이 확정됐다.", "계약이 확정될 경우 공급할 계획이다.")
