from dataclasses import dataclass


@dataclass(slots=True)
class AgentError(Exception):
    status_code: int
    code: str
    message: str
    details: object | None = None


class OutputValidationError(ValueError):
    """Carry one application-defined kind per output violation for safe diagnostics.

    Kinds must be application-defined constants, never raw model data. Keep repeated
    kinds so the violation count remains accurate; messages may contain repair details.
    """

    def __init__(self, message: str, *, error_kinds: tuple[str, ...]) -> None:
        super().__init__(message)
        self.error_kinds = error_kinds
