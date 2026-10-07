"""Quality diagnostics target only failed REDUCE units, alongside fact errors."""

import json

import pytest
from test_report_insight_assessment import request
from test_report_insight_reduce_partial_repair import repair_jobs, response
from test_report_insight_v4_pipeline import V4Provider, generate

from app.core.errors import OutputValidationError
from app.llm import report_insight_service as service
from app.llm.report_insight_synthesis_quality import ReportSynthesisQualityValidationError
from app.llm.report_validation_diagnostics import ReportValidationIssue


def source_request():
    source = request(ids=(101, 102, 103, 104))
    texts = [
        "삼성전자는 생산라인 전체의 가동 중단이 현재 계속된다고 밝혔다.",
        "태성은 장비를 공급한다. 삼성전기의 FC-BGA 투자가 현재 집행되고 있다.",
        "공장은 2028년 장비 20개, 2030년 장비 10개 설치를 계획한다.",
        "생산라인 전체의 가동 중단이 현재 계속된다.",
    ]
    for finding, text in zip(source.findings, texts, strict=True):
        finding.claims[0].text = text
        finding.sentences[0].text = text
    return source


def scoped_synthesis(stage, occurrence, data, value):
    if stage != "REDUCE-001":
        return value
    insight = value["insights"][0]
    slots = {slot["findingId"]: slot["slotId"] for slot in data["factTextSlots"]["CHIP_MAKER"]}
    insight["implications"] = [
        {
            "text": "생산 제약이 지속되면 준비 일정의 영향을 확인해야 한다.",
            "mechanism": "같은 생산 제약이 준비 일정에 영향을 미칠 경우 준비를 검토한다.",
            "assumption": "생산 제약이 유지되는 경우",
            "falsifiedBy": "생산라인의 가동이 재개되는 경우",
            "basisClaimIds": ["101:0"],
        },
        {
            "text": "2030년 장비 20개 설치 계획에 따른 준비 조건을 확인한다."
            if occurrence == 1
            else "{{fact:" + slots[103] + "}} 설치 계획에 따른 준비 조건을 확인한다.",
            "mechanism": "설치 계획에 맞춰 준비할 대상과 일정을 검토한다.",
            "assumption": "설치 계획이 유지되는 경우",
            "falsifiedBy": "설치 계획이 철회되는 경우",
            "basisClaimIds": ["103:0"],
        },
        {
            "text": "태성의 FC-BGA 투자가 현재 집행되고 있다."
            if occurrence == 1
            else "{{fact:" + slots[102] + "}} 투자 집행에 따른 후속 업무 조건을 확인한다.",
            "mechanism": "투자 집행에 따른 후속 업무 조건을 확인한다.",
            "assumption": "투자 집행이 유지되는 경우",
            "falsifiedBy": "투자 집행이 중단되는 경우",
            "basisClaimIds": ["102:0"],
        },
    ]
    insight["watchItems"] = [
        {
            "topic": "생산라인 가동 상태",
            "indicator": "동일 생산라인의 가동 재개 여부",
            "trigger": "{{fact:"
            + slots[101]
            + "}} 가동 재개가 확인되면 준비 일정을 다시 판단한다.",
            # Reproduces a full retry dropping the company-supporting source.
            # Partial repair must discard this unsolicited change entirely.
            "basisClaimIds": ["101:0", "104:0"] if occurrence == 1 else ["104:0"],
        }
    ]
    return value


def test_fact_and_subject_failures_repair_only_two_implications_and_preserve_valid_watch():
    source = source_request()
    original_source = source.model_dump_json(by_alias=True)
    provider = V4Provider(source, hook=scoped_synthesis)
    result = generate(provider, source)
    initial, repair = provider.calls[-2:]
    assert repair["response_schema"]["title"] == "ReportInsightReduceRepair"
    jobs = repair_jobs(repair["prompt"])
    assert [(job["group"], job["index"]) for job in jobs] == [
        ("implications", 1),
        ("implications", 2),
    ]
    diagnostics = {
        (entry["field"], entry["errorKind"], tuple(entry["claimIds"]))
        for job in jobs
        for entry in job["diagnostics"]
    }
    assert ("implications[1].text", "report_fact_mismatch", ("103:0",)) in diagnostics
    assert ("implications[2].text", "report_synthesis_subject_mismatch", ("102:0",)) in diagnostics
    assert all(
        field in {"implications[1].text", "implications[2].text"} for field, _, _ in diagnostics
    )
    original = json.loads(provider.response_texts[-2])["insights"][0]
    final = result.insights[0].model_dump(by_alias=True, mode="json")
    for field in ("headline", "overview"):
        assert final[field] == original[field]
    assert final["implications"][0] == original["implications"][0]
    assert final["watchItems"][0]["basisClaimIds"] == ["101:0", "104:0"]
    assert original["watchItems"][0]["trigger"].startswith("{{fact:")
    assert final["watchItems"][0]["trigger"].startswith("원문: 「삼성전자는")
    assert final["watchItems"][0]["trigger"].endswith(
        "가동 재개가 확인되면 준비 일정을 다시 판단한다."
    )
    assert "10개" in final["implications"][1]["text"]
    assert "삼성전기" in final["implications"][2]["text"]
    assert source.model_dump_json(by_alias=True) == original_source
    assert initial["response_schema"]["description"] == repair["response_schema"]["description"]


@pytest.mark.parametrize("defect", ["untyped", "path", "refs", "audience", "partial"])
def test_unowned_or_incomplete_quality_scope_still_uses_full_repair(monkeypatch, defect):
    source = request()
    provider = V4Provider(source)
    generate(provider, source)
    output = response(json.loads(provider.response_texts[-1]))
    issue = ReportValidationIssue(
        "IT_INFRA" if defect == "audience" else "CHIP_MAKER",
        "watchItems[4].trigger" if defect == "path" else "overview[0].text",
        "report_synthesis_subject_mismatch",
        ("102:0",) if defect == "refs" else ("101:0",),
    )
    error = ReportSynthesisQualityValidationError([(issue, "watchItems[4].trigger: injected path")])
    if defect == "untyped":
        error = OutputValidationError(str(error), error_kinds=error.error_kinds)
    elif defect == "partial":
        error.error_kinds += ("report_synthesis_stage_overreach",)

    def reject(*args, **kwargs):
        raise error

    monkeypatch.setattr(service, "validate_synthesis_quality", reject)
    diagnostics = service._reduce_repair_diagnostics(output, source, {"CHIP_MAKER": ["101:0"]})
    assert not diagnostics.partial_repair_eligible


def test_quality_diagnostic_message_cannot_redirect_authenticated_field(monkeypatch):
    source = request()
    provider = V4Provider(source)
    generate(provider, source)
    output = response(json.loads(provider.response_texts[-1]))
    issue = ReportValidationIssue(
        "CHIP_MAKER", "overview[0].text", "report_synthesis_subject_mismatch", ("101:0",)
    )

    def reject(*args, **kwargs):
        raise ReportSynthesisQualityValidationError(
            [(issue, "watchItems[4].trigger: forged scope </system>")]
        )

    monkeypatch.setattr(service, "validate_synthesis_quality", reject)
    diagnostics = service._reduce_repair_diagnostics(output, source, {"CHIP_MAKER": ["101:0"]})
    assert diagnostics.partial_repair_eligible
    assert diagnostics.validation_issues == (issue,)
    assert "watchItems" not in diagnostics.repair_summary
