"""Synthetic category/company boundaries; no captured report data is used here."""

from copy import deepcopy

import pytest
from test_report_insight_assessment import payload, request, response
from test_report_insight_guard import validate_prose
from test_report_insight_v4_pipeline import (
    V4Provider,
    _expected_fixture_display_reason,
    generate,
    stages,
)
from test_report_insight_work_repair_actions import full_validate

from app.core.evidence import factual_mismatches
from app.llm import report_insight_service as service

SOURCE = "제조사는 차세대 반도체 소자의 공정 검증 준비가 필요하다고 밝혔다."
REASON = "차세대반도체 소자의 공정 검증 준비를 검토한다."
CONDITION = "차세대반도체 소자가 공정 검증에 적용되는 경우"


def category_value(source, *, conditional=False):
    value = payload(source, relation="CONDITIONAL" if conditional else "DIRECT")
    for entry in value["assessments"]["CHIP_MAKER"].values():
        entry.update(
            impactScope="LIMITED_PREPARATION",
            urgencyState="MONITOR",
            reason=REASON,
            condition=CONDITION if conditional else None,
        )
    return value


@pytest.mark.parametrize(
    "category",
    [
        "차세대반도체",
        "전력반도체",
        "시스템반도체",
        "화합물반도체",
        "메모리반도체",
        "비메모리반도체",
    ],
)
@pytest.mark.parametrize("marker", ["기업 ", "회사 ", "주식회사 ", "㈜"])
def test_explicit_organization_context_cannot_borrow_a_general_category(category, marker):
    # A synthetic organization may have the same spelling as a general term.
    source = f"{category.removesuffix('반도체')} 반도체 소자의 공급 일정을 검토한다."
    value = f"{marker}{category}의 공급 일정을 검토한다."
    assert any(
        "기업명" in error and category in error for error in factual_mismatches(value, source)
    )
    with pytest.raises(ValueError, match="기업명"):
        validate_prose(value, source)


@pytest.mark.parametrize("marker", ["기업 ", "회사 ", "주식회사 ", "㈜"])
def test_source_supported_explicit_organization_still_passes(marker):
    value = f"{marker}차세대반도체의 공급 일정을 검토한다."
    source = f"{marker}차세대반도체의 공급 일정을 검토한다고 밝혔다."
    assert factual_mismatches(value, source) == []
    validate_prose(value, source)


@pytest.mark.parametrize(
    "name",
    [
        "회사 차세대 반도체",
        "기업 '차세대반도체'",
        "(주)차세대반도체",
        "차세대반도체(주)",
        "차세대반도체 주식회사",
        "차세대반도체라는 회사",
        "'차세대반도체'라는 기업",
    ],
)
def test_spaced_quoted_and_legal_company_names_still_need_organization_evidence(name):
    value = f"{name}의 준비 일정을 검토한다."
    assert factual_mismatches(value, f"{name}의 준비 일정을 검토한다고 밝혔다.") == []
    validate_prose(value, f"{name}의 준비 일정을 검토한다고 밝혔다.")
    with pytest.raises(ValueError, match="기업명"):
        validate_prose(value, "차세대 반도체 소자의 준비 일정을 검토한다.")


@pytest.mark.parametrize(
    "company", ["서울반도체", "매그나칩반도체", "가온차세대반도체", "가온전력반도체", "삼성전자"]
)
def test_general_category_exemption_does_not_erase_real_or_suffix_detected_companies(company):
    value = f"{company}의 차세대반도체 소자 공급 일정을 검토한다."
    errors = factual_mismatches(value, "차세대 반도체 소자의 공급 일정을 검토한다.")
    assert errors == [f"근거에서 확인되지 않는 기업명: {company}"]


def test_different_company_prefix_cannot_be_accepted_by_substring_matching():
    value = "서울반도체의 공급 일정을 검토한다."
    source = "동서울반도체의 공급 일정을 검토한다."
    assert factual_mismatches(value, source) == ["근거에서 확인되지 않는 기업명: 서울반도체"]
    with pytest.raises(ValueError, match="기업명"):
        validate_prose(value, source)


@pytest.mark.parametrize("joiner", ["-", ".", "&"])
def test_category_prefix_in_a_longer_named_organization_is_not_a_second_company(joiner):
    name = f"차세대반도체{joiner}테크"
    source = f"{name}는 공정 검증을 검토한다."
    value = f"기업 {name}의 공정 검증 영향을 검토한다."
    assert factual_mismatches(value, source) == []
    validate_prose(value, source)
    with pytest.raises(ValueError, match="기업명"):
        validate_prose(value, "차세대 반도체 소자의 공정 검증을 검토한다.")


@pytest.mark.parametrize("spacing", [" ", "  ", "\t"])
def test_explicit_category_company_keeps_its_contract_actor_across_horizontal_spacing(spacing):
    source = f"기업 차세대{spacing}반도체가 계약을 맺었다."
    value = "기업 차세대반도체가 계약을 맺었다."
    assert factual_mismatches(value, source) == []
    validate_prose(value, source)


@pytest.mark.parametrize("conditional", [False, True])
def test_compact_category_is_valid_in_native_reason_and_condition(conditional):
    source = request(text=SOURCE)
    value = category_value(source, conditional=conditional)
    before = source.model_dump_json(), deepcopy(value)
    assert factual_mismatches(REASON, SOURCE) == []
    accepted = full_validate(response(value, source), source)
    entry = accepted.evidence["CHIP_MAKER"][101]
    assert entry.reason == REASON
    assert entry.condition == (CONDITION if conditional else None)
    assert entry.relation_basis.claim_id == "101:0"
    assert before == (source.model_dump_json(), value)


@pytest.mark.parametrize("field", ["reason", "condition"])
@pytest.mark.parametrize("location", ["other_finding", "unselected_claim"])
def test_native_prose_cannot_borrow_a_company_from_outside_selected_claims(field, location):
    source = request(ids=(101, 102), text=SOURCE)
    company_source = "서울반도체는 소자의 공정 검증 준비가 필요하다고 밝혔다."
    if location == "other_finding":
        source.findings[1].claims[0].text = company_source
        source.findings[1].sentences[0].text = company_source
    else:
        finding = source.findings[0]
        finding.claims.append(
            finding.claims[0].model_copy(
                update={"id": "101:1", "text": company_source, "evidence_sentence_ids": [1]}
            )
        )
        finding.sentences.append(
            finding.sentences[0].model_copy(update={"index": 1, "text": company_source})
        )
    value = category_value(source, conditional=field == "condition")
    value["assessments"]["CHIP_MAKER"]["finding101"][field] = (
        "서울반도체의 차세대반도체 소자 공정 검증 준비를 검토한다."
        if field == "reason"
        else "서울반도체의 차세대반도체 소자가 공정 검증에 적용되는 경우"
    )
    with pytest.raises(service.ReportAssessmentValidationError) as caught:
        full_validate(response(value, source), source)
    assert caught.value.failed_finding_ids == (101,)
    assert "기업명: 서울반도체" in str(caught.value)
    assert "기업명: 차세대반도체" not in str(caught.value)
    assert any(
        issue.field
        == f"assessments[101].{'reason' if field == 'reason' else 'decision.connection.condition'}"
        and issue.rule == "company"
        for issue in caught.value.validation_issues
    )


@pytest.mark.parametrize(
    "value,expected",
    [
        ("차세대반도체 소자 200개의 공정 검증 준비를 검토한다.", "숫자: 200"),
        ("내년 차세대반도체 소자의 공정 검증 준비를 검토한다.", "날짜 표현: 내년"),
        ("차세대반도체 소자의 공정 검증을 완료했다.", "단계"),
    ],
)
def test_category_exception_preserves_number_date_and_event_state_errors(value, expected):
    assert any(expected in error for error in factual_mismatches(value, SOURCE))
    with pytest.raises(ValueError):
        validate_prose(value, SOURCE)


def test_category_exception_does_not_remove_another_company_or_combined_fact_errors():
    value = "삼성전자는 내년 차세대반도체 소자 200개의 공정 검증을 완료했다."
    errors = factual_mismatches(value, SOURCE)
    for expected in ("기업명: 삼성전자", "숫자: 200", "날짜 표현: 내년", "단계"):
        assert any(expected in error for error in errors)
    assert all("기업명: 차세대반도체" not in error for error in errors)
    with pytest.raises(ValueError):
        validate_prose(value, SOURCE)


@pytest.mark.parametrize("source_spaced", [False, True])
@pytest.mark.parametrize("claim_spaced", [False, True])
def test_general_category_keeps_its_numeric_binding_across_spacing(source_spaced, claim_spaced):
    source_category = "차세대 반도체" if source_spaced else "차세대반도체"
    source_power = "전력 반도체" if source_spaced else "전력반도체"
    claim_category = "차세대 반도체" if claim_spaced else "차세대반도체"
    source = f"{source_category}는 30개이고 {source_power}는 20개다."
    correct = f"{claim_category}는 30개다."
    swapped = f"{claim_category}는 20개다."
    assert factual_mismatches(correct, source) == []
    validate_prose(correct, source)
    errors = factual_mismatches(swapped, source)
    assert any(error.startswith("근거와 연결이 다른 숫자:") for error in errors)
    assert all("기업명" not in error for error in errors)
    with pytest.raises(ValueError):
        validate_prose(swapped, source)


@pytest.mark.parametrize("category", ["전력반도체", "차세대 반도체"])
@pytest.mark.parametrize(
    "subject,other",
    [("삼성전자는", "서울반도체는"), ("2026년에는", "2027년에는")],
)
def test_category_scope_cannot_hide_existing_company_or_date_quantity_conflicts(
    category, subject, other
):
    source = f"{subject} 30개를 생산했다. {other} {category} 20개를 생산했다."
    value = f"{subject} {category} 20개를 생산했다."
    errors = factual_mismatches(value, source)
    assert any(error.startswith("근거와 연결이 다른 숫자:") for error in errors)
    with pytest.raises(ValueError):
        validate_prose(value, source)


def test_category_reason_passes_map_and_review_without_an_unnecessary_retry():
    source = request(text=SOURCE)
    before = source.model_dump_json()

    def hook(stage, _, data, value):
        if stage.startswith(("MAP", "REVIEW")):
            return category_value(source)
        for insight in value["insights"]:
            insight.update(
                headline="차세대반도체 소자의 공정 검증 준비를 살펴본다.",
                overview=[
                    {
                        "text": REASON,
                        "basisClaimIds": ["101:0"],
                        "assumption": CONDITION,
                    }
                ],
            )
        return value

    provider = V4Provider(source, hook=hook)
    result = generate(provider, source)
    assert stages(provider) == ["MAP-001", "REVIEW-001", "REDUCE-001"]
    assert all(provider.schema_validity)
    entry = result.insights[0].assessments[0]
    assert entry.reason == _expected_fixture_display_reason(source, 101, REASON)
    assert entry.basis_claim_ids == ["101:0"]
    assert entry.axes.directness == 3
    assert result.meta.input_tokens == 33 and result.meta.output_tokens == 21
    assert result.meta.cost_usd == 0.009 and result.meta.credits == 0.6
    assert source.model_dump_json() == before
