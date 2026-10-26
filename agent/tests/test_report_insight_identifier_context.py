"""Synthetic identifier cases; no report/article text or live model output is used."""

from copy import deepcopy

import pytest
from test_report_insight_assessment import payload, request
from test_report_insight_grounded_prose import _checked_map

SYNTHETIC_SOURCE = "삼성전자는 HBM4 생산라인 전체의 가동 중단이 현재 계속된다고 밝혔다."


def assessment_with_prose(source, field, prose):
    value = payload(source, relation="CONDITIONAL" if field == "condition" else "DIRECT")
    value["assessments"]["CHIP_MAKER"]["finding101"][field] = prose
    return value


@pytest.mark.parametrize("field", ["reason", "condition"])
def test_native_assessment_accepts_an_established_english_alias_of_the_source_company(field):
    source = request(text=SYNTHETIC_SOURCE)
    prose = (
        "Samsung Electronics의 공정 검증 준비 영향을 확인한다."
        if field == "reason"
        else "Samsung Electronics의 생산 제약이 공정 검증 일정에 영향을 주는 경우"
    )
    value = assessment_with_prose(source, field, prose)
    before = deepcopy((source.model_dump(), value))

    native, mapped = _checked_map(source, value)

    assert getattr(native.evidence["CHIP_MAKER"][101], field) == prose
    assert prose in mapped.insights[0].assessments[0].reason
    assert (source.model_dump(), value) == before


@pytest.mark.parametrize("term", ["NAND", "PDK", "SDK", "NPU", "Foundry", "Yield"])
@pytest.mark.parametrize("field", ["reason", "condition"])
def test_generic_work_vocabulary_is_not_classified_as_an_absent_company_by_capitalization(
    term, field
):
    source = request(text=SYNTHETIC_SOURCE)
    prose = (
        f"{term} 공정 검증 준비 부담을 확인한다."
        if field == "reason"
        else f"{term} 공정 검증 준비가 필요한 경우"
    )
    value = assessment_with_prose(source, field, prose)

    native, mapped = _checked_map(source, value)

    assert getattr(native.evidence["CHIP_MAKER"][101], field) == prose
    assert prose in mapped.insights[0].assessments[0].reason


@pytest.mark.parametrize("company", ["TSMC", "기업 Acme"])
@pytest.mark.parametrize("field", ["reason", "condition"])
def test_known_or_explicitly_named_organization_still_requires_cited_source_support(company, field):
    source = request(text=SYNTHETIC_SOURCE)
    value = assessment_with_prose(
        source,
        field,
        f"{company}의 공정 검증 준비 영향을 확인한다."
        if field == "reason"
        else f"{company}의 생산 제약이 공정 검증 일정에 영향을 주는 경우",
    )
    _checked_map(source, payload(source))

    with pytest.raises(ValueError):
        _checked_map(source, value)


@pytest.mark.parametrize("company", ["TSMC", "기업 Acme"])
@pytest.mark.parametrize("field", ["reason", "condition"])
def test_company_from_another_finding_is_not_authorized_for_this_assessment(company, field):
    source = request(ids=(101, 102), text=SYNTHETIC_SOURCE)
    other_source = f"{company}는 생산라인 전체의 가동 중단이 현재 계속된다고 밝혔다."
    source.findings[1].claims[0].text = other_source
    source.findings[1].sentences[0].text = other_source
    value = assessment_with_prose(
        source,
        field,
        f"{company}의 공정 검증 준비 영향을 확인한다."
        if field == "reason"
        else f"{company}의 생산 제약이 공정 검증 일정에 영향을 주는 경우",
    )
    before = source.model_dump_json()
    _checked_map(source, payload(source))

    with pytest.raises(ValueError):
        _checked_map(source, value)
    assert source.model_dump_json() == before


@pytest.mark.parametrize("field", ["reason", "condition"])
def test_product_generation_cannot_change_while_generic_technical_vocabulary_is_allowed(field):
    source = request(text=SYNTHETIC_SOURCE)
    supported = (
        "HBM4 공정 검증 준비 영향을 확인한다."
        if field == "reason"
        else "HBM4 공정 검증 준비가 필요한 경우"
    )
    _checked_map(source, assessment_with_prose(source, field, supported))

    with pytest.raises(ValueError):
        _checked_map(
            source, assessment_with_prose(source, field, supported.replace("HBM4", "HBM5"))
        )
