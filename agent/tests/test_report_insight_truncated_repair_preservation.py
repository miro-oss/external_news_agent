"""Closed-prefix retries keep complete diagnostics and authenticated decisions."""

import json
from copy import deepcopy
from dataclasses import replace

import pytest
from test_report_insight_assessment import payload, request, response
from test_report_insight_mixed_record_preservation import (
    BAD_COMPANY_REASON,
    GOOD_REASON,
    template_validator,
    templated_connection_case,
)
from test_report_insight_preserved_connection_repair import corrected_capacity
from test_report_insight_preserved_decision_repair import repair_payload, wire_validator
from test_report_insight_truncated_prefix import prefix
from test_report_insight_v4_pipeline import V4Provider, generate, stages

from app.core.errors import AgentError, OutputValidationError
from app.llm import report_insight_service as service
from app.llm.report_insight_assessment import draft_prompt, draft_schema, review_prompt


def truncated(source, value, count):
    raw = response(value, source)
    records = json.loads(raw.text)["assessments"]["CHIP_MAKER"]
    return replace(raw, text=prefix(dict(list(records.items())[:count])), truncated=True)


def repair_for(source, raw, prompt=draft_prompt):
    error = service._truncated_assessment_repair_error(raw, source, template_wire=True)
    assert error is not None
    calls = []
    repair = object.__new__(service.ReportInsightService)._repair_call(
        prompt(source), draft_schema(source), raw.text, error, template_validator(source, calls)
    )
    return repair, error, calls


def candidate(repair, source, raw, fixed):
    wire = repair_payload(repair, source, fixed)
    return wire, replace(raw, text=json.dumps(wire, ensure_ascii=False), truncated=False)


def mixed_case():
    source = request(ids=(101, 102, 103, 104))
    value = payload(source)
    records = value["assessments"]["CHIP_MAKER"]
    records["finding101"]["reason"] = "영향 범위는 미확인이다. " + BAD_COMPANY_REASON
    records["finding102"]["reason"] = BAD_COMPANY_REASON
    return source, value


def corrected(value):
    fixed = deepcopy(value)
    records = fixed["assessments"]["CHIP_MAKER"]
    records["finding101"].update(
        reason="영향 범위는 미확인이다. 공정 검증 영향을 확인한다.",
        impactScope="UNDETERMINED",
        impactBasis=None,
    )
    records["finding102"]["reason"] = GOOD_REASON
    # 104 has no completed original record, so there is no decision to freeze.
    records["finding104"].update(
        reason="영향 범위는 미확인이다. 공정 검증 영향을 확인한다.",
        impactScope="UNDETERMINED",
        impactBasis=None,
    )
    return fixed


@pytest.mark.parametrize("prompt", [draft_prompt, review_prompt], ids=["MAP", "REVIEW"])
def test_closed_native_failure_reports_public_failure_before_the_only_retry(prompt):
    source, value = mixed_case()
    raw = truncated(source, value, 3)
    original = source.model_dump_json(), raw.text
    repair, error, calls = repair_for(source, raw, prompt)
    assert error.failed_finding_ids == (101, 102, 104)
    assert set(error.error_kinds) == {
        "report_assessment_truncated_prefix",
        "report_assessment_draft_invalid",
        "report_evidence_insufficient",
    }
    assert ("assessments[101].reason", "company") in {
        (issue.field, issue.rule) for issue in error.validation_issues
    }
    assert set(error.native_prose_repairs) == {102}
    assert not error.native_connection_repairs
    assert set(error.preserved_wire["assessments"]["CHIP_MAKER"]) == {"finding103"}
    assert "company" in repair.prompt and "findingId=101" in repair.prompt
    wire, fixed_raw = candidate(repair, source, raw, corrected(value))
    assert set(wire["assessments"]["CHIP_MAKER"]) == {"finding101", "finding102", "finding104"}
    wire_validator(repair).validate(wire)
    result = repair.validate(fixed_raw)
    assert result.evidence["CHIP_MAKER"][101].impact_scope == "UNDETERMINED"
    assert result.evidence["CHIP_MAKER"][102].impact_scope == "CORE_CONSTRAINT"
    assert result.evidence["CHIP_MAKER"][104].impact_scope == "UNDETERMINED"
    assert len(calls) == 1
    assert set(calls[0]["assessments"]["CHIP_MAKER"]) == {
        "finding101",
        "finding102",
        "finding103",
        "finding104",
    }
    assert (
        calls[0]["assessments"]["CHIP_MAKER"]["finding103"]
        == (json.loads(response(value, source).text)["assessments"]["CHIP_MAKER"]["finding103"])
    )
    assert original == (source.model_dump_json(), raw.text)
    assert raw.truncated


@pytest.mark.parametrize("impact_scope", ["UNDETERMINED", "NO_CHANGE"])
def test_truncated_prose_repair_rejects_changes_to_verified_decisions(impact_scope):
    source, value = mixed_case()
    raw = truncated(source, value, 3)
    repair, _, calls = repair_for(source, raw)
    fixed = corrected(value)
    item = fixed["assessments"]["CHIP_MAKER"]["finding102"]
    item["impactScope"] = impact_scope
    if impact_scope == "UNDETERMINED":
        item["impactBasis"] = None
    wire, fixed_raw = candidate(repair, source, raw, fixed)
    assert not wire_validator(repair).is_valid(wire)
    with pytest.raises(OutputValidationError, match="변경할 수 없습니다"):
        repair.validate(fixed_raw)
    assert calls == []


@pytest.mark.parametrize("template_condition", [False, True])
def test_truncated_axis_repair_keeps_the_completed_template_connection(template_condition):
    source, value, source_id = templated_connection_case(condition=template_condition)
    # 101 has only an effect/timing error, 102 is healthy, and 103 is absent.
    value["assessments"]["CHIP_MAKER"]["finding102"]["reason"] = GOOD_REASON
    raw = truncated(source, value, 2)
    repair, error, calls = repair_for(source, raw)
    assert set(error.native_connection_repairs) == {101}
    assert not error.native_prose_repairs
    fixed = corrected_capacity(value)
    assert fixed["assessments"]["CHIP_MAKER"]["finding101"]["sourceQuotes"]["reason"] == source_id
    wire, fixed_raw = candidate(repair, source, raw, fixed)
    wire_validator(repair).validate(wire)
    result = repair.validate(fixed_raw)
    assert result.evidence["CHIP_MAKER"][101].relation_basis.claim_id == "101:1"
    assert "{{fact:" not in result.mapped.model_dump_json()
    assert len(calls) == 1

    wire["assessments"]["CHIP_MAKER"]["finding101"]["decision"]["connection"]["work"] = (
        "MATERIAL_SUPPLY"
    )
    assert not wire_validator(repair).is_valid(wire)
    with pytest.raises(OutputValidationError, match="connection을 변경할 수 없습니다"):
        repair.validate(replace(raw, text=json.dumps(wire, ensure_ascii=False), truncated=False))
    assert len(calls) == 1


@pytest.mark.parametrize("native_failure", [False, True])
def test_no_healthy_complete_record_still_keeps_diagnostics_and_full_repair(native_failure):
    source = request(ids=(101, 102))
    value = payload(source)
    value["assessments"]["CHIP_MAKER"]["finding101"]["reason"] = (
        "영향 범위는 미확인이다. " if native_failure else ""
    ) + BAD_COMPANY_REASON
    raw = truncated(source, value, 1)
    repair, error, calls = repair_for(source, raw)
    assert error.failed_finding_ids == (101, 102)
    assert not error.preserved_wire["assessments"]["CHIP_MAKER"]
    assert "company" in repair.prompt
    assert set(error.native_prose_repairs) == (set() if native_failure else {101})
    fixed = deepcopy(value)
    fixed["assessments"]["CHIP_MAKER"]["finding101"]["reason"] = GOOD_REASON
    wire, fixed_raw = candidate(repair, source, raw, fixed)
    assert set(wire["assessments"]["CHIP_MAKER"]) == {"finding101", "finding102"}
    wire_validator(repair).validate(wire)
    result = repair.validate(fixed_raw)
    assert len(result.evidence["CHIP_MAKER"]) == 2
    assert len(calls) == 1


@pytest.mark.parametrize("mutation", ["raw", "snapshot", "preserved", "parser_failure"])
def test_unauthenticated_prefix_never_enables_partial_or_axis_preservation(monkeypatch, mutation):
    source, value = mixed_case()
    raw = truncated(source, value, 3)
    _, error, _ = repair_for(source, raw)
    if mutation == "raw":
        raw = replace(raw, text=raw.text.replace(BAD_COMPANY_REASON, GOOD_REASON, 1))
    elif mutation == "snapshot":
        error._closed_records["finding101"]["reason"] = GOOD_REASON
    elif mutation == "preserved":
        error.preserved_wire["assessments"]["CHIP_MAKER"]["finding103"]["reason"] = GOOD_REASON
    else:
        monkeypatch.setattr(service, "closed_assessment_prefix", lambda *args: None)
    schema = draft_schema(source)
    calls = []
    repair = object.__new__(service.ReportInsightService)._repair_call(
        draft_prompt(source), schema, raw.text, error, template_validator(source, calls)
    )
    assert repair.response_schema == schema
    assert calls == []


@pytest.mark.parametrize("defect", ["trailing_comma", "open_record", "missing_native_field"])
def test_unfinished_or_unvalidated_prefix_keeps_full_fallback(defect):
    source, value = mixed_case()
    raw = truncated(source, value, 3)
    if defect == "trailing_comma":
        raw = replace(raw, text=raw.text + ",")
    elif defect == "open_record":
        raw = replace(raw, text=raw.text + ',"finding104":{"findingId":104')
    else:
        records = json.loads(response(value, source).text)["assessments"]["CHIP_MAKER"]
        records["finding101"]["decision"].pop("timing")
        raw = replace(raw, text=prefix(dict(list(records.items())[:3])))
    assert service._truncated_assessment_repair_error(raw, source, template_wire=True) is None


@pytest.mark.parametrize("defect", ["fact", "native", "extra", "missing", "truncated"])
def test_closed_prefix_retry_rechecks_full_output_and_never_accepts_truncated_repair(defect):
    source, value = mixed_case()
    raw = truncated(source, value, 3)
    repair, _, calls = repair_for(source, raw)
    wire, fixed_raw = candidate(repair, source, raw, corrected(value))
    records = wire["assessments"]["CHIP_MAKER"]
    if defect == "fact":
        records["finding101"]["reason"] = BAD_COMPANY_REASON
    elif defect == "native":
        records["finding104"]["reason"] = "원문 claim이 없다."
    elif defect == "extra":
        records["finding103"] = json.loads(response(value, source).text)["assessments"][
            "CHIP_MAKER"
        ]["finding103"]
    elif defect == "missing":
        records.pop("finding104")
    fixed_raw = replace(
        fixed_raw, text=json.dumps(wire, ensure_ascii=False), truncated=defect == "truncated"
    )
    with pytest.raises(ValueError):
        repair.validate(fixed_raw)
    assert len(calls) == (0 if defect in {"extra", "missing"} else 1)


@pytest.mark.parametrize("permanent", [False, True])
def test_pipeline_uses_one_truncated_retry_and_preserves_usage(permanent):
    source = request(ids=(101, 102, 103))

    class Provider(V4Provider):
        def generate(self, **kwargs):
            answer = super().generate(**kwargs)
            if self.calls[-1]["response_schema"]["description"] != "reportInsightCall:MAP-001":
                return answer
            wire = json.loads(answer.text)
            record = wire["assessments"]["CHIP_MAKER"]["finding101"]
            if len(self.calls) == 1:
                record["reason"] = "영향 범위는 미확인이다. " + BAD_COMPANY_REASON
                return replace(
                    answer,
                    text=prefix(dict(list(wire["assessments"]["CHIP_MAKER"].items())[:2])),
                    truncated=True,
                )
            record["reason"] = BAD_COMPANY_REASON if permanent else GOOD_REASON
            return replace(answer, text=json.dumps(wire, ensure_ascii=False))

    provider = Provider(source)
    if permanent:
        with pytest.raises(AgentError) as caught:
            generate(provider, source)
        assert stages(provider) == ["MAP-001", "MAP-001"]
        assert caught.value.details["truncated"] is True
        assert caught.value.details["usage"] == {
            "inputTokens": 22,
            "outputTokens": 14,
            "costUsd": 0.006,
            "credits": 0.4,
        }
    else:
        output = generate(provider, source)
        assert stages(provider)[:2] == ["MAP-001", "MAP-001"]
        assert stages(provider).count("MAP-001") == 2
        assert len(output.insights[0].assessments) == 3
    details = provider.calls[1]["prompt"].split("<validation-error>", 1)[1]
    assert "company" in details and "effect.impactScope" in details
