"""Source identity and provenance survive stage, claim and prompt selection."""

import hashlib
import json
from copy import deepcopy
from dataclasses import FrozenInstanceError
from types import SimpleNamespace

import pytest
from test_report_insight_assessment import framed, request

from app.llm.report_insight_assessment import draft_prompt, review_prompt
from app.llm.report_insight_fact_index import build_fact_index, prompt_fact_index
from app.llm.report_insight_service import _reduce_v4_prompt
from app.schemas.report_insight import ReportInsightRequest


def source_request():
    data = request().model_dump(by_alias=True, mode="json")
    finding = data["findings"][0]
    finding["sentences"] = [
        {
            "index": 7,
            "text": "삼성전자와 SK하이닉스의 생산량은 각각 20개와 10개다.",
        },
        {"index": 12, "text": "LG전자의 매출은 30억원이다."},
        {"index": 24, "text": "TSMC의 생산량은 999개다."},
    ]
    finding["claims"] = [
        {
            "id": "101:0",
            "text": "삼성전자의 생산량은 777개다.",
            "claimType": "FORECAST",
            "attributedTo": None,
            "evidenceSentenceIds": [7],
        },
        {
            "id": "101:1",
            "text": "관계자는 공급 규모와 매출의 영향을 설명했다.",
            "claimType": "OPINION",
            "attributedTo": "업계 관계자",
            "evidenceSentenceIds": [7, 12],
        },
    ]
    return ReportInsightRequest.model_validate(data)


def test_index_only_parses_linked_originals_and_preserves_exact_claim_metadata():
    source = source_request()
    before = source.model_dump_json(by_alias=True)
    index = build_fact_index(source)
    assert [row.sentence_index for row in index.evidence] == [7, 12]
    first = index.evidence[0]
    assert first.source_sha256 == hashlib.sha256(first.text.encode()).hexdigest()
    assert first.finding_id == 101 and first.article_id == 1101
    assert first.published_at.isoformat() == "2026-09-25"
    assert [(claim.claim_id, claim.claim_type, claim.attributed_to) for claim in first.claims] == [
        ("101:0", "FORECAST", None),
        ("101:1", "OPINION", "업계 관계자"),
    ]
    payload = json.dumps(prompt_fact_index(source), ensure_ascii=False)
    assert "777" not in payload and "999" not in payload
    assert "metadata" not in payload and "TSMC" not in payload
    assert "title" not in payload
    assert source.model_dump_json(by_alias=True) == before
    with pytest.raises(FrozenInstanceError):
        first.text = "cannot replace original"


def test_fact_ids_do_not_depend_on_claim_subset_or_source_order():
    source = source_request()
    all_facts = build_fact_index(source)
    selected = build_fact_index(source, ["101:0"])
    assert [row.sentence_index for row in selected.evidence] == [7]
    assert len(selected.evidence[0].claims) == 1
    assert {fact.fact_id for fact in selected.facts} <= {fact.fact_id for fact in all_facts.facts}
    reordered = source.model_copy(deep=True)
    reordered.findings[0].claims.reverse()
    reordered.findings[0].sentences.reverse()
    assert build_fact_index(reordered) == all_facts
    assert build_fact_index(source, []).evidence == ()
    assert build_fact_index(source, ["missing:0"]).facts == ()


def test_source_text_change_invalidates_identity_without_borrowing_other_sentence():
    source = source_request()
    before = build_fact_index(source)
    source.findings[0].sentences[0].text += " "
    after = build_fact_index(source)
    assert before.evidence[0].evidence_id != after.evidence[0].evidence_id
    assert before.evidence[1].evidence_id == after.evidence[1].evidence_id
    unchanged_source = after.evidence[1].evidence_id
    assert {fact.fact_id for fact in before.facts if fact.evidence_id == unchanged_source} == {
        fact.fact_id for fact in after.facts if fact.evidence_id == unchanged_source
    }


def test_every_prompt_binding_is_an_exact_original_offset():
    source = source_request()
    payload = prompt_fact_index(source)
    rows = {row.evidence_id: row for row in build_fact_index(source).evidence}
    assert payload["facts"]
    assert payload["offsetUnit"] == "unicode_code_points"
    for fact in payload["facts"]:
        original = rows[fact["evidenceId"]].text
        indexed = next(
            item for item in build_fact_index(source).facts if item.fact_id == fact["factId"]
        )
        assert original[fact["span"]["start"] : fact["span"]["end"]] == indexed.relation.span.text
        slots = [
            *fact["subjects"],
            fact["target"],
            fact["quantity"],
            fact["time"],
            fact["attributedTo"],
            *fact["bindings"],
        ]
        for slot in (item for item in slots if item is not None):
            span = slot["span"]
            assert any(
                binding.span.start == span["start"]
                and binding.span.end == span["end"]
                and original[span["start"] : span["end"]] == binding.span.text
                for binding in indexed.relation.bindings
            )


def test_unresolved_source_is_explicit_without_promoting_unbound_quantity():
    source = request(text="생산량은 20개다.")
    payload = prompt_fact_index(source)
    assert not payload["facts"]
    assert payload["uncertainty"]
    assert payload["counts"]["mentions"] > 0
    assert payload["counts"]["boundFacts"] == 0
    assert payload["sourceScope"] == "linked_original_sentences"
    assert payload["extractionScope"] == "partial_explicit_relations"
    assert all(item["reasons"] for item in payload["uncertainty"])


def test_prompt_limits_report_full_counts_without_truncating_runtime_index():
    source = source_request().model_dump(by_alias=True, mode="json")
    finding = source["findings"][0]
    finding["sentences"] = [
        {"index": index, "text": f"삼성전자의 생산량은 {index + 1}개다."} for index in range(30)
    ] + [
        {"index": index, "text": "원문은 시스템의 구성 변경을 설명한다."} for index in range(30, 38)
    ]
    finding["claims"] = [finding["claims"][0]]
    finding["claims"][0]["evidenceSentenceIds"] = list(range(38))
    source = ReportInsightRequest.model_validate(source)
    index = build_fact_index(source)
    payload = prompt_fact_index(source)
    assert len(index.evidence) == 38 and len(index.facts) == 30
    assert len(payload["facts"]) == 24 and len(payload["uncertainty"]) == 1
    assert payload["counts"]["sourceSentences"] == 38
    assert payload["counts"]["boundFacts"] == 30
    assert payload["counts"]["uncertainties"] == 8
    assert payload["uncertaintyReasonCounts"] == {"no_explicit_relation": 8}
    assert payload["limitsPerFinding"] == {
        "facts": 24,
        "uncertaintyExamples": 2,
        "roleProposals": 12,
    }
    assert payload["truncated"] is True


def test_finding_projection_does_not_leak_another_findings_sources():
    source = request(ids=(101, 102), text="삼성전자의 생산량은 20개다.")
    payload = prompt_fact_index(source, finding_id=102)
    assert payload["facts"]
    assert {row["findingId"] for row in payload["evidence"]} == {102}
    assert prompt_fact_index(source, ["101:0"], finding_id=102)["evidence"] == []


def test_map_review_reduce_use_identical_fact_ids_and_selected_source_provenance(monkeypatch):
    source = source_request()
    map_index = framed(draft_prompt(source))["findings"][0]["sourceFactIndex"]
    review_index = framed(review_prompt(source))["findings"][0]["sourceFactIndex"]
    assert map_index == review_index
    monkeypatch.setattr("app.llm.report_insight_service._decision_candidates", lambda *_: {})
    raw = deepcopy(source.findings[0].claims[0].model_dump(by_alias=True))
    retrieved = {
        "CHIP_MAKER": SimpleNamespace(
            evidence=[SimpleNamespace(finding_id=source.findings[0].id, to_payload=lambda: raw)]
        )
    }
    reduced = framed(_reduce_v4_prompt(source, None, retrieved, {"CHIP_MAKER": ("101:0",)}))
    reduce_index = reduced["sourceFactIndex"]["CHIP_MAKER"]
    assert "sourceFactHints" not in reduced
    assert {fact["factId"] for fact in reduce_index["facts"]} <= {
        fact["factId"] for fact in map_index["facts"]
    }
    assert reduce_index["facts"]
    assert {row["sentenceIndex"] for row in reduce_index["evidence"]} == {7}
    assert [origin["claimId"] for row in reduce_index["evidence"] for origin in row["claims"]] == [
        "101:0"
    ]
