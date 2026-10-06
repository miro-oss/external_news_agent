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
