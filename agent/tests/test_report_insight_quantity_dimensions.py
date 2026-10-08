"""Explicit budget groups and relative power changes keep their quantity roles."""

import pytest

from app.llm.report_insight_fact_graph import analyze_sentence
from app.llm.report_insight_fact_verification import compare_source_facts, fact_graph_mismatches


@pytest.mark.parametrize("first,second,amount", [("해솔", "나래", 24), ("Atlas", "Orion", 63)])
def test_explicit_joint_budget_does_not_assign_total_to_one_participant(first, second, amount):
    source = f"{first}·{second}는 공동 투자 예산을 총 {amount}조 원으로 정했다."
    individual = f"{first}의 개별 투자 예산은 {amount}조 원이다."
    checks = compare_source_facts(individual, [source])
    assert any(c.outcome == "unknown" and c.reason == "subject_group_mismatch" for c in checks)
    assert fact_graph_mismatches(individual, [source])
    assert not fact_graph_mismatches(source, [source])
    assert not fact_graph_mismatches(individual, [source, individual])
    changed = source.replace(f"{amount}조", f"{amount + 1}조")
    assert fact_graph_mismatches(changed, [source])


def test_dotted_name_without_explicit_joint_context_is_not_split_into_companies():
    source = "가온·솔빛은 투자 예산을 24조 원으로 정했다."
    analysis = analyze_sentence(source)
    assert any(a.value == "가온·솔빛" for a in analysis.mentions if a.kind == "actor")
    assert not any(a.value in {"가온", "솔빛"} for a in analysis.mentions if a.kind == "actor")


@pytest.mark.parametrize("product,percent", [("신제품", 24), ("시제품", 63)])
def test_relative_consumption_reduction_does_not_establish_absolute_watts(product, percent):
    source = f"가온소자 {product}은 이전 제품보다 전력 소비량이 최대 {percent}% 낮다."
    absolute = f"{product} 전력 소비량은 {percent}W다."
    checks = compare_source_facts(absolute, [source])
    assert any(c.outcome == "unknown" and c.reason == "quantity_dimension_mismatch" for c in checks)
    assert not any(c.outcome == "contradicted" for c in checks)
    assert fact_graph_mismatches(absolute, [source])
    assert not fact_graph_mismatches(source, [source])
    assert not fact_graph_mismatches(absolute, [source, absolute])
    for relation in analyze_sentence(source).relations:
        assert all(binding.span.matches(source) for binding in relation.bindings)


@pytest.mark.parametrize(
    "source",
    [
        "신제품의 전력 소비량이 최대 24% 감소한다면 도입을 검토한다.",
        "다른제품의 전력 소비량은 최대 24% 감소했다.",
        "신제품의 전력 소비량이 최대 24% 감소했는지 확인한다.",
    ],
)
def test_uncertain_or_other_product_change_does_not_establish_a_unit_role_conflict(source):
    absolute = "신제품 전력 소비량은 24W다."
    assert not fact_graph_mismatches(absolute, [source])
    assert all(c.outcome == "unknown" for c in compare_source_facts(absolute, [source]))
