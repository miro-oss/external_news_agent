"""Provenance, uncertainty and article clocks survive relation comparison."""

from datetime import date

import pytest
from test_report_insight_assessment import request

from app.llm.report_insight_fact_index import build_fact_index
from app.llm.report_insight_fact_verification import (
    compare_indexed_facts,
    compare_source_facts,
    fact_graph_mismatches,
    fact_index_mismatches,
)


@pytest.mark.parametrize(
    "sources",
    [
        ["삼성전자의 생산량은 20개다.", "이후 30개로 정정됐다."],
        ["삼성전자의 생산량은 20개다. 이후 30개로 정정됐다."],
    ],
)
def test_unparsed_revision_cannot_cause_a_false_quantity_rejection(sources):
    checks = compare_source_facts("삼성전자의 생산량은 30개다.", sources)
    assert checks and all(check.outcome == "unknown" for check in checks)
    assert fact_graph_mismatches("삼성전자의 생산량은 30개다.", sources) == []


def test_unrelated_correction_does_not_erase_a_known_relation_conflict():
    checks = compare_source_facts(
        "삼성전자의 생산량은 30개다.",
        ["삼성전자의 생산량은 20개다.", "SK하이닉스의 수치를 30개로 정정했다."],
    )
    assert any(check.outcome == "contradicted" for check in checks)


def test_negated_amount_does_not_become_an_observed_value_or_disprove_other_values():
    source = "삼성전자의 생산량은 20개가 아니다."
    checks = compare_source_facts("삼성전자의 생산량은 30개다.", [source])
    assert checks and all(check.outcome == "unknown" for check in checks)
    same_value = compare_source_facts("삼성전자의 생산량은 20개다.", [source])
    assert any(check.outcome == "contradicted" for check in same_value)


@pytest.mark.parametrize(
    ("source", "candidate"),
    [
        ("삼성전자의 평택공장 생산량은 20개다.", "삼성전자의 기흥공장 생산량은 20개다."),
        ("삼성전자의 HBM 매출은 20억원이다.", "삼성전자의 DDR5 매출은 20억원이다."),
        ("삼성전자의 매출은 5% 증가했다.", "삼성전자의 매출은 5% 감소했다."),
    ],
)
def test_matching_numbers_never_prove_a_different_target_or_direction(source, candidate):
    checks = compare_source_facts(candidate, [source])
    assert checks and not any(check.outcome == "supported" for check in checks)


def test_indexed_comparison_preserves_fact_ids_claim_origins_and_literal_offsets():
    req = request(text="삼성전자의 생산량은 20개다.")
    index = build_fact_index(req, ["101:0"])
    checks = compare_indexed_facts(req.findings[0].claims[0].text, index, reference_date=None)
    supported = next(check for check in checks if check.outcome == "supported")
    proof = supported.evidence[0]
    assert proof.fact_id == index.facts[0].fact_id
    assert proof.evidence_id == index.evidence[0].evidence_id
    assert proof.claim_ids == ("101:0",)
    assert proof.claim_types == ("FACT",)
    assert proof.relation.span.matches(req.findings[0].sentences[0].text)


@pytest.mark.parametrize("claim_type", ["OPINION", "FORECAST"])
def test_claim_metadata_prevents_plain_text_support_becoming_an_observed_fact(claim_type):
    req = request(text="삼성전자의 매출은 20억원이다.")
    req.findings[0].claims[0].claim_type = claim_type
    if claim_type == "OPINION":
        req.findings[0].claims[0].attributed_to = "한빛증권"
    checks = compare_indexed_facts(
        "삼성전자의 매출은 20억원이다.", build_fact_index(req), reference_date=None
    )
    assert checks and all(check.outcome == "unknown" for check in checks)


def test_article_clock_is_used_for_sources_and_report_clock_for_generated_prose():
    req = request(text="삼성전자의 매출은 내년 20억원으로 전망된다.")
    req.findings[0].published_at = date(2025, 12, 10)
    index = build_fact_index(req)
    snapshot = req.model_dump_json(by_alias=True)
    # The article's next year is 2026; the report's this year is also 2026.
    checks = compare_indexed_facts(
        "삼성전자의 매출은 올해 20억원으로 전망된다.",
        index,
        reference_date=date(2026, 1, 10),
    )
    assert any(check.outcome == "supported" for check in checks)
    assert fact_index_mismatches(
        "삼성전자의 매출은 올해 30억원으로 전망된다.",
        index,
        reference_date=date(2026, 1, 10),
    )
    assert all(
        check.outcome == "unknown"
        for check in compare_indexed_facts(
            "삼성전자의 매출은 내년 20억원으로 전망된다.",
            index,
            reference_date=date(2026, 1, 10),
        )
    )
    assert req.model_dump_json(by_alias=True) == snapshot
    assert index.evidence[0].analysis.relations[0].time.value == "next_year"
