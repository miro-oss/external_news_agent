from typing import Annotated

from fastapi import APIRouter, Depends

from app.core.config import Settings, get_settings
from app.llm.keyword_strategy_service import KeywordStrategyService
from app.schemas.keyword_strategy import KeywordStrategyRequest, KeywordStrategyResponse

router = APIRouter(tags=["keyword-strategy"])


@router.post("/keyword-strategy", response_model=KeywordStrategyResponse)
def keyword_strategy(
    request: KeywordStrategyRequest,
    settings: Annotated[Settings, Depends(get_settings)],
) -> KeywordStrategyResponse:
    return KeywordStrategyService(settings).propose(request)
