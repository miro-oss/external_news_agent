from dataclasses import replace
from decimal import Decimal
from pathlib import Path

import pytest

from app.eval.sensitivity_scoring import (
    AXES,
    SensitivityScoringConfig,
    config_from_dict,
    level_for_score,
    load_sensitivity_scoring_config,
    score_axes,
)


@pytest.fixture
def config() -> SensitivityScoringConfig:
    return SensitivityScoringConfig(
        customer_move_weight=Decimal("0.35"),
        deal_signal_weight=Decimal("0.30"),
        competitor_threat_weight=Decimal("0.20"),
        industry_shift_weight=Decimal("0.15"),
        medium_threshold=Decimal("40"),
        high_threshold=Decimal("70"),
    )


@pytest.mark.parametrize(
    ("scores", "expected", "level"),
    [
        ((3, None, 2, 1), "76.19", "high"),
        ((0, 0, 0, 0), "0.00", "low"),
        ((3, 3, 3, 3), "100.00", "high"),
        ((1, None, None, None), "33.33", "low"),
        ((2, None, None, None), "66.67", "medium"),
        ((3, 0, 2, 1), "53.33", "medium"),
    ],
)
def test_backend_compatible_scores(
    config: SensitivityScoringConfig,
    scores: tuple[int | None, ...],
    expected: str,
    level: str,
) -> None:
    score = score_axes(dict(zip(AXES, scores, strict=True)), config)

    assert str(score) == expected
    assert level_for_score(score, config) == level


def test_half_up_rounding_precedes_threshold_comparison(config: SensitivityScoringConfig) -> None:
    config = replace(
        config,
        customer_move_weight=Decimal("0.40005"),
        deal_signal_weight=Decimal("0.19995"),
        competitor_threat_weight=Decimal("0.20"),
        industry_shift_weight=Decimal("0.20"),
        medium_threshold=Decimal("40.01"),
    )

    score = score_axes(dict(zip(AXES, (3, 0, 0, 0), strict=True)), config)

    assert score == Decimal("40.01")
    assert level_for_score(score, config) == "medium"


@pytest.mark.parametrize(
    ("score", "level"),
    [
        ("0", "low"),
        ("39.99", "low"),
        ("40", "medium"),
        ("69.99", "medium"),
        ("70", "high"),
        ("100", "high"),
    ],
)
def test_thresholds_are_inclusive(config: SensitivityScoringConfig, score: str, level: str) -> None:
    assert level_for_score(Decimal(score), config) == level


def test_rejects_all_unavailable_axes(config: SensitivityScoringConfig) -> None:
    with pytest.raises(ValueError, match="하나 이상"):
        score_axes(dict.fromkeys(AXES), config)


@pytest.mark.parametrize("value", [-1, 4, True, False, "1", 1.0, Decimal("1"), [], {}])
def test_rejects_invalid_axis_values(config: SensitivityScoringConfig, value: object) -> None:
    axes = dict.fromkeys(AXES, 0)
    axes["dealSignal"] = value
    with pytest.raises(ValueError, match="0~3"):
        score_axes(axes, config)


@pytest.mark.parametrize("axes", [{}, {"customerMove": 1}, dict.fromkeys((*AXES, "unknown"), 1)])
def test_rejects_missing_and_unknown_axes(config: SensitivityScoringConfig, axes: dict) -> None:
    with pytest.raises(ValueError, match="네 축"):
        score_axes(axes, config)


@pytest.mark.parametrize(
    "field",
    [
        "customer_move_weight",
        "deal_signal_weight",
        "competitor_threat_weight",
        "industry_shift_weight",
        "medium_threshold",
        "high_threshold",
    ],
)
@pytest.mark.parametrize(
    "value",
    [Decimal("NaN"), Decimal("sNaN"), Decimal("Infinity"), Decimal("-Infinity"), 0.25, True],
)
def test_config_rejects_nonfinite_and_nondecimal_values(
    config: SensitivityScoringConfig, field: str, value: object
) -> None:
    with pytest.raises(ValueError, match="유한한 Decimal"):
        replace(config, **{field: value})


@pytest.mark.parametrize("value", ["0", "-0.1", "0.36", "1.1"])
def test_config_requires_positive_weights_that_sum_to_one(
    config: SensitivityScoringConfig, value: str
) -> None:
    with pytest.raises(ValueError, match="가중치"):
        replace(config, customer_move_weight=Decimal(value))


def test_config_does_not_round_an_invalid_weight_sum_to_one(
    config: SensitivityScoringConfig,
) -> None:
    with pytest.raises(ValueError, match="가중치"):
        replace(config, customer_move_weight=Decimal("0.35000000000000000000000000000001"))


@pytest.mark.parametrize(
    ("medium", "high"), [("-1", "70"), ("70", "70"), ("71", "70"), ("40", "101")]
)
def test_config_rejects_invalid_thresholds(
    config: SensitivityScoringConfig, medium: str, high: str
) -> None:
    with pytest.raises(ValueError, match="임계값"):
        replace(config, medium_threshold=Decimal(medium), high_threshold=Decimal(high))


def test_existing_report_config_round_trips(config: SensitivityScoringConfig) -> None:
    assert config_from_dict(config.to_dict()) == config
    assert config_from_dict({key: str(value) for key, value in config.to_dict().items()}) == config


@pytest.mark.parametrize("value", [None, True, [], {}, "not-a-number", "NaN", float("inf")])
def test_report_config_rejects_invalid_numbers(
    config: SensitivityScoringConfig, value: object
) -> None:
    payload = config.to_dict()
    payload["customerMoveWeight"] = value
    with pytest.raises(ValueError):
        config_from_dict(payload)


def test_report_config_rejects_missing_and_extra_fields(config: SensitivityScoringConfig) -> None:
    payload = config.to_dict()
    with pytest.raises(ValueError, match="가중치 4개"):
        config_from_dict({**payload, "unexpected": 1})
    payload.pop("mediumThreshold")
    with pytest.raises(ValueError, match="가중치 4개"):
        config_from_dict(payload)


@pytest.mark.parametrize(
    "score", [Decimal("NaN"), Decimal("Infinity"), Decimal("-0.01"), Decimal("100.01"), 50]
)
def test_level_rejects_invalid_score(config: SensitivityScoringConfig, score: object) -> None:
    with pytest.raises(ValueError, match="0~100"):
        level_for_score(score, config)


def test_loads_public_backend_configuration(config: SensitivityScoringConfig) -> None:
    assert load_sensitivity_scoring_config() == config


def test_loader_rejects_other_file_names_before_reading(monkeypatch: pytest.MonkeyPatch) -> None:
    def forbid_read(*args, **kwargs):
        raise AssertionError("A non-public configuration file must not be read")

    monkeypatch.setattr(Path, "read_text", forbid_read)
    with pytest.raises(ValueError, match="application.yml"):
        load_sensitivity_scoring_config(Path("application-secret.yml"))


def test_loader_rejects_incomplete_public_config(tmp_path: Path) -> None:
    path = tmp_path / "application.yml"
    path.write_text("news:\n  analysis: {}\n", encoding="utf-8")
    with pytest.raises(ValueError, match="news.analysis.sensitivity"):
        load_sensitivity_scoring_config(path)
