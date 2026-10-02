"""Keep public importance and review priority consistent for every allowed axis."""

import pytest

from app.core.report_importance import score_importance
from app.llm.report_insight_assessment import _public_priority
from app.llm.report_insight_service import importance_grade, importance_score
from app.schemas.report_insight import ReportImportanceAxes, ReportInsightAssessment


@pytest.mark.parametrize("directness", [None, 0, 1, 2, 3])
@pytest.mark.parametrize("impact", [None, 0, 1, 2, 3])
@pytest.mark.parametrize("urgency", [None, 0, 1, 2, 3])
def test_all_axis_combinations_keep_public_grade_and_review_priority_consistent(
    directness, impact, urgency
):
    axes = ReportImportanceAxes(directness=directness, impact=impact, urgency=urgency, novelty=None)
    item = ReportInsightAssessment(
        findingId=101,
        reason="원문에 근거한 관점 업무 연결을 평가했다.",
        basisClaimIds=["101:0"],
        axes=axes,
    )
    before = axes.model_dump()
    if directness == 0:
        expected = 0.0
    elif directness is None or impact is None:
        expected = None
    elif urgency is None:
        expected = (directness + impact) / 2
    else:
        expected = (2 * directness + 2 * impact + urgency) / 5

    scores = (score_importance(axes), importance_score(axes), _public_priority(item))
    if expected is None:
        assert scores == (None, None, None)
        assert importance_grade(axes) == "unavailable"
    else:
        assert scores == pytest.approx((expected, expected, expected))
        grade = "high" if expected >= 2.25 else "medium" if expected >= 1.25 else "low"
        assert importance_grade(axes) == grade
    assert axes.model_dump() == before
