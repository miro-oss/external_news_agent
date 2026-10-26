"""Shared report importance calculation for public grades and review ordering."""

from app.schemas.report_insight import ReportImportanceAxes


def score_importance(axes: ReportImportanceAxes) -> float | None:
    """Known unrelated evidence is low even when its effect cannot be assessed."""
    if axes.directness == 0:
        return 0.0
    if axes.directness is None or axes.impact is None:
        return None
    weighted = axes.directness * 0.4 + axes.impact * 0.4
    return weighted / 0.8 if axes.urgency is None else weighted + axes.urgency * 0.2
