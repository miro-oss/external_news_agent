from typing import Any, Literal

from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel


class AgentModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
    )


class HealthResponse(AgentModel):
    status: Literal["ok"]
    mock: bool


class ErrorDetail(AgentModel):
    code: str
    message: str
    details: Any | None = None


class ErrorResponse(AgentModel):
    error: ErrorDetail
