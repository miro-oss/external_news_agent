import logging
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.api.v1.analyze import router as analyze_router
from app.api.v1.evidence import router as evidence_router
from app.api.v1.explore import router as explore_router
from app.api.v1.feedback import router as feedback_router
from app.api.v1.health import router as health_router
from app.api.v1.insight import router as insight_router
from app.api.v1.keyword_strategy import router as keyword_strategy_router
from app.api.v1.report import router as report_router
from app.api.v1.report_changes import router as report_changes_router
from app.api.v1.report_insight import router as report_insight_router
from app.api.v1.topic_relevance import router as topic_relevance_router
from app.api.v1.weekly_report import router as weekly_report_router
from app.core.config import Settings, get_settings
from app.core.errors import AgentError
from app.core.security import AgentRequestGuardMiddleware, require_agent_token
from app.llm.router import close_analyze_providers
from app.schemas.common import ErrorDetail, ErrorResponse

logger = logging.getLogger(__name__)

_UNAVAILABLE_CODES = frozenset({"PROVIDER_UNAVAILABLE", "API_KEY_MISSING"})
_UNAVAILABLE_FLAGS = (
    "concurrencyLimited",
    "circuitOpen",
    "requestDeadlineExceeded",
    "pipelineCancelled",
    "rateLimited",
    "requestNotStarted",
)


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
    protected_routers = (
        analyze_router,
        evidence_router,
        explore_router,
        feedback_router,
        insight_router,
        keyword_strategy_router,
        topic_relevance_router,
        report_router,
        report_changes_router,
        report_insight_router,
        weekly_report_router,
    )
    for router in protected_routers:
        application.include_router(
            router, prefix="/v1", dependencies=[Depends(require_agent_token)]
        )

    def request_settings() -> Settings:
        # Keep middleware and route dependency settings identical, including
        # explicitly isolated settings supplied by tests/embedded applications.
        return application.dependency_overrides.get(get_settings, get_settings)()

    protected_paths = frozenset(
        f"/v1{route.path}" for router in protected_routers for route in router.routes
    )
    application.add_middleware(
        AgentRequestGuardMiddleware,
        protected_paths=protected_paths,
        settings_provider=request_settings,
    )

    @application.exception_handler(AgentError)
    async def handle_agent_error(request: Request, exc: AgentError) -> JSONResponse:
        if exc.status_code == 503:
            _log_unavailable(request, exc, protected_paths)
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


def _log_unavailable(request: Request, error: AgentError, paths: frozenset[str]) -> None:
    # Route templates are application-owned; raw paths, headers, body and error
    # prose can contain private data. There is no shared safe request ID context.
    path = getattr(request.scope.get("route"), "path", None)
    # Included routers may retain the unprefixed template in the request scope.
    candidate = path if isinstance(path, str) and path.startswith("/v1/") else f"/v1{path}"
    endpoint = candidate if candidate in paths else "UNKNOWN"
    code = error.code if error.code in _UNAVAILABLE_CODES else "UNKNOWN"
    details = error.details if type(error.details) is dict else {}
    flags = {key: details[key] for key in _UNAVAILABLE_FLAGS if type(details.get(key)) is bool}
    logger.warning(
        "Agent 요청 실패. endpoint=%s status=503 code=%s reasons=%s",
        endpoint,
        code,
        flags,
        extra={
            "agent_endpoint": endpoint,
            "agent_status": 503,
            "agent_error_code": code,
            "agent_failure_reasons": flags,
        },
    )


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
