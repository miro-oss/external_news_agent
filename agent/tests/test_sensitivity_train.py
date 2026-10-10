"""Synthetic control cases test calibration mechanics, never production quality."""

import csv
import hashlib
import json
from dataclasses import asdict, replace
from decimal import Decimal
from itertools import product
from pathlib import Path

import pytest

from app.eval.sensitivity_data import LabeledCase
from app.eval.sensitivity_scoring import AXES, SensitivityScoringConfig, config_from_dict
from app.eval.sensitivity_train import (
    PARTITION_VERSION,
    _binned_metrics,
    _exact_config,
    _require_weight_information,
    evaluate_cases,
    evaluate_holdout,
    group_role,
    load_partition,
    main,
    split_dataset,
    train,
)

BASELINE = SensitivityScoringConfig(
    Decimal("0.35"), Decimal("0.30"), Decimal("0.20"), Decimal("0.15"),
    Decimal(40), Decimal(70),
)


def controls(role: str) -> tuple[LabeledCase, ...]:
    groups = (f"{role}-{i}" for i in range(10000))
    groups = (group for group in groups if group_role(group, 42) == role)
    result = []
    for i, scores in enumerate(product((0, 1, 3), repeat=4)):
        identity = f"{role}-{i}"
        # Independent synthetic rule: uniform weights, thresholds 30 and 60.
        total = sum(scores)
        label = "low" if total < 3.6 else "medium" if total < 7.2 else "high"
        result.append(LabeledCase(
            case_id=identity, group_id=next(groups), label=label,
            reviewer="synthetic-control", rationale="Test-only known classification rule",
            axes=dict(zip(AXES, scores, strict=True)),
            content_hash=hashlib.sha256(identity.encode()).hexdigest(),
            canonical_url=f"https://example.test/{identity}",
        ))
    return tuple(result)


def partition(tmp_path: Path, role: str, cases=None) -> Path:
    path = tmp_path / f"{role}.json"
    path.write_text(json.dumps({
        "version": PARTITION_VERSION, "role": role, "seed": 42,
        "promptVersion": "synthetic-test-v1", "sourceKind": "golden-demo",
        "sourceVersion": "synthetic-controls",
        "cases": [asdict(case) for case in (controls(role) if cases is None else cases)],
    }), encoding="utf-8")
    return path


def fit(tmp_path: Path, *, baseline=BASELINE):
    return train(
        partition(tmp_path, "train"), partition(tmp_path, "validation"), baseline,
        weight_step=25, threshold_step=10, shortlist_size=25,
    )


def model_file(tmp_path: Path, artifact=None) -> Path:
    path = tmp_path / "model.json"
    path.write_text(json.dumps(fit(tmp_path) if artifact is None else artifact), encoding="utf-8")
    return path


def test_learns_coefficients_and_improves_unseen_synthetic_controls(tmp_path):
    artifact = fit(tmp_path)
    assert artifact["selection"] == "candidate"
    assert artifact["train"]["selected"]["macroF1"] == 1
    assert artifact["validation"]["selected"]["macroF1"] == 1
    assert artifact["validation"]["baseline"]["macroF1"] < 1
    assert artifact["search"]["candidatesEvaluatedOnTrain"] > 100
    result = evaluate_holdout(model_file(tmp_path, artifact), partition(tmp_path, "holdout"))
    assert result["result"] == "improved"
    assert result["selected"]["macroF1"] == 1
    assert result["selected"]["highFalseNegatives"] == 0
    assert result["demonstrationOnly"] is True
    assert result["statisticalSignificanceAssessed"] is False
    assert result["runtimeConfigChanged"] is False


def test_baseline_retained_whole_on_ties_even_off_grid(tmp_path):
    baseline = SensitivityScoringConfig(
        *(Decimal("0.25") for _ in AXES), Decimal("30.001"), Decimal("60.001")
    )
    artifact = fit(tmp_path, baseline=baseline)
    assert artifact["selection"] == "keep-baseline"
    assert config_from_dict(artifact["selectedConfig"]) == baseline


def test_configs_preserve_exact_decimal_values():
    baseline = SensitivityScoringConfig(
        *(Decimal("0.33333333333333333333333333") for _ in range(3)),
        Decimal("0.00000000000000000000000001"), Decimal("40.001"), Decimal("70.003"),
    )
    assert config_from_dict(_exact_config(baseline)) == baseline


def test_threshold_metrics_obey_rounding_and_inclusive_boundaries():
    metrics = _binned_metrics(
        [[Decimal("39.99")], [Decimal("40.00")], [Decimal("70.00")]],
        Decimal(40), Decimal(70),
    )
    assert metrics["macroF1"] == 1
    assert metrics["confusionMatrix"]["rowsActualColumnsPredicted"] == [
        [1, 0, 0], [0, 1, 0], [0, 0, 1],
    ]


def test_training_does_not_access_or_depend_on_holdout(tmp_path):
    first = fit(tmp_path)
    # Deliberately invalid file must have no effect on train or its complete artifact/hash.
    (tmp_path / "holdout.json").write_text('{"broken": true}', encoding="utf-8")
    second = fit(tmp_path)
    assert first == second


@pytest.mark.parametrize("field", ["case_id", "content_hash", "canonical_url"])
def test_tuning_overlap_rejected_despite_different_issue_groups(tmp_path, field):
    training = controls("train")
    validation = controls("validation")
    validation = (replace(validation[0], **{field: getattr(training[0], field)}), *validation[1:])
    with pytest.raises(ValueError, match="overlap"):
        train(partition(tmp_path, "train", training),
              partition(tmp_path, "validation", validation), BASELINE)


@pytest.mark.parametrize("field", ["case_id", "content_hash", "canonical_url"])
def test_holdout_overlap_with_validation_is_rejected(tmp_path, field):
    model = model_file(tmp_path)
    validation = controls("validation")
    holdout = controls("holdout")
    holdout = (replace(holdout[0], **{field: getattr(validation[0], field)}), *holdout[1:])
    with pytest.raises(ValueError, match="overlaps tuning"):
        evaluate_holdout(model, partition(tmp_path, "holdout", holdout))


def test_changed_model_and_false_partition_role_rejected(tmp_path):
    model = model_file(tmp_path)
    payload = json.loads(model.read_text())
    payload["selectedConfig"]["highThreshold"] = "99"
    model.write_text(json.dumps(payload))
    with pytest.raises(ValueError, match="modified model"):
        evaluate_holdout(model, partition(tmp_path, "holdout"))
    with pytest.raises(ValueError, match="frozen group hash split"):
        load_partition(partition(tmp_path, "holdout", controls("train")), "holdout")


def test_missing_axis_and_equal_coobservations_cannot_train_weights(tmp_path):
    missing = tuple(replace(case, axes={**case.axes, "dealSignal": None})
                    for case in controls("train"))
    with pytest.raises(ValueError, match="dealSignal"):
        train(partition(tmp_path, "train", missing), partition(tmp_path, "validation"), BASELINE)
    equal = tuple(replace(case, axes={axis: i % 4 for axis in AXES})
                  for i, case in enumerate(controls("train")))
    with pytest.raises(ValueError, match="connected"):
        _require_weight_information(equal)


def test_missing_class_is_not_silently_scored_as_zero(tmp_path):
    validation = tuple(case for case in controls("validation") if case.label != "high")
    with pytest.raises(ValueError, match="Validation needs all three"):
        train(partition(tmp_path, "train"),
              partition(tmp_path, "validation", validation), BASELINE)


def test_high_recall_guard_and_macro_f1_metrics():
    cases = controls("holdout")
    bad = SensitivityScoringConfig(*BASELINE.weights, Decimal(99), Decimal(100))
    metrics = evaluate_cases(cases, bad)
    assert metrics["highRecall"] < 0.1
    assert metrics["macroF1"] < 0.5
    assert metrics["highFalseNegatives"] > 0


def test_split_is_label_independent_and_keeps_issue_groups_together(tmp_path):
    features = tmp_path / "features.json"
    labels = tmp_path / "labels.csv"
    cases = (*controls("train"), *controls("validation"), *controls("holdout"))
    features.write_text(json.dumps({
        "version": "sensitivity-features.v1", "promptVersion": "test-v1",
        "cases": [{"caseId": case.case_id, "title": case.case_id,
                   "text": f"unique content {case.case_id}", "canonicalUrl": case.canonical_url,
                   "axes": case.axes} for case in cases],
    }))

    def write_labels(reverse=False):
        with labels.open("w", newline="") as target:
            writer = csv.writer(target)
            writer.writerow(["caseId", "groupId", "label", "reviewer", "rationale"])
            for case in cases:
                writer.writerow([case.case_id, case.group_id,
                                 "high" if reverse else case.label, "person", "reason"])

    write_labels()
    split_dataset(features, labels, tmp_path / "split-one")
    write_labels(reverse=True)
    split_dataset(features, labels, tmp_path / "split-two")
    for role in ("train", "validation", "holdout"):
        _, first = load_partition(tmp_path / "split-one" / f"{role}.json", role)
        _, second = load_partition(tmp_path / "split-two" / f"{role}.json", role)
        assert [case.case_id for case in first] == [case.case_id for case in second]
    with pytest.raises(ValueError, match="already exists"):
        split_dataset(features, labels, tmp_path / "split-one")


def test_cli_completes_train_evaluate_and_refuses_overwrite(tmp_path, capsys):
    training, validation = partition(tmp_path, "train"), partition(tmp_path, "validation")
    output = tmp_path / "selected.json"
    args = ["train", "--train", str(training), "--validation", str(validation),
            "--output", str(output), "--weight-step", "25", "--threshold-step", "10"]
    assert main(args) == 0
    assert json.loads(capsys.readouterr().out)["selection"] == "candidate"
    assert main(args) == 2
    assert "already exists" in capsys.readouterr().err
    report = tmp_path / "result.json"
    assert main(["evaluate", "--model", str(output), "--holdout",
                 str(partition(tmp_path, "holdout")), "--output", str(report)]) == 0
    assert json.loads(report.read_text())["result"] == "improved"


@pytest.mark.parametrize("size", [0, -1, 1001, True, 1.5])
def test_bad_search_limits_rejected_without_accessing_data(tmp_path, size):
    with pytest.raises(ValueError, match="shortlist"):
        train(tmp_path / "absent", tmp_path / "absent2", BASELINE, shortlist_size=size)
