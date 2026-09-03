import logging
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.api.v1.analyze import router as analyze_router
from app.api.v1.evidence import router as evidence_router
from app.api.v1.explore import router as explore_router
from app.api.v1.health import router as health_router
from app.api.v1.insight import router as insight_router
from app.api.v1.keyword_strategy import router as keyword_strategy_router
from app.api.v1.report import router as report_router
from app.core.errors import AgentError
from app.core.security import require_agent_token
from app.llm.router import close_analyze_providers
from app.schemas.common import ErrorDetail, ErrorResponse

logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(_: FastAPI):
    yield
    close_analyze_providers()


def create_app() -> FastAPI:
    application = FastAPI(
        title="External News Agent",
        version="0.1.0",
        lifespan=lifespan,
    )
    application.include_router(health_router, prefix="/v1")
    application.include_router(
        analyze_router,
        prefix="/v1",
        dependencies=[Depends(require_agent_token)],
    )
    application.include_router(
        evidence_router,
        prefix="/v1",
        dependencies=[Depends(require_agent_token)],
    )
    application.include_router(
        explore_router,
        prefix="/v1",
        dependencies=[Depends(require_agent_token)],
    )
    application.include_router(
        insight_router,
        prefix="/v1",
        dependencies=[Depends(require_agent_token)],
    )
    application.include_router(
        keyword_strategy_router,
        prefix="/v1",
        dependencies=[Depends(require_agent_token)],
    )
    application.include_router(
        report_router,
        prefix="/v1",
        dependencies=[Depends(require_agent_token)],
    )

    @application.exception_handler(AgentError)
    async def handle_agent_error(_: Request, exc: AgentError) -> JSONResponse:
        return _error_response(exc.status_code, exc.code, exc.message, exc.details)

    @application.exception_handler(RequestValidationError)
    async def handle_validation_error(_: Request, exc: RequestValidationError) -> JSONResponse:
        return _error_response(
            422,
            "SCHEMA_VIOLATION",
            "요청 스키마가 올바르지 않습니다.",
            _validation_details(exc),
        )

    @application.exception_handler(Exception)
    async def handle_unexpected_error(_: Request, exc: Exception) -> JSONResponse:
        logger.exception("처리되지 않은 Agent 오류가 발생했습니다.", exc_info=exc)
        return _error_response(
            500,
            "INTERNAL_ERROR",
            "Agent 내부 오류가 발생했습니다.",
        )

    return application


def _error_response(
    status: int,
    code: str,
    message: str,
    details: object | None = None,
) -> JSONResponse:
    body = ErrorResponse(error=ErrorDetail(code=code, message=message, details=details))
    return JSONResponse(status_code=status, content=body.model_dump(by_alias=True, mode="json"))


def _validation_details(exc: RequestValidationError) -> list[dict[str, object]]:
    return [
        {
            "loc": list(error.get("loc", ())),
            "msg": error.get("msg", ""),
            "type": error.get("type", ""),
        }
        for error in exc.errors()
    ]


app = create_app()
