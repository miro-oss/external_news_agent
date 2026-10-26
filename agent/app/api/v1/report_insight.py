from typing import Annotated

from fastapi import APIRouter, Depends

from app.core.config import Settings, get_settings
from app.llm.report_insight_service import ReportInsightService
from app.schemas.report_insight import ReportInsightRequest, ReportInsightResponse

router = APIRouter(tags=["report-insights"])


@router.post("/report-insight", response_model=ReportInsightResponse)
def report_insight(
    request: ReportInsightRequest,
    settings: Annotated[Settings, Depends(get_settings)],
) -> ReportInsightResponse:
    return ReportInsightService(settings).generate(request)
