"""Source-local lexer and argument binding precision, independently of a model."""

from dataclasses import FrozenInstanceError
from decimal import Decimal

import pytest

from app.llm.report_insight_fact_graph import analyze_sentence


def metric(text, predicate="revenue"):
    return next(r for r in analyze_sentence(text).relations if r.predicate == predicate)


@pytest.mark.parametrize(
    ("literal", "number", "qualifier", "upper"),
    [
        ("2조9435억원", "2943500000000", "exact", None),
        ("-2조9435억원", "-2943500000000", "exact", None),
        ("−20억원", "-2000000000", "exact", None),
        ("+20억원", "2000000000", "exact", None),
        ("약 20억원", "2000000000", "approx", None),
        ("20억원 안팎", "2000000000", "approx", None),
        ("20억원대", "2000000000", "approx", None),
        ("최소 20억원", "2000000000", "min", None),
        ("적어도 20억원", "2000000000", "min", None),
        ("최대 20억원", "2000000000", "max", None),
        ("20억원 이상", "2000000000", "min", None),
        ("20억원 이하", "2000000000", "max", None),
        ("20억원 초과", "2000000000", "gt", None),
        ("20억원 미만", "2000000000", "lt", None),
        ("at least 2 million USD", "2000000", "min", None),
        ("at most 2 million USD", "2000000", "max", None),
        ("over 2 million USD", "2000000", "gt", None),
        ("less than 2 million USD", "2000000", "lt", None),
        ("7~15%", "7", "range", "15"),
        ("7–15%", "7", "range", "15"),
        ("20000만원", "200000000", "exact", None),
    ],
)
def test_quantities_retain_sign_scale_comparator_and_interval(literal, number, qualifier, upper):
    source = f"한빛소재의 매출은 {literal}이다."
    relation = metric(source)
    assert relation.quantity is not None
    assert Decimal(relation.quantity.value) == Decimal(number)
    assert (relation.quantity.qualifier, relation.quantity.upper) == (qualifier, upper)
    assert relation.quantity.span.matches(source)


@pytest.mark.parametrize(
    ("source", "expected"),
    [
        ("한빛소재의 매출은 20% 증가했다.", "change_increase"),
        ("한빛소재의 매출은 20% 감소했다.", "change_decrease"),
        ("한빛소재의 매출은 전년 대비 20% 증가했다.", "change_increase:previous_year"),
        (
            "한빛소재의 매출은 전년 동기 대비 20% 증가했다.",
            "change_increase:previous_year_same_period",
        ),
        ("한빛소재의 매출은 전 분기보다 20% 뛰었다.", "change_increase:previous_quarter"),
        ("한빛소재의 매출은 현재의 2배로 전망된다.", "multiple:current"),
    ],
)
def test_quantity_roles_keep_direction_and_explicit_comparison_baseline(source, expected):
    relation = metric(source)
    assert relation.quantity.role == expected
    assert all(b.span.matches(source) for b in relation.bindings)


@pytest.mark.parametrize("qualifier", ["평택공장", "기흥공장", "HBM", "DDR5", "연결"])
def test_metric_qualification_is_part_of_relation_identity(qualifier):
    source = f"한빛소재의 {qualifier} 매출은 20억원이다."
    relation = metric(source)
    assert relation.target.value == "revenue:" + qualifier.casefold()
    assert relation.target.span.text == qualifier + " 매출"
    assert not relation.uncertainty


def test_reporter_does_not_become_the_owner_and_consensus_remains_a_forecast():
    source = "미래증권은 한빛소재의 내년 연결 영업이익 전망치가 20억원으로 집계됐다고 밝혔다."
    relation = metric(source, "operating_profit")
    assert [s.value for s in relation.subjects] == ["한빛소재"]
    assert relation.attributed_to.value == "미래증권"
    assert relation.state == "forecast"
    assert any(b.kind == "state" and b.span.text == "전망" for b in relation.bindings)


def test_topic_share_does_not_become_brokers_absolute_revenue():
    source = "미래증권은 내년 삼성전자 D램 매출에서 HBM이 차지하는 비중이 15%로 오를 것으로 봤다."
    relation = metric(source, "revenue_share")
    assert [s.value for s in relation.subjects] == ["삼성전자"]
    assert relation.attributed_to.value == "미래증권"
    assert relation.quantity.role == "share"
    assert relation.state == "forecast"


@pytest.mark.parametrize(
    ("source", "state"),
    [
        ("한빛소재는 2028년 공장을 완공할 계획이다.", "planned"),
        ("한빛소재는 2028년 공장을 완공했다.", "completed"),
        ("한빛소재는 2028년 공장을 완공하지 않았다.", "negated"),
        ("한빛소재는 2028년 공장을 완공할 경우 공급을 확인한다.", "conditional"),
    ],
)
def test_event_state_has_its_own_literal_operator(source, state):
    relations = [r for r in analyze_sentence(source).relations if r.predicate == "construction"]
    assert relations and relations[0].state == state
    assert any(b.kind == "state" and b.value == state for b in relations[0].bindings)
    assert all(b.span.matches(source) for b in relations[0].bindings)


def test_mixed_indirect_quotations_cannot_create_a_certain_speaker_event():
    source = (
        '도시시장은 "반도체는 공장 하나로 완성되는 산업이 아니다"며 "소재를 공급하겠다"고 말했다.'
    )
    relations = analyze_sentence(source).relations
    assert relations
    assert all(r.uncertainty for r in relations)
    assert all("quoted_proposition_scope" in r.uncertainty for r in relations)


def test_coreferential_completion_retains_an_alternative_without_inventing_an_owner():
    source = "이후 이 공장의 건설을 마쳤다."
    relation = next(r for r in analyze_sentence(source).relations if r.predicate == "construction")
    assert relation.subjects == ()
    assert relation.state == "completed"
    assert "missing_subject" in relation.uncertainty
    assert relation.target.value == "공장"


def test_joint_owner_amount_is_never_assigned_to_each_participant():
    source = "삼성전자와 SK하이닉스의 매출은 20억원이다."
    relation = metric(source)
    assert {s.value for s in relation.subjects} == {"삼성전자", "sk하이닉스"}
    assert relation.subject_mode == "joint"
    assert len([r for r in analyze_sentence(source).relations if r.quantity]) == 1


def test_respective_owner_amounts_preserve_written_order():
    source = "삼성전자와 SK하이닉스의 매출은 각각 20억원과 10억원이다."
    relations = [r for r in analyze_sentence(source).relations if r.quantity]
    assert [(r.subjects[0].value, r.quantity.value) for r in relations] == [
        ("삼성전자", "2000000000"),
        ("sk하이닉스", "1000000000"),
    ]
    assert all(r.subject_mode == "respective" and not r.uncertainty for r in relations)


def test_partial_parse_and_original_positions_survive_whitespace_and_other_sentences():
    source = "  한빛소재의 매출은 20억원이다.\n의미를 확정할 수 없는 추가 설명."
    analysis = analyze_sentence(source)
    assert analysis.unresolved
    assert all(m.span.matches(source) for m in analysis.mentions)
    assert all(r.span.matches(source) for r in analysis.relations)
    assert all(b.span.matches(source) for r in analysis.relations for b in r.bindings)
    assert analyze_sentence(source) is analysis
    with pytest.raises(FrozenInstanceError):
        analysis.relations = ()


def test_each_source_content_has_a_distinct_immutable_identity():
    first = analyze_sentence("한빛소재의 매출은 20억원이다.")
    second = analyze_sentence("한빛소재의 매출은 30억원이다.")
    assert first.source_sha256 != second.source_sha256
    assert first is not second
    assert analyze_sentence.cache_info().maxsize == 256
