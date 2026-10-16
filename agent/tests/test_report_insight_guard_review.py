from copy import deepcopy
from datetime import date

import pytest
from test_report_insight_eval_ablation import setup_run
from test_report_insight_explanation_recovery import source_report
from test_report_insight_operational import prepared as operational_prepared

from app.eval import report_insight_operational as operational
from app.eval.report_insight_guard_review import export_guard_packet, measure_guard_labels
from app.eval.report_insight_paired_compare import (
    export_comparison_packet,
    freeze_comparison,
    summarize_human_comparison,
)
from app.eval.report_insight_run import EvaluationStopped, digest

prepared = operational_prepared


def human(packet, unit, verdict="SUPPORTED"):
    claim = unit["source"][0]
    sentence = claim["sentences"][0]
    return {
        "packet_hash": packet["packetHash"],
        "kind": "human",
        "annotator": "synthetic-reviewer",
        "judgments": [
            {
                "unit_id": unit["unitId"],
                "verdict": verdict,
                "anchors": [
                    {
                        "claim_id": claim["claimId"],
                        "sentence_id": sentence["sentenceId"],
                        "start": 0,
                        "end": len(sentence["text"]),
                        "quote": sentence["text"],
                    }
                ],
            }
        ],
        "provenance": {
            "origin": "external_human_review",
            "reference": "synthetic-test-only",
            "reviewed_at": "2026-01-01",
            "independent_of_judge": True,
        },
    }


def test_guard_export_preserves_full_source_and_missing_labels_are_unmeasured(
    prepared, monkeypatch
):
    state, _ = setup_run(prepared, monkeypatch)
    manifest, _, _ = operational.check(prepared[0])
    packet = export_guard_packet(manifest, state)
    assert packet["units"]
    unit = packet["units"][0]
    original = manifest["cases"][0]["request"]
    projection = packet["requests"][unit["requestHash"]]
    assert projection["originalRequest"] == original
    assert unit["audience"] == "CHIP_MAKER"
    assert unit["candidateClaimIds"] == ["101:0"]
    assert (
        unit["source"][0]["sentences"][0]["text"] == original["findings"][0]["sentences"][0]["text"]
    )
    result = measure_guard_labels(packet)
    assert result["supportedFalseRejectionRate"] is None
    assert result["contradictionMissRate"] is None
    assert not result["semanticQualityMeasured"]
    labeled = measure_guard_labels(packet, human(packet, unit))
    assert labeled["humanLabeledUnits"] == 1
    assert labeled["unmeasuredUnits"] == len(packet["units"]) - 1
    modified = deepcopy(packet)
    modified["units"][0]["statement"] = "altered"
    with pytest.raises(EvaluationStopped, match="REVIEW_PACKET_CHANGED"):
        measure_guard_labels(modified, human(packet, unit))


def test_final_and_stage_review_packet_never_infers_semantic_benefit(prepared, monkeypatch):
    state, _ = setup_run(prepared, monkeypatch)
    manifest, _, _ = operational.check(prepared[0])
    candidate = deepcopy(manifest)
    candidate["runtimeHashes"] = {"candidate": "new"}
    after = deepcopy(state)
    after["manifestHash"] = digest(candidate)
    plan = freeze_comparison(manifest, candidate)
    packet = export_comparison_packet(plan, manifest, state, candidate, after)
    assert {unit["variant"] for unit in packet["units"]} == {"baseline", "candidate"}
    assert {unit["stage"] for unit in packet["units"]} >= {
        "FINAL",
        "MAP-001",
        "REVIEW-001",
        "EXPLAIN-001",
    }
    assert all(
        unit["reportId"] == manifest["cases"][0]["request"]["report"]["id"]
        for unit in packet["units"]
    )
    result = summarize_human_comparison(packet, human(packet, packet["units"][0]))
    assert result["humanLabeledUnits"] == 1
    assert result["automaticReleaseDecision"] is None


def test_unparseable_attempts_are_retained_as_unreviewable(prepared, monkeypatch):
    state, _ = setup_run(
        prepared,
        monkeypatch,
        raw_hook=lambda stage, _n, _data, raw: "{" if stage.startswith("EXPLAIN-") else raw,
    )
    manifest, _, _ = operational.check(prepared[0])
    packet = export_guard_packet(manifest, state)
    assert len(packet["unreviewableAttempts"]) == 2
    assert measure_guard_labels(packet)["unreviewableAttempts"] == 2


def test_guard_review_preserves_selected_multi_sentence_context_and_report_clock(
    prepared, monkeypatch
):
    source = source_report(audiences=("MARKET_INVESTOR",))
    source.report.report_end_date = date(2026, 9, 30)
    source.findings[0].claims[0].evidence_sentence_ids.append(1)
    state, _ = setup_run(prepared, monkeypatch, source=source)
    manifest, _, _ = operational.check(prepared[0])
    packet = export_guard_packet(manifest, state)
    assert packet["units"]
    assert all(unit["audience"] == "MARKET_INVESTOR" for unit in packet["units"])
    assert all(unit["referenceDate"] == "2026-09-30" for unit in packet["units"])
    assert any(len(unit["source"][0]["sentences"]) == 2 for unit in packet["units"])
    unit = packet["units"][0]
    invalid = human(packet, unit)
    invalid["judgments"][0]["anchors"][0]["quote"] = "different quote"
    with pytest.raises(EvaluationStopped, match="ANCHOR_QUOTE_MISMATCH"):
        measure_guard_labels(packet, invalid)
