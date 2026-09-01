from typing import Annotated

from fastapi import APIRouter, Depends

from app.core.config import Settings, get_settings
from app.llm.analyze_service import ArticleAnalyzeService
from app.llm.mock_provider import MockAnalyzeProvider
from app.llm.self_critique_service import ArticleSelfCritiqueService
from app.schemas.analyze import AnalyzeRequest, AnalyzeResponse, SelfCritiqueResponse

router = APIRouter(tags=["analysis"])


@router.post("/analyze", response_model=AnalyzeResponse | SelfCritiqueResponse)
def analyze(
    request: AnalyzeRequest,
    settings: Annotated[Settings, Depends(get_settings)],
) -> AnalyzeResponse | SelfCritiqueResponse:
    input_truncated = len(request.article.body_text) > settings.max_body_chars
    if input_truncated:
        request = request.model_copy(
            update={
                "article": request.article.model_copy(
                    update={"body_text": request.article.body_text[: settings.max_body_chars]}
                )
            }
        )

    if request.self_critique:
        return ArticleSelfCritiqueService(settings).critique(
            request,
            input_truncated=input_truncated,
        )
    if settings.mock:
        return MockAnalyzeProvider(settings).analyze(request, input_truncated=input_truncated)
    return ArticleAnalyzeService(settings).analyze(
        request,
        input_truncated=input_truncated,
    )
