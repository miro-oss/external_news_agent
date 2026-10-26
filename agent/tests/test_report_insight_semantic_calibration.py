"""Calibration plumbing fixtures are synthetic, never human quality measurements."""

from copy import deepcopy

import pytest
from test_report_insight_semantic_judge import Provider, execute

from app.eval.report_insight_run import EvaluationStopped, digest
from app.eval.report_insight_semantic_calibration import (
    disagreement_packet,
    evaluate_holdout,
    fit,
    import_human_labels,
    metrics,
    split_manifest,
)
from app.eval.report_insight_semantic_cases import pilot_packet
from app.eval.report_insight_semantic_judge import shadow_observation


def human(packet, labels):
    return import_human_labels(
        packet,
        {
            "packetHash": packet["packetHash"],
            "sourceContextReviewed": True,
            "independentOfJudge": True,
            "reviewer": "synthetic-unit-test-fixture",
            "reference": "Not a real human review; test of import provenance mechanics only.",
            "reviewedAt": "2026-01-01T00:00:00Z",
            "labels": labels,
        },
    )


def seal(packet):
    packet["packetHash"] = digest({k: v for k, v in packet.items() if k != "packetHash"})
    return packet


def fixture(tmp_path):
    packet = pilot_packet()
    split = split_manifest(packet, salt="test-pre-label-split")
    calibration = [i for i, s in split["assignments"].items() if s == "calibration"]
    heldout = [i for i, s in split["assignments"].items() if s == "holdout"]
    assert len(calibration) >= 3 and len(heldout) >= 3
    truth = dict.fromkeys(split["assignments"], "INSUFFICIENT")
    for ids in (calibration, heldout):
        truth[ids[0]] = "SUPPORTED"
        truth[ids[1]] = "CONTRADICTED"
    predictions = {**truth, calibration[1]: "SUPPORTED"}
    provider = Provider(verdicts=predictions, confidences={calibration[1]: 60})
    _, manifest, result, _ = execute(tmp_path, packet=packet, provider=provider)
    rules = {
        "minimum_per_class": 1,
        "minimum_supported_predictions": 1,
        "maximum_false_support_upper95": 0.8,
        "minimum_agreement": 0.5,
        "thresholds": [50, 90, 95],
    }
    return packet, split, human(packet, truth), manifest, result, rules


def test_threshold_fit_uses_calibration_only_then_scores_frozen_holdout(tmp_path):
    packet, split, labels, manifest, state, rules = fixture(tmp_path)
    calibrated = fit(packet, split, labels, manifest, state, rules)
    assert calibrated["status"] == "fitted"
    assert calibrated["selectedThreshold"] == 90
    changed = deepcopy(labels)
    for row in changed["judgments"]:
        if split["assignments"][row["unit_id"]] == "holdout" and row["anchors"]:
            row["verdict"] = "CONTRADICTED" if row["verdict"] == "SUPPORTED" else "SUPPORTED"
    assert fit(packet, split, changed, manifest, state, rules) == calibrated
    evaluation = evaluate_holdout(packet, split, labels, manifest, state, calibrated)
    assert evaluation["status"] == "HELDOUT_CRITERIA_MET"
    assert evaluation["rawHoldoutMetrics"]["agreementRate"] == 1
    assert evaluation["rawHoldoutMetrics"]["cohenKappa"] == 1
    assert evaluation["automaticReleaseDecision"] is None
    assert evaluation["mode"] == "shadow"
    assert not evaluation["mayOverrideDeterministicValidation"]
    bad = evaluate_holdout(packet, split, changed, manifest, state, calibrated)
    assert bad["rawHoldoutMetrics"]["agreementRate"] < 1
    assert calibrated["selectedThreshold"] == 90


def test_small_real_pilot_cannot_satisfy_default_calibration_acceptance(tmp_path):
    packet, split, labels, manifest, state, _ = fixture(tmp_path)
    calibrated = fit(packet, split, labels, manifest, state)
    assert calibrated["status"] == "INSUFFICIENT_CALIBRATION_EVIDENCE"
    assert calibrated["selectedThreshold"] is None
    evaluation = evaluate_holdout(packet, split, labels, manifest, state, calibrated)
    assert evaluation["status"] == "INSUFFICIENT_HELDOUT_EVIDENCE"
    assert evaluation["calibratedHoldoutMetrics"] is None
    assert evaluation["rawHoldoutMetrics"]["pairedUnits"] > 0


def test_shared_report_family_or_original_sentence_never_cross_splits():
    packet = pilot_packet()
    first, second, third, fourth = packet["units"][:4]
    second["sourceHash"] = first["sourceHash"]
    third["sourceGroup"] = second["sourceGroup"]
    fourth["source"][0]["sentences"][0]["text"] = third["source"][0]["sentences"][0]["text"]
    split = split_manifest(seal(packet), salt="label-independent")
    groups = [split["sourceGroups"][unit["unitId"]] for unit in packet["units"][:4]]
    assert len(set(groups)) == 1
    assert len({split["assignments"][unit["unitId"]] for unit in packet["units"][:4]}) == 1


def test_human_labels_are_never_filled_or_inferred():
    packet = pilot_packet()
    labels = human(packet, {"pilot-01": "CONTRADICTED"})
    assert len(labels["judgments"]) == 1
    assert labels["provenance"]["origin"] == "external_human_review"
    assert labels["provenance"]["anchor_origin"] == "system_context_mapping"
    assert labels["judgments"][0]["anchors"][0]["start"] == 0
    assert human(packet, {})["judgments"] == []


def test_missing_provenance_cannot_calibrate_and_ai_cannot_impersonate_human(tmp_path):
    packet, split, labels, manifest, state, rules = fixture(tmp_path)
    labels["provenance"] = None
    with pytest.raises(EvaluationStopped, match="HUMAN_PROVENANCE_REQUIRED"):
        fit(packet, split, labels, manifest, state, rules)
    labels["provenance"] = {
        "origin": "model_generated",
        "reference": "model",
        "reviewed_at": "now",
        "independent_of_judge": False,
    }
    with pytest.raises(EvaluationStopped, match="ANNOTATION_PROVENANCE_KIND_MISMATCH"):
        fit(packet, split, labels, manifest, state, rules)


def test_frozen_split_and_changed_calibration_labels_are_rejected(tmp_path):
    packet, split, labels, manifest, state, rules = fixture(tmp_path)
    calibrated = fit(packet, split, labels, manifest, state, rules)
    tampered = deepcopy(split)
    tampered["assignments"]["pilot-01"] = "holdout"
    if tampered == split:
        tampered["assignments"]["pilot-01"] = "calibration"
    with pytest.raises(EvaluationStopped, match="FROZEN_SPLIT_CHANGED"):
        fit(packet, tampered, labels, manifest, state, rules)
    changed = deepcopy(labels)
    for row in changed["judgments"]:
        if split["assignments"][row["unit_id"]] == "calibration" and row["anchors"]:
            row["verdict"] = "INSUFFICIENT"
            break
    with pytest.raises(EvaluationStopped, match="CALIBRATION_LABELS_CHANGED"):
        evaluate_holdout(packet, split, changed, manifest, state, calibrated)


def test_model_or_prompt_change_requires_fresh_calibration(tmp_path):
    packet, split, labels, manifest, state, rules = fixture(tmp_path)
    calibrated = fit(packet, split, labels, manifest, state, rules)
    changed = deepcopy(manifest)
    changed["policy"]["model"] = "different-model"
    with pytest.raises(EvaluationStopped, match="CALIBRATED_JUDGE_CHANGED"):
        evaluate_holdout(packet, split, labels, changed, state, calibrated)


def test_metrics_use_three_way_confusion_and_explicit_missing_denominators():
    truth = {"a": "SUPPORTED", "b": "CONTRADICTED", "c": "INSUFFICIENT", "d": "SUPPORTED"}
    predictions = {
        "a": {"verdict": "SUPPORTED", "confidence": 99},
        "b": {"verdict": "SUPPORTED", "confidence": 55},
        "c": {"verdict": "INSUFFICIENT", "confidence": 99},
    }
    raw = metrics(["a", "b", "c", "d", "e"], truth, predictions, 0)
    filtered = metrics(["a", "b", "c", "d", "e"], truth, predictions, 90)
    assert raw["pairedUnits"] == 3
    assert raw["pairCoverage"] == 0.6
    assert raw["missingHumanUnits"] == raw["missingJudgeUnits"] == 1
    assert raw["falseSupport"]["rate"] == 0.5
    assert filtered["falseSupport"]["rate"] == 0
    assert filtered["confusionRowsHumanColumnsJudge"]["CONTRADICTED"]["INSUFFICIENT"] == 1
    assert filtered["confusionRowsHumanColumnsJudge"]["INSUFFICIENT"]["SUPPORTED"] == 0
    assert raw["cohenKappa"] == pytest.approx(0.5)


def test_empty_truth_is_unmeasured_not_perfect_agreement():
    result = metrics(["a"], {}, {}, 90)
    assert result["agreementRate"] is None
    assert result["cohenKappa"] is None
    assert result["falseSupport"]["rate"] is None
    assert result["missingHumanUnits"] == 1


def test_frozen_threshold_filters_support_only_in_shadow(tmp_path):
    packet, split, labels, manifest, state, rules = fixture(tmp_path)
    calibrated = fit(packet, split, labels, manifest, state, rules)
    assert calibrated["selectedThreshold"] == 90
    low_confidence = next(row for row in state["judgments"] if row["confidence"] == 60)
    observation = shadow_observation(
        low_confidence, calibration=calibrated, judge_identity=calibrated["judgeIdentity"]
    )
    assert observation["verdict"] == "SUPPORTED"
    assert observation["calibratedVerdict"] == "INSUFFICIENT"
    assert observation["automaticPass"] is False
    unknown = next(row for row in state["judgments"] if row["verdict"] == "INSUFFICIENT")
    unknown["confidence"] = 100
    assert (
        shadow_observation(
            unknown, calibration=calibrated, judge_identity=calibrated["judgeIdentity"]
        )["calibratedVerdict"]
        == "INSUFFICIENT"
    )


def test_disagreement_review_preserves_both_original_labels_without_adjudication(tmp_path):
    packet, _, labels, manifest, state, _ = fixture(tmp_path)
    before = deepcopy(labels)
    review = disagreement_packet(packet, labels, manifest, state)
    assert labels == before
    assert len(review["disagreements"]) == 1
    row = review["disagreements"][0]
    assert row["humanVerdict"] == "CONTRADICTED"
    assert row["judgeVerdict"] == "SUPPORTED"
    assert row["adjudicatedVerdict"] is None
    assert review["automaticLabelChanges"] == 0
    assert row["source"]
