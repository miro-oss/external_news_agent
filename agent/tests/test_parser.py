import pytest

from app.core.parser import JsonObjectParseError, parse_json_object


def test_removes_json_markdown_fence() -> None:
    assert parse_json_object('```json\n{"ok": true}\n```') == {"ok": True}


@pytest.mark.parametrize("constant", ["NaN", "Infinity", "-Infinity"])
def test_rejects_non_finite_numbers(constant: str) -> None:
    with pytest.raises(JsonObjectParseError):
        parse_json_object(f'{{"score": {constant}}}')


@pytest.mark.parametrize("raw", ["[]", '"value"', "1", "true", "null"])
def test_rejects_non_object_root(raw: str) -> None:
    with pytest.raises(JsonObjectParseError):
        parse_json_object(raw)
