"""MAP repair and terminal logs retain owned causes without expanding repair authority."""

import json
from dataclasses import replace

import pytest
from test_report_insight_assessment import payload, request, response
from test_report_insight_axis_support_repair import case
from test_report_insight_repair_actions import structured_diagnostics
from test_report_insight_v4_pipeline import V4Provider, generate

from app.core.errors import AgentError
from app.llm import report_insight_service as service
from app.llm.report_insight_assessment import (
    ReportAssessmentDraftValidationError,
    _validate_draft,
    validate_draft,
)
from app.schemas.report_insight_assessment import ReportAssessmentDraft


@pytest.mark.parametrize(
    "relation,changes,field,rule,category",
    [
        (
            "DIRECT",
            {"reason": "영향 범위는 미확인이다."},
            "decision.effect.impactScope",
            "report_assessment_impact_reason_conflict",
            "CONTRADICTION",
        ),
        (
            "DIRECT",
            {"reason": "업무 연결 자체는 미확인이다."},
            "decision.connection.relation",
            "report_assessment_relation_reason_undetermined",
            "CONTRADICTION",
        ),
        (
            "DIRECT",
            {"reason": "해당 업무와 직접 관련이 없다."},
            "decision.connection.relation",
            "report_assessment_direct_reason_conflict",
            "CONTRADICTION",
        ),
        (
            "UNRELATED",
            {"reason": "해당 업무와 직접 연결된다."},
            "decision.connection.relation",
            "report_assessment_unrelated_reason_conflict",
            "CONTRADICTION",
        ),
        (
            "CONDITIONAL",
            {"condition": "미확인 업무 연결 조건입니다."},
            "decision.connection.condition",
            "report_assessment_condition_not_business_premise",
            "EXPRESSION_POLICY",
        ),
        (
            "DIRECT",
            {"reason": "제공된 claim이 없다."},
            "reason",
            "report_assessment_reason_claim_absence",
            "EXPRESSION_POLICY",
        ),
    ],
)
def test_semantic_causes_keep_locations_refs_and_closed_rules(
    relation, changes, field, rule, category
):
    source = request(ids=(8298,), audiences=("MARKET_INVESTOR",))
    value = payload(source, relation=relation)
    value["assessments"]["MARKET_INVESTOR"]["finding8298"].update(changes)
    with pytest.raises(ReportAssessmentDraftValidationError) as caught:
        validate_draft(response(value, source), source)
    error = caught.value
    (issue,) = error.validation_issues
    assert (issue.field, issue.rule, issue.claim_ids, issue.category) == (
        f"assessments[8298].{field}",
        rule,
        ("8298:0",),
        category,
    )
    assert not error.native_connection_repairs
    # Metadata is not authority to preserve decisions in a semantic repair.
    aggregated = service._native_assessment_repair_errors(response(value, source), source)
    assert not aggregated.native_connection_repairs and not aggregated.native_prose_repairs
    rows = structured_diagnostics(
        "<validation-error>"
        + service._repair_validation_diagnostics(aggregated, for_prompt=True)
        + "</validation-error>"
    )
    assert any(row["rule"] == rule and row["field"] == issue.field for row in rows)


@pytest.mark.parametrize("impact", ["PROJECT_CHANGE", "CORE_CONSTRAINT"])
def test_forecast_rule_identity_survives_aggregation_and_preservation_stays_narrow(impact):
    source, value = case("forecast")
    value["assessments"]["IT_INFRA"]["finding101"]["impactScope"] = impact
    error = service._native_assessment_repair_errors(response(value, source), source)
    assert [issue.rule for issue in error.validation_issues] == [
        "report_axis_market_forecast_only_" + impact.lower(),
        "report_axis_market_forecast_only_scheduled_preparation",
    ]
    assert set(error.native_connection_repairs) == {101}
    assert all(issue.claim_ids == ("101:0",) for issue in error.validation_issues)
    value["assessments"]["IT_INFRA"]["finding101"]["reason"] = (
        "9999년의 가격 전망은 시스템 조달과 연결된다."
    )
    mixed = service._native_assessment_repair_errors(response(value, source), source)
    assert set(mixed.native_connection_repairs) == {101}
    assert not mixed.native_prose_repairs
    assert [issue.rule for issue in mixed.validation_issues] == [
        "report_axis_market_forecast_only_" + impact.lower(),
        "report_axis_market_forecast_only_scheduled_preparation",
        "unsupported_number",
    ]


@pytest.mark.parametrize(
    "basis,field,rule,refs",
    [
        (
            {"claimId": "101:0", "sourceSpanId": "s999_0_0"},
            "sourceSpanId",
            "report_assessment_basis_span_invalid",
            ("101:0",),
        ),
        (
            {"claimId": "999:0", "sourceSpanId": "s999_0_0"},
            "claimId",
            "report_assessment_basis_claim_invalid",
            (),
        ),
    ],
)
def test_bad_wire_basis_has_typed_path_without_disclosing_rejected_ids(basis, field, rule, refs):
    source = request()
    raw = response(payload(source), source)
    wire = json.loads(raw.text)
    wire["assessments"]["CHIP_MAKER"]["finding101"]["decision"]["connection"]["basis"] = basis
    with pytest.raises(ReportAssessmentDraftValidationError) as caught:
        validate_draft(replace(raw, text=json.dumps(wire)), source)
    (issue,) = caught.value.validation_issues
    assert (issue.field, issue.rule, issue.claim_ids, issue.category) == (
        f"assessments[101].decision.connection.basis.{field}",
        rule,
        refs,
        "REFERENCE",
    )
    assert "999" not in json.dumps(issue.diagnostic_payload())


@pytest.mark.parametrize(
    "changes,field,rule",
    [
        ({"relationBasis": None}, "connection.basis", "report_assessment_basis_required"),
        (
            {"impactScope": "UNDETERMINED"},
            "effect.basis",
            "report_assessment_basis_unexpected",
        ),
        (
            {"relationBasis": {"claimId": "101:0", "quote": "PRIVATE_NOT_A_SOURCE"}},
            "connection.basis.sourceSpanId",
            "report_assessment_basis_span_invalid",
        ),
    ],
)
def test_flat_basis_contract_errors_keep_owned_rules(changes, field, rule):
    source = request()
    value = payload(source)
    value["assessments"]["CHIP_MAKER"]["finding101"].update(changes)
    with pytest.raises(ReportAssessmentDraftValidationError) as caught:
        _validate_draft(ReportAssessmentDraft.model_validate(value), source)
    (issue,) = caught.value.validation_issues
    assert issue.field == f"assessments[101].decision.{field}"
    assert issue.rule == rule
    assert "PRIVATE" not in json.dumps(issue.diagnostic_payload())
    assert not caught.value.native_connection_repairs


def test_exhausted_map_logs_identify_reason_axis_conflict_without_provider_prose(caplog):
    source = request(ids=(8298,), audiences=("MARKET_INVESTOR",))

    def hook(stage, occurrence, _, value):
        value["assessments"]["MARKET_INVESTOR"]["finding8298"]["reason"] = (
            "영향 범위는 미확인이다. PRIVATE_RULE_SENTINEL"
        )
        return value

    with pytest.raises(AgentError) as caught:
        generate(V4Provider(source, hook=hook), source)
    failure = caught.value.details["validationFailure"]
    assert failure["issues"][0]["field"] == "assessments[8298].decision.effect.impactScope"
    terminal = [record.message for record in caplog.records if "action=EXHAUSTED" in record.message]
    assert len(terminal) == 1
    assert "report_assessment_impact_reason_conflict" in terminal[0]
    assert "8298:0" in terminal[0]
    assert "PRIVATE_RULE_SENTINEL" not in caplog.text
    assert "PRIVATE_RULE_SENTINEL" not in json.dumps(caught.value.details)
