"""Offline, human-labelled sensitivity calibration; never updates runtime configuration.

Run ``python -m app.eval.sensitivity_train --help`` for the separate preparation,
group split, training, and held-out evaluation commands. Generated files belong
under the repository's ignored docs/ directory.
"""

import argparse
import hashlib
import heapq
import json
import sys
from bisect import bisect_left
from collections import Counter
from dataclasses import asdict
from decimal import Decimal
from itertools import combinations
from pathlib import Path
from typing import Any

from app.eval.sensitivity_data import LabeledCase, load_labeled_cases, prepare_dataset
from app.eval.sensitivity_scoring import (
    AXES,
    SensitivityScoringConfig,
    config_from_dict,
    level_for_score,
    load_sensitivity_scoring_config,
    score_axes,
)

LEVELS = ("low", "medium", "high")
POLICY = "train-shortlist-validation-macro-f1-high-recall-guard-v1"
PARTITION_VERSION = "sensitivity-partition.v1"
MODEL_VERSION = "sensitivity-calibration.v1"
_EPSILON = 1e-12


def _digest(value: object) -> str:
    return hashlib.sha256(
        json.dumps(value, sort_keys=True, ensure_ascii=False, allow_nan=False).encode("utf-8")
    ).hexdigest()


def _read(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"JSON root must be an object: {path}")
    return value


def _write(path: Path, value: object) -> None:
    # Exclusive creation also protects manually completed annotations and prior evaluations.
    rendered = json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n"
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8") as target:
        target.write(rendered)


def _validate_cases(cases: tuple[LabeledCase, ...]) -> None:
    if not cases:
        raise ValueError("Partition must contain labelled cases")
    seen_ids: set[str] = set()
    content_groups: dict[str, str] = {}
    url_groups: dict[str, str] = {}
    for case in cases:
        for field in (case.case_id, case.group_id, case.reviewer, case.rationale):
            if not isinstance(field, str) or not field.strip():
                raise ValueError("caseId, groupId, reviewer and rationale must be nonblank")
        if case.label not in LEVELS:
            raise ValueError(f"Independent human label required for {case.case_id}")
        if case.case_id in seen_ids:
            raise ValueError(f"Duplicate case ID: {case.case_id}")
        seen_ids.add(case.case_id)
        if (
            not isinstance(case.content_hash, str)
            or len(case.content_hash) != 64
            or any(c not in "0123456789abcdef" for c in case.content_hash)
        ):
            raise ValueError("Invalid article content hash")
        other_group = content_groups.setdefault(case.content_hash, case.group_id)
        if other_group != case.group_id:
            raise ValueError("Identical article text must belong to the same issue group")
        if not isinstance(case.canonical_url, str):
            raise ValueError("Article canonical URL must be a string")
        if case.canonical_url:
            other_group = url_groups.setdefault(case.canonical_url, case.group_id)
            if other_group != case.group_id:
                raise ValueError("The same article URL must belong to the same issue group")
        # Validate scores including null-vs-zero, without importing application settings.
        score_axes(case.axes, _uniform_config())


def _uniform_config() -> SensitivityScoringConfig:
    return SensitivityScoringConfig(
        *(Decimal("0.25") for _ in AXES), Decimal(40), Decimal(70)
    )


def _assert_disjoint(left: tuple[LabeledCase, ...], right: tuple[LabeledCase, ...]) -> None:
    for field in ("case_id", "group_id", "content_hash", "canonical_url"):
        left_values = {getattr(case, field) for case in left} - {""}
        right_values = {getattr(case, field) for case in right} - {""}
        if left_values & right_values:
            raise ValueError(f"Partitions overlap on {field}; split by independent issue")


def _require_classes(cases: tuple[LabeledCase, ...], name: str) -> None:
    absent = set(LEVELS) - {case.label for case in cases}
    if absent:
        raise ValueError(f"{name} needs all three human label classes; missing {sorted(absent)}")


def axis_coverage(cases: tuple[LabeledCase, ...]) -> dict[str, object]:
    return {
        axis: {
            "available": sum(case.axes[axis] is not None for case in cases),
            "distinctScores": sorted({
                case.axes[axis] for case in cases if case.axes[axis] is not None
            }),
        }
        for axis in AXES
    }


def _require_weight_information(cases: tuple[LabeledCase, ...]) -> None:
    coverage = axis_coverage(cases)
    for axis in AXES:
        if len(coverage[axis]["distinctScores"]) < 2:
            raise ValueError(f"Training axis {axis} needs at least two observed scores")
    # Disconnected axes have an arbitrary relative scale under available-axis renormalization.
    neighbours: dict[str, set[str]] = {axis: set() for axis in AXES}
    for left, right in combinations(AXES, 2):
        if any(
            case.axes[left] is not None and case.axes[right] is not None
            and case.axes[left] != case.axes[right]
            for case in cases
        ):
            neighbours[left].add(right)
            neighbours[right].add(left)
    visited: set[str] = set()
    pending = [AXES[0]]
    while pending:
        axis = pending.pop()
        if axis not in visited:
            visited.add(axis)
            pending.extend(neighbours[axis] - visited)
    if len(visited) != len(AXES):
        raise ValueError("Training axes lack connected, differing co-observations to fit weights")


def group_role(group: str, seed: int) -> str:
    bucket = int(hashlib.sha256(f"{seed}:{group}".encode()).hexdigest(), 16) % 10
    return "holdout" if bucket < 2 else "validation" if bucket < 4 else "train"


def split_dataset(
    features_path: Path, labels_path: Path, output_dir: Path, *, seed: int = 42
) -> dict[str, object]:
    cases = load_labeled_cases(features_path, labels_path)
    _validate_cases(cases)
    if output_dir.exists():
        raise ValueError("Split output directory already exists; refusing to overwrite")
    source = _read(features_path)
    groups = sorted({case.group_id for case in cases})
    # Stable label-independent assignment. Never retry seeds based on held-out outcomes.
    assignments = {group: group_role(group, seed) for group in groups}
    partitions = {
        role: tuple(case for case in cases if assignments[case.group_id] == role)
        for role in ("train", "validation", "holdout")
    }
    if any(not rows for rows in partitions.values()):
        raise ValueError("Not enough independent groups for a nonempty 60/20/20 hash split")
    output_dir.mkdir(parents=True)
    summary: dict[str, object] = {
        "splitPolicy": "sha256(seed:groupId)-mod10; holdout=0,1; validation=2,3; train=4..9",
        "seed": seed,
        "warning": "Assign groups before splitting; do not retry seeds after viewing holdout",
        "partitions": {},
    }
    for role, rows in partitions.items():
        payload = {
            "version": PARTITION_VERSION,
            "role": role,
            "promptVersion": source["promptVersion"],
            "sourceKind": source.get("sourceKind", "feature-export"),
            "sourceVersion": source.get("sourceVersion", source["version"]),
            "seed": seed,
            "cases": [asdict(case) for case in rows],
        }
        _write(output_dir / f"{role}.json", payload)
        summary["partitions"][role] = {
            "cases": len(rows), "groups": len({case.group_id for case in rows}),
            "labels": dict(Counter(case.label for case in rows)),
        }
    _write(output_dir / "split-summary.json", summary)
    return summary


def load_partition(path: Path, role: str) -> tuple[dict[str, Any], tuple[LabeledCase, ...]]:
    payload = _read(path)
    if payload.get("version") != PARTITION_VERSION or payload.get("role") != role:
        raise ValueError(f"Expected {role} partition of version {PARTITION_VERSION}")
    if not isinstance(payload.get("promptVersion"), str) or not payload["promptVersion"].strip():
        raise ValueError("Partition must record the analysis prompt version")
    if not isinstance(payload.get("cases"), list):
        raise ValueError("Partition cases must be an array")
    if type(payload.get("seed")) is not int:
        raise ValueError("Partition must record an integer split seed")
    try:
        cases = tuple(LabeledCase(**row) for row in payload["cases"])
    except TypeError as error:
        raise ValueError("Invalid labelled partition record") from error
    _validate_cases(cases)
    if any(group_role(case.group_id, payload["seed"]) != role for case in cases):
        raise ValueError("Partition role does not match the frozen group hash split")
    return payload, cases


def _metrics(matrix: list[list[int]]) -> dict[str, object]:
    support = [sum(row) for row in matrix]
    predicted = [sum(row[i] for row in matrix) for i in range(3)]
    f1 = [
        2 * matrix[i][i] / (support[i] + predicted[i])
        if support[i] + predicted[i] else 0.0
        for i in range(3)
    ]
    return {
        "macroF1": sum(f1) / 3,
        "accuracy": sum(matrix[i][i] for i in range(3)) / sum(support),
        "highPrecision": matrix[2][2] / predicted[2] if predicted[2] else 0.0,
        "highRecall": matrix[2][2] / support[2] if support[2] else 0.0,
        "highFalseNegatives": support[2] - matrix[2][2],
        "highFalsePositives": predicted[2] - matrix[2][2],
        "support": dict(zip(LEVELS, support, strict=True)),
        "confusionMatrix": {"labels": list(LEVELS), "rowsActualColumnsPredicted": matrix},
    }


def evaluate_cases(
    cases: tuple[LabeledCase, ...], config: SensitivityScoringConfig
) -> dict[str, object]:
    matrix = [[0] * 3 for _ in LEVELS]
    for case in cases:
        predicted = level_for_score(score_axes(case.axes, config), config)
        matrix[LEVELS.index(case.label)][LEVELS.index(predicted)] += 1
    return _metrics(matrix)


def _binned_metrics(
    scores: list[list[Decimal]], medium: Decimal, high: Decimal
) -> dict[str, object]:
    matrix = []
    for row in scores:
        low_count = bisect_left(row, medium)
        non_high_count = bisect_left(row, high)
        matrix.append([low_count, non_high_count - low_count, len(row) - non_high_count])
    return _metrics(matrix)


def _distance(config: SensitivityScoringConfig, baseline: SensitivityScoringConfig) -> float:
    left, right = config.to_dict(), baseline.to_dict()
    return sum(
        abs(left[key] - right[key]) / (100 if key.endswith("Threshold") else 1)
        for key in left
    )


def _exact_config(config: SensitivityScoringConfig) -> dict[str, str]:
    return dict(zip(
        config.to_dict(),
        (str(value) for value in (*config.weights, config.medium_threshold, config.high_threshold)),
        strict=True,
    ))


def _quality(metrics: dict[str, Any]) -> tuple[float, float, float]:
    return metrics["macroF1"], metrics["highRecall"], metrics["highPrecision"]


def _no_regression(metrics: dict[str, Any], baseline: dict[str, Any]) -> bool:
    return (
        metrics["macroF1"] + _EPSILON >= baseline["macroF1"]
        and metrics["highRecall"] + _EPSILON >= baseline["highRecall"]
    )


def _weight_grid(step: int):
    for a in range(step, 100, step):
        for b in range(step, 100 - a, step):
            for c in range(step, 100 - a - b, step):
                d = 100 - a - b - c
                if d >= step and d % step == 0:
                    yield tuple(Decimal(value) / 100 for value in (a, b, c, d))


def train(
    train_path: Path, validation_path: Path, baseline: SensitivityScoringConfig, *,
    weight_step: int = 5, threshold_step: int = 5, shortlist_size: int = 25,
) -> dict[str, object]:
    if weight_step not in (5, 10, 20, 25) or threshold_step not in (5, 10, 20):
        raise ValueError("Unsupported grid steps")
    if isinstance(shortlist_size, bool) or not isinstance(shortlist_size, int):
        raise ValueError("shortlist size must be an integer")
    if not 1 <= shortlist_size <= 1000:
        raise ValueError("shortlist size must be between 1 and 1000")
    train_payload, training = load_partition(train_path, "train")
    validation_payload, validation = load_partition(validation_path, "validation")
    for key in ("promptVersion", "sourceKind", "sourceVersion", "seed"):
        if train_payload.get(key) != validation_payload.get(key):
            raise ValueError(f"Training and validation metadata differ: {key}")
    _assert_disjoint(training, validation)
    _require_classes(training, "Training")
    _require_classes(validation, "Validation")
    _require_weight_information(training)
    baseline_train = evaluate_cases(training, baseline)
    baseline_validation = evaluate_cases(validation, baseline)
    thresholds = sorted({
        *(Decimal(value) for value in range(0, 101, threshold_step)),
        baseline.medium_threshold, baseline.high_threshold,
    })
    baseline_weights = (
        baseline.customer_move_weight, baseline.deal_signal_weight,
        baseline.competitor_threat_weight, baseline.industry_shift_weight,
    )
    weights = sorted({*_weight_grid(weight_step), baseline_weights})
    heap = []
    candidate_count = 0
    for values in weights:
        scoring = SensitivityScoringConfig(
            *values, baseline.medium_threshold, baseline.high_threshold
        )
        scores = [sorted(
            score_axes(case.axes, scoring) for case in training if case.label == label
        ) for label in LEVELS]
        for medium, high in combinations(thresholds, 2):
            candidate_count += 1
            metrics = _binned_metrics(scores, medium, high)
            if not _no_regression(metrics, baseline_train):
                continue
            candidate = SensitivityScoringConfig(*values, medium, high)
            rank = (*_quality(metrics), -_distance(candidate, baseline), -candidate_count)
            entry = (rank, candidate_count, candidate, metrics)
            if len(heap) < shortlist_size:
                heapq.heappush(heap, entry)
            elif rank > heap[0][0]:
                heapq.heapreplace(heap, entry)
    shortlist = [(baseline, baseline_train)] + [
        (entry[2], entry[3]) for entry in sorted(heap, reverse=True) if entry[2] != baseline
    ]
    selected, selected_train, selected_validation = baseline, baseline_train, baseline_validation
    validation_results = []
    for candidate, train_metrics in shortlist:
        metrics = evaluate_cases(validation, candidate)
        validation_results.append(metrics)
        if not _no_regression(metrics, baseline_validation):
            continue
        # Keep the full existing config on a macro-F1 tie, irrespective of arbitrary weights.
        if metrics["macroF1"] <= baseline_validation["macroF1"] + _EPSILON:
            continue
        candidate_rank = (*_quality(metrics), -_distance(candidate, baseline))
        selected_rank = (*_quality(selected_validation), -_distance(selected, baseline))
        if candidate_rank > selected_rank:
            selected, selected_train, selected_validation = candidate, train_metrics, metrics
    all_tuning = training + validation
    artifact: dict[str, object] = {
        "version": MODEL_VERSION,
        "policy": POLICY,
        "promptVersion": train_payload["promptVersion"],
        "sourceKind": train_payload.get("sourceKind", "feature-export"),
        "sourceVersion": train_payload.get("sourceVersion"),
        "seed": train_payload["seed"],
        "selection": "candidate" if selected != baseline else "keep-baseline",
        "selectedConfig": _exact_config(selected),
        "baselineConfig": _exact_config(baseline),
        "train": {"baseline": baseline_train, "selected": selected_train,
                  "axisCoverage": axis_coverage(training)},
        "validation": {"baseline": baseline_validation, "selected": selected_validation,
                       "axisCoverage": axis_coverage(validation)},
        "search": {
            "weightStepPercent": weight_step, "thresholdStep": threshold_step,
            "shortlistSize": shortlist_size, "candidatesEvaluatedOnTrain": candidate_count,
            "candidatesEvaluatedOnValidation": len(shortlist),
            "validationQualityTies": sum(
                _quality(metrics) == _quality(selected_validation) for metrics in validation_results
            ),
        },
        "lineage": {
            "trainSha256": _digest(train_payload),
            "validationSha256": _digest(validation_payload),
            "caseIds": sorted(case.case_id for case in all_tuning),
            "groupIds": sorted({case.group_id for case in all_tuning}),
            "contentHashes": sorted({case.content_hash for case in all_tuning}),
            "canonicalUrls": sorted({case.canonical_url for case in all_tuning} - {""}),
        },
        "warnings": [
            "Finite grid and shortlist; fitted coefficients are not a unique/global optimum.",
            "Human provenance is an annotator assertion; review labels independently.",
            "Held-out evaluation is still required; runtime configuration was not changed.",
        ],
    }
    if artifact["sourceKind"] == "golden-demo":
        artifact["warnings"].append("Golden replay is demonstration data, not production evidence.")
    artifact["artifactSha256"] = _digest(artifact)
    return artifact


def evaluate_holdout(model_path: Path, holdout_path: Path) -> dict[str, object]:
    artifact = _read(model_path)
    checksum = artifact.pop("artifactSha256", None)
    if (
        artifact.get("version") != MODEL_VERSION or artifact.get("policy") != POLICY
        or checksum != _digest(artifact)
    ):
        raise ValueError("Invalid or modified model artifact; train and freeze a model first")
    payload, cases = load_partition(holdout_path, "holdout")
    for key in ("promptVersion", "sourceKind", "sourceVersion", "seed"):
        if payload.get(key) != artifact.get(key):
            raise ValueError(f"Holdout metadata differs from training: {key}")
    for field, key in (("case_id", "caseIds"), ("group_id", "groupIds"),
                       ("content_hash", "contentHashes"), ("canonical_url", "canonicalUrls")):
        if ({getattr(case, field) for case in cases} - {""}) & set(artifact["lineage"][key]):
            raise ValueError(f"Holdout overlaps tuning data on {field}")
    _require_classes(cases, "Holdout")
    baseline = evaluate_cases(cases, config_from_dict(artifact["baselineConfig"]))
    selected = evaluate_cases(cases, config_from_dict(artifact["selectedConfig"]))
    improved = (
        _no_regression(selected, baseline)
        and selected["macroF1"] > baseline["macroF1"] + _EPSILON
    )
    return {
        "version": "sensitivity-holdout-evaluation.v1",
        "modelSha256": checksum, "holdoutSha256": _digest(payload),
        "sourceKind": artifact["sourceKind"], "promptVersion": artifact["promptVersion"],
        "baseline": baseline, "selected": selected,
        "macroF1Delta": selected["macroF1"] - baseline["macroF1"],
        "highRecallDelta": selected["highRecall"] - baseline["highRecall"],
        "axisCoverage": axis_coverage(cases),
        "result": "improved" if improved else "no-improvement" if _no_regression(
            selected, baseline
        ) else "regression",
        "demonstrationOnly": artifact["sourceKind"] == "golden-demo",
        "statisticalSignificanceAssessed": False,
        "runtimeConfigChanged": False,
        "warning": "Do not tune or choose a new seed from holdout results; use a new final set.",
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    prepare = commands.add_parser("prepare", help="Create blank blind human review files")
    prepare.add_argument("--source", type=Path, required=True)
    prepare.add_argument("--output-dir", type=Path, required=True)
    split = commands.add_parser("split", help="Freeze label-independent issue-group partitions")
    split.add_argument("--features", type=Path, required=True)
    split.add_argument("--labels", type=Path, required=True)
    split.add_argument("--output-dir", type=Path, required=True)
    split.add_argument("--seed", type=int, default=42)
    fit = commands.add_parser("train", help="Fit train shortlist; select using validation only")
    fit.add_argument("--train", type=Path, required=True)
    fit.add_argument("--validation", type=Path, required=True)
    fit.add_argument("--backend-config", type=Path)
    fit.add_argument("--weight-step", type=int, choices=(5, 10, 20, 25), default=5)
    fit.add_argument("--threshold-step", type=int, choices=(5, 10, 20), default=5)
    fit.add_argument("--shortlist-size", type=int, default=25)
    fit.add_argument("--output", type=Path, required=True)
    evaluate = commands.add_parser("evaluate", help="Evaluate frozen coefficients on holdout")
    evaluate.add_argument("--model", type=Path, required=True)
    evaluate.add_argument("--holdout", type=Path, required=True)
    evaluate.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if getattr(args, "output", None) is not None and args.output.exists():
            raise ValueError("Output already exists; refusing to overwrite")
        if args.command == "prepare":
            result = prepare_dataset(args.source, args.output_dir)
        elif args.command == "split":
            result = split_dataset(args.features, args.labels, args.output_dir, seed=args.seed)
        elif args.command == "train":
            config = (
                load_sensitivity_scoring_config(args.backend_config) if args.backend_config
                else load_sensitivity_scoring_config()
            )
            result = train(
                args.train, args.validation, config, weight_step=args.weight_step,
                threshold_step=args.threshold_step, shortlist_size=args.shortlist_size,
            )
            _write(args.output, result)
        else:
            result = evaluate_holdout(args.model, args.holdout)
            _write(args.output, result)
        print(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False))
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"sensitivity calibration error: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
