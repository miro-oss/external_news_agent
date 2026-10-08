"""Quantity ownership and role changes are bound to the same literal source scope."""

import pytest

from app.llm.report_insight_fact_graph import analyze_sentence
from app.llm.report_insight_fact_verification import compare_source_facts, fact_graph_mismatches


@pytest.mark.parametrize("first,second", [("해솔", "은하"), ("가온", "나래"), ("Atlas", "Orion")])
def test_joint_total_does_not_prove_an_individual_allocation(first, second):
    source = (
        f"{first}과 {second}가 공동으로 투자하는 총액은 80억 원이며 "
        "각 회사의 분담액은 공개하지 않았다."
    )
    claim = f"{first}의 개별 투자액은 80억 원이다."
    checks = compare_source_facts(claim, [source])
    assert any(
        check.reason == "subject_group_mismatch" and check.outcome == "unknown" for check in checks
    )
    assert not any(check.outcome == "contradicted" for check in checks)
    assert fact_graph_mismatches(claim, [source])
    assert not fact_graph_mismatches(source, [source])
    assert not fact_graph_mismatches(claim, [source, claim])


@pytest.mark.parametrize("owner,other", [("다솜", "누리"), ("푸른", "새봄"), ("Alpha", "Beta")])
def test_later_owner_cannot_supply_an_earlier_factory_quantity(owner, other):
    source = f"{owner} 공장의 생산량은 31개, {other} 공장은 18개다."
    wrong = f"{owner} 공장의 생산량은 18개다."
    right = f"{owner} 공장의 생산량은 31개다."
    assert fact_graph_mismatches(wrong, [source])
    assert not fact_graph_mismatches(right, [source])
    assert any(check.outcome == "supported" for check in compare_source_facts(right, [source]))
    # The second clause omitted its metric; do not fabricate an inherited fact.
    omitted = compare_source_facts(f"{other} 공장의 생산량은 18개다.", [source])
    assert all(check.outcome == "unknown" for check in omitted)


@pytest.mark.parametrize(
    "owner,count,total,each", [("별빛장비", 3, 9, 3), ("하늘장비", 4, 20, 5), ("Rigel", 2, 8, 4)]
)
def test_same_equipment_count_and_total_can_prove_a_unit_price_conflict(owner, count, total, each):
    source = f"{owner}는 검사 장비 {count}대를 총 {total}억 원에 공급했다."
    claim = f"{owner}가 공급한 검사 장비의 대당 가격은 {total}억 원이다."
    corrected = f"{owner}가 공급한 검사 장비의 대당 가격은 {each}억 원이다."
    assert any(
        check.outcome == "contradicted" and check.reason == "quantity_conflict"
        for check in compare_source_facts(claim, [source])
    )
    assert fact_graph_mismatches(claim, [source])
    assert not fact_graph_mismatches(corrected, [source])
    assert any(
        check.outcome == "supported" and check.reason == "explicit_unit_price_derived"
        for check in compare_source_facts(corrected, [source])
    )
    # Every input to the calculation still points into the untouched original.
    for relation in analyze_sentence(source).relations:
        assert all(binding.span.matches(source) for binding in relation.bindings)


@pytest.mark.parametrize(
    "source",
    [
        "별빛장비는 검사 장비 3대를 공급했다. 누리장비는 검사 장비를 총 9억 원에 공급했다.",
        "별빛장비는 검사 장비 약 3대를 총 9억 원에 공급했다.",
        "별빛장비는 검사 장비 3대를 총 9억 원에 공급하지 않았다.",
        "별빛장비는 검사 장비 3대를 총 9억 원에 공급한다면 검증을 진행한다.",
    ],
)
def test_uncertain_or_cross_sentence_counts_never_prove_a_calculated_price(source):
    claim = "별빛장비가 공급한 검사 장비의 대당 가격은 3억 원이다."
    checks = compare_source_facts(claim, [source])
    assert not any(check.outcome in {"supported", "contradicted"} for check in checks)


def test_one_item_total_is_not_mislabeled_as_a_unit_price_conflict():
    source = "별빛장비는 검사 장비 1대를 총 9억 원에 공급했다."
    claim = "별빛장비가 공급한 검사 장비의 대당 가격은 9억 원이다."
    assert not fact_graph_mismatches(claim, [source])
    assert any(check.outcome == "supported" for check in compare_source_facts(claim, [source]))


def test_unparsed_prose_is_not_globally_rejected_by_role_check():
    source = "공정 검증의 상황을 설명했지만 개별 장비 수량은 공개하지 않았다."
    assert not fact_graph_mismatches("검증 준비의 필요 조건을 확인한다.", [source])
