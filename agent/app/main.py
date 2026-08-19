from fastapi import Depends, FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.api.v1.analyze import router as analyze_router
from app.api.v1.health import router as health_router
from app.core.errors import AgentError
from app.core.security import require_agent_token
from app.schemas.common import ErrorDetail, ErrorResponse


def create_app() -> FastAPI:
    application = FastAPI(title="External News Agent", version="0.1.0")
    application.include_router(health_router, prefix="/v1")
    application.include_router(
        analyze_router,
        prefix="/v1",
        dependencies=[Depends(require_agent_token)],
    )

    @application.exception_handler(AgentError)
    async def handle_agent_error(_: Request, exc: AgentError) -> JSONResponse:
        return _error_response(exc.status_code, exc.code, exc.message)

    @application.exception_handler(RequestValidationError)
    async def handle_validation_error(_: Request, exc: RequestValidationError) -> JSONResponse:
        return _error_response(
            422,
            "SCHEMA_VIOLATION",
            "요청 스키마가 올바르지 않습니다.",
            exc.errors(),
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


app = create_app()
