"""Completion questions bind to one event rather than asserting its completion."""

import pytest

from app.llm.report_insight_fact_graph import analyze_sentence
from app.llm.report_insight_fact_verification import compare_source_facts, fact_graph_mismatches


@pytest.mark.parametrize(
    "predicate",
    [
        "공급계약을 체결했는지 확인한다",
        "공급 계약을 체결했는지를 확인해야 한다",
        "공급계약이 완료됐는지 점검한다",
        "공급계약은 확정되었는지 검토한다",
        "공급계약 체결하였는지 확인한다",
        "공급계약이 진행됐는지 확인한다",
    ],
)
def test_particle_and_verbal_noun_inquiry_keeps_literal_unknown_state(predicate):
    candidate = f"삼성전자는 {predicate}."
    source = "삼성전자는 공급계약을 체결할 계획이다."
    relation = next(r for r in analyze_sentence(candidate).relations if r.predicate == "contract")

    assert relation.state == "unknown"
    assert any(b.kind == "state" and b.value == "unknown" for b in relation.bindings)
    assert all(b.span.matches(candidate) for b in relation.bindings)
    assert fact_graph_mismatches(candidate, [source]) == []
    assert not any(c.outcome == "supported" for c in compare_source_facts(candidate, [source]))


@pytest.mark.parametrize("assertion_first", [False, True])
@pytest.mark.parametrize("separator", [". ", "으며 "])
def test_contract_inquiry_does_not_waive_a_separate_completed_contract(assertion_first, separator):
    source = "삼성전자는 공급계약을 체결할 계획이다."
    inquiry = "삼성전자는 공급계약을 체결했는지 확인한다"
    assertion = "삼성전자는 공급계약을 체결했다"
    parts = [assertion, inquiry] if assertion_first else [inquiry, assertion]
    if separator == "으며 ":
        parts[0] = (
            parts[0].removesuffix("한다") + "했"
            if parts[0].endswith("한다")
            else parts[0].removesuffix("다")
        )
    candidate = separator.join(parts) + "."

    assert any(r.state == "completed" for r in analyze_sentence(candidate).relations)
    assert fact_graph_mismatches(candidate, [source])
    assert any(
        c.outcome == "contradicted" and c.reason == "event_state_conflict"
        for c in compare_source_facts(candidate, [source])
    )


@pytest.mark.parametrize(
    "candidate",
    [
        "삼성전자는 공급계약을 체결했고 공장을 완공했는지 확인한다.",
        "삼성전자는 공장을 완공했는지 확인했으며 삼성전자는 공급계약을 체결했다.",
        "삼성전자는 공급계약을 체결했다는 사실을 확인한다.",
    ],
)
def test_only_the_immediate_question_predicate_is_unasserted(candidate):
    source = "삼성전자는 공급계약을 체결할 계획이다."
    contracts = [r for r in analyze_sentence(candidate).relations if r.predicate == "contract"]

    assert contracts and all(r.state == "completed" for r in contracts)
    assert fact_graph_mismatches(candidate, [source])
