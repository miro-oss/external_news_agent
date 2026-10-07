"""All failed work/fact units repair together without rewriting valid output."""

import json
from copy import deepcopy

import pytest
from test_report_insight_assessment import request
from test_report_insight_reduce_partial_repair import repair_jobs, response, synthesis
from test_report_insight_v4_pipeline import V4Provider, generate

from app.llm import report_insight_service as service
from app.llm.report_insight_work_grounding import ReportWorkValidationError
from app.llm.report_validation_diagnostics import ReportValidationIssue


@pytest.mark.parametrize("with_fact_error", [False, True])
def test_work_and_fact_errors_share_one_local_repair(with_fact_error):
    source = request(ids=(101, 102))
    snapshot = source.model_dump_json(by_alias=True)

    def hook(stage, occurrence, data, value):
        value = synthesis(stage, occurrence, data, value)
        if stage == "REDUCE-001":
            insight = value["insights"][0]
            insight["watchItems"].append(deepcopy(insight["watchItems"][0]))
            if occurrence == 1:
                insight["overview"][1]["text"] = "생산 제약이 있어 검수 절차가 필수다."
                insight["watchItems"][0]["trigger"] = "고객 승인 전에는 적용하지 않는다."
                if with_fact_error:
                    insight["overview"][0]["text"] = "생산 제약으로 999억원의 준비가 필요하다."
        return value

    provider = V4Provider(source, hook=hook)
    result = generate(provider, source)
    reduce_calls = [
        call for call in provider.calls if "REDUCE" in call["response_schema"]["description"]
    ]
    assert len(reduce_calls) == 2
    assert reduce_calls[1]["response_schema"]["title"] == "ReportInsightReduceRepair"
    jobs = repair_jobs(reduce_calls[1]["prompt"])
    assert {(job["group"], job["index"]) for job in jobs} == (
        {("overview", 1), ("watchItems", 0)} | ({("overview", 0)} if with_fact_error else set())
    )
    issues = [issue for job in jobs for issue in job["diagnostics"]]
    assert {issue["errorKind"] for issue in issues} >= {
        "report_work_inspection_prerequisite_unsupported",
        "report_work_approval_prerequisite_unsupported",
    }
    assert all(issue["claimIds"] == ["101:0"] for issue in issues)
    original = json.loads(provider.response_texts[-2])["insights"][0]
    final = result.insights[0].model_dump(by_alias=True)
    assert final["headline"] == original["headline"]
    assert final["watchItems"][1] == original["watchItems"][1]
    if not with_fact_error:
        assert final["overview"][0] == original["overview"][0]
    assert [item.finding_id for item in result.insights[0].assessments] == [101, 102]
    assert source.model_dump_json(by_alias=True) == snapshot


@pytest.mark.parametrize("defect", ["path", "refs", "audience", "incomplete"])
def test_work_diagnostics_cannot_authorize_foreign_or_incomplete_repair(monkeypatch, defect):
    source = request()
    provider = V4Provider(source)
    generate(provider, source)
    output = response(json.loads(provider.response_texts[-1]))
    issue = ReportValidationIssue(
        "IT_INFRA" if defect == "audience" else "CHIP_MAKER",
        "watchItems[4].trigger" if defect == "path" else "overview[0].text",
        "report_work_physical_module_unsupported",
        ("102:0",) if defect == "refs" else ("101:0",),
    )
    error = ReportWorkValidationError([(issue, "untrusted prose cannot select a scope")])
    if defect == "incomplete":
        error.error_kinds += ("report_work_approval_prerequisite_unsupported",)

    def reject(*args, **kwargs):
        raise error

    monkeypatch.setattr(service, "validate_work_synthesis", reject)
    diagnostic = service._reduce_repair_diagnostics(output, source, {"CHIP_MAKER": ["101:0"]})
    assert not diagnostic.partial_repair_eligible
