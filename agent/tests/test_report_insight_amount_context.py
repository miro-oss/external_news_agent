"""Synthetic monetary annotations preserve their own company, metric and period."""

from types import SimpleNamespace

import pytest
from test_report_insight_assessment import payload, request
from test_report_insight_grounded_prose import _checked_map

from app.core.evidence import factual_mismatches
from app.llm.report_insight_amount_context import supported_labeled_amount_context
from app.llm.report_insight_guard import report_prose_mismatches
from app.llm.report_insight_service import _validate_prose

ESTIMATE = "가온전자의 3분기 연결 영업이익 전망치(컨센서스)는 21조9435억원으로 집계됐다."
TARGET = "가온전자의 3분기 영업이익 20조원대 달성 가능성이 유력하다."
SOURCE = ESTIMATE + "\n" + TARGET
PROSE = (
    "증권사 컨센서스(약 21조9435억원)가 3분기 연결 영업이익을 높게 제시해 "
    "분기 영업익 20조원대 달성 가능성이 투자 관점의 수익성 근거로 연결된다."
)


def validate(prose=PROSE, source=SOURCE):
    _validate_prose([prose], ["101:0"], {"101:0": source}, {"101:0": SimpleNamespace(text=source)})


@pytest.mark.parametrize(
    "prose,source",
    [
        (PROSE, SOURCE),
        (PROSE.replace("컨센서스", "추정치"), SOURCE.replace("컨센서스", "추정치")),
        (
            PROSE.replace("영업이익을 높게 제시해 분기 영업익", "매출"),
            SOURCE.replace("영업이익", "매출"),
        ),
        (
            PROSE.replace("21조9435억원", "19조7321억원").replace("20조원", "18조원"),
            SOURCE.replace("21조9435억원", "19조7321억원").replace("20조원", "18조원"),
        ),
        (PROSE.replace("증권사 ", "가온전자의 "), SOURCE),
    ],
)
def test_same_context_amounts_in_separate_source_clauses_are_independently_proven(prose, source):
    assert any("연결이 다른 숫자" in error for error in factual_mismatches(prose, source))
    assert supported_labeled_amount_context(prose, source)
    validate(prose, source)


@pytest.mark.parametrize(
    "prose,source",
    [
        (PROSE.replace("21조9435억원", "22조9435억원"), SOURCE),
        (PROSE.replace("20조원", "22조원"), SOURCE),
        (PROSE.replace("증권사 ", "TSMC의 "), SOURCE),
        (PROSE.replace("3분기", "4분기"), SOURCE),
        (PROSE, ESTIMATE.replace("3분기", "4분기") + "\n" + TARGET),
        (PROSE, ESTIMATE + "\n" + TARGET.replace("3분기", "4분기")),
        (PROSE, ESTIMATE + "\n" + TARGET.replace("가온전자", "누리전자")),
        (PROSE, ESTIMATE.replace("영업이익", "매출") + "\n" + TARGET),
        (PROSE, ESTIMATE + "\n" + TARGET.replace("영업이익", "투자액")),
        (PROSE, ESTIMATE.replace("컨센서스", "컨센서스조정") + "\n" + TARGET),
        (
            PROSE.replace("21조9435억원", "TEMP")
            .replace("20조원", "21조9435억원")
            .replace("TEMP", "20조원"),
            SOURCE,
        ),
        (
            PROSE,
            ESTIMATE.replace("3분기", "2027년 3분기")
            + "\n"
            + TARGET.replace("3분기", "2028년 3분기"),
        ),
        (PROSE, ESTIMATE.replace("3분기", "내년 3분기") + "\n" + TARGET),
        (PROSE.replace("연결 영업이익", "연결 매출"), SOURCE),
        (PROSE.replace("21조9435억원", "21조9435억달러"), SOURCE),
        (PROSE.replace("증권사 ", "생산량 30개와 증권사 "), SOURCE),
    ],
)
def test_amounts_cannot_borrow_their_label_metric_owner_period_or_unit(prose, source):
    assert not supported_labeled_amount_context(prose, source)
    with pytest.raises(ValueError):
        validate(prose, source)


def test_unparsed_or_unlabeled_multiple_amounts_do_not_get_a_general_union_exception():
    prose = PROSE.replace("컨센서스(약 21조9435억원)", "컨센서스 21조9435억원")
    assert not supported_labeled_amount_context(prose, SOURCE)
    with pytest.raises(ValueError):
        validate(prose)


def test_native_map_validates_the_same_synthetic_source_and_prose():
    source = request(audiences=("MARKET_INVESTOR",), text=SOURCE)
    value = payload(source)
    item = value["assessments"]["MARKET_INVESTOR"]["finding101"]
    item["reason"] = PROSE
    _, mapped = _checked_map(source, value)
    assert PROSE in mapped.insights[0].assessments[0].reason


def test_amount_proof_does_not_erase_a_new_asserted_event():
    prose = PROSE.rstrip(".") + " 공급 계약을 체결했다."
    with pytest.raises(ValueError):
        validate(prose)


@pytest.mark.parametrize(
    "estimate_scope,target_scope",
    [
        ("반도체 부문 ", "전사 "),
        ("반도체사업부 ", "가전사업부 "),
        ("연결 ", "별도 "),
        ("메모리사업 ", ""),
        ("HBM ", "DDR "),
    ],
)
def test_same_metric_does_not_merge_different_business_or_accounting_scopes(
    estimate_scope, target_scope
):
    source = (
        ESTIMATE.replace("연결 영업이익", estimate_scope + "영업이익")
        + "\n"
        + TARGET.replace("영업이익", target_scope + "영업이익")
    )
    assert not supported_labeled_amount_context(PROSE, source)
    with pytest.raises(ValueError):
        validate(PROSE, source)


@pytest.mark.parametrize("target_owner", [None, "나래"])
def test_a_mentioned_company_cannot_replace_the_actual_metric_owner(target_owner):
    estimate = ESTIMATE.replace("가온전자의", "가온전자는 해솔의")
    target = (
        TARGET.replace("가온전자의", f"가온전자는 {target_owner}의") if target_owner else TARGET
    )
    source = estimate + "\n" + target
    assert not supported_labeled_amount_context(PROSE, source)
    with pytest.raises(ValueError):
        validate(PROSE, source)


def test_an_unresolved_product_qualification_cannot_become_the_generic_metric():
    source = ESTIMATE + "\n" + TARGET.replace("영업이익", "해외에서 생산하는 HBM 영업이익")
    assert not supported_labeled_amount_context(PROSE, source)
    with pytest.raises(ValueError):
        validate(PROSE, source)


def test_a_literal_bare_metric_quote_retains_its_observed_scope_after_a_preamble():
    target = (
        "가온전자가 3분기 실적을 발표할 예정인 가운데 사상 처음으로 "
        "‘분기 영업이익 20조원’ 시대를 열 것으로 관측된다."
    )
    assert supported_labeled_amount_context(PROSE, ESTIMATE + "\n" + target)
    validate(PROSE, ESTIMATE + "\n" + target)


@pytest.mark.parametrize(
    "replacement",
    ["이 아니다", "으로 집계되지 않았다", "이라면 검토한다"],
)
def test_a_negated_or_hypothetical_annotation_is_not_an_observed_estimate(replacement):
    source = ESTIMATE.replace("으로 집계됐다", replacement) + "\n" + TARGET
    assert not supported_labeled_amount_context(PROSE, source)
    with pytest.raises(ValueError):
        validate(PROSE, source)


@pytest.mark.parametrize(
    "replacement",
    ["최대 21조9435억원", "21조9435억원 이하", "21조9435억원 미만", "최소 21조9435억원"],
)
def test_an_upper_or_lower_bound_is_not_the_central_estimate(replacement):
    source = ESTIMATE.replace("21조9435억원", replacement) + "\n" + TARGET
    assert not supported_labeled_amount_context(PROSE, source)
    with pytest.raises(ValueError):
        validate(PROSE, source)


@pytest.mark.parametrize(
    "assertion",
    [
        "이미 달성했다는 사실이",
        "이미 확정됐다는 사실이",
        "이미 달성했고 이후 수익성 조건이 반영될 경우",
    ],
)
def test_forecast_amounts_cannot_be_promoted_to_achieved_results(assertion):
    prose = PROSE.replace("달성 가능성이", assertion)
    assert not supported_labeled_amount_context(prose, SOURCE)
    with pytest.raises(ValueError):
        validate(prose)


def test_only_the_exact_proven_legacy_numeric_context_diagnostic_is_removed():
    other = "근거와 연결이 다른 숫자: 동일 주체·대상·시점의 수치 충돌"
    errors = report_prose_mismatches(
        PROSE,
        SOURCE,
        [*factual_mismatches(PROSE, SOURCE), other],
        modality_reason=None,
        fact_source=SOURCE,
    )
    assert errors == [other]
