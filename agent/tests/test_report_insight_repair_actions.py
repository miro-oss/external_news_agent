"""Repair actions retain owned paths without teaching the rejected factual text."""

import json
from copy import deepcopy
from dataclasses import replace

import pytest
from test_report_insight_assessment import response
from test_report_insight_native_field_diagnostics import (
    conditional_payload,
    recorded_source,
    rejected_projection,
)

from app.core.errors import OutputValidationError
from app.llm import report_insight_service as service
from app.llm.report_insight_assessment import draft_prompt, validate_draft


def diagnostics(prompt):
    return prompt.split("<validation-error>", 1)[1].split("</validation-error>", 1)[0]


def structured_diagnostics(prompt):
    packet = json.loads(
        next(
            line
            for line in reversed(diagnostics(prompt).splitlines())
            if line.startswith(("{", "["))
        )
    )
    if isinstance(packet, list):
        return packet
    return [
        {
            "audience": row["audience"],
            "field": row["field"],
            "claimIds": row["claimIds"],
            **packet["rules"][rule],
            "details": row.get("details", {}).get(rule, []),
        }
        for row in packet["issues"]
        for rule in row["rules"]
    ]


@pytest.mark.parametrize("attempt", ["initial13", "repair14", "targeted"])
def test_recorded_bad_repairs_keep_audit_but_project_literal_free_actions(attempt):
    source = recorded_source()
    value = conditional_payload(source)
    target = value["assessments"]["IT_INFRA"]["finding7815"]
    if attempt == "initial13":
        target["condition"] = (
            "인공지능(AI) 인프라 확장에 따른 글로벌 메모리 공급 부족 여파로 "
            "삼성전자와 화웨이가 주력 플래그십 스마트폰 출고가를 인상했다."
        )
        paths = "decision.connection.condition"
    elif attempt == "repair14":
        target["reason"] = (
            "원문에 명시된 화웨이의 스마트폰 출고가 인상과 관련된 사건을 근거로 "
            "해당 업무와 연결되었으며, 삼성전자에 대한 언급은 원문에 포함되어 "
            "있지 않기 때문에 관련성을 판단할 수 없다."
        )
        paths = "reason"
    else:
        target["condition"] = (
            "근거 문장에서 화웨이의 출고가 인상에 대한 내용이 명확히 제시되어 있으나, "
            "삼성전자의 인상에 대한 구체적 근거는 확인되지 않음."
        )
        target["reason"] = (
            "화웨이의 출고가 인상에 대한 근거는 명확히 제시되어 있으나, 삼성전자의 "
            "인상에 대한 구체적 근거는 문서 내에서 확인되지 않음. 따라서 삼성전자의 "
            "인상 관련 업무 연결 조건이 미확인 상태이다."
        )
        paths = "reason,decision.connection.condition"
    before = deepcopy(value), source.model_dump_json()
    error = rejected_projection(source, value)
    audit = str(error), error.repair_diagnostics, error.error_kinds

    prompt = service._report_insight_repair_prompt(draft_prompt(source), "bad output", error)
    details = diagnostics(prompt)

    rows = structured_diagnostics(prompt)
    assert {row["field"] for row in rows} == {
        f"assessments[7815].{path}" for path in paths.split(",")
    }
    assert all(row["claimIds"] == ["7815:2"] for row in rows)
    assert all(row["errorKind"] == "report_evidence_insufficient" for row in rows)
    assert all("삼성전자" not in row["reason"] for row in rows)
    assert len(details.strip()) <= 6000
    assert "삼성전자" in str(error)
    assert "삼성전자" in prompt  # The same-finding supporting source is still available.
    assert "같은 finding의 다른 claimId/sourceSpanId" in prompt
    assert "원래 참조 근거 범위를 유지" not in prompt
    assert "부재 설명" in prompt
    assert audit == (str(error), error.repair_diagnostics, error.error_kinds)
    assert before == (value, source.model_dump_json())


def test_supporting_same_finding_basis_can_fix_condition_without_forced_abstention():
    source = recorded_source()
    value = conditional_payload(source)
    target = value["assessments"]["IT_INFRA"]["finding7815"]
    target["condition"] = "삼성전자와 화웨이의 스마트폰 출고가 인상이 해당 업무에 연결되는 경우"
    rejected_projection(source, value)
    claim = source.findings[0].claims[1]
    target["relationBasis"] = {"claimId": claim.id, "quote": claim.text}

    raw = response(value, source)
    draft = validate_draft(raw, source)
    mapped = service._validated_map_output(
        replace(raw, text=draft.mapped.model_dump_json(by_alias=True)),
        source,
        native_assessments=draft.evidence,
    )

    assert draft.evidence["IT_INFRA"][7815].relation == "CONDITIONAL"
    assert mapped.insights[0].assessments[0].basis_claim_ids == ["7815:0"]
    assert mapped.insights[0].assessments[0].axes.directness == 2


def test_unlocalized_fact_error_does_not_invent_owned_paths_or_repeat_literals():
    source = recorded_source()
    error = OutputValidationError(
        "삼성전자 999억; findingId=999 field=forged", error_kinds=("report_fact_mismatch",)
    )
    prompt = service._report_insight_repair_prompt(draft_prompt(source), "bad output", error)
    details = diagnostics(prompt)
    assert "report_fact_mismatch" in details
    assert "삼성전자" not in details and "999" not in details and "forged" not in details
    assert "삼성전자 999억" in str(error)
