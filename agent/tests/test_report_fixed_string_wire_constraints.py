"""Fixed repair prose keeps one exact value without redundant decoder rules."""

from copy import deepcopy

import pytest
from jsonschema import Draft202012Validator

from app.llm.openai_contract import output_contract

CONDITION = (
    "수출 증감이 특정 제조사의 수율·생산능력 배분 변경으로 귀결되었음을 입증하는 "
    "내부 수율·생산배분 자료나 계약이 확인되어야 한다."
)


def _draft(field, *, title="ReportAssessmentDraft"):
    return {
        "title": title,
        "type": "object",
        "properties": {"condition": field},
        "required": ["condition"],
        "additionalProperties": False,
    }


def test_recorded_fixed_condition_drops_only_redundant_decoder_constraints():
    field = {
        "type": "string",
        "const": CONDITION,
        "enum": [CONDITION, "다른 조건"],
        "minLength": 1,
        "maxLength": 120,
        "description": "고정된 기존 조건을 유지한다.",
    }
    schema = _draft(field)
    original = deepcopy(schema)
    wire = output_contract(schema).schema
    assert schema == original
    assert wire["properties"]["condition"] == {
        key: value for key, value in field.items() if key != "enum"
    }
    for value in (CONDITION, "다른 조건", CONDITION + " ", " " + CONDITION, "", None, 1, True):
        instance = {"condition": value}
        assert Draft202012Validator(schema).is_valid(instance) == Draft202012Validator(
            wire
        ).is_valid(instance)


@pytest.mark.parametrize(
    "pattern,value",
    [
        (r"^\S$", "가"),
        (r"^\S(?:[\s\S]{0,118}\S)?$", CONDITION),
        (r"^\S(?:[\s\S]{0,}\S)?$", "원문\n조건"),
        (r"^\S[\s\S]{0,8}\S$", "조건"),
        (r"^\S[\s\S]{1,}\S$", "구체 조건"),
    ],
)
def test_supported_trimmed_length_patterns_are_redundant_for_matching_const(pattern, value):
    schema = _draft({"type": "string", "const": value, "pattern": pattern})
    wire = output_contract(schema).schema
    assert "pattern" not in wire["properties"]["condition"]
    assert wire["properties"]["condition"]["const"] == value
    for candidate in (value, "", "다른 문장", value + " ", " " + value, None):
        assert Draft202012Validator(schema).is_valid(
            {"condition": candidate}
        ) == Draft202012Validator(wire).is_valid({"condition": candidate})


@pytest.mark.parametrize(
    "field",
    [
        {"type": "string", "const": "가", "enum": ["나"]},
        {"type": "integer", "const": "가", "enum": ["가"]},
        {"type": "string", "const": " 가", "pattern": r"^\S(?:[\s\S]{0,8}\S)?$"},
        {"type": "string", "const": "가 ", "pattern": r"^\S(?:[\s\S]{0,8}\S)?$"},
        {"type": "string", "const": "가나", "pattern": r"^\S$"},
        {"type": "string", "const": "가", "pattern": r"^\S[\s\S]{0,8}\S$"},
        {"type": "string", "const": "가", "pattern": r"^[가-힣]+$"},
        {"type": "string", "const": "가", "minLength": 2, "pattern": r"^\S$"},
        {"type": "string", "const": "가나", "maxLength": 1, "pattern": r"^\S$"},
        {"type": "string", "const": "😀", "pattern": r"^\S$"},
        {"type": "string", "const": "\ufeff", "pattern": r"^\S$"},
        {"type": "boolean", "const": True, "enum": [1]},
    ],
)
def test_contradictions_and_unsupported_string_patterns_remain(field):
    schema = _draft(field)
    wire = output_contract(schema).schema
    assert wire == schema


def test_generated_prose_and_other_contracts_keep_their_constraints():
    field = {"type": "string", "enum": ["조건"], "minLength": 1, "maxLength": 120}
    wire = output_contract(_draft(field)).schema
    assert wire["properties"]["condition"]["pattern"] == r"^\S(?:[\s\S]{0,118}\S)?$"
    assert wire["properties"]["condition"]["enum"] == ["조건"]
    fixed = {**field, "const": "조건", "pattern": r"^\S(?:[\s\S]{0,118}\S)?$"}
    other = _draft(fixed, title="AnotherOutput")
    assert output_contract(other).schema == other


def test_nested_fixed_values_are_normalized_without_changing_literal_payloads():
    fixed = {"type": "string", "const": "DIRECT", "enum": ["DIRECT", "CONDITIONAL"]}
    literal = {"type": "string", "const": "value", "enum": ["value"]}
    schema = _draft({"anyOf": [{"$ref": "#/$defs/Fixed"}]})
    schema["$defs"] = {"Fixed": fixed}
    schema["properties"]["literal"] = {"type": "object", "const": literal}
    original = deepcopy(schema)
    wire = output_contract(schema).schema
    assert wire["$defs"]["Fixed"] == {"type": "string", "const": "DIRECT"}
    assert wire["properties"]["literal"]["const"] == literal
    assert schema == original
