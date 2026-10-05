"""One repair receives independent native and public defects in the same record."""

import json

import pytest
from test_report_insight_assessment import framed, payload, request
from test_report_insight_v4_pipeline import V4Provider, generate, stages

from app.core.errors import AgentError
from app.llm.base import ProviderResponse, ProviderUsage
from app.llm.report_insight_assessment import draft_to_wire
from app.llm.report_insight_service import _native_assessment_repair_errors


def test_native_semantic_failure_does_not_hide_same_finding_fact_mismatch():
    source = request(ids=(101, 102))
    value = payload(source)
    value["assessments"]["CHIP_MAKER"]["finding101"]["reason"] = (
        "영향 범위는 미확인이다. 9999년의 공정 검증 업무를 검토해야 한다."
    )
    wire = draft_to_wire(value, source)
    response = ProviderResponse(
        json.dumps(wire, ensure_ascii=False), "mock", "offline", ProviderUsage()
    )

    error = _native_assessment_repair_errors(response, source)

    assert error.failed_finding_ids == (101,)
    assert error.error_kinds == ("report_assessment_draft_invalid", "report_fact_mismatch")
    assert "effect.impactScope" in str(error)
    assert "근거에서 확인되지 않는 숫자: 9999" in str(error)
    assert json.loads(response.text) == wire


def test_diagnostic_projection_cannot_resolve_a_foreign_source_handle():
    source = request(ids=(101, 102))
    value = draft_to_wire(payload(source), source)
    item = value["assessments"]["CHIP_MAKER"]["finding101"]
    item["decision"]["connection"]["basis"]["sourceSpanId"] = "s9999_0_0"
    item["reason"] = "9999년의 공정 검증 업무를 검토해야 한다."
    response = ProviderResponse(
        json.dumps(value, ensure_ascii=False), "mock", "offline", ProviderUsage()
    )

    error = _native_assessment_repair_errors(response, source)

    assert error.failed_finding_ids == (101,)
    assert error.error_kinds == ("report_assessment_draft_invalid",)
    assert "sourceSpanId" in str(error)


@pytest.mark.parametrize("retained_defect", [None, "native", "public"])
def test_partial_repair_gets_both_causes_and_full_validation_still_gates_acceptance(
    retained_defect,
):
    source = request(
        ids=(101, 102), text="도서관은 독서 모임의 참가 신청이 현재 계속된다고 밝혔다."
    )
    reasons = {
        101: "관점 업무와의 연결 조건을 확인해야 한다.",
        102: "원문 사건과 관점 업무의 연결 여부를 검토해야 한다.",
    }

    def hook(stage, occurrence, _, value):
        for record in value["assessments"]["CHIP_MAKER"].values():
            record["reason"] = reasons[record["findingId"]]
        if stage == "MAP-001":
            item = value["assessments"]["CHIP_MAKER"]["finding101"]
            if occurrence == 1:
                item["reason"] = "제공된 claim이 없다. 9999년의 업무 연결 조건을 검토해야 한다."
            elif retained_defect == "native":
                item["reason"] = "제공된 claim이 없다."
            elif retained_defect == "public":
                item["reason"] = "9999년의 업무 연결 조건을 검토해야 한다."
        return value

    provider = V4Provider(source, relation="UNRELATED", hook=hook)
    if retained_defect is None:
        result = generate(provider, source)
        assert [item.reason for item in result.insights[0].assessments] == list(reasons.values())
        assert result.meta.input_tokens == 22
        assert result.meta.output_tokens == 14
        assert result.meta.credits == 0.4
    else:
        with pytest.raises(AgentError) as caught:
            generate(provider, source)
        assert caught.value.code == "SCHEMA_VIOLATION"
        assert caught.value.details["usage"]["inputTokens"] == 22
        assert caught.value.details["usage"]["outputTokens"] == 14

    assert stages(provider) == ["MAP-001", "MAP-001"]
    assert provider.schema_validity == [True, True]
    repair = provider.calls[1]
    assert [finding["id"] for finding in framed(repair["prompt"])["findings"]] == [101]
    assert "reason: 원문 claim이 존재합니다." in repair["prompt"]
    assert "근거에서 확인되지 않는 숫자: 9999" in repair["prompt"]
    assert (
        framed(repair["prompt"])["findings"][0]
        == framed(provider.calls[0]["prompt"])["findings"][0]
    )
    assert "<invalid-output>" not in repair["prompt"]
