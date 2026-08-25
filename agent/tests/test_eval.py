import json
from pathlib import Path

import pytest

from app.eval.dataset import GoldenDataset, dump_dataset, load_dataset
from app.eval.runner import run_evaluation
from app.eval.scorer import compare_metrics, korean_summary_pass

_GOLDEN_DIR = Path(__file__).resolve().parents[1] / "app" / "eval" / "golden"
_DATASET_PATH = _GOLDEN_DIR / "semiconductor.v1.json"
_BASELINE_PATH = _GOLDEN_DIR / "analyze.ko.v1.baseline.json"


def test_replay_golden_eval_matches_v1_baseline() -> None:
    result = run_evaluation(load_dataset(_DATASET_PATH))
    baseline = json.loads(_BASELINE_PATH.read_text(encoding="utf-8"))

    assert result.errors == ()
    assert result.metrics == baseline["metrics"]
    assert compare_metrics(result.metrics, baseline["metrics"])["regressions"] == []


def test_schema_violation_is_counted_without_stopping_remaining_cases() -> None:
    payload = json.loads(dump_dataset(load_dataset(_DATASET_PATH)))
    payload["cases"][0]["replay"]["sections"][0]["bullets"][0]["evidenceSentenceIds"] = [999]
    dataset = GoldenDataset.model_validate(payload)

    result = run_evaluation(dataset)

    assert result.metrics["schemaPasses"] == 24
    assert result.metrics["schemaPassRate"] == 0.96
    assert [error.to_dict() for error in result.errors] == [
        {"check": "hbm4-pilot-ko", "code": "SCHEMA_VIOLATION"}
    ]


@pytest.mark.parametrize(
    ("summary", "expected"),
    [
        ("HBM4 양산 일정이 앞당겨졌다.", True),
        ("The HBM4 schedule moved forward.", False),
        ("HBM4", False),
    ],
)
def test_korean_summary_gate(summary: str, expected: bool) -> None:
    assert korean_summary_pass(summary) is expected


def test_metric_comparison_uses_quality_direction() -> None:
    baseline = {
        "schemaPassRate": 1.0,
        "groundedRate": 0.9,
        "koreanSummaryPassRate": 1.0,
        "unsupportedReportClaimCount": 0,
    }
    current = {
        "schemaPassRate": 0.9,
        "groundedRate": 0.95,
        "koreanSummaryPassRate": 1.0,
        "unsupportedReportClaimCount": 1,
    }

    comparison = compare_metrics(current, baseline)

    assert comparison["regressions"] == [
        "schemaPassRate",
        "unsupportedReportClaimCount",
    ]


def test_dataset_requires_twenty_to_thirty_unique_articles() -> None:
    payload = json.loads(dump_dataset(load_dataset(_DATASET_PATH)))
    payload["cases"] = payload["cases"][:19]

    with pytest.raises(ValueError):
        GoldenDataset.model_validate(payload)
