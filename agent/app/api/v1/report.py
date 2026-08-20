from typing import Annotated

from fastapi import APIRouter, Depends

from app.core.config import Settings, get_settings
from app.llm.report_service import ReportWriterService
from app.schemas.report import ReportRequest, ReportResponse

router = APIRouter(tags=["reports"])


@router.post("/report", response_model=ReportResponse)
def report(
    request: ReportRequest,
    settings: Annotated[Settings, Depends(get_settings)],
) -> ReportResponse:
    return ReportWriterService(settings).write(request)
