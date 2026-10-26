"""Explicit event assertions are checked against source, not renderer word bans."""

import pytest
from test_report_insight_assessment import payload, request
from test_report_insight_grounded_prose import _checked_map, _checked_reduce, _reduce_payload

from app.llm.report_insight_fact_assertions import unsupported_fact_assertions

SOURCE_WITHOUT_EVENTS = "원문에는 공정 검증 조건만 소개되어 있다."
EVENT_ASSERTIONS = (
    "공장 가동이 진행 중이다.",
    "공급 계약이 체결돼 있다.",
    "공급 계약이 체결되어 있다.",
    "공장 건설을 추진한다.",
    "공장 건설을 검토한다.",
    "장비를 공급한다.",
    "공장을 건설하고 있다.",
    "장비 설치가 완료되어 있다.",
    "양산이 진행되고 있다.",
    "공장 가동이 재개되어 있다.",
    "투자했다.",
    "수주가 확정이다.",
    "투자할 계획이다.",
    "공급할 예정이다.",
    "양산이 예정되어 있다.",
    "공장 건설을 계획했다.",
)


def checked_prose(text, source_text, stage):
    source = request(text=source_text)
    if stage == "MAP":
        value = payload(source)
        value["assessments"]["CHIP_MAKER"]["finding101"]["reason"] = text
        _, mapped = _checked_map(source, value)
        return mapped.insights[0].assessments[0].reason
    value = _reduce_payload()
    value["insights"][0]["overview"][0]["text"] = text
    return _checked_reduce(source, value).insights[0].overview[0].text


@pytest.mark.parametrize("value", EVENT_ASSERTIONS)
def test_event_absence_check_is_source_aware_and_accepts_the_same_sourced_assertion(value):
    assert unsupported_fact_assertions(value, SOURCE_WITHOUT_EVENTS)
    assert unsupported_fact_assertions(value, SOURCE_WITHOUT_EVENTS + " " + value) == []


@pytest.mark.parametrize("value", EVENT_ASSERTIONS[:10])
@pytest.mark.parametrize("stage", ["MAP", "REDUCE"])
def test_native_and_public_paths_reject_absent_events_but_accept_grounded_events(value, stage):
    prose = value + " 공정 검증 일정을 확인한다."
    with pytest.raises(ValueError):
        checked_prose(prose, SOURCE_WITHOUT_EVENTS, stage)
    assert checked_prose(prose, SOURCE_WITHOUT_EVENTS + " " + value, stage) == prose


@pytest.mark.parametrize(
    "value",
    [
        "공장 가동이 진행 중이라면 공정 검증 조건을 확인한다.",
        "공급 계약이 체결돼 있다면 납기 조건을 확인한다.",
        "공급 계약이 체결되어 있으면 납기 조건을 확인한다.",
        "공장 건설을 추진한다면 공정 검증 조건을 확인한다.",
        "공장 건설을 검토한다면 공정 검증 조건을 확인한다.",
        "장비를 공급한다면 납기 조건을 확인한다.",
        "공장을 건설하고 있다면 공정 검증 조건을 확인한다.",
        "장비 설치가 완료되어 있다면 공정 검증 조건을 확인한다.",
        "양산이 진행되고 있으면 공정 검증 조건을 확인한다.",
        "공장 가동이 재개되어 있다면 공정 검증 조건을 확인한다.",
        "공장 가동이 진행 중인지 확인한다.",
        "계약 체결 여부와 장비 공급 조건을 확인한다.",
        "공급량이 늘었는지 확인한다.",
        "수율이 증가했는지 확인한다.",
        "공급량이 늘었다는 가정에서 조달 조건을 점검한다.",
        "공급량이 늘었다고 가정하면 조달 조건을 점검한다.",
        "공급량이 늘었다는 가정하에 조달 조건을 점검한다.",
        "공급량이 늘었다는 전제하에 조달 조건을 점검한다.",
        "공급 차질이 이어지는 경우 공장 건설을 검토한다.",
        "생산 여력이 부족하다면 장비를 공급한다.",
    ],
)
def test_conditional_or_investigative_work_is_not_an_asserted_new_event(value):
    assert unsupported_fact_assertions(value, SOURCE_WITHOUT_EVENTS) == []


@pytest.mark.parametrize("stage", ["MAP", "REDUCE"])
@pytest.mark.parametrize("value", EVENT_ASSERTIONS[1:5] + EVENT_ASSERTIONS[6:9])
def test_halt_source_does_not_license_unrelated_contract_construction_or_installation(value, stage):
    with pytest.raises(ValueError):
        checked_prose(
            value + " 공정 검증 일정을 확인한다.",
            "생산라인 전체의 가동 중단이 현재 계속된다.",
            stage,
        )


@pytest.mark.parametrize(
    "term", ["TSV", "FPGA", "DDR", "XYZ", "AMD", "IBM", "TSMC", "Acme", "DDR5"]
)
def test_source_supported_company_and_product_identifiers_are_valid_in_work_prose(term):
    value = f"{term} 공정 검증 부담을 확인한다."
    assert (
        checked_prose(value, f"{term} 생산라인 전체의 가동 중단이 현재 계속된다.", "MAP") == value
    )


@pytest.mark.parametrize("term", ["기업 ACME", "기업 Acme", "DDR5"])
def test_explicit_organization_or_product_identifier_requires_selected_source(term):
    with pytest.raises(ValueError):
        checked_prose(f"{term} 공정 검증 부담을 확인한다.", SOURCE_WITHOUT_EVENTS, "MAP")


@pytest.mark.parametrize("term", ["TSV", "FPGA", "DDR"])
def test_technical_work_topic_is_not_itself_an_unsupported_company_assertion(term):
    value = f"{term} 공정 검증 부담을 확인한다."
    assert checked_prose(value, SOURCE_WITHOUT_EVENTS, "MAP") == value


def test_another_findings_product_identifier_is_not_in_the_cited_source_scope():
    source = request(ids=(101, 102))
    source.findings[1].claims[0].text = "DDR5 생산라인 전체의 가동 중단이 현재 계속된다."
    source.findings[1].sentences[0].text = source.findings[1].claims[0].text
    value = payload(source)
    value["assessments"]["CHIP_MAKER"]["finding101"]["reason"] = "DDR5 공정 검증 부담을 확인한다."
    with pytest.raises(ValueError):
        _checked_map(source, value)


@pytest.mark.parametrize(
    "value",
    [
        "ETF·주가 흐름만으로 장비 발주 증가를 증명하지는 않는다.",
        "장비업종 ETF 흐름 점검",
        "ETF의 상대적 성과 변화를 확인한다.",
        "ETF 성과 변화가 실제 수주로 이어지는지 확인한다.",
    ],
)
def test_generic_fund_category_is_not_a_company_identifier(value):
    assert checked_prose(value, SOURCE_WITHOUT_EVENTS, "MAP") == value


@pytest.mark.parametrize(
    "value",
    [
        "KODEX ETF 성과를 확인한다.",
        "SPY ETF 성과를 확인한다.",
        "Acme ETF 성과를 확인한다.",
        "삼성전자 ETF 성과를 확인한다.",
        "ETF 수익률은 20퍼센트다.",
        "ETF 자금 유입은 내년에 확대될 예정이다.",
        "ETF 구성 기업의 공급 계약이 체결돼 있다.",
        "ETF 구성 기업의 공장 가동이 진행 중이다.",
    ],
)
def test_fund_category_does_not_authorize_absent_identifiers_values_or_events(value):
    with pytest.raises(ValueError):
        checked_prose(value, SOURCE_WITHOUT_EVENTS, "MAP")


@pytest.mark.parametrize(
    "source", ["공급량을 확인한다.", "공급량이 세 배로 늘었다.", "공급량은 2개다."]
)
def test_written_multiplier_is_checked_with_quantity_values_and_units(source):
    assert unsupported_fact_assertions("공급량이 두 배로 늘었다.", source)


@pytest.mark.parametrize("source", ["공급량이 두 배로 늘었다.", "공급량이 2배로 늘었다."])
def test_grounded_written_or_numeric_multiplier_is_accepted(source):
    assert unsupported_fact_assertions("공급량이 두 배로 늘었다.", source) == []


def test_known_event_kind_does_not_become_an_entailment_decision_by_this_helper():
    assert (
        unsupported_fact_assertions(
            "공장 가동이 진행 중이다.", "생산라인 전체의 가동 중단이 현재 계속된다."
        )
        == []
    )


def test_hypothetical_prefix_does_not_hide_a_later_independent_assertion():
    assert unsupported_fact_assertions(
        "공급 차질이 이어지는 경우 일정 조정을 검토한다. 공장 건설을 추진한다.",
        SOURCE_WITHOUT_EVENTS,
    )


def test_map_does_not_bind_a_following_metrics_predicate_to_an_earlier_question():
    source = "제조사는 생산라인 전체의 가동 중단이 현재 계속되며 매출이 늘었다고 밝혔다."
    prose = "수율을 확인하고 매출이 늘었다는 원문을 점검한다. 공정 검증 일정을 확인한다."
    assert checked_prose(prose, source, "MAP") == prose


@pytest.mark.parametrize(
    "nonassertion",
    [
        "수율이 증가했다는 것은 아니다.",
        "수율이 증가했다고 단정할 수 없다.",
        "수율이 증가했다는 주장에 대한 근거가 없다.",
        "공급량이 늘었다고 가정하면 조달 조건을 점검한다.",
        "공급량이 늘었다는 가정하에 조달 조건을 점검한다.",
        "공급량이 늘었다는 전제하에 조달 조건을 점검한다.",
    ],
)
def test_map_does_not_treat_directly_attached_denial_or_assumption_as_new_fact(nonassertion):
    prose = nonassertion + " 공정 검증 일정을 확인한다."
    assert checked_prose(prose, SOURCE_WITHOUT_EVENTS, "MAP") == prose


@pytest.mark.parametrize("separator", [". ", ", ", "고 "])
def test_a_later_metrics_denial_does_not_hide_an_independent_assertion(separator):
    value = "수율이 증가했다" + separator + "매출이 늘었다는 것은 아니다."
    errors = unsupported_fact_assertions(value, SOURCE_WITHOUT_EVENTS)
    assert errors == ["연결 원문에 없는 수치 지표 단정: 수율"]
    with pytest.raises(ValueError):
        checked_prose(value + " 공정 검증 일정을 확인한다.", SOURCE_WITHOUT_EVENTS, "MAP")
