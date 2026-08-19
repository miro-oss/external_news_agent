from typing import Annotated

from fastapi import APIRouter, Depends

from app.core.config import Settings, get_settings
from app.schemas.common import HealthResponse

router = APIRouter(tags=["health"])


@router.get("/health", response_model=HealthResponse)
def health(settings: Annotated[Settings, Depends(get_settings)]) -> HealthResponse:
    return HealthResponse(status="ok", mock=settings.mock)
