"""Saved semantic judgments need exact source anchors and human denominators."""

from copy import deepcopy

import pytest
from pydantic import ValidationError
from test_report_insight_assessment import request

from app.eval.report_insight_run import EvaluationStopped, digest
from app.eval.report_insight_semantic_review import compare_annotations, export_packet


@pytest.fixture
def packet():
    source = request(ids=(101, 102))
    manifest = {
        "promptVersion": "test-current",
        "cases": [{"request": source.model_dump(mode="json", by_alias=True)}],
    }
    insight = {
        "audience": "CHIP_MAKER",
        "headline": "현재 생산 제약을 확인한다.",
        "assessments": [
            {
                "findingId": 101,
                "reason": "생산 제약이 계속된다.",
                "basisClaimIds": [],
                "axes": {"directness": None, "impact": None, "urgency": None, "novelty": None},
            },
            {
                "findingId": 102,
                "reason": "생산 제약이 계속된다.",
                "basisClaimIds": ["102:0"],
                "axes": {"directness": 5, "impact": None, "urgency": None, "novelty": None},
            },
        ],
        "overview": [
            {
                "text": "생산 제약을 확인한다.",
                "assumption": "생산 제약이 유지되는 경우",
                "basisClaimIds": ["102:0"],
            }
        ],
        "implications": [],
        "watchItems": [],
    }
    state = {
        "manifestHash": digest(manifest),
        "results": [
            {
                "jobId": "0:0:CHIP_MAKER",
                "caseIndex": 0,
                "audience": "CHIP_MAKER",
                "status": "success",
                "response": {"insights": [insight]},
            }
        ],
    }
    return export_packet(manifest, state)


def annotation(packet, *, kind, labels=(), assessments=()):
    return {
        "packet_hash": packet["packetHash"],
        "kind": kind,
        "annotator": "test-reviewer",
        "judgments": list(labels),
        "assessments": list(assessments),
    }


def judgment(unit, verdict):
    source = unit["source"][0]
    sentence = source["sentences"][0]
    return {
        "unit_id": unit["unitId"],
        "verdict": verdict,
        "anchors": [
            {
                "claim_id": source["claimId"],
                "sentence_id": sentence["sentenceId"],
                "start": 0,
                "end": len(sentence["text"]),
                "quote": sentence["text"],
            }
        ],
    }


def test_three_classes_and_missing_labels_do_not_become_quality_passes(packet):
    human_labels = [
        judgment(unit, verdict)
        for unit, verdict in zip(
            packet["units"], ("SUPPORTED", "CONTRADICTED", "INSUFFICIENT"), strict=False
        )
    ]
    judge_labels = [
        judgment(packet["units"][0], "SUPPORTED"),
        judgment(packet["units"][1], "SUPPORTED"),
    ]
    human = annotation(
        packet,
        kind="human",
        labels=human_labels,
        assessments=[
            {
                "assessment_id": packet["assessments"][0]["assessmentId"],
                "decidable_axes": ["directness", "impact"],
                "required_claim_ids": ["101:0"],
            }
        ],
    )
    result = compare_annotations(
        packet, human, annotation(packet, kind="judge", labels=judge_labels)
    )
    assert result["confusionMatrix"]["CONTRADICTED"]["SUPPORTED"] == 1
    assert result["agreementRate"] == 0.5
    assert result["falseSupportAmongPredictedSupport"] == 0.5
    assert result["missingJudgeUnits"] == 1
    assert result["unlabeledHumanUnits"] == len(packet["units"]) - 3
    assert result["humanDecidableAxes"] == 2
    assert result["unnecessaryAbstentionRate"] == 1
    assert result["evidenceOmissionRate"] == 1
    assert result["automaticReleaseDecision"] is None
    assert result["providerCalls"] == 0
    assert packet["units"][1]["candidateContext"]["axes"]["directness"] is None
    assert packet["units"][1]["candidateClaimIds"] == []
    assert packet["units"][1]["source"][0]["claimId"] == "101:0"


@pytest.mark.parametrize(
    "mutate,code",
    [
        (lambda label: label["anchors"][0].update(quote="invented quote"), "ANCHOR_QUOTE_MISMATCH"),
        (lambda label: label["anchors"][0].update(claim_id="101:0"), "ANCHOR_CLAIM_OUTSIDE_SOURCE"),
        (lambda label: label["anchors"][0].update(sentence_id=9), "ANCHOR_SENTENCE_OUTSIDE_SOURCE"),
        (lambda label: label["anchors"][0].update(end=99999), "ANCHOR_QUOTE_MISMATCH"),
        (lambda label: label.update(anchors=[]), "SOURCE_ANCHOR_REQUIRED"),
    ],
)
def test_quotes_must_be_exact_offsets_in_linked_source(packet, mutate, code):
    label = judgment(packet["units"][0], "SUPPORTED")
    mutate(label)
    with pytest.raises(EvaluationStopped, match=code):
        compare_annotations(
            packet,
            annotation(packet, kind="human", labels=[label]),
            annotation(packet, kind="judge"),
        )


def test_closed_verdict_and_source_candidate_bindings(packet):
    label = judgment(packet["units"][0], "SUPPORTED")
    label["verdict"] = "MAYBE_SUPPORTED"
    with pytest.raises(ValidationError):
        compare_annotations(
            packet,
            annotation(packet, kind="human", labels=[label]),
            annotation(packet, kind="judge"),
        )
    human = annotation(packet, kind="human")
    judge = annotation(packet, kind="judge")
    changed = deepcopy(packet)
    changed["units"][0]["statement"] = "changed candidate"
    with pytest.raises(EvaluationStopped, match="REVIEW_PACKET_CHANGED"):
        compare_annotations(changed, human, judge)
    human["packet_hash"] = "different-source-snapshot"
    with pytest.raises(EvaluationStopped, match="ANNOTATION_PACKET_MISMATCH"):
        compare_annotations(packet, human, judge)


def test_unmeasured_omission_and_abstention_are_null(packet):
    result = compare_annotations(
        packet, annotation(packet, kind="human"), annotation(packet, kind="judge")
    )
    assert result["pairedUnits"] == 0
    assert result["agreementRate"] is None
    assert result["evidenceOmissionRate"] is None
    assert result["unnecessaryAbstentionRate"] is None


def test_required_evidence_cannot_cross_findings(packet):
    human = annotation(
        packet,
        kind="human",
        assessments=[
            {
                "assessment_id": packet["assessments"][0]["assessmentId"],
                "decidable_axes": [],
                "required_claim_ids": ["102:0"],
            }
        ],
    )
    with pytest.raises(EvaluationStopped, match="EXPECTED_CLAIM_OUTSIDE_FINDING"):
        compare_annotations(packet, human, annotation(packet, kind="judge"))
