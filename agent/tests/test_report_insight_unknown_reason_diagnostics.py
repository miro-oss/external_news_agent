"""Unknown decisions keep prose evidence without inventing public score bases."""

import json
from copy import deepcopy
from dataclasses import replace

import pytest
from test_report_insight_assessment import payload, request, response
from test_report_insight_connection_evidence_dependency import full_validate
from test_report_insight_preserved_decision_repair import repair_for, repair_payload
from test_report_insight_repair_actions import structured_diagnostics

from app.llm.report_insight_assessment import validate_draft
from app.schemas.report_insight import CLAIMLESS_ASSESSMENT_REASON


@pytest.mark.parametrize("relation", ["UNDETERMINED", "DIRECT"])
def test_reason_repair_keeps_actual_local_evidence_scope_and_original_public_basis(relation):
    source = request(ids=(101, 202))
    finding = source.findings[0]
    extra = "제조사는 HBM4의 검증을 준비한다."
    finding.claims.append(
        finding.claims[0].model_copy(
            update={"id": "101:1", "text": extra, "evidence_sentence_ids": [1]}
        )
    )
    finding.sentences.append(finding.sentences[0].model_copy(update={"index": 1, "text": extra}))
    value = payload(source, relation=relation)
    value["assessments"]["CHIP_MAKER"]["finding101"]["reason"] = (
        "HBM5의 검증 준비 영향을 확인한다."
    )
    original = source.model_dump_json(), deepcopy(value)
    repair, error, raw, _ = repair_for(source, value)
    (issue,) = error.validation_issues
    expected_refs = ("101:0", "101:1") if relation == "UNDETERMINED" else ("101:0",)
    assert issue.rule == "source_binding"
    assert issue.claim_ids == expected_refs
    # Previously empty diagnostic refs discarded these exact evidence slices.
    assert issue.repair_payload()["details"]
    evidence = [
        row
        for detail in issue.repair_payload()["details"]
        for row in detail["expectedEvidence"]
    ]
    assert evidence
    assert all(set(row["claimIds"]) <= set(expected_refs) for row in evidence)
    rows = structured_diagnostics(repair.prompt)
    assert any(
        row["field"] == "assessments[101].reason"
        and row["claimIds"] == list(expected_refs)
        and row["rule"] == "source_binding"
        for row in rows
    )
    assert f"refs={list(expected_refs)}" in error.repair_action_diagnostics[0]
    assert "202:0" not in str(issue.repair_payload())

    corrected = deepcopy(value)
    corrected["assessments"]["CHIP_MAKER"]["finding101"]["reason"] = (
        "알려진 생산 제약에 맞춰 공정 검증 준비의 영향을 확인한다."
    )
    accepted = repair.validate(
        replace(raw, text=json.dumps(repair_payload(repair, source, corrected), ensure_ascii=False))
    )
    public = accepted.mapped.insights[0].assessments[0]
    assert public.basis_claim_ids == ([] if relation == "UNDETERMINED" else ["101:0"])
    assert source.model_dump_json() == original[0] and value == original[1]


def test_claimless_unknown_assessment_keeps_its_existing_empty_evidence_contract():
    source = request()
    value = payload(source, relation="UNDETERMINED")
    source.findings[0].claims = []
    source.findings[0].sentences = []
    value["assessments"]["CHIP_MAKER"]["finding101"]["reason"] = CLAIMLESS_ASSESSMENT_REASON
    draft = validate_draft(response(value, source), source)
    public = draft.mapped.insights[0].assessments[0]
    assert public.basis_claim_ids == []
    assert public.reason == CLAIMLESS_ASSESSMENT_REASON
    full_validate(response(value, source), source)
