from typing import Annotated

from fastapi import APIRouter, Depends

from app.core.config import Settings, get_settings
from app.llm.explore_service import ExploreService
from app.schemas.explore import ExploreRequest, ExploreResponse

router = APIRouter(tags=["investigation"])


@router.post("/explore", response_model=ExploreResponse)
def explore(
    request: ExploreRequest,
    settings: Annotated[Settings, Depends(get_settings)],
) -> ExploreResponse:
    return ExploreService(settings).propose(request)
