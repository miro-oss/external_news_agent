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


def test_only_typed_tuples_and_stage_matching_fields_are_accepted():
    error = ValueError("PRIVATE")
    error.validation_issues = ({"audience": "CHIP_MAKER", "field": "PRIVATE"},)
    assert report_validation_issue_details(error, {}) == {}
    error.validation_issues = [issue()]
    assert report_validation_issue_details(error, {}) == {}
    error.validation_issues = (issue(),)
    assert "issues" not in _validation_failure(error, "MAP-001", 2, schema("CHIP_MAKER"))
    assert "issues" not in _validation_failure(error, "REVIEW-001", 2, schema("CHIP_MAKER"))
    assert _validation_failure(error, None, 2, schema("CHIP_MAKER")) is None


@pytest.mark.parametrize(
    "field",
    [
        "assessments[7869]",
        "assessments[7869].reason",
        "assessments[7869].axes.urgency",
        "assessments[7869].decision.connection.condition",
        "assessments[7869].decision.effect.impactScope",
        "assessments[7869].decision.timing.urgencyState",
    ],
)
def test_closed_assessment_fields_survive_map_and_review_diagnostics(field):
    error = OutputValidationError("PRIVATE_PROSE", error_kinds=("report_fact_mismatch",))
    error.validation_issues = (replace(issue(), field=field),)
    for stage in ("MAP", "MAP-001", "REVIEW", "REVIEW-001"):
        failure = _validation_failure(error, stage, 2, {})
        assert failure["issues"][0]["field"] == field
        assert failure["issues"][0]["claimIds"] == ["7869:1"]
        assert "PRIVATE" not in json.dumps(failure)
    for stage in ("REDUCE", "REDUCE-001"):
        assert "issues" not in _validation_failure(error, stage, 2, {})


@pytest.mark.parametrize(
    "field",
    [
        "assessments[0].reason",
        "assessments[-1].reason",
        "assessments[01].reason",
        "assessments[１].reason",
        "assessments[" + "1" * 20 + "].reason",
        "assessments[PRIVATE].reason",
        "assessments[7869].reason\nPRIVATE",
        "assessments[7869].PRIVATE",
        "assessments[7869].decision.connection.basis.PRIVATE",
        "assessments[7869].reason nativeFields=PRIVATE",
    ],
)
def test_map_field_injection_is_omitted(field):
    error = ValueError("PRIVATE_PROSE")
    error.validation_issues = (replace(issue(), field=field),)
    assert "issues" not in _validation_failure(error, "MAP-001", 2, {})


def test_final_map_retry_keeps_all_finding_locations_without_provider_prose():
    from test_report_insight_map_complete_diagnostics import long_fact_reason, long_fact_source
    from test_report_insight_v4_pipeline import V4Provider, generate, stages

    source = long_fact_source()

    def hook(stage, occurrence, _, value):
        for record in value["assessments"]["CHIP_MAKER"].values():
            record["reason"] = long_fact_reason(record["findingId"])[0]
        return value

    provider = V4Provider(source, relation="UNRELATED", hook=hook)
    with pytest.raises(AgentError) as caught:
        generate(provider, source)
    assert stages(provider) == ["MAP-001", "MAP-001"]
    failure = caught.value.details["validationFailure"]
    assert failure["stage"] == "MAP-001" and failure["attempt"] == 2
    # Each location violates both the new fact-template contract and the
    # existing grounding rule; the public location list remains deduplicated.
    assert failure["errorCount"] == 12
    assert {item["field"] for item in failure["issues"]} == {
        f"assessments[{finding.id}].reason" for finding in source.findings
    }
    assert all(item["errorKind"] == "report_fact_mismatch" for item in failure["issues"])
    assert all(
        item["claimIds"] == [f"{finding.id}:0"]
        for finding, item in zip(source.findings, failure["issues"], strict=True)
    )
    assert not failure["issuesTruncated"]
    assert caught.value.details["usage"]["inputTokens"] == 22
    assert "NVIDIA" not in json.dumps(caught.value.details)
    assert "9101" not in json.dumps(caught.value.details)


def test_native_and_public_diagnostics_survive_aggregation_and_truncated_prefix():
    from test_report_insight_assessment import payload, request, response

    from app.llm import report_insight_service as service

    source = request(ids=(101, 102), text="검증 장비 도입의 마감은 2026년 9월 20일이다.")
    value = payload(source)
    for record in value["assessments"]["CHIP_MAKER"].values():
        record["reason"] = "영향 범위는 미확인이다. NVIDIA의 9901억원 마감이 임박한다."
    error = service._native_assessment_repair_errors(response(value, source), source)
    details = report_validation_issue_details(error, {}, stage="MAP-001")
    assert {item["field"] for item in details["issues"]} >= {
        "assessments[101]",
        "assessments[101].reason",
        "assessments[101].axes.urgency",
    }
    assert "NVIDIA" not in json.dumps(details)
    singleton = source.model_copy(update={"findings": source.findings[:1]})
    nested = service._native_assessment_repair_errors(
        response(
            {
                "assessments": {
                    "CHIP_MAKER": {"finding101": value["assessments"]["CHIP_MAKER"]["finding101"]}
                }
            },
            singleton,
        ),
        singleton,
    )
    truncated = service._TruncatedAssessmentRepairError(source, {}, (101, 102), [(101, nested)])
    assert truncated.validation_issues[:-1] == nested.validation_issues
    assert truncated.validation_issues[-1] == ReportValidationIssue(
        "CHIP_MAKER", "assessments[102]", "report_assessment_truncated_prefix", ()
    )


def test_final_review_retry_keeps_diagnostics_before_validated_map_fallback(monkeypatch):
    from test_report_insight_assessment import request
    from test_report_insight_map_complete_diagnostics import long_fact_reason
    from test_report_insight_v4_pipeline import V4Provider, generate, stages

    from app.core.errors import StructuredOutputExhaustedError
    from app.llm import report_insight_service as service

    source = request()
    failures = []
    original = service.structured_call

    def capture(*args, **kwargs):
        try:
            return original(*args, **kwargs)
        except StructuredOutputExhaustedError as error:
            failures.append(error)
            raise

    monkeypatch.setattr(service, "structured_call", capture)

    def hook(stage, occurrence, _, value):
        if stage.startswith("REVIEW"):
            value["assessments"]["CHIP_MAKER"]["finding101"]["reason"] = long_fact_reason(101)[0]
        return value

    provider = V4Provider(source, hook=hook)
    output = generate(provider, source)
    assert stages(provider) == ["MAP-001", "REVIEW-001", "REVIEW-001", "REDUCE-001"]
    assert len(failures) == 1
    failure = failures[0].details["validationFailure"]
    assert failure["stage"] == "REVIEW-001" and failure["attempt"] == 2
    assert failure["issues"] == [
        {
            "audience": "CHIP_MAKER",
            "field": "assessments[101].reason",
            "errorKind": "report_fact_mismatch",
            "claimIds": ["101:0"],
        }
    ]
    assert "NVIDIA" not in json.dumps(failures[0].details)
    assert "9101" not in output.model_dump_json()
    assert output.meta.input_tokens == 44


def test_native_shape_diagnostic_uses_requested_finding_id_not_rejected_provider_id():
    from test_report_insight_assessment import payload, request, response

    from app.llm.report_insight_assessment import (
        ReportAssessmentDraftValidationError,
        validate_draft,
    )

    source = request()
    value = payload(source)
    raw = response(value, source)
    wire = json.loads(raw.text)
    wire["assessments"]["CHIP_MAKER"]["finding101"]["findingId"] = 999999
    with pytest.raises(ReportAssessmentDraftValidationError) as caught:
        validate_draft(replace(raw, text=json.dumps(wire)), source)
    details = _validation_failure(caught.value, "MAP-001", 2, {})
    assert details["issues"] == [
        {
            "audience": "CHIP_MAKER",
            "field": "assessments[101]",
            "errorKind": "report_assessment_draft_invalid",
            "claimIds": [],
        }
    ]
    assert "999999" not in json.dumps(details)


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
