from typing import Annotated

from fastapi import APIRouter, Depends

from app.core.config import Settings, get_settings
from app.llm.insight_service import InsightService
from app.schemas.insight import InsightRequest, InsightResponse

router = APIRouter(tags=["insights"])


@router.post("/insight", response_model=InsightResponse)
def insight(
    request: InsightRequest,
    settings: Annotated[Settings, Depends(get_settings)],
) -> InsightResponse:
    return InsightService(settings).generate(request)
