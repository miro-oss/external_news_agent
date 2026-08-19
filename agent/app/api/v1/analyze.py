from typing import Annotated

from fastapi import APIRouter, Depends

from app.core.config import Settings, get_settings
from app.llm.analyze_service import ArticleAnalyzeService
from app.llm.mock_provider import MockAnalyzeProvider
from app.schemas.analyze import AnalyzeRequest, AnalyzeResponse

router = APIRouter(tags=["analysis"])


@router.post("/analyze", response_model=AnalyzeResponse)
def analyze(
    request: AnalyzeRequest,
    settings: Annotated[Settings, Depends(get_settings)],
) -> AnalyzeResponse:
    input_truncated = len(request.article.body_text) > settings.max_body_chars
    if input_truncated:
        request = request.model_copy(
            update={
                "article": request.article.model_copy(
                    update={"body_text": request.article.body_text[: settings.max_body_chars]}
                )
            }
        )

    if settings.mock:
        return MockAnalyzeProvider(settings).analyze(request, input_truncated=input_truncated)
    return ArticleAnalyzeService(settings).analyze(
        request,
        input_truncated=input_truncated,
    )
