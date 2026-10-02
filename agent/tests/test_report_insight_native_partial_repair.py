"""Native repair preserves records only after native and public validation."""

import json
from copy import deepcopy
from dataclasses import replace
from datetime import date

import pytest
from test_report_insight_assessment import framed
from test_report_insight_v4_pipeline import (
    V4Provider,
    generate,
    partial_repair_fixture,
    stages,
)

from app.core.errors import AgentError
from app.llm import report_insight_service as service


def mixed_failures(reasons, *, stage_name="MAP-001", permanent=False):
    def hook(stage, occurrence, _, value):
        for entries in value["assessments"].values():
            for record in entries.values():
                record["reason"] = reasons[record["findingId"]]
        if stage == stage_name and (occurrence == 1 or permanent):
            entries = value["assessments"]["CHIP_MAKER"]
            entries["finding101"]["reason"] = (
                "검증을 통과한 claim 근거가 없어 중요도 판단을 보류합니다."
            )
            entries["finding103"]["reason"] = "2031년에는 검증 준비 조건을 확인해야 한다."
        return value

    return hook


def test_native_and_public_failure_union_preserves_every_other_record_and_full_date(monkeypatch):
    source, reasons, _ = partial_repair_fixture()
    before = source.model_dump_json(by_alias=True)
    native_calls, public_calls, repair_errors = [], [], []
    native_validate, public_validate = service.validate_draft, service._validated_map_output
    partial_repair = service._partial_assessment_repair

    def capture_native(response, request):
        native_calls.append((json.loads(response.text), [f.id for f in request.findings]))
        return native_validate(response, request)

    def capture_public(response, request):
        public_calls.append(([f.id for f in request.findings], request.report.report_end_date))
        return public_validate(response, request)

    def capture_repair(prompt, schema, raw, error, validate, fallback):
        repair_errors.append(error)
        return partial_repair(prompt, schema, raw, error, validate, fallback)

    monkeypatch.setattr(service, "validate_draft", capture_native)
    monkeypatch.setattr(service, "_validated_map_output", capture_public)
    monkeypatch.setattr(service, "_partial_assessment_repair", capture_repair)
    provider = V4Provider(source, relation="UNRELATED", hook=mixed_failures(reasons))
    result = generate(provider, source)

    assert stages(provider) == ["MAP-001", "MAP-001", "MAP-002"]
    repair = framed(provider.calls[1]["prompt"])
    assert [f["id"] for f in repair["findings"]] == [101, 103]
    assert repair["reportReferenceDate"] == "2026-09-30"
    assert repair_errors[0].failed_finding_ids == (101, 103)
    assert repair_errors[0].error_kinds == (
        "report_assessment_draft_invalid",
        "report_fact_mismatch",
    )
    assert [(ids, day) for ids, day in public_calls if len(ids) == 1] == [
        ([finding_id], date(2026, 9, 30)) for finding_id in (102, 103, 104, 105, 106)
    ]
    full_inputs = [wire for wire, ids in native_calls if ids == list(range(101, 107))]
    assert len(full_inputs) == 2
    original, merged = [wire["assessments"]["CHIP_MAKER"] for wire in full_inputs]
    for key in original:
        if key not in {"finding101", "finding103"}:
            assert merged[key] == original[key]
    assert (list(range(101, 107)), date(2026, 9, 30)) in public_calls
    assert [record.reason for record in result.insights[0].assessments] == list(reasons.values())
    assert result.meta.input_tokens == 33 and result.meta.output_tokens == 21
    assert result.meta.cost_usd == 0.009 and result.meta.credits == 0.6
    assert source.model_dump_json(by_alias=True) == before


def test_unlocalized_public_error_falls_back_to_full_repair(monkeypatch):
    source, reasons, _ = partial_repair_fixture()
    original = service._validated_map_output

    def validate(response, request):
        if [finding.id for finding in request.findings] == [102]:
            raise ValueError("Unlocalized public validation failure")
        return original(response, request)

    monkeypatch.setattr(service, "_validated_map_output", validate)
    provider = V4Provider(source, relation="UNRELATED", hook=mixed_failures(reasons))
    generate(provider, source)
    assert [f["id"] for f in framed(provider.calls[1]["prompt"])["findings"]] == list(
        range(101, 107)
    )


def test_literal_source_selection_failure_also_collects_other_public_failures():
    source, reasons, _ = partial_repair_fixture()

    def wire_hook(stage, occurrence, _, value):
        if stage == "MAP-001" and occurrence == 1:
            entries = value["assessments"]["CHIP_MAKER"]
            entries["finding101"]["reason"] = reasons[101]
            entries["finding101"]["connection"]["basis"]["sourceSpanId"] = "s101_0_99999"
        return value

    provider = V4Provider(
        source,
        relation="UNRELATED",
        hook=mixed_failures(reasons),
        wire_hook=wire_hook,
        validate_wire=False,
    )
    result = generate(provider, source)
    assert [f["id"] for f in framed(provider.calls[1]["prompt"])["findings"]] == [101, 103]
    assert [record.reason for record in result.insights[0].assessments] == list(reasons.values())


@pytest.mark.parametrize(
    "defect",
    [
        "missing_key",
        "extra_key",
        "duplicate_id",
        "wrong_id",
        "missing_field",
        "bad_enum",
        "duplicate_key",
        "malformed",
        "nonfinite",
        "multiple_audiences",
    ],
)
def test_ambiguous_native_structure_falls_back_to_full_batch(defect):
    source, reasons, _ = partial_repair_fixture()
    if defect == "multiple_audiences":
        source = source.model_copy(update={"audiences": ["CHIP_MAKER", "EQUIPMENT_MAKER"]})

    def wire_hook(stage, occurrence, _, value):
        if stage != "MAP-001" or occurrence != 1:
            return value
        entries = value["assessments"]["CHIP_MAKER"]
        if defect == "missing_key":
            del entries["finding106"]
        elif defect == "extra_key":
            entries["finding999"] = deepcopy(entries["finding106"])
            entries["finding999"]["findingId"] = 999
        elif defect in {"duplicate_id", "wrong_id"}:
            entries["finding106"]["findingId"] = 105 if defect == "duplicate_id" else 999
        elif defect == "missing_field":
            del entries["finding106"]["timing"]
        elif defect == "bad_enum":
            entries["finding106"]["effect"]["impactScope"] = "INVALID"
        return value

    def raw_hook(stage, occurrence, _, raw):
        if stage != "MAP-001" or occurrence != 1:
            return raw
        if defect == "duplicate_key":
            return raw.replace('"findingId": 106', '"findingId": 106, "findingId": 106')
        if defect == "malformed":
            return raw[:-1]
        if defect == "nonfinite":
            return raw.replace('"findingId": 106', '"findingId": NaN')
        return raw

    provider = V4Provider(
        source,
        relation="UNRELATED",
        hook=mixed_failures(reasons),
        wire_hook=wire_hook,
        raw_hook=raw_hook,
        validate_wire=False,
    )
    result = generate(provider, source)
    assert [f["id"] for f in framed(provider.calls[1]["prompt"])["findings"]] == list(
        range(101, 107)
    )
    assert stages(provider) == ["MAP-001", "MAP-001", "MAP-002"]
    assert len(result.insights) == len(source.audiences)


@pytest.mark.parametrize("defect", ["native", "public", "missing", "extra", "truncated"])
def test_native_partial_repair_revalidates_merged_batch_without_third_attempt(defect):
    source, reasons, _ = partial_repair_fixture()

    def wire_hook(stage, occurrence, _, value):
        if stage == "MAP-001" and occurrence == 2:
            entries = value["assessments"]["CHIP_MAKER"]
            if defect == "native":
                entries["finding101"]["reason"] = "원문 claim이 없다."
            elif defect == "public":
                entries["finding103"]["reason"] = "2031년에는 검증 준비 조건을 확인해야 한다."
            elif defect == "missing":
                del entries["finding103"]
            elif defect == "extra":
                entries["finding102"] = deepcopy(
                    provider.wire_payloads[0]["assessments"]["CHIP_MAKER"]["finding102"]
                )
        return value

    class Provider(V4Provider):
        def generate(self, **kwargs):
            response = super().generate(**kwargs)
            return (
                replace(response, truncated=True)
                if defect == "truncated" and len(self.calls) == 2
                else response
            )

    provider = Provider(
        source,
        relation="UNRELATED",
        hook=mixed_failures(reasons),
        wire_hook=wire_hook,
        validate_wire=False,
    )
    with pytest.raises(AgentError) as caught:
        generate(provider, source)
    assert caught.value.code == "SCHEMA_VIOLATION"
    assert stages(provider) == ["MAP-001", "MAP-001"]
    assert [f["id"] for f in framed(provider.calls[1]["prompt"])["findings"]] == [101, 103]
    assert caught.value.details["usage"] == {
        "inputTokens": 22,
        "outputTokens": 14,
        "costUsd": 0.006,
        "credits": 0.4,
    }


def test_native_partial_repair_uses_existing_stage_and_credit_limit():
    source, reasons, _ = partial_repair_fixture()
    provider = V4Provider(source, relation="UNRELATED", hook=mixed_failures(reasons))
    with pytest.raises(AgentError) as caught:
        generate(provider, source, AGENT_HARD_CAP_CREDITS_PER_REQUEST=0.35)
    assert caught.value.code == "BUDGET_EXCEEDED"
    assert stages(provider) == ["MAP-001", "MAP-001"]
    assert caught.value.details["usage"]["credits"] == 0.4


def test_native_failure_during_review_preserves_review_stage_and_repair_bound():
    source, reasons, _ = partial_repair_fixture()
    hook = mixed_failures(reasons, stage_name="REVIEW-001", permanent=True)
    provider = V4Provider(source, hook=hook)
    with pytest.raises(AgentError) as caught:
        generate(provider, source)
    assert caught.value.code == "SCHEMA_VIOLATION"
    assert stages(provider) == ["MAP-001", "MAP-002", "REVIEW-001", "REVIEW-001"]
    assert [f["id"] for f in framed(provider.calls[-1]["prompt"])["findings"]] == [101, 103]
    assert caught.value.details["usage"]["inputTokens"] == 44
