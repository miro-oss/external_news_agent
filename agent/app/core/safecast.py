import math
from typing import Any

_TRUE_VALUES = frozenset({"1", "true", "yes", "y", "on"})
_FALSE_VALUES = frozenset({"0", "false", "no", "n", "off"})


def safe_bool(value: Any, default: bool | None = None) -> bool | None:
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        normalized = value.strip().lower()
        if normalized in _TRUE_VALUES:
            return True
        if normalized in _FALSE_VALUES:
            return False
    if isinstance(value, int) and value in (0, 1):
        return bool(value)
    return default


def safe_int(value: Any, default: int | None = None) -> int | None:
    if isinstance(value, bool):
        return default
    try:
        return int(value)
    except (TypeError, ValueError, OverflowError):
        return default


def safe_float(value: Any, default: float | None = None) -> float | None:
    if isinstance(value, bool):
        return default
    try:
        converted = float(value)
    except (TypeError, ValueError, OverflowError):
        return default
    return converted if math.isfinite(converted) else default
