"""Independent acceptance cases drawn from anonymized report sentence patterns.

The cases distinguish an actual subject/metric/time binding from merely finding
the same words or numbers somewhere in the source. No live report is a fixture.
"""

import hashlib

import pytest

from app.llm.report_insight_fact_graph import analyze_sentence
from app.llm.report_insight_fact_verification import compare_source_facts, fact_graph_mismatches


@pytest.mark.parametrize(
    ("source", "subject", "predicate", "quantity", "time"),
    [
        ("오로라전자의 생산량은 20개다.", "오로라전자", "production_volume", "20", None),
        ("한빛소재의 매출은 3억원이다.", "한빛소재", "revenue", "300000000", None),
        (
            "오로라전자의 매출은 2026년 2조원에서 2030년 3조원으로 증가할 전망이다.",
            "오로라전자",
            "revenue",
            "2000000000000",
            "2026",
        ),
        (
            "오로라전자의 매출은 2026년 2조원에서 2030년 3조원으로 증가할 전망이다.",
            "오로라전자",
            "revenue",
            "3000000000000",
            "2030",
        ),
        (
            "오로라전자의 냉각 대응용량은 2026년 2.9GW에서 2030년 10.5GW로 증가할 전망이다.",
            "오로라전자",
            "cooling_capacity",
            "2900000000",
            "2026",
        ),
        (
            "오로라전자의 냉각 대응용량은 2026년 2.9GW에서 2030년 10.5GW로 증가할 전망이다.",
            "오로라전자",
            "cooling_capacity",
            "10500000000",
            "2030",
        ),
        (
            "Aurora Electronics' revenue is 2 million USD.",
            "aurora electronics",
            "revenue",
            "2000000",
            None,
        ),
    ],
)
def test_report_style_metrics_bind_owner_metric_quantity_and_time(
    source, subject, predicate, quantity, time
):
    analysis = analyze_sentence(source)
    matching = [
        relation
        for relation in analysis.relations
        if subject.casefold() in {item.value.casefold() for item in relation.subjects}
        and relation.predicate == predicate
        and relation.quantity is not None
        and relation.quantity.value == quantity
        and (relation.time.value if relation.time else None) == time
    ]
    assert matching, "Finding independent actor/number mentions is not a bound source fact."
    assert any(not relation.uncertainty for relation in matching)


@pytest.mark.parametrize(
    "source",
    [
        "오로라전자의 생산량은 20개다. 한빛소재의 생산량은 10개다.",
        "오로라전자의 매출은 2026년 2조원에서 2030년 3조원으로 증가할 전망이다.",
        "Aurora Electronics' revenue is 2 million USD.",
        "오로라전자는 2028년 공장을 완공할 계획이다.",
        "오로라전자는 한빛소재와 공급 계약을 체결했다.",
        "오로라전자의 영업이익은 약 4000억원이다.",
    ],
)
def test_every_relation_and_slot_has_a_literal_anchor_in_its_own_source(source):
    analysis = analyze_sentence(source)
    assert analysis.source_sha256 == hashlib.sha256(source.encode()).hexdigest()
    assert analysis.relations
    for relation in analysis.relations:
        assert source[relation.span.start : relation.span.end] == relation.span.text
        slots = [
            *relation.subjects,
            relation.target,
            relation.quantity,
            relation.time,
            *relation.bindings,
        ]
        if relation.attributed_to is not None:
            slots.append(relation.attributed_to)
        for mention in filter(None, slots):
            assert source[mention.span.start : mention.span.end] == mention.span.text
            assert relation.span.start <= mention.span.start < mention.span.end <= relation.span.end


@pytest.mark.parametrize(
    ("source", "candidate"),
    [
        ("오로라전자의 생산량은 20개다.", "오로라전자의 생산량은 20개다."),
        (
            "오로라전자의 생산량은 20개다. 한빛소재의 생산량은 10개다.",
            "한빛소재의 생산량은 10개다.",
        ),
        ("오로라전자의 매출은 2억원이다.", "오로라전자의 매출은 20000만원이다."),
        (
            "오로라전자의 매출은 2026년 2조원에서 2030년 3조원으로 증가할 전망이다.",
            "오로라전자의 매출은 2030년 3조원으로 전망된다.",
        ),
        (
            "오로라전자는 2028년 공장을 완공할 계획이다.",
            "오로라전자는 2028년 공장을 완공할 계획이다.",
        ),
    ],
)
def test_supported_bound_facts_are_recognized_without_raw_word_order_dependency(source, candidate):
    checks = compare_source_facts(candidate, [source])
    assert checks and any(check.outcome == "supported" for check in checks)
    assert not any(check.outcome == "contradicted" for check in checks)
    assert fact_graph_mismatches(candidate, [source]) == []


@pytest.mark.parametrize(
    ("source", "candidate"),
    [
        (
            "오로라전자의 생산량은 20개다. 한빛소재의 생산량은 10개다.",
            "오로라전자의 생산량은 10개다.",
        ),
        (
            "오로라전자의 생산량은 20개다. 한빛소재의 생산량은 10개다.",
            "한빛소재의 생산량은 20개다.",
        ),
        (
            "오로라전자의 매출은 20억원이다. 오로라전자의 영업이익은 10억원이다.",
            "오로라전자의 매출은 10억원이다.",
        ),
        (
            "오로라전자의 매출은 2026년 2조원에서 2030년 3조원으로 증가할 전망이다.",
            "오로라전자의 매출은 2030년 2조원으로 전망된다.",
        ),
        (
            "오로라전자의 냉각 대응용량은 2026년 2.9GW에서 2030년 10.5GW로 증가할 전망이다.",
            "오로라전자의 냉각 대응용량은 2030년 2.9GW로 전망된다.",
        ),
        (
            "오로라전자는 공장을 완공할 계획이다. 한빛소재는 공장을 완공했다.",
            "오로라전자는 공장을 완공했다.",
        ),
    ],
)
def test_same_source_numbers_and_completion_cannot_be_borrowed_from_another_fact(source, candidate):
    checks = compare_source_facts(candidate, [source])
    assert checks and any(check.outcome == "contradicted" for check in checks)
    assert fact_graph_mismatches(candidate, [source])


@pytest.mark.parametrize(
    ("sources", "candidate"),
    [
        (["오로라전자가 제품을 발표했다.", "생산량은 20개다."], "오로라전자의 생산량은 20개다."),
        (["한빛소재의 생산량은 20개다."], "오로라전자의 생산량은 20개다."),
        (["오로라전자의 생산량은 20개다."], "오로라전자의 공급량은 20개다."),
        (["오로라전자의 매출은 2026년 2조원이다."], "오로라전자의 매출은 2030년 2조원이다."),
        (["그 회사의 생산량은 20개다."], "오로라전자의 생산량은 20개다."),
        (["오로라전자와 한빛소재의 생산량은 20개와 10개다."], "오로라전자의 생산량은 20개다."),
    ],
)
def test_missing_or_ambiguous_bindings_never_become_positive_support(sources, candidate):
    checks = compare_source_facts(candidate, sources)
    assert checks and all(check.outcome == "unknown" for check in checks)
    assert fact_graph_mismatches(candidate, sources) == []


@pytest.mark.parametrize(
    ("source", "candidate"),
    [
        ("오로라전자의 생산량은 약 20개다.", "오로라전자의 생산량은 20개다."),
        ("오로라전자의 생산량은 20개 이상이다.", "오로라전자의 생산량은 20개다."),
        ("오로라전자의 생산량은 10~20개다.", "오로라전자의 생산량은 20개다."),
        (
            "한빛증권은 오로라전자의 매출이 2030년 3조원에 이를 것으로 전망했다.",
            "오로라전자의 매출은 2030년 3조원이다.",
        ),
    ],
)
def test_approximation_bounds_and_attributed_forecasts_do_not_prove_exact_observations(
    source, candidate
):
    checks = compare_source_facts(candidate, [source])
    assert checks
    assert all(check.outcome != "supported" for check in checks)


def test_unparsed_text_remains_visible_instead_of_becoming_a_fabricated_relation():
    source = "그 회사의 상황은 이후 달라졌을 수도 있다."
    analysis = analyze_sentence(source)
    assert analysis.unresolved
    assert not any(not relation.uncertainty for relation in analysis.relations)


def test_unrelated_company_does_not_become_owner_of_a_later_metric():
    source = "오로라전자가 제품을 발표했다. 이후 생산량은 20개로 제시됐다."
    checks = compare_source_facts("오로라전자의 생산량은 20개다.", [source])
    assert checks and all(check.outcome == "unknown" for check in checks)


@pytest.mark.parametrize(
    ("source", "candidate", "outcome"),
    [
        ("오로라전자의 생산량은 20개다.", "오로라전자의 생산량은 20개 이상이다.", "supported"),
        ("오로라전자의 생산량은 20개 이상이다.", "오로라전자의 생산량은 10개다.", "contradicted"),
        ("오로라전자의 생산량은 20개 이하이다.", "오로라전자의 생산량은 30개다.", "contradicted"),
        ("오로라전자의 생산량은 10~20개다.", "오로라전자의 생산량은 30개다.", "contradicted"),
    ],
)
def test_numeric_bounds_use_interval_meaning_instead_of_merely_matching_one_number(
    source, candidate, outcome
):
    checks = compare_source_facts(candidate, [source])
    assert checks and any(check.outcome == outcome for check in checks)


def test_unrelated_unparsed_sentence_does_not_disable_a_proven_owner_number_conflict():
    source = "오로라전자의 생산량은 20개다. 날씨에 관한 이야기가 이어졌다."
    checks = compare_source_facts("오로라전자의 생산량은 10개다.", [source])
    assert checks and any(check.outcome == "contradicted" for check in checks)


def test_conflicting_source_values_are_uncertain_instead_of_arbitrarily_choosing_one():
    source = "오로라전자의 생산량은 20개다. 오로라전자의 생산량은 10개다."
    checks = compare_source_facts("오로라전자의 생산량은 30개다.", [source])
    assert checks and all(check.outcome == "unknown" for check in checks)


def test_joint_owner_total_does_not_prove_each_owner_has_the_entire_amount():
    source = "오로라전자와 한빛소재의 매출은 총 20억원이다."
    checks = compare_source_facts("오로라전자의 매출은 20억원이다.", [source])
    assert checks and all(check.outcome == "unknown" for check in checks)


def test_korean_subject_particle_matching_does_not_cut_inside_a_company_name():
    analysis = analyze_sentence("세미파이브는 제품을 양산했다.")
    assert analysis.relations
    subjects = {item.value for relation in analysis.relations for item in relation.subjects}
    assert "세미파이브" in subjects
    assert "세미파" not in subjects


@pytest.mark.parametrize(
    "source",
    [
        "40층 규모의 고성능 기판 양산이 본격화됐다.",
        "고부가 제품 양산에 집중할 전망이다.",
        "Optics Letters 51, no. 18 (2026): 5185–5188.",
        "https://doi.org/10.1364/OL.611581",
    ],
)
def test_product_modifiers_and_publication_metadata_do_not_invent_a_bound_company_fact(source):
    analysis = analyze_sentence(source)
    assert not any(
        relation.subjects and not relation.uncertainty for relation in analysis.relations
    )


def test_broker_forecast_of_another_company_share_is_not_the_brokers_own_revenue():
    source = (
        "한빛증권은 내년 차세대 제품 출하 확대로 오로라전자 D램 매출에서 "
        "HBM이 차지하는 비중이 15% 안팎으로 오를 것으로 봤다."
    )
    analysis = analyze_sentence(source)
    for relation in analysis.relations:
        if not relation.uncertainty:
            assert "한빛증권" not in {subject.value for subject in relation.subjects}
            assert relation.predicate != "revenue", "A revenue share is not the revenue itself."
            assert relation.state == "forecast"
            assert relation.attributed_to is not None
            assert relation.attributed_to.value == "한빛증권"


def test_planned_announcement_does_not_turn_the_predicted_profit_into_a_company_plan():
    source = (
        "오로라전자가 오는 8일 3분기 잠정 실적을 발표할 예정인 가운데 "
        "사상 처음으로 분기 영업이익 100조원 시대를 열 것으로 관측된다."
    )
    analysis = analyze_sentence(source)
    profits = [
        relation for relation in analysis.relations if relation.predicate == "operating_profit"
    ]
    assert profits
    assert all(relation.uncertainty or relation.state == "forecast" for relation in profits)


def test_nested_political_quote_does_not_bind_earlier_negation_to_later_supply_plan():
    source = (
        '김민수 한빛특별시장은 "반도체는 팹 하나만 세운다고 완성되는 산업이 아니다"며 '
        '"북부에서 반도체를 만들고, 동부에서 소재·부품을 공급하며, 서부의 재생에너지로 '
        '전력을 뒷받침하는 산업 생태계를 만들겠다"고 강조했다.'
    )
    analysis = analyze_sentence(source)
    for relation in analysis.relations:
        if relation.predicate == "supply" and not relation.uncertainty:
            assert relation.state != "negated"
            assert not any("시장" in subject.value for subject in relation.subjects)
            assert relation.target is None or relation.target.value != "반도체"


def test_counting_an_analyst_consensus_does_not_make_the_forecast_an_observed_profit():
    source = (
        "주요 증권사들이 제시한 오로라전자의 올해 3분기 연결 영업이익 전망치(컨센서스)는 "
        "106조9435억원으로 집계됐다."
    )
    analysis = analyze_sentence(source)
    profits = [
        relation for relation in analysis.relations if relation.predicate == "operating_profit"
    ]
    assert profits
    assert all(relation.uncertainty or relation.state == "forecast" for relation in profits)


def test_demand_in_a_causal_clause_does_not_become_the_speaker_of_a_price_forecast():
    source = (
        "증권가에 따르면 서버용 제품 수요가 폭증하면서 3분기 D램과 낸드의 "
        "평균판매단가(ASP)는 전 분기보다 각각 17~22%, 17~20% 뛰었을 것으로 추정된다."
    )
    analysis = analyze_sentence(source)
    for relation in analysis.relations:
        if relation.predicate == "selling_price" and not relation.uncertainty:
            assert relation.attributed_to is not None
            assert relation.attributed_to.value == "증권가"
            assert relation.state == "forecast"


def test_revenue_share_keeps_both_the_denominator_and_the_product_being_measured():
    source = (
        "한빛증권은 2030년 오로라전자의 D램 매출에서 HBM이 차지하는 비중이 "
        "15% 안팎으로 오를 것으로 봤다."
    )
    different_product = source.replace("HBM", "CSP")
    checks = compare_source_facts(different_product, [source])
    assert checks and all(check.outcome == "unknown" for check in checks)


def test_product_scope_before_a_time_expression_is_not_silently_discarded():
    source = (
        "업계에 따르면 오로라전자는 주력 제품이 HBM3에서 HBM4로 넘어가는 2030년 "
        "공급 가격을 현재의 2배 이상으로 올릴 계획이다."
    )
    different_products = source.replace("HBM3에서 HBM4", "DDR4에서 DDR5")
    checks = compare_source_facts(different_products, [source])
    assert checks and all(check.outcome == "unknown" for check in checks)
