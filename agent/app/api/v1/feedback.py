from typing import Annotated

from fastapi import APIRouter, Depends

from app.core.config import Settings, get_settings
from app.llm.feedback_service import FeedbackService
from app.schemas.feedback import (
    FeedbackEvaluateRequest,
    FeedbackEvaluateResponse,
    FeedbackReviewInput,
    FeedbackReviewResponse,
)

router = APIRouter(tags=["feedback"])


@router.post("/feedback/review", response_model=FeedbackReviewResponse)
def review_feedback(
    request: FeedbackReviewInput,
    settings: Annotated[Settings, Depends(get_settings)],
) -> FeedbackReviewResponse:
    return FeedbackService(settings).review(request)


@router.post("/feedback/evaluate", response_model=FeedbackEvaluateResponse)
def evaluate_feedback(
    request: FeedbackEvaluateRequest,
    settings: Annotated[Settings, Depends(get_settings)],
) -> FeedbackEvaluateResponse:
    return FeedbackService(settings).evaluate(request)
