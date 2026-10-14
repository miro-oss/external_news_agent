"""A local question or evidence-absence predicate cannot erase another assertion."""

import pytest
from test_report_insight_assessment import payload, request
from test_report_insight_grounded_prose import _checked_map, _checked_reduce, _reduce_payload

from app.llm.report_insight_guard import factual_states, has_asserted_event
from app.llm.report_insight_service import (
    _asserted_event_stage,
    _prose_validation_errors,
    _source_context,
)

SOURCE = "센터를 데이터센터의 핵심 거점으로 삼겠다는 구상이다."
INQUIRIES = (
    "실제 운영 주체가 확인되어야 업무로 확정된다.",
    "확정된 설계와 조달 조건의 추가 확인이 필요하다.",
    "확정된 설계·승인 또는 조달 조건 등의 추가 확인이 필요하다.",
    "확정된 계약서나 서면 약정의 존재는 명시되어 있지 않다.",
    "확정된 계약서의 존재 여부 확인이 필요하다.",
    "고객사 계약 확정 여부",
    "고객사 계약 확정 여부를 확인한다.",
)


def errors(value, *, source_text=SOURCE, conditional=False, topic=False):
    source = request(text=source_text)
    claims, evidence = _source_context(source)
    return _prose_validation_errors(
        [value], ["101:0"], evidence, claims, request=source, conditional=conditional, topic=topic
    )


@pytest.mark.parametrize("value", INQUIRIES)
@pytest.mark.parametrize("flags", [{}, {"conditional": True}, {"topic": True}])
def test_only_local_confirmation_or_existence_checks_are_nonassertive(value, flags):
    assert not has_asserted_event(value)
    assert _asserted_event_stage(value) == 0
    assert factual_states(value) == {}
    assert errors(value, **flags) == []


@pytest.mark.parametrize(
    "value",
    [
        "계약을 확정했다. 계약서 존재는 명시되지 않았다.",
        "운영 주체가 확인되어야 한다. 고객사 계약은 확정됐다.",
        "고객사 계약 확정 여부를 확인한다. 공급 계약을 체결했다.",
        "확정된 계약서의 금액은 명시되어 있지 않다.",
        "확정된 계약서의 이행 일정은 명시되어 있지 않다.",
        "확정된 설계가 생산에 적용됐다. 추가 확인이 필요하다.",
        "확정된 설계의 적용 일정은 명시되어 있지 않다.",
        "확정된 계약서나 서면 약정의 존재는 명시되어 있지 않다. 계약을 체결했다.",
        "확정된 계약서의 존재는 명시되어 있지 않다는 뜻은 아니다.",
        "이미 확정된 계약서 확인이 필요하다.",
        "실제로 확정된 계약서 확인이 필요하다.",
        "이미 확정된 설계 추가 확인이 필요하다.",
    ],
)
@pytest.mark.parametrize("flags", [{}, {"conditional": True}, {"topic": True}])
def test_missing_information_or_later_inquiry_does_not_hide_actual_confirmation(value, flags):
    assert has_asserted_event(value)
    assert _asserted_event_stage(value) >= 4
    assert errors(value, **flags)


def test_confirmed_contract_still_requires_and_accepts_its_source():
    value = "고객사 계약이 확정됐다."
    assert factual_states(value) == {"contract": {True}}
    assert errors(value)
    assert errors(value, source_text=value) == []


@pytest.mark.parametrize(
    "value",
    [
        "계약 확정 여부는 확정으로 결론났다.",
        "계약 확정 여부는 확인됐다.",
        "계약 확정 여부는 확정이다.",
    ],
)
def test_question_noun_does_not_erase_its_following_finite_conclusion(value):
    assert factual_states(value) == {"contract": {True}}
    assert errors(value, source_text="공급 계약은 체결되지 않았으며 계약 체결을 검토하고 있다.")


def test_native_reason_can_classify_work_after_confirmation_without_claiming_execution():
    source = request(text=SOURCE)
    value = payload(source, relation="UNDETERMINED")
    prose = "실제 운영 주체가 확인되어야 업무로 확정된다. 업무 영향과 시점은 판단을 보류한다."
    value["assessments"]["CHIP_MAKER"]["finding101"]["reason"] = prose
    assert _checked_map(source, value)[1].insights[0].assessments[0].reason == prose


def test_reduce_assumption_does_not_treat_document_absence_as_a_finalized_contract():
    source = request()
    value = _reduce_payload()
    prose = "확정된 계약서나 서면 약정의 존재는 명시되어 있지 않다."
    value["insights"][0]["overview"][0]["assumption"] = prose
    assert _checked_reduce(source, value).insights[0].overview[0].assumption == prose


FUNDING_SOURCE = (
    "블룸버그통신에 따르면 솔리다임은 미국 IPO를 위한 대표 주관사를 선정했고, "
    "이르면 내년 상장을 통해 약 100억달러(13조4000억원)를 조달할 것으로 예상된다."
)
FUNDING_INQUIRY = "확정된 조달 실행 여부와 최종 규모는 기사에서 확인되지 않는다."


@pytest.mark.parametrize(
    "value",
    [
        FUNDING_INQUIRY,
        "확정된 조달 실행 여부는 확인되지 않는다.",
        "확정된 자금 조달 집행 여부는 원문에서 확인되지 않습니다.",
        "확정된 조달 실행 여부와 규모는 미확인이다.",
    ],
)
def test_funding_execution_question_with_local_absence_does_not_assert_confirmation(value):
    assert not has_asserted_event(value)
    assert _asserted_event_stage(value) == 0
    assert factual_states(value) == {}
    assert errors(value, source_text=FUNDING_SOURCE) == []


def test_captured_8288_repair_keeps_funding_execution_unknown():
    # Exact report 2005 MAP-001 repair text, using only selected claim 8288:1.
    reason = (
        "대표 주관사 선정과 이르면 내년 약 100억달러 조달 보도는 회사의 자금조달 계획과 "
        "자본집행 준비에 직접적 영향을 줄 수 있으나, 보도는 전망(예상 금액·시점)을 전하며 "
        + FUNDING_INQUIRY
    )
    assert _asserted_event_stage(reason) == 0
    assert errors(reason, source_text=FUNDING_SOURCE) == []


@pytest.mark.parametrize(
    "assertion",
    [
        "솔리다임은 조달 조건을 확정했다.",
        "솔리다임은 계약을 체결했다.",
        "솔리다임은 공장을 완공했다.",
    ],
)
@pytest.mark.parametrize("inquiry_first", [False, True])
def test_funding_inquiry_does_not_erase_an_independent_confirmation(assertion, inquiry_first):
    clauses = (FUNDING_INQUIRY, assertion) if inquiry_first else (assertion, FUNDING_INQUIRY)
    value = " ".join(clauses)
    assert has_asserted_event(value)
    assert _asserted_event_stage(value) >= 4
    assert errors(value, source_text=FUNDING_SOURCE)


@pytest.mark.parametrize(
    "value",
    [
        "확정된 조달 실행의 최종 규모는 기사에서 확인되지 않는다.",
        "확정된 조달 실행 여부와 최종 규모는 기사에서 확인됐다.",
        "확정된 조달 실행 여부와 최종 규모는 기사에서 확인되지 않는다는 뜻은 아니다.",
        "이미 확정된 조달 실행 여부와 최종 규모는 기사에서 확인되지 않는다.",
        "확정된 조달 실행과 공장 완공 여부는 기사에서 확인되지 않는다.",
        "확정된 조달 실행 여부는 기사에 있으며 최종 규모는 확인되지 않는다.",
        "투자 조건이 확정된 조달 실행 여부는 기사에서 확인되지 않는다.",
        "계약 규모가 확정된 자금 조달 집행 여부는 원문에서 확인되지 않습니다.",
        "이미 규모가 확정된 조달 실행 여부와 최종 규모는 기사에서 확인되지 않는다.",
        "보도는 규모가 확정된 조달 실행 여부는 기사에서 확인되지 않는다고 전한다.",
    ],
)
def test_funding_question_exception_requires_execution_itself_to_remain_unknown(value):
    assert has_asserted_event(value)
    assert _asserted_event_stage(value) >= 4
    assert errors(value, source_text=FUNDING_SOURCE)


DEPLOYMENT_SOURCE = "MS는 내년부터 마이아300을 데이터센터에 100만 개 이상 배치할 예정이다."


@pytest.mark.parametrize(
    "value",
    [
        "계약 확정성은 기사에서 명확하지 않다.",
        "계약 확정성·일정 세부는 기사에서 명확하지 않다.",
        "계약 확정성은 미확인이다.",
    ],
)
def test_contract_certainty_noun_does_not_assert_contract_confirmation(value):
    assert factual_states(value) == {}
    assert not has_asserted_event(value)
    assert errors(value, source_text=DEPLOYMENT_SOURCE) == []


def test_captured_8323_repair_does_not_turn_contract_certainty_into_a_confirmed_event():
    reason = (
        "기사의 보도는 내년부터 제품을 데이터센터에 대규모 배치할 계획을 밝히며 매출 인식 "
        "가능성을 직접 시사하지만, 배치의 계약 확정성·일정 세부와 실제 수량 집행 여부는 "
        "기사에서 명확하지 않다."
    )
    assert factual_states(reason) == {}
    assert errors(reason, source_text=DEPLOYMENT_SOURCE) == []


@pytest.mark.parametrize("inquiry_first", [False, True])
def test_contract_certainty_noun_does_not_erase_an_independent_confirmed_contract(inquiry_first):
    inquiry = "계약 확정성은 기사에서 명확하지 않다."
    assertion = "공급 계약이 확정됐다."
    value = " ".join((inquiry, assertion) if inquiry_first else (assertion, inquiry))
    assert factual_states(value) == {"contract": {True}}
    assert errors(value, source_text=DEPLOYMENT_SOURCE)


def test_unknown_contract_certainty_in_source_cannot_authenticate_a_confirmed_contract():
    assert errors("공급 계약이 확정됐다.", source_text="계약 확정성은 기사에서 명확하지 않다.")


CONSTRUCTION_SOURCE = "일본 요코하마에는 350억엔을 들여 첨단 패키징 연구 거점(APL)을 짓고 있다."


def test_captured_8290_construction_in_progress_supports_building_description():
    reason = (
        "요코하마에 첨단 패키징 연구 거점(APL)을 350억엔을 투입해 건설한다고 명시되어 있어, "
        "해당 시설의 건설 집행은 수급 측면의 제약(공정·설비 조달·투자 집행)에 직접 연결되며 "
        "프로젝트 일정·준비 관점의 조정이 필요하다고 판단된다."
    )
    assert _asserted_event_stage(CONSTRUCTION_SOURCE) == 5
    assert errors(reason, source_text=CONSTRUCTION_SOURCE) == []


def test_construction_in_progress_does_not_authenticate_completion():
    assert errors("첨단 패키징 연구 거점(APL)을 완공했다.", source_text=CONSTRUCTION_SOURCE)
