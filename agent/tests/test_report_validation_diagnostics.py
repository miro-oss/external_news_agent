import json
from copy import deepcopy
from dataclasses import replace

import pytest
from pydantic import ValidationError
from test_structured_call import SequenceProvider, invoke, response

from app.core.errors import AgentError, OutputValidationError
from app.llm.report_validation_diagnostics import (
    ReportValidationIssue,
    pydantic_reduce_issues,
    report_validation_issue_details,
)
from app.llm.structured_call import _validation_failure


def schema(*audiences):
    branches = [{"properties": {"audience": {"const": audience}}} for audience in audiences]
    return {
        "title": "ReportInsightReduceOutput",
        "properties": {"insights": {"items": {"$ref": "#/$defs/Audience"}}},
        "$defs": {"Audience": {"anyOf": branches}},
    }


def length_error(loc=("insights", 0, "implications", 1, "falsifiedBy")):
    return ValidationError.from_exception_data(
        "Synthetic output",
        [
            {
                "type": "string_too_long",
                "loc": loc,
                "input": "PRIVATE_PROSE",
                "ctx": {"max_length": 2},
            }
        ],
    )


def issue():
    return ReportValidationIssue(
        "CHIP_MAKER", "implications[1].falsifiedBy", "report_fact_mismatch", ("7869:1",)
    )


def test_typed_final_attempt_metadata_survives_without_error_or_response_prose():
    first = OutputValidationError("PRIVATE_FIRST", error_kinds=("report_synthesis_invalid",))
    first.validation_issues = (replace(issue(), field="headline"),)
    last = OutputValidationError("PRIVATE_LAST", error_kinds=("report_fact_mismatch",))
    last.validation_issues = (issue(),)
    errors = iter((first, last))

    def validate(_):
        raise next(errors)

    with pytest.raises(AgentError) as caught:
        invoke(
            SequenceProvider(response(), response()), failure_stage="REDUCE-001", validator=validate
        )
    failure = caught.value.details["validationFailure"]
    assert failure["issues"] == [
        {
            "audience": "CHIP_MAKER",
            "field": "implications[1].falsifiedBy",
            "errorKind": "report_fact_mismatch",
            "claimIds": ["7869:1"],
        }
    ]
    assert not failure["issuesTruncated"]
    assert failure["attempt"] == 2
    assert "PRIVATE" not in json.dumps(caught.value.details)
    assert caught.value.details["usage"]["inputTokens"] == 20


@pytest.mark.parametrize(
    "mutation",
    [
        {"audience": "CHIP_MAKER\nPRIVATE"},
        {"field": "implications[5].text"},
        {"field": "overview[-1].text"},
        {"field": "watchItems[0].PRIVATE"},
        {"field": "PRIVATE_PROSE"},
        {"error_kind": "report_PRIVATE"},
        {"error_kind": "value_error"},
        {"claim_ids": ("PRIVATE",)},
        {"claim_ids": ("7869:1\nPRIVATE",)},
        {"claim_ids": ("０:1",)},
        {"claim_ids": ("1" * 20 + ":0",)},
        {"claim_ids": ["7869:1"]},
    ],
)
def test_invalid_issue_parts_cannot_become_metadata(mutation):
    error = ValueError("PRIVATE")
    error.validation_issues = (replace(issue(), **mutation),)
    assert report_validation_issue_details(error, {}) == {}


def test_only_typed_tuples_are_accepted_and_non_reduce_stages_omit_issues():
    error = ValueError("PRIVATE")
    error.validation_issues = ({"audience": "CHIP_MAKER", "field": "PRIVATE"},)
    assert report_validation_issue_details(error, {}) == {}
    error.validation_issues = [issue()]
    assert report_validation_issue_details(error, {}) == {}
    error.validation_issues = (issue(),)
    assert "issues" not in _validation_failure(error, "MAP-001", 2, schema("CHIP_MAKER"))
    assert _validation_failure(error, None, 2, schema("CHIP_MAKER")) is None


def test_issue_and_reference_caps_are_explicit_and_do_not_mutate_original():
    refs = tuple(f"{n}:0" for n in range(1, 11))
    issues = tuple(
        replace(issue(), field=f"implications[{i}].{field}", claim_ids=refs)
        for i in range(5)
        for field in ("text", "mechanism")
    )
    error = ValueError("PRIVATE")
    error.validation_issues = issues + (issues[0],)
    result = report_validation_issue_details(error, {})
    assert len(result["issues"]) == 8
    assert all(len(item["claimIds"]) == 8 for item in result["issues"])
    assert result["issuesTruncated"] is True
    assert len(error.validation_issues) == 11
    assert error.validation_issues[0].claim_ids == refs


def test_pydantic_lengths_use_schema_constant_only_without_input_or_context():
    error = length_error()
    expected = ReportValidationIssue(
        "CHIP_MAKER", "implications[1].falsifiedBy", "string_too_long", ()
    )
    assert pydantic_reduce_issues(error, schema("CHIP_MAKER")) == (expected,)
    failure = _validation_failure(error, "REDUCE-001", 2, schema("CHIP_MAKER"))
    assert failure["issues"][0]["field"] == expected.field
    assert failure["issues"][0]["audience"] == expected.audience
    assert "PRIVATE" not in json.dumps(failure)
    assert pydantic_reduce_issues(error, schema("CHIP_MAKER", "IT_INFRA")) == ()
    assert (
        pydantic_reduce_issues(
            error, {"title": "AnotherTask", **{"properties": schema("CHIP_MAKER")["properties"]}}
        )
        == ()
    )


def test_partial_repair_validation_can_attach_original_schema_issues():
    error = length_error()
    error.validation_issues = pydantic_reduce_issues(error, schema("CHIP_MAKER"))
    assert (
        _validation_failure(error, "REDUCE", 2, {"title": "ReportInsightReduceRepair"})["issues"][
            0
        ]["field"]
        == "implications[1].falsifiedBy"
    )


@pytest.mark.parametrize(
    "loc",
    [
        ("PRIVATE", 0, "headline"),
        ("insights", "PRIVATE", "headline"),
        ("insights", 4, "headline"),
        ("insights", 0, "implications", 5, "text"),
        ("insights", 0, "implications", "PRIVATE", "text"),
        ("insights", 0, "overview", 0, "PRIVATE"),
        ("insights", 0, "headline", "PRIVATE"),
    ],
)
def test_pydantic_untrusted_or_out_of_bounds_locations_are_omitted(loc):
    assert pydantic_reduce_issues(length_error(loc), schema("CHIP_MAKER")) == ()


def test_prefix_items_bind_audiences_by_server_index_and_aliases_are_closed():
    original = schema("CHIP_MAKER", "IT_INFRA")
    bound = deepcopy(original)
    bound["properties"]["insights"] = {"prefixItems": bound["$defs"]["Audience"]["anyOf"]}
    error = length_error(("insights", 1, "watch_items", 4, "trigger"))
    assert pydantic_reduce_issues(error, bound) == (
        ReportValidationIssue("IT_INFRA", "watchItems[4].trigger", "string_too_long", ()),
    )
    assert pydantic_reduce_issues(length_error(("insights", 2, "headline")), bound) == ()


def test_actual_reduce_schema_length_errors_are_localized_without_claim_or_text_input():
    from test_report_insight import output, request_body

    from app.llm.request_contract import report_insight_reduce_schema
    from app.schemas.report_insight import ReportInsightReduceOutput, ReportInsightRequest

    request = ReportInsightRequest.model_validate(request_body())
    contract = report_insight_reduce_schema(request, {"CHIP_MAKER": ("501:0",)})
    value = output()
    del value["insights"][0]["assessments"]
    value["insights"][0]["headline"] = "PRIVATE_PROSE" * 30
    value["insights"][0]["overview"] *= 4
    with pytest.raises(ValidationError) as caught:
        ReportInsightReduceOutput.model_validate(value)
    issues = pydantic_reduce_issues(caught.value, contract)
    assert {(item.audience, item.field, item.error_kind, item.claim_ids) for item in issues} == {
        ("CHIP_MAKER", "headline", "string_too_long", ()),
        ("CHIP_MAKER", "overview", "too_long", ()),
    }
    assert "PRIVATE_PROSE" not in json.dumps(
        report_validation_issue_details(caught.value, contract)
    )
