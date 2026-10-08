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
