"""Field-specific factual rules survive aggregation without exposing generated text."""

import json

import pytest
from test_report_insight_assessment import payload, request, response
from test_report_insight_grounded_prose import _provider_response, _reduce_payload
from test_report_insight_native_field_diagnostics import rejected_projection

from app.core.errors import OutputValidationError
from app.llm import report_insight_service as service
from app.llm.report_validation_diagnostics import report_validation_issue_details


def rule_fields(error):
    return {(issue.field, issue.rule) for issue in error.validation_issues}


def test_native_reason_and_condition_keep_their_own_fact_rules():
    source = request()
    value = payload(source, relation="CONDITIONAL")
    item = value["assessments"]["CHIP_MAKER"]["finding101"]
    item["reason"] = "생산량은 999개다. 공정 검증 일정을 확인한다."
    item["condition"] = "삼성전자의 준비가 공정 검증 일정에 필요한 경우"

    error = rejected_projection(source, value)

    assert rule_fields(error) == {
        ("assessments[101].reason", "unsupported_number"),
        ("assessments[101].decision.connection.condition", "company"),
    }
    assert set(error.fact_repair_kinds) == {"company", "unsupported_number"}
    diagnostics = json.dumps([issue.diagnostic_payload() for issue in error.validation_issues])
    assert "999" not in diagnostics and "삼성전자" not in diagnostics


@pytest.mark.parametrize("stage", ["MAP", "REDUCE"])
def test_multiple_rules_keep_one_public_location_and_all_internal_rules(stage):
    source = request()
    prose = "TSMC의 생산량은 999개다. 공정 검증 일정을 확인한다."
    if stage == "MAP":
        value = payload(source)
        value["assessments"]["CHIP_MAKER"]["finding101"]["reason"] = prose
        error = rejected_projection(source, value)
        field = "assessments[101].reason"
    else:
        value = _reduce_payload()
        value["insights"][0]["overview"][0]["text"] = prose
        error = service._reduce_repair_diagnostics(
            _provider_response(value), source, {"CHIP_MAKER": ["101:0"]}
        )
        field = "overview[0].text"

    assert rule_fields(error) == {(field, "company"), (field, "unsupported_number")}
    public = report_validation_issue_details(error, {}, stage=stage)
    assert public == {
        "issues": [
            {
                "audience": "CHIP_MAKER",
                "field": field,
                "errorKind": "report_evidence_insufficient",
                "claimIds": ["101:0"],
            }
        ],
        "issuesTruncated": False,
    }
    diagnostics = json.dumps([issue.diagnostic_payload() for issue in error.validation_issues])
    assert "TSMC" not in diagnostics and "999" not in diagnostics


def test_mixed_native_and_public_failure_retains_closed_fact_rule_union():
    source = request()
    value = payload(source)
    value["assessments"]["CHIP_MAKER"]["finding101"]["reason"] = (
        "영향 범위는 미확인이다. TSMC의 공정 검증 영향을 확인한다."
    )

    error = service._native_assessment_repair_errors(response(value, source), source)

    assert set(error.error_kinds) == {
        "report_assessment_draft_invalid",
        "report_evidence_insufficient",
    }
    assert error.fact_repair_kinds == ("company",)
    assert ("assessments[101].reason", "company") in rule_fields(error)
    truncated = service._TruncatedAssessmentRepairError(source, {}, [101], [(101, error)])
    assert truncated.fact_repair_kinds == ("company",)
    assert ("assessments[101].reason", "company") in rule_fields(truncated)


def test_unknown_fact_rule_cannot_inject_text_into_map_diagnostics(monkeypatch):
    def invalid(*args, **kwargs):
        error = OutputValidationError("PRIVATE prose", error_kinds=("report_fact_mismatch",))
        error.fact_repair_kinds = ("PRIVATE rule", "company")
        return [error]

    monkeypatch.setattr(service, "_prose_validation_errors", invalid)
    source = request()
    error = rejected_projection(source, payload(source))
    assert rule_fields(error) == {("assessments[101].reason", "company")}
    assert "PRIVATE" not in json.dumps(
        [issue.diagnostic_payload() for issue in error.validation_issues]
    )
