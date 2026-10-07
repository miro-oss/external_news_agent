"""Identical REDUCE citations normalize without changing evidence or skipping guards."""

import json
from copy import deepcopy

import pytest
from test_report_insight_assessment import framed, request
from test_report_insight_reduce_partial_repair import repair_jobs, response, synthesis
from test_report_insight_v4_pipeline import V4Provider, generate, stages

from app.core.config import Settings
from app.llm.report_insight_service import (
    ReportInsightService,
    _reduce_repair_diagnostics,
    _validated_reduce_output,
)
from app.schemas.report_insight import ReportInsightMapOutput


def complete_synthesis(stage, occurrence, data, value):
    value = synthesis(stage, occurrence, data, value)
    if stage == "REDUCE-001":
        value["insights"][0]["implications"] = [
            {
                "text": "생산 제약의 지속은 준비 일정 판단에 영향을 줄 수 있다.",
                "mechanism": "생산라인 가동 중단 → 생산 제약 지속 → 준비 일정 판단 필요",
                "basisClaimIds": ["101:0"],
                "assumption": "같은 생산 제약이 준비 일정에 영향을 주는 경우",
                "falsifiedBy": "같은 생산라인의 가동 재개가 확인되는 경우",
            }
        ]
    return value


def prepared():
    source = request(ids=(101, 102))
    provider = V4Provider(source, hook=complete_synthesis)
    result = generate(provider, source)
    mapped = ReportInsightMapOutput(
        insights=[
            {"audience": insight.audience, "assessments": insight.assessments}
            for insight in result.insights
        ]
    )
    allowed = {
        group["audience"]: [item["claimId"] for item in group["evidence"]]
        for group in framed(provider.calls[-1]["prompt"])["retrievedEvidence"]
    }
    return source, mapped, allowed, json.loads(provider.response_texts[-1])


def test_repeated_valid_reduce_refs_keep_first_order_without_provider_retry_or_map_changes():
    source = request(ids=(101, 102))
    source_before = source.model_dump_json(by_alias=True)

    def hook(stage, occurrence, data, value):
        value = complete_synthesis(stage, occurrence, data, value)
        if stage == "REDUCE-001":
            for group in ("overview", "implications", "watchItems"):
                for item in value["insights"][0][group]:
                    item["basisClaimIds"] = ["102:0", "101:0", "102:0", "101:0"]
        return value

    provider = V4Provider(source, hook=hook)
    result = generate(provider, source)
    assert stages(provider) == ["MAP-001", "REVIEW-001", "REDUCE-001"]
    original_text = provider.response_texts[-1]
    original = json.loads(original_text)["insights"][0]
    final = result.insights[0].model_dump(by_alias=True)
    for group in ("overview", "implications", "watchItems"):
        for before, after in zip(original[group], final[group], strict=True):
            assert before["basisClaimIds"] == ["102:0", "101:0", "102:0", "101:0"]
            assert after == {**before, "basisClaimIds": ["102:0", "101:0"]}
    assert final["headline"] == original["headline"]
    assert [item.finding_id for item in result.insights[0].assessments] == [101, 102]
    assert [item.basis_claim_ids for item in result.insights[0].assessments] == [
        ["101:0"],
        ["102:0"],
    ]
    assert provider.response_texts[-1] == original_text
    assert source.model_dump_json(by_alias=True) == source_before


@pytest.mark.parametrize(
    "refs",
    [
        ["101:0", "101:0", "999:0"],
        ["101:0", "101:0", 101],
        ["101:0", "101:0", None],
        ["101:0", "101:0", {"claimId": "101:0"}],
    ],
)
def test_duplicate_normalization_does_not_hide_unknown_or_malformed_references(refs):
    source, mapped, allowed, value = prepared()
    value["insights"][0]["overview"][0]["basisClaimIds"] = refs
    raw = response(value)
    before = (source.model_dump_json(), mapped.model_dump_json(), raw.text)
    with pytest.raises(ValueError):
        _validated_reduce_output(raw, source, mapped, allowed)
    diagnostics = _reduce_repair_diagnostics(raw, source, allowed)
    if all(isinstance(ref, str) for ref in refs):
        assert diagnostics is not None
        assert diagnostics.partial_repair_eligible
        assert any(
            issue.field == "overview[0].basisClaimIds"
            for issue in diagnostics.validation_issues
        )
        assert all("999:0" not in issue.claim_ids for issue in diagnostics.validation_issues)
    else:
        assert diagnostics is None
    assert (source.model_dump_json(), mapped.model_dump_json(), raw.text) == before


def test_duplicate_normalization_keeps_unrelated_prose_validation_and_input_snapshots():
    source, mapped, allowed, value = prepared()
    value["insights"][0]["overview"][0].update(
        basisClaimIds=["101:0", "101:0"], text="생산 제약으로 999억원이 필요하다."
    )
    raw = response(value)
    before = deepcopy((source.model_dump(), mapped.model_dump(), allowed, raw.text))
    with pytest.raises(ValueError, match="숫자: 999"):
        _validated_reduce_output(raw, source, mapped, allowed)
    assert (source.model_dump(), mapped.model_dump(), allowed, raw.text) == before


@pytest.mark.parametrize("defect", ["fact", "work", "both"])
def test_duplicate_refs_with_other_errors_repair_only_failed_units_and_keep_original_context(
    defect,
):
    source = request(ids=(101, 102))
    before_source = source.model_dump_json(by_alias=True)

    def hook(stage, occurrence, data, value):
        value = complete_synthesis(stage, occurrence, data, value)
        if stage == "REDUCE-001":
            insight = value["insights"][0]
            for group in ("overview", "implications", "watchItems"):
                for item in insight[group]:
                    item["basisClaimIds"] = ["102:0", "101:0", "102:0", "101:0"]
            if occurrence == 1:
                insight["overview"][1]["text"] = (
                    "생산 제약으로 검수 절차가 필수다."
                    if defect == "work"
                    else "생산 제약으로 999억원이 필요하다."
                )
                if defect == "both":
                    insight["watchItems"][0]["trigger"] = "고객 승인 전에는 적용하지 않는다."
        return value

    provider = V4Provider(source, hook=hook)
    engine = ReportInsightService(Settings(_env_file=None, AGENT_MOCK=False), provider)
    captured = []
    factory = engine._repair_call

    def capture(prompt, schema, raw, error, validate):
        captured.append((raw, error))
        return factory(prompt, schema, raw, error, validate)

    engine._repair_call = capture
    result = engine.generate(source)
    assert stages(provider) == ["MAP-001", "REVIEW-001", "REDUCE-001", "REDUCE-001"]
    assert provider.calls[-1]["response_schema"]["title"] == "ReportInsightReduceRepair"
    jobs = repair_jobs(provider.calls[-1]["prompt"])
    assert {(job["group"], job["index"]) for job in jobs} == (
        {("overview", 1)} | ({("watchItems", 0)} if defect == "both" else set())
    )
    assert all(
        issue["claimIds"] == ["102:0", "101:0"] for job in jobs for issue in job["diagnostics"]
    )
    raw, error = captured[-1]
    original = json.loads(raw)["insights"][0]
    assert error.repair_context.original == json.loads(raw)
    assert raw == provider.response_texts[-2]
    assert all(
        job["original"] == original[job["group"]][job["index"]]
        and job["original"]["basisClaimIds"] == ["102:0", "101:0", "102:0", "101:0"]
        for job in jobs
    )
    final = result.insights[0].model_dump(by_alias=True)
    assert final["headline"] == original["headline"]
    assert final["overview"][0] == {
        **original["overview"][0],
        "basisClaimIds": ["102:0", "101:0"],
    }
    assert final["implications"][0] == {
        **original["implications"][0],
        "basisClaimIds": ["102:0", "101:0"],
    }
    assert [item.basis_claim_ids for item in result.insights[0].assessments] == [
        ["101:0"],
        ["102:0"],
    ]
    assert source.model_dump_json(by_alias=True) == before_source
