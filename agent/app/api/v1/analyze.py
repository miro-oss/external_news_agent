from typing import Annotated

from fastapi import APIRouter, Depends

from app.core.config import Settings, get_settings
from app.core.errors import AgentError
from app.llm.mock_provider import MockAnalyzeProvider
from app.schemas.analyze import AnalyzeRequest, AnalyzeResponse

router = APIRouter(tags=["analysis"])


@router.post("/analyze", response_model=AnalyzeResponse)
def analyze(
    request: AnalyzeRequest,
    settings: Annotated[Settings, Depends(get_settings)],
) -> AnalyzeResponse:
    if not settings.mock:
        raise AgentError(
            status_code=503,
            code="API_KEY_MISSING",
            message="A0에서는 AGENT_MOCK=1 모드만 지원합니다.",
        )

    input_truncated = len(request.article.body_text) > settings.max_body_chars
    if input_truncated:
        request = request.model_copy(
            update={
                "article": request.article.model_copy(
                    update={"body_text": request.article.body_text[: settings.max_body_chars]}
                )
            }
        )

    return MockAnalyzeProvider(settings).analyze(request, input_truncated=input_truncated)
