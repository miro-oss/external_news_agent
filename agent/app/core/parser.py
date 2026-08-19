import json
import re
from typing import Any

_JSON_FENCE = re.compile(r"^\s*```(?:json)?\s*\n(?P<body>.*)\n```\s*$", re.DOTALL | re.IGNORECASE)


class JsonObjectParseError(ValueError):
    """Raised when an LLM response is not a strict JSON object."""


def parse_json_object(raw: str) -> dict[str, Any]:
    candidate = _strip_markdown_fence(raw)
    try:
        parsed = json.loads(candidate, parse_constant=_reject_non_finite)
    except (json.JSONDecodeError, ValueError) as exc:
        raise JsonObjectParseError("응답이 유효한 JSON object가 아닙니다.") from exc

    if not isinstance(parsed, dict):
        raise JsonObjectParseError("최상위 JSON 값은 object여야 합니다.")
    return parsed


def _strip_markdown_fence(raw: str) -> str:
    match = _JSON_FENCE.fullmatch(raw)
    return match.group("body") if match else raw.strip()


def _reject_non_finite(value: str) -> None:
    raise ValueError(f"비유한 숫자는 허용되지 않습니다: {value}")
