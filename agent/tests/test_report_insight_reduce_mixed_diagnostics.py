"""One bounded REDUCE repair receives every independent field failure."""

from copy import deepcopy

import pytest
from test_report_insight_assessment import request
from test_report_insight_grounded_prose import SOURCE, _provider_response, _reduce_payload
from test_report_insight_reduce_partial_repair import (
    public_reduce_projection,
    repair_jobs,
    synthesis,
)
from test_report_insight_v4_pipeline import V4Provider, stages

from app.core.config import Settings
from app.core.errors import StructuredOutputExhaustedError
from app.llm import report_insight_service as service

MIXED_FAILURE = "이전 보고서보다 TSMC의 생산 제약 영향이 커졌다."


@pytest.mark.parametrize("field", ["headline", "text", "assumption"])
def test_each_prose_field_reports_comparison_and_company_failures(field):
    source = request()
    value = _reduce_payload()
    insight = value["insights"][0]
    target = insight if field == "headline" else insight["overview"][0]
    target[field] = MIXED_FAILURE

    error = service._reduce_repair_diagnostics(
        _provider_response(value), source, {"CHIP_MAKER": ["101:0"]}
    )

    expected_field = "headline" if field == "headline" else f"overview[0].{field}"
    assert {(issue.field, issue.rule) for issue in error.validation_issues} == {
        (expected_field, "report_synthesis_invalid"),
        (expected_field, "company"),
    }
    assert error.fact_repair_kinds == ("company",)
    assert error.partial_repair_eligible


def mixed_scenario(*, shape=False, repair_first_only=False):
    source = request(ids=(101, 102), text=SOURCE)
    original = []

    def hook(stage, occurrence, data, value):
        value = synthesis(stage, occurrence, data, value)
        if stage != "REDUCE-001":
            return value
        units = value["insights"][0]["overview"]
        units[0]["text"] = "삼성전자의 HBM4 생산 제약과 공정 검증 조건을 확인한다."
        if shape:
            units.append(deepcopy(units[1]))
        if occurrence == 1:
            units[-1]["text"] = MIXED_FAILURE
            if shape:
                del units[1]["assumption"]
            original.append(deepcopy(value))
        elif repair_first_only:
            units[-1]["text"] = "TSMC의 생산 제약 영향을 확인한다."
        return value

    provider = V4Provider(source, hook=hook, validate_wire=not shape)
    engine = service.ReportInsightService(Settings(_env_file=None, AGENT_MOCK=False), provider)
    captured = []
    factory = engine._repair_call

    def capture(prompt, schema, raw, error, validate):
        captured.append(error)
        return factory(prompt, schema, raw, error, validate)

    engine._repair_call = capture
    return source, engine, provider, captured, original


def test_single_bad_unit_has_both_diagnostics_before_only_retry_and_preserves_good_units():
    source, engine, provider, captured, original = mixed_scenario()
    before_source = source.model_dump_json()

    output = engine.generate(source)

    assert stages(provider) == ["MAP-001", "REVIEW-001", "REDUCE-001", "REDUCE-001"]
    (job,) = repair_jobs(provider.calls[-1]["prompt"])
    assert (job["group"], job["index"]) == ("overview", 1)
    assert {diagnostic["field"] for diagnostic in job["diagnostics"]} == {"overview[1].text"}
    assert {rule["rule"] for d in job["diagnostics"] for rule in d["rules"]} == {
        "report_synthesis_invalid",
        "company",
    }
    assert captured[-1].fact_repair_kinds == ("company",)
    initial = original[0]["insights"][0]
    final = output.insights[0].model_dump(by_alias=True)
    assert final["overview"][0] == public_reduce_projection(initial["overview"][0])
    assert final["headline"] == initial["headline"]
    assert final["watchItems"] == public_reduce_projection(initial["watchItems"])
    assert "TSMC" not in output.model_dump_json()
    assert source.model_dump_json() == before_source


def test_fixing_only_first_error_is_rejected_by_full_revalidation():
    source, engine, provider, _, _ = mixed_scenario(repair_first_only=True)

    with pytest.raises(StructuredOutputExhaustedError) as caught:
        engine.generate(source)

    assert stages(provider).count("REDUCE-001") == 2
    failure = caught.value.details["validationFailure"]
    assert caught.value.status_code == 502
    assert failure["attempt"] == 2
    assert failure["errorKinds"] == ["report_evidence_insufficient"]
    assert failure["issues"][0]["field"] == "overview[1].text"


def test_shape_merge_keeps_fact_rule_union_and_original_partial_repair_locations(caplog):
    source, engine, provider, captured, original = mixed_scenario(shape=True)

    output = engine.generate(source)

    error = captured[-1]
    assert error.fact_repair_kinds == ("company",)
    assert "validationRules=('company',)" in caplog.text
    jobs = repair_jobs(provider.calls[-1]["prompt"])
    assert {(job["group"], job["index"]) for job in jobs} == {("overview", 1), ("overview", 2)}
    assert {(issue.field, issue.rule) for issue in error.validation_issues} == {
        ("overview[1].assumption", "schema_missing"),
        ("overview[2].text", "report_synthesis_invalid"),
        ("overview[2].text", "company"),
    }
    initial_good_unit = original[0]["insights"][0]["overview"][0]
    assert output.insights[0].overview[0].model_dump(by_alias=True) == public_reduce_projection(
        initial_good_unit
    )
