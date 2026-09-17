from typing import Annotated

from fastapi import APIRouter, Depends

from app.core.config import Settings, get_settings
from app.llm.topic_relevance_service import TopicRelevanceService
from app.schemas.topic_relevance import TopicRelevanceRequest, TopicRelevanceResponse

router = APIRouter(tags=["topic-relevance"])


@router.post("/topic-relevance", response_model=TopicRelevanceResponse)
def topic_relevance(
    request: TopicRelevanceRequest,
    settings: Annotated[Settings, Depends(get_settings)],
) -> TopicRelevanceResponse:
    return TopicRelevanceService(settings).classify(request)
