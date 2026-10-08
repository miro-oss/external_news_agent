"""Technical qualification aliases retain their object, action and authority."""

import pytest
from test_report_insight_v6_work_grounding import _reduce, _source, _validate_reduce

from app.llm.report_insight_work_grounding import (
    ReportWorkValidationError,
    work_prose_problems,
)


@pytest.mark.parametrize(
    "source",
    [
        "The chips are designed and qualified for extreme environments.",
        "The chips are designed and qualified for harsh environments. "
        "Reliability cannot remain a final qualification step.",
        "The chip qualification process includes evaluating reliability.",
        "The qualification of semiconductor devices includes environmental tests.",
    ],
)
@pytest.mark.parametrize(
    "prose",
    [
        "공정인증 절차에 신뢰성·사용환경 검증을 앞단에서 포함시켜야 할 필요성이 커진다.",
        "현재 공정인증 절차가 신뢰성 시험을 주로 최종 인증 시점에 배치하고 있다면 "
        "시험 시점·항목 조정이 필요하다는 조건이다.",
    ],
)
def test_device_qualification_supports_technical_work_without_inventing_a_new_gate(source, prose):
    assert work_prose_problems(prose, source) == ()


@pytest.mark.parametrize(
    "source",
    [
        "The company is hiring qualified engineers.",
        "Chip engineers have the educational qualifications for the position.",
        "Chip qualifications are required for engineer applicants.",
        "The qualification for chip manufacturing jobs depends on education.",
        "The qualification of chip production staff depends on education.",
        "The chip qualification training course is available to engineering students.",
        "The qualification of chip engineers depends on their education.",
        "The applicant's reliability is evaluated in the final qualification step.",
        "The chip company evaluates applicants' reliability as a final qualification step.",
        "The chips operate in the lab, and employee reliability is assessed "
        "in the final qualification process.",
        "The chips operate normally, while applicants reliability is evaluated "
        "as the final qualification step.",
        "The chips operate in the lab, reliability of applicants is a final qualification step.",
        "The chips operate in the lab, reliability is a final qualification step for employees.",
        "The company supplied a qualified opinion on chip demand.",
        "The chips are designed by qualified workers.",
        "The devices have improved reliability. Employees completed qualification training.",
        "The company announced a new chip design.",
    ],
)
def test_personnel_qualifications_and_other_predicates_cannot_supply_device_qualification(source):
    assert "certification_prerequisite" in work_prose_problems(
        "공정 인증 절차를 바꾸는 경우 시험 시점 조정이 필요하다.", source
    )


@pytest.mark.parametrize(
    "prose",
    [
        "고객 공정 인증 절차를 바꾸는 경우 조달 계획이 바뀐다.",
        "규제기관의 공정 인증 절차를 바꾸는 경우 조달 계획이 바뀐다.",
        "공정 인증 절차에는 법정 인증 요건이 추가된다.",
        "공정 인증 절차에는 ISO 인증 요건이 추가된다.",
        "공정 인증 절차의 통과가 제품 도입의 필수 전제다.",
        "공정 인증 절차를 거쳐야 제품을 도입할 수 있다.",
        "공정 인증 절차를 마쳤다.",
        "공정 인증 승인을 받았다.",
        "공정 인증 완료가 확인됐다.",
        "정부 인증 절차가 요구된다.",
        "인증 절차가 필요하다.",
    ],
)
def test_technical_qualification_does_not_supply_another_authority_required_gate_or_outcome(prose):
    assert "certification_prerequisite" in work_prose_problems(
        prose, "The chips are designed and qualified for extreme environments."
    )


def test_qualification_translation_repairs_both_synthesis_fields_with_the_same_citation():
    source = _source("The chips are designed and qualified for extreme environments.")
    value = _reduce(source, overview="칩의 설계와 적격성 평가 방식을 검토한다.")
    item = value["insights"][0]["implications"][0]
    item["text"] = "공정인증 절차에 신뢰성·사용환경 검증을 앞단에서 포함시킬 필요성이 커진다."
    item["assumption"] = (
        "현재 공정인증 절차가 최종 신뢰성 시험에 집중되어 있다면 시험 시점 조정이 필요하다."
    )
    _validate_reduce(value, source)


def test_power_conversion_description_does_not_supply_an_invented_module_in_watch_indicator():
    source = _source(
        "The manufacturer says its new GaN devices improve power conversion efficiency "
        "and ease the transition from existing silicon designs."
    )
    value = _reduce(source, overview="새 소자의 기존 실리콘 설계 전환 가능성을 검토한다.")
    item = value["insights"][0]["watchItems"][0]
    item["indicator"] = "새 소자를 적용한 전력변환 모듈의 실환경 효율 개선 실증 보고서."
    with pytest.raises(ReportWorkValidationError) as caught:
        _validate_reduce(value, source)
    issue = caught.value.validation_issues[0]
    assert issue.field == "watchItems[0].indicator"
    assert issue.error_kind == "report_work_physical_module_unsupported"
    assert issue.claim_ids == ("4901:0",)
    item["indicator"] = "새 소자를 적용한 기존 실리콘 설계 전환 사례와 전력변환 효율 개선 보고서."
    _validate_reduce(value, source)
