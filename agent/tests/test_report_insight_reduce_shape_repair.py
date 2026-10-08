"""Shape/reference repairs preserve siblings and always validate the merged output."""

import json
from copy import deepcopy

import pytest
from pydantic import ValidationError
from test_report_insight_assessment import request
from test_report_insight_reduce_partial_repair import repair_jobs, response, sdk_validator

from app.core.errors import OutputValidationError
from app.llm.report_insight_reduce_repair import ReduceRepairContext, partial_reduce_repair
from app.llm.report_validation_diagnostics import (
    ReportValidationIssue,
    collect_report_validation_issues,
)
from app.llm.request_contract import report_insight_reduce_schema
from app.schemas.report_insight import ReportInsightReduceOutput


def valid_output():
    return {
        "insights": [
            {
                "audience": "CHIP_MAKER",
                "headline": "생산 제약과 검증 준비의 연결 조건을 확인한다.",
                "overview": [
                    {
                        "text": f"생산 제약에 따른 검증 준비 조건을 확인한다. 구분 {index}",
                        "assumption": "같은 생산 대상의 준비 조건이 이어지는 경우",
                        "basisClaimIds": ["101:0"],
                    }
                    for index in (0, 1)
                ],
                "implications": [],
                "watchItems": [],
            }
        ]
    }


def prepare(value, *, issues=None):
    schema = report_insight_reduce_schema(request(), {"CHIP_MAKER": ("101:0",)})
    raw = json.dumps(value, ensure_ascii=False)
    if issues is None:
        with pytest.raises(ValidationError) as caught:
            ReportInsightReduceOutput.model_validate(value)
        issues = collect_report_validation_issues(caught.value, schema, stage="REDUCE")
    context = ReduceRepairContext.capture(raw, "server prompt", schema, issues)

    def validate(output):
        parsed = ReportInsightReduceOutput.model_validate_json(output.text)
        for insight in parsed.insights:
            for item in (*insight.overview, *insight.implications, *insight.watch_items):
                if set(item.basis_claim_ids) - {"101:0"}:
                    raise ValueError("foreign reference")
        return parsed

    return partial_reduce_repair("server prompt", schema, raw, context, validate)


@pytest.mark.parametrize("defect", ["missing", "null", "oversize", "extra", "wrong_item_type"])
def test_bad_list_unit_is_replaced_without_changing_valid_siblings(defect):
    valid = valid_output()
    value = deepcopy(valid)
    bad = value["insights"][0]["overview"][1]
    if defect == "missing":
        del bad["text"]
    elif defect == "null":
        bad["text"] = None
    elif defect == "oversize":
        bad["text"] = "가" * 5001
    elif defect == "extra":
        bad["PRIVATE_EXTRA"] = "PRIVATE_PROSE"
    else:
        value["insights"][0]["overview"][1] = "PRIVATE_PROSE"
    repair = prepare(value)
    assert repair is not None
    (job,) = repair_jobs(repair.prompt)
    assert (job["group"], job["index"]) == ("overview", 1)
    patch = {"repairs": {job["key"]: valid["insights"][0]["overview"][1]}}
    sdk_validator(repair.response_schema).validate(patch)
    result = repair.validate(response(patch))
    assert result.model_dump(by_alias=True) == valid
    assert (
        result.insights[0].overview[0].model_dump(by_alias=True)
        == (value["insights"][0]["overview"][0])
    )


def test_collection_length_and_nested_failure_coalesce_to_one_job():
    valid = valid_output()
    value = deepcopy(valid)
    value["insights"][0]["overview"] *= 2
    issues = (
        ReportValidationIssue("CHIP_MAKER", "overview", "too_long", ()),
        ReportValidationIssue("CHIP_MAKER", "overview[1].text", "report_fact_mismatch", ("101:0",)),
    )
    repair = prepare(value, issues=issues)
    (job,) = repair_jobs(repair.prompt)
    assert (job["group"], job["index"]) == ("overview", None)
    assert len(job["diagnostics"]) == 2
    patch = {"repairs": {job["key"]: valid["insights"][0]["overview"]}}
    sdk_validator(repair.response_schema).validate(patch)
    result = repair.validate(response(patch))
    assert result.model_dump(by_alias=True) == valid
    patch["repairs"][job["key"]] *= 2
    with pytest.raises(ValidationError):
        repair.validate(response(patch))


def test_missing_headline_or_collection_repairs_only_that_field():
    for field in ("headline", "overview", "watchItems"):
        valid = valid_output()
        value = deepcopy(valid)
        del value["insights"][0][field]
        repair = prepare(value)
        (job,) = repair_jobs(repair.prompt)
        assert (job["group"], job["index"]) == (field, None)
        patch = {"repairs": {job["key"]: valid["insights"][0][field]}}
        assert repair.validate(response(patch)).model_dump(by_alias=True) == valid


def test_reference_failure_reselects_only_failed_unit_and_revalidates_every_reference():
    value = valid_output()
    value["insights"][0]["overview"][1]["basisClaimIds"] = ["999:0"]
    repair = prepare(
        value,
        issues=(
            ReportValidationIssue(
                "CHIP_MAKER", "overview[1].basisClaimIds", "report_synthesis_reference_gap", ()
            ),
        ),
    )
    (job,) = repair_jobs(repair.prompt)
    patch = {"repairs": {job["key"]: valid_output()["insights"][0]["overview"][1]}}
    assert repair.validate(response(patch)).model_dump(by_alias=True) == valid_output()
    patch["repairs"][job["key"]]["basisClaimIds"] = ["999:0"]
    with pytest.raises(ValueError, match="foreign reference"):
        repair.validate(response(patch))


def test_one_unlocated_guard_forces_whole_repair_even_when_another_field_is_known():
    error = OutputValidationError("PRIVATE", error_kinds=("report_fact_mismatch",) * 2)
    error.validation_issues = (
        ReportValidationIssue("CHIP_MAKER", "headline", "report_fact_mismatch", ("101:0",)),
    )
    issues = collect_report_validation_issues(error, {})
    assert prepare(valid_output(), issues=issues) is None


def test_whole_revalidation_keeps_original_schema_location_after_failed_shape_repair():
    value = valid_output()
    del value["insights"][0]["overview"][1]["text"]
    repair = prepare(value)
    (job,) = repair_jobs(repair.prompt)
    with pytest.raises(ValidationError) as caught:
        repair.validate(response({"repairs": {job["key"]: {"text": "불완전한 항목"}}}))
    issues = caught.value.validation_issues
    assert {issue.field for issue in issues} == {
        "overview[1].assumption",
        "overview[1].basisClaimIds",
    }
    assert all(issue.rule == "schema_missing" for issue in issues)
