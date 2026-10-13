"""Index spacing is an exact source alias, never a generic numeric exemption."""

from copy import deepcopy
from types import SimpleNamespace

import pytest
from test_report_insight_assessment import payload, request
from test_report_insight_grounded_prose import _checked_map

from app.core.evidence import factual_mismatches
from app.llm import report_insight_service as service
from app.llm.report_insight_fact_graph import analyze_sentence
from app.llm.report_insight_guard import (
    _source_identifier_mismatches,
    report_prose_mismatches,
)
from app.llm.report_insight_index_identifiers import S_AND_P_INDEX


def validate(value, source):
    service._validate_prose(
        [value], ["101:0"], {"101:0": source}, {"101:0": SimpleNamespace(text=source)}
    )


@pytest.mark.parametrize(
    "prose_index,source_index",
    [
        ("S&P500", "S&P 500"),
        ("S&P 500", "S&P500"),
        ("S & P 500", "S&P500"),
        ("S&P500", "S & P 500"),
        ("S\t&\tP\t500", "S&P 500"),
        ("S&P\u00a0500", "S&P500"),
        ("s&p500", "S&P 500"),
        ("S&P600", "S&P 600"),
        ("S&P 1500", "S&P1500"),
    ],
)
def test_same_complete_source_index_accepts_horizontal_spacing_in_both_directions(
    prose_index, source_index
):
    value = f"{prose_index} 지수의 흐름을 확인한다."
    source = f"{source_index} 지수의 흐름이 관찰됐다."
    before = value, source
    assert _source_identifier_mismatches(value, source) == []
    validate(value, source)
    assert (value, source) == before


@pytest.mark.parametrize(
    "value,source",
    [
        ("S&P 500과 S&P500 지수의 흐름", "S&P500 지수의 흐름"),
        ("S&P500과 S&P 600 지수의 흐름", "S&P 500과 S&P600 지수의 흐름"),
        ("S & P 500과 S&P600 지수의 흐름", "S&P500과 S & P 600 지수의 흐름"),
    ],
)
def test_repeated_and_multiple_indices_match_each_full_identifier(value, source):
    validate(value, source)


@pytest.mark.parametrize(
    "value,source",
    [
        ("S&P500", "S&P 600"),
        ("S&P600", "S&P 500"),
        ("S&P5000", "S&P 500"),
        ("S&P500", "S&P 5000"),
        ("S&P0500", "S&P 500"),
        ("S&P 500", "지수는 500포인트다."),
        ("S&P500", "S&P600과 P500"),
        ("S&P 500", "P500"),
        ("S&P500과 S&P 600", "S&P500만 관찰됐다."),
        ("XS&P500", "S&P 500"),
        ("S&P500", "XS&P 500"),
        ("S&P500A", "S&P 500"),
        ("S&P500", "S&P 500A"),
        ("S&P500", "S&P 500_수정"),
        ("S&P500.0", "S&P 500"),
        ("S&P500", "S&P 500.0"),
        ("S&P500", "다른 지수의 흐름"),
    ],
)
def test_index_number_and_identifier_boundaries_are_not_relaxed(value, source):
    assert _source_identifier_mismatches(value, source)
    with pytest.raises(ValueError):
        validate(value, source)


@pytest.mark.parametrize("separator", ["\n", "\r\n", "\v", "\f", "\u2028", "\u2029"])
def test_index_cannot_be_assembled_across_line_boundaries(separator):
    with pytest.raises(ValueError):
        validate("S&P500 지수", f"S&P{separator}500 지수")


def test_index_alias_does_not_match_an_underscored_identifier_prefix():
    assert S_AND_P_INDEX.search("S&P500_수정") is None


@pytest.mark.parametrize(
    "value,source",
    [
        ("P500", "S&P500"),
        ("P500", "S&P 500"),
        ("S&P500", "P500"),
        ("HBM4", "HBM 4"),
        ("HBM 4", "HBM4"),
        ("S&P 500과 HBM4", "S&P500과 HBM"),
        ("S&P 500과 HBM5", "S&P500과 HBM4"),
    ],
)
def test_index_alias_cannot_authorize_an_independent_product_or_product_spacing(value, source):
    with pytest.raises(ValueError):
        validate(value, source)


def test_standalone_product_remains_supported_when_separately_present_in_source():
    validate("S&P 500 지수와 P500 제품", "S&P500 지수와 P500 제품을 관찰했다.")


@pytest.mark.parametrize(
    "value,source",
    [
        ("S&P 500 지수와 서버 500개", "S&P500 지수의 흐름이 관찰됐다."),
        ("S&P 500 지수와 500억원", "S&P500 지수의 흐름이 관찰됐다."),
        ("S&P 500 지수와 6%의 변화", "S&P500 지수와 5%의 변화가 관찰됐다."),
        ("2027년 S&P 500 지수의 흐름", "2026년 S&P500 지수의 흐름이 관찰됐다."),
        ("TSMC의 S&P 500 지수 관찰", "S&P500 지수의 흐름이 관찰됐다."),
    ],
)
def test_comparison_view_keeps_other_quantities_dates_and_companies(value, source):
    assert factual_mismatches(value, source)
    with pytest.raises(ValueError):
        validate(value, source)


def test_reverse_spacing_rechecks_numbers_without_erasing_another_same_number_occurrence():
    source = "S&P500 지수의 흐름이 관찰됐다."
    value = "S&P 500 지수와 서버 500개를 확인한다."
    mismatches = factual_mismatches(value, source)
    assert mismatches == ["근거에서 확인되지 않는 숫자: 500"]
    assert report_prose_mismatches(value, source, mismatches, modality_reason=None) == mismatches
    # The index's name alone has no standalone quantity assertion.
    validate("S&P 500 지수의 흐름을 확인한다.", source)


@pytest.mark.parametrize("field", ["reason", "condition"])
@pytest.mark.parametrize("source_index,prose_index", [("S&P 500", "S&P500"), ("S&P500", "S&P 500")])
def test_native_prose_accepts_alias_without_rewriting_any_source_or_literal_basis(
    field, source_index, prose_index
):
    source = request(audiences=("MARKET_INVESTOR",), text=f"{source_index} 지수 흐름이 관찰됐다.")
    value = payload(source, relation="CONDITIONAL" if field == "condition" else "DIRECT")
    entry = value["assessments"]["MARKET_INVESTOR"]["finding101"]
    entry.update(
        impactScope="UNDETERMINED",
        impactBasis=None,
        urgencyState="MONITOR",
        reason=f"{prose_index} 지수 흐름은 투자 전망 판단에 참고할 관찰 정보다.",
    )
    if field == "condition":
        entry["condition"] = f"{prose_index} 지수 흐름이 투자 전망 판단에 반영되는 경우"
    before = deepcopy((source.model_dump(), value))
    native, _ = _checked_map(source, value)
    assert native.evidence["MARKET_INVESTOR"][101].relation_basis.quote == (
        source.findings[0].sentences[0].text
    )
    assert (source.model_dump(), value) == before


def test_raw_selected_source_cannot_borrow_index_from_claim_summary_or_other_finding():
    source = request(ids=(101, 102), audiences=("MARKET_INVESTOR",), text="S&P 600 지수 관찰")
    source.findings[0].claims[0].text = "S&P 500 지수 관찰"
    source.findings[1].claims[0].text = "S&P 500 지수 관찰"
    source.findings[1].sentences[0].text = "S&P 500 지수 관찰"
    claims, evidence = service._source_context(source)
    errors = service._prose_validation_errors(
        ["S&P500 지수 흐름을 확인한다."], ["101:0"], evidence, claims, request=source
    )
    assert any("source_binding" in error.fact_repair_kinds for error in errors)
    assert all("S&P500" in str(error) for error in errors)


@pytest.mark.parametrize(
    "value,source",
    [
        (
            "2027년 S&P 500 지수는 20% 상승했다.",
            "2027년 S&P500 지수는 10% 상승했다. 2028년 S&P600 지수는 20% 상승했다.",
        ),
        (
            "삼성전자는 S&P 500 지수에서 20% 상승했다.",
            "삼성전자는 S&P500 지수에서 10% 상승했다. SK하이닉스는 S&P600 지수에서 20% 상승했다.",
        ),
    ],
)
def test_existing_company_and_date_numeric_anchors_survive_index_number_recheck(value, source):
    remaining = report_prose_mismatches(
        value, source, factual_mismatches(value, source), modality_reason=None
    )
    assert any(error.startswith("근거와 연결이 다른 숫자:") for error in remaining)
    with pytest.raises(ValueError):
        validate(value, source)


@pytest.mark.parametrize("source_index,prose_index", [("S&P500", "S&P 500"), ("S&P 500", "S&P500")])
@pytest.mark.parametrize("metric,quantity", [("순이익", "% 증가했다"), ("매출", "억원이다")])
def test_spaced_index_cannot_borrow_another_indexs_quantity(
    source_index, prose_index, metric, quantity
):
    source = request(
        audiences=("MARKET_INVESTOR",),
        text=f"{source_index}의 {metric}은 10{quantity}. S&P600의 {metric}은 20{quantity}.",
    )
    claims, evidence = service._source_context(source)
    value = f"{prose_index}의 {metric}은 20{quantity}."
    errors = service._assessment_field_errors(
        value, ["101:0"], evidence, claims, source, audience="MARKET_INVESTOR"
    )
    assert any("동일 주체·사건·대상·시점의 수치 충돌" in str(error) for error in errors)
    valid = service._assessment_field_errors(
        value.replace("20", "10"), ["101:0"], evidence, claims, source, audience="MARKET_INVESTOR"
    )
    assert not valid


def test_graph_index_alias_retains_original_source_spans():
    source = "S & P 500의 매출은 10억원이다."
    analysis = analyze_sentence(source)
    (relation,) = analysis.relations
    (subject,) = relation.subjects
    assert subject.value == "s&p500"
    assert subject.span.text == "S & P 500"
    assert subject.span.matches(source)
    assert all(mention.span.matches(source) for mention in analysis.mentions)


def test_index_number_recheck_never_removes_an_independent_graph_quantity_conflict():
    value = "S&P 500의 순이익은 20% 증가했다."
    source = "S&P500의 순이익은 10% 증가했다. S&P600의 순이익은 20% 증가했다."
    conflict = "근거와 연결이 다른 숫자: 동일 주체·사건·대상·시점의 수치 충돌"
    remaining = report_prose_mismatches(
        value, source, [*factual_mismatches(value, source), conflict], modality_reason=None
    )
    assert conflict in remaining
