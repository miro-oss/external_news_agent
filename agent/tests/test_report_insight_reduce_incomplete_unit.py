"""Malformed REDUCE units retain diagnostics from their intact original fields."""

import json
from copy import deepcopy

import pytest
from pydantic import ValidationError
from test_report_insight_assessment import request
from test_report_insight_grounded_prose import _reduce_payload
from test_report_insight_reduce_partial_repair import (
    public_reduce_projection,
    repair_jobs,
    synthesis,
)
from test_report_insight_v4_pipeline import V4Provider, stages

from app.core.config import Settings
from app.core.errors import StructuredOutputExhaustedError
from app.llm import report_insight_service as service
from app.llm.report_insight_reduce_shape_scan import build_reduce_shape_scan
from app.llm.report_validation_diagnostics import (
    ReportValidationIssue,
    collect_report_validation_issues,
)
from app.llm.request_contract import report_insight_reduce_schema
from app.schemas.report_insight import ReportInsightReduceOutput


def scenario(mutate, *, sole=False, repair_text=None):
    source = request(ids=(101, 102))
    initial = []

    def hook(stage, occurrence, data, value):
        value = synthesis(stage, occurrence, data, value)
        if stage != "REDUCE-001":
            return value
        insight = value["insights"][0]
        if sole:
            insight["overview"] = insight["overview"][1:]
            insight["watchItems"] = []
        unit = insight["overview"][-1]
        if occurrence == 1:
            del unit["assumption"]
            mutate(unit, data)
            initial.append(deepcopy(value))
        elif repair_text is not None:
            unit["text"] = repair_text
        return value

    provider = V4Provider(source, hook=hook, validate_wire=False)
    engine = service.ReportInsightService(Settings(_env_file=None, AGENT_MOCK=False), provider)
    captured = []
    factory = engine._repair_call

    def capture(prompt, schema, raw, error, validate):
        before = prompt, deepcopy(schema), raw
        repair = factory(prompt, schema, raw, error, validate)
        assert (prompt, schema, raw) == before
        captured.append((error, repair))
        return repair

    engine._repair_call = capture
    return source, engine, provider, captured, initial


@pytest.mark.parametrize(
    ("text", "rule"),
    [
        ("TSMC의 생산 제약 영향을 확인한다.", "company"),
        ("생산 제약으로 999억원의 준비가 필요하다.", "unsupported_number"),
        (
            "생산 제약을 확인한다.",
            "report_fact_slot_unknown",
        ),
        ("보안 모듈의 검증 준비 조건을 확인한다.", "report_work_physical_module_unsupported"),
    ],
)
def test_missing_field_does_not_hide_other_field_failures_from_only_repair(text, rule):
    def mutate(unit, _):
        unit["text"] = text
        if rule == "report_fact_slot_unknown":
            unit["sourceQuotes"] = {"text": "source-000000000000000000000000", "assumption": None}

    source, engine, provider, captured, initial = scenario(mutate)
    before = source.model_dump_json()

    output = engine.generate(source)

    error, repair = captured[-1]
    assert {(issue.field, issue.rule) for issue in error.validation_issues} >= {
        ("overview[1].assumption", "schema_missing"),
        ("overview[1].text", rule),
    }
    (job,) = repair_jobs(repair.prompt)
    assert (job["group"], job["index"]) == ("overview", 1)
    assert rule in {rule["rule"] for d in job["diagnostics"] for rule in d["rules"]}
    assert error.partial_repair_eligible
    assert stages(provider) == ["MAP-001", "REVIEW-001", "REDUCE-001", "REDUCE-001"]
    assert output.insights[0].overview[0].model_dump(by_alias=True) == public_reduce_projection(
        initial[0]["insights"][0]["overview"][0]
    )
    assert public_reduce_projection(
        json.loads(provider.response_texts[-2])
    ) == public_reduce_projection(initial[0])
    assert source.model_dump_json() == before


@pytest.mark.parametrize("sole", [False, True])
def test_repairing_only_missing_field_still_fails_complete_revalidation(sole):
    text = "TSMC의 생산 제약 영향을 확인한다."
    source, engine, provider, captured, _ = scenario(
        lambda unit, _: unit.update(text=text), sole=sole, repair_text=text
    )

    with pytest.raises(StructuredOutputExhaustedError) as caught:
        engine.generate(source)

    assert stages(provider).count("REDUCE-001") == 2
    assert any(issue.rule == "company" for issue in captured[0][0].validation_issues)
    assert caught.value.status_code == 502
    failure = caught.value.details["validationFailure"]
    assert failure["attempt"] == 2 and failure["errorKinds"] == ["report_evidence_insufficient"]


def test_sole_incomplete_unit_keeps_real_diagnostics_in_whole_repair_without_synthetic_empty():
    source, engine, provider, captured, _ = scenario(
        lambda unit, _: unit.update(text="TSMC의 생산 제약 영향을 확인한다."), sole=True
    )

    output = engine.generate(source)

    error, repair = captured[-1]
    assert not error.partial_repair_eligible
    assert repair.response_schema["title"] == "ReportInsightReduceOutput"
    assert "report_synthesis_empty" not in error.error_kinds
    assert "report_output_unlocated" in error.error_kinds
    assert any(
        issue.field == "overview[0].text" and issue.claim_ids == ("101:0",)
        for issue in error.validation_issues
    )
    assert "선택 원문에서 해당 기업·기관을 확인할 수 없습니다." in repair.prompt
    assert (
        "TSMC의 생산 제약 영향을 확인한다." not in repair.prompt.split("<validation-error>", 1)[1]
    )
    assert output.insights[0].overview
    assert stages(provider).count("REDUCE-001") == 2


@pytest.mark.parametrize("refs", [None, [], [101], ["999:0"], ["101:0 "], ["101:0", "999:0"]])
def test_invalid_original_references_are_not_replaced_with_sibling_or_all_evidence(refs):
    def mutate(unit, _):
        unit["text"] = "TSMC의 생산 제약 영향을 확인한다."
        if refs is None:
            del unit["basisClaimIds"]
        else:
            unit["basisClaimIds"] = refs

    source, engine, _, captured, _ = scenario(mutate)
    engine.generate(source)
    error, _ = captured[-1]

    assert any(
        issue.field == "overview[1].basisClaimIds"
        and issue.error_kind == "report_synthesis_reference_gap"
        for issue in error.validation_issues
    )
    assert not any(issue.field == "overview[1].text" for issue in error.validation_issues)


def test_valid_original_template_does_not_treat_quoted_source_as_new_interpretation():
    def mutate(unit, data):
        slot = data["factTextSlots"]["CHIP_MAKER"][0]["slotId"]
        unit["text"] = "생산 제약에 따른 검증 준비 조건을 확인한다."
        unit["sourceQuotes"] = {"text": slot, "assumption": None}

    source, engine, _, captured, _ = scenario(mutate)
    engine.generate(source)
    error, _ = captured[-1]
    assert [(issue.field, issue.rule) for issue in error.validation_issues] == [
        ("overview[1].assumption", "schema_missing")
    ]


@pytest.mark.parametrize("repair_falsifier", [False, True])
def test_incomplete_implication_keeps_falsifier_quality_error_before_only_retry(repair_falsifier):
    source = request()

    def hook(stage, occurrence, _, value):
        if stage != "REDUCE-001":
            return value
        unit = {
            "text": "생산 제약이 지속되면 준비 일정의 영향을 확인해야 한다.",
            "mechanism": "같은 생산 제약이 준비 일정에 영향을 미칠 경우 준비를 검토한다.",
            "assumption": "생산 제약이 유지되는 경우",
            "falsifiedBy": "생산라인의 가동이 재개되는 경우",
            "basisClaimIds": ["101:0"],
        }
        if occurrence == 1 or not repair_falsifier:
            unit["falsifiedBy"] = "추가 근거가 없으면 해석을 바꾼다."
        if occurrence == 1:
            del unit["assumption"]
            unit["text"] = "TSMC의 생산 제약이 준비 일정에 영향을 줄 수 있다."
        value["insights"][0]["implications"] = [unit]
        return value

    provider = V4Provider(source, hook=hook, validate_wire=False)
    engine = service.ReportInsightService(Settings(_env_file=None, AGENT_MOCK=False), provider)
    if repair_falsifier:
        output = engine.generate(source)
        assert output.insights[0].implications[0].falsified_by == "생산라인의 가동이 재개되는 경우"
    else:
        output = engine.generate(source)
        assert output.insights[0].overview
        assert output.insights[0].implications == []
    assert stages(provider).count("REDUCE-001") == 2
    (job,) = repair_jobs(provider.calls[-1]["prompt"])
    assert (job["group"], job["index"]) == ("implications", 0)
    assert {(item["field"], item["errorKind"]) for item in job["diagnostics"]} >= {
        ("implications[0].assumption", "report_output_shape"),
        ("implications[0].text", "report_evidence_insufficient"),
        ("implications[0].falsifiedBy", "report_falsification_missing_observation"),
    }


def test_raw_scalar_projection_keeps_original_scope_without_filling_missing_fields():
    source = request()
    raw = _reduce_payload()
    unit = raw["insights"][0]["overview"][0]
    del unit["assumption"]
    unit["text"] = "TSMC의 생산 제약 영향을 확인한다."
    before = deepcopy(raw)
    schema = report_insight_reduce_schema(source, {"CHIP_MAKER": ("101:0",)})
    with pytest.raises(ValidationError) as caught:
        ReportInsightReduceOutput.model_validate(raw)
    issues = collect_report_validation_issues(caught.value, schema, stage="REDUCE")

    scan = build_reduce_shape_scan(raw, issues, source.audiences)

    (record,) = scan.incomplete_units
    assert (record.audience, record.group, record.index, record.claim_ids) == (
        "CHIP_MAKER",
        "overview",
        0,
        ("101:0",),
    )
    assert record.prose == (("text", unit["text"], 600),)
    assert scan.candidate.insights[0].overview == []
    assert raw == before
    stale = ReportValidationIssue("CHIP_MAKER", "overview[1].assumption", "report_output_shape", ())
    assert build_reduce_shape_scan(raw, (stale,), source.audiences) is None
    unknown = deepcopy(raw)
    unknown["untrusted"] = "overview[0].text"
    assert build_reduce_shape_scan(unknown, issues, source.audiences) is None
