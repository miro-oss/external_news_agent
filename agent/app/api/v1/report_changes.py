from typing import Annotated

from fastapi import APIRouter, Depends

from app.core.config import Settings, get_settings
from app.llm.report_changes_service import ReportChangesService
from app.schemas.report_changes import ReportChangesRequest, ReportChangesResponse

router = APIRouter(tags=["reports"])


@router.post("/report-changes", response_model=ReportChangesResponse)
def report_changes(
    request: ReportChangesRequest,
    settings: Annotated[Settings, Depends(get_settings)],
) -> ReportChangesResponse:
    return ReportChangesService(settings).compare(request)
