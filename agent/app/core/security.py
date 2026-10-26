import hmac
from collections.abc import Callable
from typing import Annotated

from fastapi import Depends
from fastapi.responses import JSONResponse
from fastapi.security import APIKeyHeader
from starlette.datastructures import Headers
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.core.config import Settings, get_settings
from app.core.errors import AgentError
from app.schemas.common import ErrorDetail, ErrorResponse

_agent_token = APIKeyHeader(name="X-Agent-Token", auto_error=False)


def require_agent_token(
    supplied_token: Annotated[str | None, Depends(_agent_token)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> None:
    _validate_agent_token(supplied_token, settings)


def _validate_agent_token(supplied_token: str | None, settings: Settings) -> None:
    if (
        not settings.shared_secret
        or supplied_token is None
        or not hmac.compare_digest(
            supplied_token.encode("utf-8"), settings.shared_secret.encode("utf-8")
        )
    ):
        raise AgentError(
            status_code=401,
            code="UNAUTHORIZED",
            message="Agent token이 올바르지 않습니다.",
        )


class AgentRequestGuardMiddleware:
    """Authenticate and bound protected request bodies before FastAPI parses JSON."""

    def __init__(
        self,
        app: ASGIApp,
        *,
        protected_paths: frozenset[str],
        settings_provider: Callable[[], Settings],
    ) -> None:
        self.app = app
        self.protected_paths = protected_paths
        self.settings_provider = settings_provider

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if (
            scope["type"] != "http"
            or scope["path"].rstrip("/") not in self.protected_paths
        ):
            await self.app(scope, receive, send)
            return

        settings = self.settings_provider()
        headers = Headers(scope=scope)
        try:
            _validate_agent_token(headers.get("x-agent-token"), settings)
        except AgentError as error:
            await self._reject(scope, receive, send, error)
            return

        limit = settings.max_request_body_bytes
        declared_size = headers.get("content-length", "").lstrip("0") or "0"
        if declared_size.isascii() and declared_size.isdecimal() and (
            len(declared_size) > len(str(limit))
            or (len(declared_size) == len(str(limit)) and declared_size > str(limit))
        ):
            await self._reject_too_large(scope, receive, send)
            return

        # Buffer only up to the cap. Raising inside FastAPI's receive wrapper is
        # too late: its body parser can turn that exception into a generic 400.
        buffer = bytearray()
        while True:
            message = await receive()
            if message["type"] == "http.disconnect":
                return
            chunk = message.get("body", b"")
            if len(buffer) + len(chunk) > limit:
                await self._reject_too_large(scope, receive, send)
                return
            buffer.extend(chunk)
            if not message.get("more_body", False):
                break

        body = bytes(buffer)
        buffer.clear()
        replayed = False

        async def bounded_receive() -> Message:
            nonlocal replayed
            if replayed:
                return await receive()
            replayed = True
            return {"type": "http.request", "body": body, "more_body": False}

        await self.app(scope, bounded_receive, send)

    async def _reject_too_large(self, scope: Scope, receive: Receive, send: Send) -> None:
        await self._reject(
            scope,
            receive,
            send,
            AgentError(413, "INPUT_TOO_LARGE", "요청 본문 크기가 허용 한도를 초과했습니다."),
        )

    @staticmethod
    async def _reject(
        scope: Scope, receive: Receive, send: Send, error: AgentError
    ) -> None:
        body = ErrorResponse(
            error=ErrorDetail(code=error.code, message=error.message, details=error.details)
        )
        response = JSONResponse(
            status_code=error.status_code,
            content=body.model_dump(by_alias=True, mode="json"),
        )
        await response(scope, receive, send)
