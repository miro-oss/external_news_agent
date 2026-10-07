"""Regression for unquoted event states and source-scoped technical vocabulary."""

import pytest
from test_report_insight_assessment import payload, request
from test_report_insight_fact_rendering import response

from app.llm.report_insight_assessment import (
    ReportAssessmentDraftValidationError,
    draft_to_wire,
    validate_template_draft,
)
from app.llm.report_insight_fact_rendering import (
    FactTemplateError,
    build_fact_text_catalog,
    render_fact_template,
)
from app.llm.report_insight_service import _validated_map_output


def rendered(value, *, source="생산라인 전체의 가동 중단이 현재 계속된다."):
    req = request(text=source)
    return render_fact_template(value, build_fact_text_catalog(req), ["101:0"], max_length=180)


@pytest.mark.parametrize(
    "value",
    [
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
    ],
)
def test_asserted_current_passive_or_planning_states_require_source_slot(value):
    with pytest.raises(FactTemplateError, match="report_fact_template_required"):
        rendered(value)


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
    ],
)
def test_conditional_or_investigative_work_is_not_an_asserted_state(value):
    assert rendered(value).interpretation == value


@pytest.mark.parametrize(
    "text",
    [
        "공장 가동이 진행 중이다.",
        "공급 계약이 체결돼 있다.",
        "공장 건설을 추진한다.",
    ],
)
def test_native_map_path_rejects_previously_accepted_unquoted_events(text):
    source = request(text="생산라인 전체의 가동 중단이 현재 계속된다.")
    wire = draft_to_wire(payload(source), source)
    wire["assessments"]["CHIP_MAKER"]["finding101"]["reason"] = text + " 공정 검증 일정을 확인한다."
    with pytest.raises(ReportAssessmentDraftValidationError) as raised:
        validate_template_draft(response(wire), source)
    assert any(
        issue.rule_id == "report_fact_template_required" for issue in raised.value.validation_issues
    )


@pytest.mark.parametrize(
    "term,category",
    [
        ("TSV", "생산라인"),
        ("FPGA", "반도체"),
        ("DDR", "메모리"),
        ("XYZ", "인터페이스"),
    ],
)
def test_source_supported_technical_modifier_does_not_need_a_manual_acronym_whitelist(
    term, category
):
    value = f"{term} 공정 검증 부담을 확인한다."
    assert rendered(value, source=f"{term} {category}의 가동 중단이 현재 계속된다.").text == value


def test_tsv_work_interpretation_passes_entire_native_map_validation():
    source = request(text="TSV 생산라인 전체의 가동 중단이 현재 계속된다.")
    wire = draft_to_wire(payload(source), source)
    wire["assessments"]["CHIP_MAKER"]["finding101"]["reason"] = "TSV 공정 검증 부담을 확인한다."
    draft = validate_template_draft(response(wire), source)
    output = _validated_map_output(
        response(draft.mapped.model_dump(by_alias=True)), source, native_assessments=draft.evidence
    )
    assert output.insights[0].assessments[0].reason == "TSV 공정 검증 부담을 확인한다."


@pytest.mark.parametrize("term", ["AMD", "IBM", "TSMC", "Acme"])
def test_company_identifiers_still_require_slots_even_next_to_a_technical_noun(term):
    with pytest.raises(FactTemplateError, match="report_fact_template_required"):
        rendered(f"{term} 공정 검증 부담을 확인한다.", source=f"{term} 생산라인이 중단됐다.")


@pytest.mark.parametrize("term", ["TSV", "FPGA", "DDR", "ACME", "Acme"])
def test_absent_technical_or_proper_identifier_cannot_be_borrowed(term):
    with pytest.raises(FactTemplateError, match="report_fact_template_required"):
        rendered(f"{term} 공정 검증 부담을 확인한다.")


def test_new_uppercase_name_explicitly_identified_as_company_is_not_a_technical_modifier():
    with pytest.raises(FactTemplateError, match="report_fact_template_required"):
        rendered(
            "QXYZ 공정 조건을 확인한다.", source="기업 QXYZ는 QXYZ 생산라인의 가동을 중단했다."
        )


def test_another_findings_technical_word_is_not_in_the_cited_source_scope():
    source = request(ids=(101, 102))
    source.findings[1].sentences[0].text = "TSV 공정 검증 조건을 확인한다."
    catalog = build_fact_text_catalog(source)
    with pytest.raises(FactTemplateError, match="report_fact_template_required"):
        render_fact_template("TSV 공정 검증 부담을 확인한다.", catalog, ["101:0"], max_length=180)


def test_numeric_product_generations_still_belong_to_source_owned_fact_slots():
    with pytest.raises(FactTemplateError, match="report_fact_template_required"):
        rendered("DDR5 메모리 검증 조건을 확인한다.", source="DDR5 메모리 공정 검증이 진행 중이다.")


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
    assert rendered(value).text == value


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
def test_fund_category_does_not_authorize_fund_names_tickers_values_or_events(value):
    with pytest.raises(FactTemplateError, match="report_fact_template_required"):
        rendered(value)
