import hmac
from typing import Annotated

from fastapi import Depends
from fastapi.security import APIKeyHeader

from app.core.config import Settings, get_settings
from app.core.errors import AgentError

_agent_token = APIKeyHeader(name="X-Agent-Token", auto_error=False)


def require_agent_token(
    supplied_token: Annotated[str | None, Depends(_agent_token)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> None:
    if (
        not settings.shared_secret
        or supplied_token is None
        or not hmac.compare_digest(supplied_token, settings.shared_secret)
    ):
        raise AgentError(
            status_code=401,
            code="UNAUTHORIZED",
            message="Agent token이 올바르지 않습니다.",
        )
