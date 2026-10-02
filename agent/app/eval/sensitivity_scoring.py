"""Shared offline scoring with the backend's null handling and decimal rounding."""

from collections.abc import Mapping
from dataclasses import dataclass
from decimal import ROUND_HALF_UP, Decimal, InvalidOperation, localcontext
from pathlib import Path

import yaml

AXES = ("customerMove", "dealSignal", "competitorThreat", "industryShift")
_DEFAULT_SENSITIVITY_CONFIG = (
    Path(__file__).resolve().parents[3] / "BE" / "src" / "main" / "resources" / "application.yml"
)
_CONFIG_FIELDS = (
    ("customer_move_weight", "customerMoveWeight", "customer-move-weight"),
    ("deal_signal_weight", "dealSignalWeight", "deal-signal-weight"),
    ("competitor_threat_weight", "competitorThreatWeight", "competitor-threat-weight"),
    ("industry_shift_weight", "industryShiftWeight", "industry-shift-weight"),
    ("medium_threshold", "mediumThreshold", "medium-threshold"),
    ("high_threshold", "highThreshold", "high-threshold"),
)


@dataclass(frozen=True, slots=True)
class SensitivityScoringConfig:
    customer_move_weight: Decimal
    deal_signal_weight: Decimal
    competitor_threat_weight: Decimal
    industry_shift_weight: Decimal
    medium_threshold: Decimal
    high_threshold: Decimal

    def __post_init__(self) -> None:
        for field, _, _ in _CONFIG_FIELDS:
            value = getattr(self, field)
            if not isinstance(value, Decimal) or not value.is_finite():
                raise ValueError(f"민감도 설정 {field}는 유한한 Decimal이어야 합니다.")
        weights = self.weights
        if any(weight <= 0 or weight > 1 for weight in weights):
            raise ValueError("민감도 축 가중치는 양수이고 합은 1이어야 합니다.")
        with localcontext() as context:
            context.prec = _weight_precision(weights)
            if sum(weights, start=Decimal("0")) != Decimal("1"):
                raise ValueError("민감도 축 가중치는 양수이고 합은 1이어야 합니다.")
        if not Decimal("0") <= self.medium_threshold < self.high_threshold <= Decimal("100"):
            raise ValueError("민감도 임계값은 0 <= medium < high <= 100이어야 합니다.")

    @property
    def weights(self) -> tuple[Decimal, Decimal, Decimal, Decimal]:
        return (
            self.customer_move_weight,
            self.deal_signal_weight,
            self.competitor_threat_weight,
            self.industry_shift_weight,
        )

    def to_dict(self) -> dict[str, float]:
        return {key: float(getattr(self, field)) for field, key, _ in _CONFIG_FIELDS}


def _weight_precision(weights: tuple[Decimal, ...]) -> int:
    # Preserve all input digits before the backend-compatible final rounding.
    return max(28, 12 - min(weight.as_tuple().exponent for weight in weights))


def config_from_dict(data: Mapping[str, object]) -> SensitivityScoringConfig:
    """Read the exact camelCase configuration emitted in evaluation reports."""
    if not isinstance(data, Mapping) or set(data) != {key for _, key, _ in _CONFIG_FIELDS}:
        raise ValueError("민감도 설정에는 가중치 4개와 임계값 2개만 모두 필요합니다.")
    values = {}
    for field, key, _ in _CONFIG_FIELDS:
        value = data[key]
        if isinstance(value, bool) or not isinstance(value, (int, float, str, Decimal)):
            raise ValueError(f"민감도 설정 {key}는 유한한 숫자여야 합니다.")
        try:
            values[field] = Decimal(str(value))
        except InvalidOperation as exc:
            raise ValueError(f"민감도 설정 {key}는 유한한 숫자여야 합니다.") from exc
    return SensitivityScoringConfig(**values)


def load_sensitivity_scoring_config(
    path: Path = _DEFAULT_SENSITIVITY_CONFIG,
) -> SensitivityScoringConfig:
    """Read public application.yml configuration without loading environment files."""
    path = Path(path).resolve()
    if path.name != "application.yml":
        raise ValueError("민감도 기본 설정은 비밀 파일이 아닌 application.yml에서 읽어야 합니다.")
    document = yaml.safe_load(path.read_text(encoding="utf-8"))
    try:
        values = document["news"]["analysis"]["sensitivity"]
        payload = {key: values[yaml_key] for _, key, yaml_key in _CONFIG_FIELDS}
    except (KeyError, TypeError) as exc:
        raise ValueError(
            "application.yml의 news.analysis.sensitivity 설정이 올바르지 않습니다."
        ) from exc
    return config_from_dict(payload)


def score_axes(axes: Mapping[str, int | None], config: SensitivityScoringConfig) -> Decimal:
    """Renormalize available axes; zero is evidence of absence, not unavailable."""
    if not isinstance(axes, Mapping) or set(axes) != set(AXES):
        raise ValueError("민감도 점수에는 네 축이 정확히 한 번씩 필요합니다.")
    for name, value in axes.items():
        if value is not None and (type(value) is not int or not 0 <= value <= 3):
            raise ValueError(f"민감도 축 {name} 점수는 0~3 정수 또는 null이어야 합니다.")
    available = [
        (Decimal(axes[name]), weight)
        for name, weight in zip(AXES, config.weights, strict=True)
        if axes[name] is not None
    ]
    if not available:
        raise ValueError("민감도 축은 하나 이상 판정 가능해야 합니다.")
    with localcontext() as context:
        context.prec = _weight_precision(config.weights)
        weighted = sum((score * weight for score, weight in available), start=Decimal("0"))
        available_weight = sum((weight for _, weight in available), start=Decimal("0"))
        return (weighted * Decimal("100") / (available_weight * Decimal("3"))).quantize(
            Decimal("0.01"), rounding=ROUND_HALF_UP
        )


def level_for_score(score: Decimal, config: SensitivityScoringConfig) -> str:
    if not isinstance(score, Decimal) or not score.is_finite() or not 0 <= score <= 100:
        raise ValueError("민감도 점수는 0~100 범위의 유한한 Decimal이어야 합니다.")
    if score >= config.high_threshold:
        return "high"
    if score >= config.medium_threshold:
        return "medium"
    return "low"
