"""Provider strict schemas keep REDUCE prose bounds before the single repair."""

from copy import deepcopy

import pytest
from jsonschema import Draft202012Validator
from pydantic_ai.profiles.openai import OpenAIJsonSchemaTransformer
from test_report_insight import output, request_body

from app.llm.openai_contract import output_contract
from app.llm.request_contract import report_insight_reduce_schema
from app.schemas.report_insight import ReportInsightReduceOutput, ReportInsightRequest


def reduce_contract():
    request = ReportInsightRequest.model_validate(request_body())
    schema = report_insight_reduce_schema(request, {"CHIP_MAKER": ("501:0",)})
    snapshot = deepcopy(schema)
    wire = OpenAIJsonSchemaTransformer(output_contract(schema).schema, strict=True).walk()
    assert schema == snapshot
    payload = output()
    del payload["insights"][0]["assessments"]
    return Draft202012Validator(wire), payload


@pytest.mark.parametrize(
    "field,maximum",
    [
        (("headline",), 200),
        (("overview", 0, "text"), 600),
        (("overview", 0, "assumption"), 500),
        (("implications", 0, "text"), 700),
        (("implications", 0, "mechanism"), 500),
        (("implications", 0, "assumption"), 500),
        (("implications", 0, "falsifiedBy"), 500),
        (("watchItems", 0, "topic"), 200),
        (("watchItems", 0, "indicator"), 400),
        (("watchItems", 0, "trigger"), 400),
    ],
)
def test_reduce_prose_bounds_survive_sdk_conversion(field, maximum):
    validator, payload = reduce_contract()
    target = payload["insights"][0]
    for key in field[:-1]:
        target = target[key]
    for value in ("", " \n\t", "가" * (maximum + 1), None):
        target[field[-1]] = value
        assert not validator.is_valid(payload)
    for value in ("가", "가" * maximum, "같은 대상의\n변경 범위"):
        target[field[-1]] = value
        validator.validate(payload)
        ReportInsightReduceOutput.model_validate(payload)


def test_reduce_nonempty_constraints_keep_empty_arrays_and_exact_refs():
    validator, payload = reduce_contract()
    validator.validate(payload)
    payload["insights"][0]["overview"][0]["basisClaimIds"] = ["another:0"]
    assert not validator.is_valid(payload)
    payload["insights"][0].update(overview=[], implications=[], watchItems=[])
    validator.validate(payload)
    ReportInsightReduceOutput.model_validate(payload)


@pytest.mark.parametrize(
    "unit,field,maximum",
    [
        ("headline", None, 200),
        ("overview", "text", 600),
        ("overview", "assumption", 500),
        ("implications", "text", 700),
        ("implications", "mechanism", 500),
        ("implications", "assumption", 500),
        ("implications", "falsifiedBy", 500),
        ("watchItems", "topic", 200),
        ("watchItems", "indicator", 400),
        ("watchItems", "trigger", 400),
    ],
)
def test_partial_reduce_prose_bounds_survive_sdk_conversion(unit, field, maximum):
    request = ReportInsightRequest.model_validate(request_body())
    full = report_insight_reduce_schema(request, {"CHIP_MAKER": ("501:0",)})
    properties = full["$defs"]["ReportInsightReduceAudience"]["anyOf"][0]["properties"]
    unit_schema = properties[unit] if field is None else properties[unit]["items"]
    schema = {
        "title": "ReportInsightReduceRepair",
        "type": "object",
        "properties": {
            "repairs": {
                "type": "object",
                "properties": {"repair0": deepcopy(unit_schema)},
                "required": ["repair0"],
                "additionalProperties": False,
            }
        },
        "required": ["repairs"],
        "additionalProperties": False,
    }
    snapshot = deepcopy(schema)
    wire = OpenAIJsonSchemaTransformer(output_contract(schema).schema, strict=True).walk()
    assert schema == snapshot
    validator = Draft202012Validator(wire)
    original = output()["insights"][0][unit]
    payload = {"repairs": {"repair0": original if field is None else deepcopy(original[0])}}

    def set_value(value):
        if field is None:
            payload["repairs"]["repair0"] = value
        else:
            payload["repairs"]["repair0"][field] = value

    for value in ("", " \n\t", None, "가" * (maximum + 1)):
        set_value(value)
        assert not validator.is_valid(payload)
    for value in ("가", "가" * maximum, "같은 대상의\n변경 범위"):
        set_value(value)
        validator.validate(payload)
    if field is not None:
        payload["repairs"]["repair0"]["basisClaimIds"] = ["foreign:0"]
        assert not validator.is_valid(payload)
