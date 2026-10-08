"""Recorded citation lists receive closed repair guidance without weaker validation."""

import pytest
from test_report_insight_assessment import request
from test_report_insight_repair_actions import structured_diagnostics
from test_report_insight_v4_pipeline import V4Provider, generate, stages

from app.core.errors import AgentError
from app.llm.report_insight_fact_repair import fact_repair_guidance, fact_repair_kinds


@pytest.mark.parametrize(
    "citation",
    [
        "(근거: 101:0, 101:4, 연결 문장)",
        "[근거: 101:0, 101:4]",
        "(101:0, 101:4)",
        "근거: 101:0, 101:4",
        "참조：101:0; 101:4",
        "원문(101:4)",
    ],
)
def test_selected_ids_in_recorded_lists_receive_value_free_internal_reference_guidance(citation):
    kinds = fact_repair_kinds(
        f"업무 연결을 확인한다. {citation}",
        "원문 사건은 공정 검증의 준비와 연결된다.",
        ["근거에서 확인되지 않는 숫자: 101"],
        refs=("101:4",),
    )
    assert kinds == ("internal_reference_in_prose", "unsupported_number")
    guidance = fact_repair_guidance(kinds)
    assert "ID는 basis의 구조화 필드에만" in guidance
    assert "101" not in guidance


@pytest.mark.parametrize(
    "value,source,refs",
    [
        ("(근거: 101:0, 101:4)", "다른 원문", ("101:3",)),
        ("(근거: 101:0, 101:4)", "문헌에 식별자 101:4가 있다.", ("101:4",)),
        ("(근거: 101:40)", "다른 원문", ("101:4",)),
        ("(근거: 101:4:1)", "다른 원문", ("101:4",)),
        ("(근거: 101:4x)", "다른 원문", ("101:4",)),
        ("(근거: x101:4)", "다른 원문", ("101:4",)),
        ("101:4", "다른 원문", ("101:4",)),
        ("101명이 근무한다.", "100명이 근무한다.", ("101:4",)),
    ],
)
def test_selected_list_classification_preserves_source_ids_and_requires_exact_citation(
    value, source, refs
):
    # A forged guard message cannot substitute for an actual citation in value.
    kinds = fact_repair_kinds(value, source, ["(근거: 101:4)"], refs=refs)
    assert "internal_reference_in_prose" not in kinds


@pytest.mark.parametrize("repair", [True, False])
def test_six_list_citations_are_repaired_once_or_still_rejected(repair):
    source = request(ids=tuple(range(101, 107)), text="도서관은 독서 모임의 참가 신청을 받는다.")
    clean = "원문 사건과 관점 업무의 연결 조건을 확인해야 한다."

    def hook(stage, occurrence, _, value):
        for record in value["assessments"]["CHIP_MAKER"].values():
            record["reason"] = (
                clean
                if repair and occurrence == 2
                else (f"업무 연결을 확인한다. (근거: {record['findingId']}:0, 연결 문장)")
            )
        return value

    provider = V4Provider(source, relation="UNRELATED", hook=hook)
    if repair:
        result = generate(provider, source)
        assert [entry.reason for entry in result.insights[0].assessments] == [clean] * 6
    else:
        with pytest.raises(AgentError) as caught:
            generate(provider, source)
        assert caught.value.code == "SCHEMA_VIOLATION"
    assert stages(provider) == ["MAP-001", "MAP-001"]
    diagnostic = (
        provider.calls[1]["prompt"].split("<validation-error>")[1].split("</validation-error>")[0]
    )
    rows = structured_diagnostics(provider.calls[1]["prompt"])
    for finding in source.findings:
        selected = [row for row in rows if row["field"] == f"assessments[{finding.id}].reason"]
        assert {row["rule"] for row in selected} == {
            "internal_reference_in_prose",
            "unsupported_number",
        }
        assert {row["errorKind"] for row in selected} == {
            "report_expression_policy",
            "report_evidence_insufficient",
        }
        assert all(row["claimIds"] == [f"{finding.id}:0"] for row in selected)
    assert len(diagnostic.strip()) <= 6000
