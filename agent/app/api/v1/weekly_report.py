from typing import Annotated

from fastapi import APIRouter, Depends

from app.core.config import Settings, get_settings
from app.llm.weekly_report_service import WeeklyReportWriterService
from app.schemas.report import ReportResponse
from app.schemas.weekly_report import WeeklyReportRequest

router = APIRouter(tags=["reports"])


@router.post("/weekly-report", response_model=ReportResponse)
def weekly_report(
    request: WeeklyReportRequest,
    settings: Annotated[Settings, Depends(get_settings)],
) -> ReportResponse:
    return WeeklyReportWriterService(settings).write(request)
