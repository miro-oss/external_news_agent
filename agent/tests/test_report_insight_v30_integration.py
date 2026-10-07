"""Production wiring for cached source proposals and complete partial repair scans."""

import json
from copy import deepcopy

import pytest
from test_report_insight_assessment import framed, request
from test_report_insight_reduce_partial_repair import repair_jobs, synthesis
from test_report_insight_v4_pipeline import V4Provider, generate, stages

from app.core.config import Settings
from app.llm.report_insight_service import ReportInsightService
from app.llm.report_insight_source_extraction import (
    prepare_source_extraction_batches,
    save_source_proposal_cache,
    validate_source_extraction,
)


def cached_source(tmp_path):
    source = request()
    (batch,) = prepare_source_extraction_batches(source, model="offline-model")

    def choose(quote):
        return {"quote": quote, "occurrence": 0}

    role = {
        "scope": choose(batch.records[0].text),
        "subjects": [choose("제조사")],
        "subjectMode": "single",
        "event": choose("가동 중단"),
        "target": choose("생산라인"),
        "quantity": None,
        "unit": None,
        "time": choose("현재"),
        "state": None,
        "attribution": None,
        "comparator": None,
        "uncertainty": ["state_ambiguous"],
    }
    raw = json.dumps({"records": {batch.records[0].evidence_id: {"relations": [role]}}})
    saved = save_source_proposal_cache(tmp_path, batch, validate_source_extraction(batch, raw))
    return source, saved


@pytest.mark.parametrize("corrupt", [False, True])
def test_service_reads_original_source_cache_without_extra_provider_calls(
    tmp_path, caplog, corrupt
):
    source, saved = cached_source(tmp_path)
    snapshot = source.model_dump_json()
    if corrupt:
        saved.write_text('{"damaged":true}')
    provider = V4Provider(source)
    settings = Settings(
        _env_file=None,
        AGENT_MOCK=False,
        AGENT_REPORT_INSIGHT_SOURCE_CACHE_DIR=str(tmp_path),
        AGENT_REPORT_INSIGHT_SOURCE_EXTRACTION_MODEL="offline-model",
    )
    output = ReportInsightService(settings, provider).generate(source)
    assert stages(provider) == ["MAP-001", "REVIEW-001", "REDUCE-001"]
    assert output.meta.prompt_version == "report-insight.ko.v30"
    for call in provider.calls:
        data = framed(call["prompt"])
        index = (
            data["sourceFactIndex"]["CHIP_MAKER"]
            if "sourceFactIndex" in data
            else data["findings"][0]["sourceFactIndex"]
        )
        assert len(index["roleProposals"]) == (0 if corrupt else 1)
        if not corrupt:
            assert index["roleProposals"][0]["authority"] == "source_anchored_role_proposal_only"
    assert ("INVALID_IGNORED" in caplog.text) is corrupt
    assert source.model_dump_json() == snapshot


@pytest.mark.parametrize("first_defect", ["shape", "foreign_reference"])
def test_shape_or_foreign_reference_cannot_hide_another_bad_fact(first_defect):
    source = request(ids=(101, 102))
    initial = []

    def hook(stage, occurrence, data, value):
        value = synthesis(stage, occurrence, data, value)
        if stage != "REDUCE-001":
            return value
        units = value["insights"][0]["overview"]
        third = deepcopy(units[0])
        third["text"] = "생산 제약의 지속 여부에 따라 검증 준비 순서를 조정한다."
        units.append(third)
        if occurrence == 1:
            units[2]["text"] = "생산 제약으로 999억원의 준비가 필요하다."
            if first_defect == "shape":
                del units[1]["assumption"]
            else:
                units[1]["basisClaimIds"] = ["999:0"]
            initial.append(deepcopy(units[0]))
        return value

    provider = V4Provider(source, hook=hook, validate_wire=False)
    output = generate(provider, source)
    repair = provider.calls[-1]
    assert stages(provider) == ["MAP-001", "REVIEW-001", "REDUCE-001", "REDUCE-001"]
    assert repair["response_schema"]["title"] == "ReportInsightReduceRepair"
    jobs = repair_jobs(repair["prompt"])
    assert {(job["group"], job["index"]) for job in jobs} == {("overview", 1), ("overview", 2)}
    assert output.insights[0].overview[0].model_dump(by_alias=True) == initial[0]
    assert "999" not in output.model_dump_json()
    assert all(unit.assumption for unit in output.insights[0].overview)
