"""Human-attested, source-group-separated semantic judge calibration.

Fit uses calibration labels only. A frozen fitted artifact is evaluated on holdout
once per report; reusing holdout to choose a prompt/model requires a new holdout.
Metrics are descriptive, not an automatic production-release authorization.
"""

from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path

from pydantic import BaseModel, ConfigDict, Field

from app.eval.report_insight_run import atomic_save, digest, require
from app.eval.report_insight_semantic_judge import (
    JudgeJudgment,
    saved_annotations,
    validate_packet,
)
from app.eval.report_insight_semantic_review import VERDICTS, _annotations
from app.eval.topic_relevance_score import wilson


class CalibrationPolicy(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)
    minimum_per_class: int = Field(default=10, ge=1)
    minimum_supported_predictions: int = Field(default=30, ge=1)
    maximum_false_support_upper95: float = Field(default=0.10, gt=0, lt=1)
    minimum_agreement: float = Field(default=0.85, gt=0, le=1)
    thresholds: tuple[int, ...] = (50, 60, 70, 80, 90, 95, 100)


def split_manifest(packet: dict, *, salt: str) -> dict:
    """Freeze splits before seeing labels, grouping source snapshots and families.

    Connected units share a split if they share a source snapshot, declared family,
    or original sentence text. Source-grouping is conservative (a full report can
    be one group), so a single report alone cannot establish held-out performance.
    """
    units = validate_packet(packet)
    require(bool(salt), "SPLIT_SALT_REQUIRED")
    owners, parent = {}, {identifier: identifier for identifier in units}

    def root(identifier):
        while parent[identifier] != identifier:
            parent[identifier] = parent[parent[identifier]]
            identifier = parent[identifier]
        return identifier

    for identifier, unit in units.items():
        keys = ["snapshot:" + unit["sourceHash"]]
        if unit.get("sourceGroup"):
            keys.append("family:" + unit["sourceGroup"])
        keys.extend(
            "sentence:" + digest(sentence["text"])
            for source in unit["source"]
            for sentence in source["sentences"]
        )
        for key in keys:
            if key in owners:
                parent[root(identifier)] = root(owners[key])
            else:
                owners[key] = identifier
    groups = {}
    for identifier in units:
        groups.setdefault(root(identifier), []).append(identifier)
    assignments, group_hashes = {}, {}
    for identifiers in groups.values():
        group_hash = digest(sorted(identifiers))
        split = "calibration" if int(digest([salt, group_hash])[:8], 16) % 2 == 0 else "holdout"
        for identifier in identifiers:
            assignments[identifier] = split
            group_hashes[identifier] = group_hash
    result = {
        "schemaVersion": 1,
        "packetHash": packet["packetHash"],
        "salt": salt,
        "algorithm": "source-connected-components-sha256-parity-v1",
        "assignments": assignments,
        "sourceGroups": group_hashes,
        "labelIndependent": True,
    }
    return {**result, "splitHash": digest(result)}


def _validate_split(packet: dict, split: dict):
    require(split == split_manifest(packet, salt=split["salt"]), "FROZEN_SPLIT_CHANGED")


def import_human_labels(packet: dict, review: dict) -> dict:
    """Record supplied human answers, never infer labels or fill missing answers.

    The reviewer must explicitly attest the labels refer to the displayed source
    context. A condensed presentation must be declared. Anchors map that context
    to original sentences; they are not misattributed as reviewer-selected offsets.
    """
    units = validate_packet(packet)
    require(review.get("packetHash") == packet["packetHash"], "HUMAN_PACKET_MISMATCH")
    require(review.get("sourceContextReviewed") is True, "HUMAN_SOURCE_REVIEW_REQUIRED")
    require(review.get("independentOfJudge") is True, "HUMAN_BLIND_REVIEW_REQUIRED")
    require(isinstance(review.get("labels"), dict), "HUMAN_LABELS_REQUIRED")
    require(set(review["labels"]) <= units.keys(), "UNKNOWN_HUMAN_UNIT")
    judgments = []
    for identifier, verdict in review["labels"].items():
        require(verdict in VERDICTS, "INVALID_HUMAN_VERDICT")
        unit = units[identifier]
        anchors = (
            []
            if verdict == "INSUFFICIENT"
            else [
                {
                    "claim_id": source["claimId"],
                    "sentence_id": sentence["sentenceId"],
                    "start": 0,
                    "end": len(sentence["text"]),
                    "quote": sentence["text"],
                }
                for source in unit["source"]
                if source["claimId"] in unit["candidateClaimIds"]
                for sentence in source["sentences"]
            ]
        )
        judgments.append({"unit_id": identifier, "verdict": verdict, "anchors": anchors})
    value = {
        "packet_hash": packet["packetHash"],
        "kind": "human",
        "annotator": review["reviewer"],
        "judgments": judgments,
        "provenance": {
            "origin": "external_human_review",
            "reference": review["reference"],
            "reviewed_at": review["reviewedAt"],
            "independent_of_judge": True,
            "source_presentation": review.get("sourcePresentation", "original_sentences"),
            "anchor_origin": "system_context_mapping",
        },
    }
    parsed, _ = _annotations(packet, value, "human")
    return parsed.model_dump(mode="json")


def _human(packet, annotations):
    parsed, labels = _annotations(packet, annotations, "human")
    require(
        parsed.provenance is not None
        and parsed.provenance.origin == "external_human_review"
        and parsed.provenance.independent_of_judge,
        "HUMAN_PROVENANCE_REQUIRED_FOR_CALIBRATION",
    )
    return labels


def _judge(packet, manifest, state):
    annotations = saved_annotations(manifest, state)
    _, labels = _annotations(packet, annotations, "judge")
    require(state.get("inFlight") is None, "JUDGE_UNSETTLED_IN_FLIGHT")
    rows = {
        row.unit_id: row.model_dump()
        for row in (JudgeJudgment.model_validate(value) for value in state["judgments"])
    }
    require(rows.keys() == labels.keys(), "JUDGE_PREDICTION_MISMATCH")
    return rows


def judge_identity(manifest: dict, state: dict) -> dict:
    """Bind calibration to observed snapshots as well as a movable model alias."""
    successful = [row for row in state["records"] if row["status"] == "success"]
    return {
        "model": manifest["policy"]["model"],
        "resolvedModels": sorted(
            {row["resolvedModel"] for row in successful if row.get("resolvedModel")}
        ),
        "promptVersion": manifest["promptVersion"],
        "promptHash": manifest["promptHash"],
        "responseSchemaHash": manifest["responseSchemaHash"],
        "anchorBindingVersions": sorted(
            {row.get("anchorBindingVersion", "legacy-offset-exact") for row in successful}
        ),
    }


def metrics(identifiers: list[str], truth: dict, predictions: dict, threshold: int) -> dict:
    matrix = {label: dict.fromkeys(VERDICTS, 0) for label in VERDICTS}
    human_counts = Counter(truth[identifier] for identifier in identifiers if identifier in truth)
    paired = 0
    for identifier in identifiers:
        if identifier not in truth or identifier not in predictions:
            continue
        row = predictions[identifier]
        predicted = row["verdict"]
        if predicted == "SUPPORTED" and row["confidence"] < threshold:
            predicted = "INSUFFICIENT"
        matrix[truth[identifier]][predicted] += 1
        paired += 1
    correct = sum(matrix[label][label] for label in VERDICTS)
    expected = (
        sum(
            sum(matrix[label].values()) * sum(matrix[row][label] for row in VERDICTS)
            for label in VERDICTS
        )
        / (paired * paired)
        if paired
        else None
    )
    agreement = correct / paired if paired else None
    kappa = (
        (agreement - expected) / (1 - expected) if expected is not None and expected < 1 else None
    )
    supported = sum(matrix[label]["SUPPORTED"] for label in VERDICTS)
    false_support = sum(matrix[label]["SUPPORTED"] for label in ("CONTRADICTED", "INSUFFICIENT"))
    return {
        "plannedUnits": len(identifiers),
        "humanLabeledUnits": sum(human_counts.values()),
        "judgeLabeledUnits": sum(identifier in predictions for identifier in identifiers),
        "pairedUnits": paired,
        "pairCoverage": paired / len(identifiers) if identifiers else None,
        "missingHumanUnits": len(identifiers) - sum(human_counts.values()),
        "missingJudgeUnits": sum(
            identifier in truth and identifier not in predictions for identifier in identifiers
        ),
        "humanCounts": {label: human_counts[label] for label in VERDICTS},
        "confusionRowsHumanColumnsJudge": matrix,
        "agreementRate": agreement,
        "cohenKappa": kappa,
        "supportedPredictions": supported,
        "falseSupport": wilson(false_support, supported),
        "supportCoverage": supported / len(identifiers) if identifiers else None,
        "threshold": threshold,
        "intervalCaveat": "descriptive; related cases are not independent Bernoulli trials",
    }


def _adequate(result: dict, policy: CalibrationPolicy) -> bool:
    interval = result["falseSupport"]["wilson95"]
    return (
        all(count >= policy.minimum_per_class for count in result["humanCounts"].values())
        and result["missingJudgeUnits"] == 0
        and result["missingHumanUnits"] == 0
        and result["supportedPredictions"] >= policy.minimum_supported_predictions
        and interval is not None
        and interval["upper"] <= policy.maximum_false_support_upper95
        and result["agreementRate"] is not None
        and result["agreementRate"] >= policy.minimum_agreement
    )


def fit(
    packet: dict,
    split: dict,
    human: dict,
    judge_manifest: dict,
    judge_state: dict,
    policy: dict | None = None,
) -> dict:
    _validate_split(packet, split)
    truth, predictions = _human(packet, human), _judge(packet, judge_manifest, judge_state)
    rules = CalibrationPolicy.model_validate(policy or {})
    require(
        bool(rules.thresholds)
        and len(set(rules.thresholds)) == len(rules.thresholds)
        and all(type(value) is int and 0 <= value <= 100 for value in rules.thresholds),
        "INVALID_CALIBRATION_THRESHOLDS",
    )
    ids = [
        identifier for identifier, value in split["assignments"].items() if value == "calibration"
    ]
    # Never inspect held-out truth when selecting a confidence threshold.
    truth = {identifier: truth[identifier] for identifier in ids if identifier in truth}
    predictions = {
        identifier: predictions[identifier] for identifier in ids if identifier in predictions
    }
    candidates = [metrics(ids, truth, predictions, threshold) for threshold in rules.thresholds]
    eligible = [row for row in candidates if _adequate(row, rules)]
    selected = (
        max(eligible, key=lambda row: (row["supportedPredictions"], -row["threshold"]))
        if eligible
        else None
    )
    result = {
        "schemaVersion": 1,
        "packetHash": packet["packetHash"],
        "splitHash": split["splitHash"],
        "judgeIdentity": judge_identity(judge_manifest, judge_state),
        "policy": rules.model_dump(mode="json"),
        "calibrationTruthHash": digest(truth),
        "calibrationPredictionsHash": digest(predictions),
        "status": "fitted" if selected else "INSUFFICIENT_CALIBRATION_EVIDENCE",
        "selectedThreshold": selected["threshold"] if selected else None,
        "calibrationMetrics": candidates,
        "heldoutLabelsUsedForFit": False,
        "automaticReleaseDecision": None,
        "confidenceMeaning": (
            "model self-rating, threshold empirically selected on calibration only"
        ),
    }
    return {**result, "calibrationHash": digest(result)}


def evaluate_holdout(
    packet: dict,
    split: dict,
    human: dict,
    judge_manifest: dict,
    judge_state: dict,
    calibration: dict,
) -> dict:
    _validate_split(packet, split)
    require(
        calibration.get("calibrationHash")
        == digest({key: value for key, value in calibration.items() if key != "calibrationHash"}),
        "CALIBRATION_ARTIFACT_CHANGED",
    )
    require(
        calibration["packetHash"] == packet["packetHash"]
        and calibration["splitHash"] == split["splitHash"],
        "CALIBRATION_SCOPE_CHANGED",
    )
    require(
        calibration["judgeIdentity"] == judge_identity(judge_manifest, judge_state),
        "CALIBRATED_JUDGE_CHANGED",
    )
    truth, predictions = _human(packet, human), _judge(packet, judge_manifest, judge_state)
    calibration_ids = [
        identifier for identifier, value in split["assignments"].items() if value == "calibration"
    ]
    require(
        calibration["calibrationTruthHash"]
        == digest(
            {identifier: truth[identifier] for identifier in calibration_ids if identifier in truth}
        ),
        "CALIBRATION_LABELS_CHANGED",
    )
    require(
        calibration["calibrationPredictionsHash"]
        == digest(
            {
                identifier: predictions[identifier]
                for identifier in calibration_ids
                if identifier in predictions
            }
        ),
        "CALIBRATION_PREDICTIONS_CHANGED",
    )
    ids = [identifier for identifier, value in split["assignments"].items() if value == "holdout"]
    threshold = calibration["selectedThreshold"]
    raw = metrics(ids, truth, predictions, 0)
    calibrated = metrics(ids, truth, predictions, threshold) if threshold is not None else None
    rules = CalibrationPolicy.model_validate(calibration["policy"])
    adequate = calibrated is not None and _adequate(calibrated, rules)
    return {
        "packetHash": packet["packetHash"],
        "splitHash": split["splitHash"],
        "calibrationHash": calibration["calibrationHash"],
        "humanAnnotationHash": digest(human),
        "judgeResultHash": digest(judge_state),
        "status": "HELDOUT_CRITERIA_MET" if adequate else "INSUFFICIENT_HELDOUT_EVIDENCE",
        "rawHoldoutMetrics": raw,
        "calibratedHoldoutMetrics": calibrated,
        "humanProvenance": human["provenance"],
        "mode": "shadow",
        "automaticReleaseDecision": None,
        "mayOverrideDeterministicValidation": False,
        "reusePolicy": (
            "Do not tune model/prompt/threshold using this heldout report; obtain new holdout."
        ),
    }


def disagreement_packet(packet: dict, human: dict, judge_manifest: dict, judge_state: dict) -> dict:
    """Present disagreements for a new human review, preserving both original labels."""
    units = validate_packet(packet)
    truth, predictions = _human(packet, human), _judge(packet, judge_manifest, judge_state)
    rows = []
    for identifier in sorted(truth.keys() & predictions.keys()):
        prediction = predictions[identifier]
        if truth[identifier] == prediction["verdict"]:
            continue
        rows.append(
            {
                "unitId": identifier,
                "statement": units[identifier]["statement"],
                "source": units[identifier]["source"],
                "humanVerdict": truth[identifier],
                "judgeVerdict": prediction["verdict"],
                "judgeRationale": prediction["rationale"],
                "judgeConfidenceUncalibrated": prediction["confidence"],
                "judgeAnchors": prediction["anchors"],
                "adjudicatedVerdict": None,
            }
        )
    result = {
        "packetHash": packet["packetHash"],
        "humanAnnotationHash": digest(human),
        "judgeResultHash": digest(judge_state),
        "disagreements": rows,
        "unlabeledHumanUnits": sorted(units.keys() - truth.keys()),
        "unlabeledJudgeUnits": sorted(units.keys() - predictions.keys()),
        "automaticLabelChanges": 0,
        "instructions": (
            "A real reviewer must adjudicate disagreements. Preserve original annotations; "
            "labels revised after seeing judge predictions are not blind heldout truth."
        ),
    }
    return {**result, "reviewHash": digest(result)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "command", choices=("split", "import-human", "fit", "holdout", "disagreements")
    )
    parser.add_argument("--packet", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--salt")
    parser.add_argument("--split", type=Path)
    parser.add_argument("--human", type=Path)
    parser.add_argument("--judge-directory", type=Path)
    parser.add_argument("--calibration", type=Path)
    args = parser.parse_args()

    def read(path):
        require(path is not None, "COMMAND_INPUT_REQUIRED")
        return json.loads(path.read_text())

    packet = read(args.packet)
    if args.command == "split":
        result = split_manifest(packet, salt=args.salt)
    elif args.command == "import-human":
        result = import_human_labels(packet, read(args.human))
    else:
        require(args.judge_directory is not None, "JUDGE_DIRECTORY_REQUIRED")
        shared_inputs = (
            packet,
            read(args.human),
            read(args.judge_directory / "manifest.json"),
            read(args.judge_directory / "result.json"),
        )
        if args.command == "disagreements":
            result = disagreement_packet(*shared_inputs)
        else:
            inputs = (packet, read(args.split), *shared_inputs[1:])
            result = (
                fit(*inputs)
                if args.command == "fit"
                else evaluate_holdout(*inputs, read(args.calibration))
            )
    require(not args.output.exists(), "FROZEN_OUTPUT_ALREADY_EXISTS")
    args.output.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    atomic_save(args.output, result)


if __name__ == "__main__":
    main()
