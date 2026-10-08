"""Every report guard has typed, bounded diagnostics; unknown scope stays unknown."""

import json

import pytest
from pydantic import ValidationError
from test_report_insight_assessment import request
from test_report_validation_diagnostics import schema

from app.core.errors import OutputValidationError
from app.core.parser import JsonObjectParseError
from app.llm.report_insight_assessment import draft_schema
from app.llm.report_validation_diagnostics import (
    ReportValidationIssue,
    ReportValidationScope,
    attach_report_validation_diagnostics,
    collect_report_validation_issues,
)


def shape_error(kind, loc, **context):
    entry = {"type": kind, "loc": loc, "input": "PRIVATE_INPUT"}
    if context:
        entry["ctx"] = context
    return ValidationError.from_exception_data("PRIVATE_TITLE", [entry])


@pytest.mark.parametrize(
    ("kind", "context", "expected_rule"),
    [
        ("missing", {}, "schema_missing"),
        ("extra_forbidden", {}, "schema_extra_forbidden"),
        ("string_type", {}, "schema_type"),
        ("literal_error", {"expected": "PRIVATE_ENUM"}, "schema_value"),
        ("string_too_short", {"min_length": 1}, "string_too_short"),
        ("string_too_long", {"max_length": 1}, "string_too_long"),
    ],
)
def test_all_pydantic_guard_families_have_closed_rule_and_reason(kind, context, expected_rule):
    error = shape_error(kind, ("insights", 0, "headline"), **context)
    issues = collect_report_validation_issues(error, schema("CHIP_MAKER"), stage="REDUCE")
    assert len(issues) == 1
    issue = issues[0]
    assert (issue.audience, issue.field, issue.rule) == ("CHIP_MAKER", "headline", expected_rule)
    assert issue.located and issue.reason
    assert "PRIVATE" not in json.dumps(issue.diagnostic_payload())


@pytest.mark.parametrize(
    ("location", "expected_field"),
    [
        (("reason",), "reason"),
        (("decision", "connection", "condition"), "decision.connection.condition"),
        (("decision", "effect", "basis", "claimId"), "decision.effect.basis.claimId"),
        (("decision", "timing", "basis", "sourceSpanId"), "decision.timing.basis.sourceSpanId"),
        (("PRIVATE_EXTRA",), None),
        (("decision", "PRIVATE_UNION", "work"), None),
    ],
)
def test_map_location_comes_from_original_schema_finding_const(location, expected_field):
    contract = draft_schema(request())
    error = shape_error("missing", ("assessments", "CHIP_MAKER", "finding101", *location))
    (issue,) = collect_report_validation_issues(error, contract, stage="MAP-001")
    assert issue.audience == "CHIP_MAKER"
    assert issue.field == "assessments[101]" + (f".{expected_field}" if expected_field else "")
    assert issue.located
    assert "PRIVATE" not in json.dumps(issue.diagnostic_payload())


@pytest.mark.parametrize(
    "location",
    [
        ("assessments", "IT_INFRA", "finding101", "reason"),
        ("assessments", "CHIP_MAKER", "finding999", "reason"),
        ("assessments", "CHIP_MAKER", "finding101\nPRIVATE", "reason"),
        ("PRIVATE",),
    ],
)
def test_foreign_map_keys_never_supply_repair_authority(location):
    error = shape_error("missing", location)
    (issue,) = collect_report_validation_issues(error, draft_schema(request()), stage="MAP")
    assert issue.audience is None and issue.field is None
    assert not issue.located
    assert issue.diagnostic_payload()["repairScope"] == "whole_output"
    assert "PRIVATE" not in json.dumps(issue.diagnostic_payload())


def test_each_legacy_failure_is_present_even_when_only_one_has_a_safe_location():
    error = OutputValidationError("PRIVATE", error_kinds=("report_fact_mismatch",) * 3)
    located = ReportValidationIssue("CHIP_MAKER", "headline", "report_fact_mismatch", ("101:0",))
    error.validation_issues = (located,)
    issues = attach_report_validation_diagnostics(error, schema("CHIP_MAKER"), stage="REDUCE")
    assert issues == error.validation_issues
    assert issues[0] == located and len(issues) == 3
    assert [issue.located for issue in issues] == [True, False, False]
    assert attach_report_validation_diagnostics(error, schema("CHIP_MAKER")) == issues


@pytest.mark.parametrize(
    ("error", "kind"),
    [
        (JsonObjectParseError("PRIVATE"), "report_output_parse"),
        (ValueError("PRIVATE"), "report_output_unlocated"),
        (OutputValidationError("PRIVATE", error_kinds=("PRIVATE",)), "report_output_unlocated"),
    ],
)
def test_unlocated_parse_or_unknown_guards_are_not_silently_omitted(error, kind):
    (issue,) = collect_report_validation_issues(error, {})
    assert issue.error_kind == kind and not issue.located
    assert "PRIVATE" not in json.dumps(issue.diagnostic_payload())


def test_server_scope_localizes_legacy_guard_without_parsing_its_message():
    error = OutputValidationError(
        "PRIVATE claims field=watchItems[4].trigger", error_kinds=("report_fact_mismatch",)
    )
    scope = ReportValidationScope("CHIP_MAKER", "overview[1].text", ("101:0",))
    (issue,) = collect_report_validation_issues(error, {}, scope=scope)
    assert (issue.field, issue.claim_ids) == ("overview[1].text", ("101:0",))
    assert "watchItems" not in json.dumps(issue.diagnostic_payload())
    with pytest.raises(ValueError):
        ReportValidationScope("CHIP_MAKER", "PRIVATE", ())
    with pytest.raises(TypeError):
        collect_report_validation_issues(error, {}, scope={"field": "headline"})


def test_unbound_multi_audience_reduce_index_is_explicitly_unlocated():
    error = shape_error("missing", ("insights", 0, "headline"))
    (issue,) = collect_report_validation_issues(error, schema("CHIP_MAKER", "IT_INFRA"))
    assert not issue.located


def test_rejected_rule_metadata_cannot_supply_reason_prose():
    issue = ReportValidationIssue("CHIP_MAKER", "headline", "report_fact_mismatch", (), "PRIVATE")
    assert issue.rule == "report_fact_mismatch"
    assert "PRIVATE" not in json.dumps(issue.diagnostic_payload())


def test_direct_payload_projection_is_safe_and_bounded_without_adapter():
    invalid = ReportValidationIssue("PRIVATE", "PRIVATE", "PRIVATE", ("PRIVATE",))
    assert "PRIVATE" not in json.dumps(invalid.diagnostic_payload())
    large = ReportValidationIssue(
        "CHIP_MAKER", "headline", "report_fact_mismatch", tuple(f"{n}:0" for n in range(1, 30))
    ).diagnostic_payload()
    assert len(large["claimIds"]) == 8
    assert large["claimIdsTruncated"] is True


def test_public_diagnostic_bound_counts_locations_not_internal_rule_duplicates():
    from app.llm.report_validation_diagnostics import report_validation_issue_details

    error = OutputValidationError("PRIVATE", error_kinds=("report_fact_mismatch",) * 12)
    error.validation_issues = tuple(
        ReportValidationIssue(
            "CHIP_MAKER",
            f"assessments[{finding}]",
            "report_fact_mismatch",
            (f"{finding}:0",),
            rule,
        )
        for finding in range(101, 107)
        for rule in ("report_fact_slot_scope", "unsupported_number")
    )
    details = report_validation_issue_details(error, {}, stage="MAP")
    assert len(details["issues"]) == 6
    assert details["issuesTruncated"] is False
    assert len(collect_report_validation_issues(error, {}, stage="MAP")) == 12
