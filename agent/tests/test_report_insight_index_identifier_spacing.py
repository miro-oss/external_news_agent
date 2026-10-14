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
from app.llm.report_insight_index_identifiers import NASDAQ_INDEX, S_AND_P_INDEX


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


NASDAQ_CONDITION = (
    "나스닥100의 약세와 국제 유가·미국 10년물 금리 상승이 동시에 관찰되어 시장 전반의 "
    "위축 신호로 작용한다는 전제가 성립할 때"
)


def nasdaq_duration_case():
    # Minimal selected claims/raw sentences from report 2005, finding 8303.
    # Keep the observed numbers; no captured files are read at test runtime.
    sentences = {
        0: "[초이스경제 조미정 기자] 12일(미국시간) 뉴욕증권거래소에 따르면 이날 "
        "뉴욕증시에서 나스닥100 지수는 3만664.70으로 218.45포인트(0.71%) 하락했다.",
        1: "이날 나스닥100 지수는 정규장 내내 약세 흐름을 보였다.",
        2: "이날엔 중동 불안에 따른 국제 유가 및 미국 10년물 국채금리 상승 속에 "
        "뉴욕증시 전반이 위축됐다.",
        11: '미국 경제방송 씨엔비씨는 "이날 미국증시 마감 5분전 11월 인도분 '
        "미국서부텍사스산원유(WTI) 가격이 배럴당 93.30 달러로 1.58%나 오르고 이 시각 "
        "벤치마크 금리인 10년물 미국 국채금리도 5.242%로 직전 거래일의 5.233% 대비 "
        '높아지면서 뉴욕증시 전반을 불안케 했다"고 전했다.',
    }
    source = request(ids=(8303,), audiences=("MARKET_INVESTOR",))
    finding = source.findings[0]
    claim, sentence = finding.claims[0], finding.sentences[0]
    finding.claims = [
        claim.model_copy(
            update={
                "id": "8303:0",
                "text": "12일 나스닥100이 0.71% 하락하며 약세 마감했다.",
                "evidence_sentence_ids": [0, 1],
            }
        ),
        claim.model_copy(
            update={
                "id": "8303:4",
                "text": "국제 유가와 미국 10년물 금리 상승이 시장을 압박했다.",
                "evidence_sentence_ids": [2, 11],
            }
        ),
    ]
    finding.sentences = [
        sentence.model_copy(update={"index": index, "text": text})
        for index, text in sentences.items()
    ]
    return source


def nasdaq_errors(source, prose=NASDAQ_CONDITION, refs=("8303:0", "8303:4")):
    claims, evidence = service._source_context(source)
    return service._assessment_field_errors(
        prose,
        list(refs),
        evidence,
        claims,
        source,
        audience="MARKET_INVESTOR",
        conditional=True,
    )


def test_captured_nasdaq_index_and_treasury_maturity_keep_distinct_numeric_roles():
    source = nasdaq_duration_case()
    before = source.model_dump_json()
    assert nasdaq_errors(source) == []
    assert nasdaq_errors(source, NASDAQ_CONDITION + " 미국 국채금리는 5.242%다.") == []
    assert source.model_dump_json() == before


@pytest.mark.parametrize("maturity", ["20", "100", "1000"])
def test_index_suffix_cannot_supply_another_treasury_maturity(maturity):
    assert nasdaq_errors(
        nasdaq_duration_case(), NASDAQ_CONDITION.replace("10년물", maturity + "년물")
    )


@pytest.mark.parametrize("index", ["나스닥10", "나스닥101", "나스닥1000", "S&P100", "S&P500"])
def test_selected_maturity_or_other_index_cannot_supply_a_numbered_index(index):
    errors = nasdaq_errors(nasdaq_duration_case(), NASDAQ_CONDITION.replace("나스닥100", index))
    assert any("source_binding" in error.fact_repair_kinds for error in errors)


@pytest.mark.parametrize(
    "extra",
    [
        " 미국 국채금리는 100%다.",
        " 미국 국채금리는 5.243%다.",
        " 나스닥100은 10% 상승했다.",
        " WTI 가격은 100달러다.",
        " WTI 가격은 93.31달러다.",
        " 삼성전자는 서버 100개를 공급한다.",
    ],
)
def test_index_number_comparison_keeps_other_rates_amounts_and_owners(extra):
    assert nasdaq_errors(nasdaq_duration_case(), NASDAQ_CONDITION + extra)


@pytest.mark.parametrize("refs", [("8303:0",), ("8303:4",)])
def test_nasdaq_and_duration_each_require_their_selected_raw_source(refs):
    assert nasdaq_errors(nasdaq_duration_case(), refs=refs)


@pytest.mark.parametrize("index", ["나스닥100", "NASDAQ100"])
def test_numbered_nasdaq_identifier_requires_the_whole_name_not_a_quantity(index):
    validate(index + " 지수 흐름을 확인한다.", index + " 지수 흐름이 관찰됐다.")
    for source in ("100억원 규모다.", "S&P100 지수가 관찰됐다.", "서버 100개를 구매했다."):
        assert _source_identifier_mismatches(index, source)
        with pytest.raises(ValueError):
            validate(index + " 지수 흐름을 확인한다.", source)


@pytest.mark.parametrize(
    "index", ["X나스닥100", "나스닥100X", "나스닥100_수정", "나스닥100.0", "나스닥100억원"]
)
def test_nasdaq_identifier_does_not_mask_an_embedded_prefix_or_currency(index):
    assert NASDAQ_INDEX.search(index) is None
    assert _source_identifier_mismatches("나스닥100", index)


@pytest.mark.parametrize("scope", ["claim_summary", "unselected_claim", "title"])
def test_nasdaq_identifier_cannot_be_borrowed_outside_selected_raw(scope):
    source = request(ids=(101,), audiences=("MARKET_INVESTOR",), text="금리는 10%로 관찰됐다.")
    finding = source.findings[0]
    index_source = "나스닥100의 약세가 관찰됐다."
    if scope == "claim_summary":
        finding.claims[0].text += " " + index_source
    elif scope == "title":
        finding.article_title = index_source
    else:
        finding.claims.append(
            finding.claims[0].model_copy(
                update={"id": "101:1", "text": index_source, "evidence_sentence_ids": [1]}
            )
        )
        finding.sentences.append(
            finding.sentences[0].model_copy(update={"index": 1, "text": index_source})
        )
    errors = nasdaq_errors(source, "나스닥100의 약세는 투자 전망의 배경이다.", ("101:0",))
    assert any("source_binding" in error.fact_repair_kinds for error in errors)
