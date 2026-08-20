from dataclasses import dataclass


@dataclass(slots=True)
class AgentError(Exception):
    status_code: int
    code: str
    message: str
    details: object | None = None
