"""Mixed REDUCE failures retain original indexes without accepting diagnostic substitutes."""

from copy import deepcopy

import pytest
from pydantic import ValidationError
from test_report_insight_assessment import request
from test_report_insight_reduce_partial_repair import response
from test_report_insight_reduce_shape_repair import valid_output

from app.llm import report_insight_service as service
from app.llm.report_insight_reduce_shape_scan import build_reduce_shape_scan
from app.llm.report_validation_diagnostics import (
    ReportValidationIssue,
    collect_report_validation_issues,
)
from app.llm.request_contract import report_insight_reduce_schema
from app.schemas.report_insight import ReportInsightReduceOutput


def scan(value, issues=None):
    if issues is None:
        schema = report_insight_reduce_schema(request(), {"CHIP_MAKER": ("101:0",)})
        with pytest.raises(ValidationError) as caught:
            ReportInsightReduceOutput.model_validate(value)
        issues = collect_report_validation_issues(caught.value, schema, stage="REDUCE")
    return build_reduce_shape_scan(value, issues, ("CHIP_MAKER",))


def test_missing_first_item_field_and_later_fact_error_are_both_identified():
    original = valid_output()
    del original["insights"][0]["overview"][0]["assumption"]
    original["insights"][0]["overview"][1]["text"] = "999억원의 생산 준비 조건을 확인한다."
    before = deepcopy(original)
    result = scan(original)
    assert result is not None
    assert original == before
    assert len(result.candidate.insights[0].overview) == 1
    diagnostics = service._reduce_repair_diagnostics(
        response(result.candidate.model_dump(by_alias=True)), request(), {"CHIP_MAKER": ["101:0"]}
    )
    assert diagnostics is not None
    # The number is in original item 1, now candidate item 0. The repair must not
    # accidentally replace the first original item twice and preserve the 999.
    fact_issues = tuple(
        issue for issue in diagnostics.validation_issues if issue.field == "overview[0].text"
    )
    assert fact_issues
    mapped = result.remap(fact_issues)
    assert mapped is not None
    assert {issue.field for issue in mapped} == {"overview[1].text"}
    assert all(issue.claim_ids == ("101:0",) for issue in mapped)


@pytest.mark.parametrize("shape", ["headline", "list", "unit"])
def test_only_actual_shape_failures_are_substituted_for_diagnostic_scan(shape):
    original = valid_output()
    before = deepcopy(original)
    if shape == "headline":
        del original["insights"][0]["headline"]
    elif shape == "list":
        original["insights"][0]["overview"] = None
    else:
        original["insights"][0]["overview"][0] = "wrong type"
    result = scan(original)
    assert result is not None
    record = result.candidate.insights[0].model_dump(by_alias=True)
    assert record["implications"] == before["insights"][0]["implications"]
    assert record["watchItems"] == before["insights"][0]["watchItems"]
    if shape == "headline":
        assert record["overview"] == before["insights"][0]["overview"]
        assert (
            result.remap(
                (
                    ReportValidationIssue(
                        "CHIP_MAKER", "headline", "report_synthesis_invalid", ("101:0",)
                    ),
                )
            )
            is None
        )
    elif shape == "list":
        assert record["overview"] == []
    else:
        assert record["overview"] == before["insights"][0]["overview"][1:]


def test_cross_unit_guard_depending_on_removed_member_requires_whole_repair():
    original = valid_output()
    original["insights"][0]["overview"][0] = None
    result = scan(original)
    assert (
        result.remap(
            (ReportValidationIssue("CHIP_MAKER", "overview", "report_synthesis_empty", ()),)
        )
        is None
    )
    assert result.remap((ReportValidationIssue(None, None, "report_output_unlocated", ()),)) is None


@pytest.mark.parametrize(
    "defect", ["foreign_audience", "duplicate_audience", "extra_root", "unknown_field"]
)
def test_unknown_output_shape_has_no_diagnostic_projection(defect):
    value = valid_output()
    if defect == "foreign_audience":
        value["insights"][0]["audience"] = "IT_INFRA"
    elif defect == "duplicate_audience":
        value["insights"] *= 2
    elif defect == "extra_root":
        value["PRIVATE"] = "untrusted"
    else:
        value["insights"][0]["PRIVATE"] = "untrusted"
    issue = ReportValidationIssue("CHIP_MAKER", "headline", "report_output_shape", ())
    assert scan(value, (issue,)) is None


def test_stale_or_incomplete_shape_diagnostics_cannot_authorize_removing_valid_units():
    value = valid_output()
    stale = ReportValidationIssue("CHIP_MAKER", "overview[1].text", "report_output_shape", ())
    assert scan(value, (stale,)) is None
    del value["insights"][0]["headline"]
    assert scan(value, (stale,)) is None
    del value["insights"][0]["overview"][0]["text"]
    partial = ReportValidationIssue("CHIP_MAKER", "headline", "report_output_shape", ())
    assert scan(value, (partial,)) is None


def test_remapped_locations_keep_rule_and_claim_metadata_and_reject_unknown_candidate_indexes():
    value = valid_output()
    value["insights"][0]["overview"][0] = None
    result = scan(value)
    issue = ReportValidationIssue(
        "CHIP_MAKER",
        "overview[0].text",
        "report_fact_mismatch",
        ("101:0",),
        "report_fact_slot_scope",
    )
    (mapped,) = result.remap((issue,))
    assert mapped.field == "overview[1].text"
    assert (mapped.rule, mapped.claim_ids) == (issue.rule, issue.claim_ids)
    unknown = ReportValidationIssue("CHIP_MAKER", "overview[2].text", "report_fact_mismatch", ())
    assert result.remap((unknown,)) is None
