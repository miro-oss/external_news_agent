"""Native condition failures retain their origin after public reason projection."""

import json
from copy import deepcopy
from dataclasses import replace

import pytest
from jsonschema import Draft202012Validator
from test_report_insight_assessment import payload, request, response
from test_report_insight_v4_pipeline import V4Provider, generate, stages

from app.core.errors import AgentError
from app.llm.report_insight_assessment import draft_prompt, draft_schema, validate_draft
from app.llm.report_insight_service import (
    ReportAssessmentValidationError,
    ReportInsightService,
    _assessment_prose_errors,
    _prose_validation_errors,
    _source_context,
    _validated_map_output,
)
from app.llm.report_validation_diagnostics import report_validation_issue_details


def recorded_source():
    source = request(
        ids=(7815, 7816),
        audiences=("IT_INFRA",),
        text="화웨이도 스마트폰 출고가를 평균 200달러 인상했다.",
    )
    finding = source.findings[0]
    finding.claims[0].id = "7815:2"
    finding.claims.append(
        finding.claims[0].model_copy(
            update={
                "id": "7815:0",
                "text": "삼성전자와 화웨이가 플래그십 스마트폰 출고가를 인상했다.",
                "evidence_sentence_ids": [1],
            }
        )
    )
    finding.sentences.append(
        finding.sentences[0].model_copy(update={"index": 1, "text": finding.claims[-1].text})
    )
    return source


def test_recorded_condition_failure_names_native_field_and_rejects_bad_repair():
    # Real MAP attempts 13/14: the first reason has no unsupported company;
    # Samsung occurs in the appended condition. The repair then moves that
    # unsupported company into the previously valid reason. The narrowed schema
    # and server-side preservation check must both reject that change.
    source = recorded_source()

    def hook(stage, occurrence, _, value):
        for item in value["assessments"]["IT_INFRA"].values():
            item["reason"] = "원문 사건과 관점 업무의 연결 여부를 검토해야 한다."
        item = value["assessments"]["IT_INFRA"].get("finding7815")
        if stage == "MAP-001" and item is not None:
            if occurrence == 1:
                item.update(
                    relation="CONDITIONAL",
                    work="DEPLOYMENT_OPERATIONS",
                    condition=(
                        "인공지능(AI) 인프라 확장에 따른 글로벌 메모리 공급 부족 여파로 "
                        "삼성전자와 화웨이가 주력 플래그십 스마트폰 출고가를 인상했다."
                    ),
                    reason=(
                        "스마트폰 출고가 인상 사건이 해당 업무와 연결되며, "
                        "구체적 영향 범위와 시점은 미확인이다."
                    ),
                )
            else:
                item.update(
                    relation="CONDITIONAL",
                    work="DEPLOYMENT_OPERATIONS",
                    condition="화웨이의 스마트폰 출고가 인상이 해당 업무에 연결되는 경우",
                )
                item["reason"] = (
                    "원문에 명시된 화웨이의 스마트폰 출고가 인상과 관련된 사건을 근거로 "
                    "해당 업무와 연결되었으며, 삼성전자에 대한 언급은 원문에 포함되어 "
                    "있지 않기 때문에 관련성을 판단할 수 없다."
                )
        return value

    # Deliberately emulate a provider violating its strict schema, so the
    # independent server-side guard must also reject the invalid repair.
    provider = V4Provider(source, relation="UNRELATED", hook=hook, validate_wire=False)
    with pytest.raises(AgentError) as caught:
        generate(provider, source)

    assert caught.value.code == "SCHEMA_VIOLATION"
    assert stages(provider) == ["MAP-001", "MAP-001"]
    assert provider.schema_validity == [True, False]
    repair = provider.calls[1]["prompt"]
    assert "field=assessments.reason nativeFields=decision.connection.condition" in repair
    details = repair.split("<validation-error>", 1)[1].split("</validation-error>", 1)[0]
    assert "report_fact_mismatch" in details
    assert "삼성전자" not in details
    assert "refs=['7815:2']" in repair
    assert "condition 오류를 reason 수정만으로 해결하지 마세요" in repair


def conditional_payload(source):
    value = payload(source, relation="CONDITIONAL")
    for item in value["assessments"][source.audiences[0]].values():
        item.update(
            work="DEPLOYMENT_OPERATIONS",
            reason="스마트폰 출고가 인상 사건의 업무 연결 조건을 확인한다.",
            condition="화웨이의 스마트폰 출고가 인상이 해당 업무에 연결되는 경우",
            impactScope="UNDETERMINED",
            impactBasis=None,
            urgencyState="UNDETERMINED",
            urgencyBasis=None,
        )
    return value


def rejected_projection(source, value, *, with_native=True):
    raw = response(value, source)
    draft = validate_draft(raw, source)
    with pytest.raises(ReportAssessmentValidationError) as caught:
        _validated_map_output(
            replace(raw, text=draft.mapped.model_dump_json(by_alias=True)),
            source,
            native_assessments=draft.evidence if with_native else None,
        )
    return caught.value


@pytest.mark.parametrize("invalid_fields", [("reason",), ("condition",), ("reason", "condition")])
def test_native_diagnostic_attributes_actual_components_without_weakening_public_guard(
    invalid_fields,
):
    source = recorded_source()
    value = conditional_payload(source)
    item = value["assessments"]["IT_INFRA"]["finding7815"]
    for field in invalid_fields:
        item[field] = "삼성전자의 스마트폰 출고가 인상과 업무 연결 조건을 검토한다."
    original = deepcopy(value)
    legacy = rejected_projection(source, value, with_native=False)
    diagnostic = rejected_projection(source, value)

    paths = [
        "reason" if field == "reason" else "decision.connection.condition"
        for field in invalid_fields
    ]
    assert f"nativeFields={','.join(paths)}" in diagnostic.repair_summary
    assert "nativeFields=" not in str(legacy)
    assert diagnostic.error_kinds == legacy.error_kinds == ("report_fact_mismatch",)
    assert diagnostic.failed_finding_ids == legacy.failed_finding_ids == (7815,)
    details = report_validation_issue_details(diagnostic, {}, stage="MAP-001")
    assert {issue["field"] for issue in details["issues"]} == {
        f"assessments[7815].{path}" for path in paths
    }
    assert all(issue["claimIds"] == ["7815:2"] for issue in details["issues"])
    assert "근거에서 확인되지 않는 기업명: 삼성전자" in str(diagnostic)
    assert value == original


def test_native_condition_does_not_turn_historical_reason_into_current_deadline():
    source = request(audiences=("IT_INFRA",), text="2026년 9월 1일에 계약 신청이 마감된다.")
    finding = source.findings[0]
    finding.claims.append(
        finding.claims[0].model_copy(
            update={
                "id": "101:1",
                "text": "별도 계약 신청은 2026년 10월 1일에 마감된다.",
                "evidence_sentence_ids": [1],
            }
        )
    )
    finding.sentences.append(
        finding.sentences[0].model_copy(update={"index": 1, "text": finding.claims[-1].text})
    )
    value = conditional_payload(source)
    item = value["assessments"]["IT_INFRA"]["finding101"]
    item.update(
        reason="원문은 2026년 9월 1일 마감을 언급한다.",
        condition="별도 마감이 임박한 경우",
        impactScope="PROJECT_CHANGE",
        impactBasis={"claimId": "101:1", "quote": finding.claims[-1].text},
    )
    claims, evidence = _source_context(source)
    for field in ("reason", "condition"):
        assert not _prose_validation_errors(
            [item[field]], ["101:0", "101:1"], evidence, claims, request=source
        )
    legacy = rejected_projection(source, value, with_native=False)
    assert legacy.error_kinds == ("report_assessment_invalid",)
    assert "이미 지난 근거 기한" in str(legacy)
    raw = response(value, source)
    draft = validate_draft(raw, source)
    validated = _validated_map_output(
        replace(raw, text=draft.mapped.model_dump_json(by_alias=True)),
        source,
        native_assessments=draft.evidence,
    )
    assert (
        validated.insights[0].assessments[0].reason
        == draft.mapped.insights[0].assessments[0].reason
    )

    # The same claim as an assertion in reason remains invalid. A provider's
    # text label cannot authorize treating an actual statement as a condition.
    item["reason"] = "2026년 9월 1일 마감이 임박하다."
    diagnostic = rejected_projection(source, value)
    assert "이미 지난 근거 기한" in str(diagnostic)
    assert "nativeFields=reason" in diagnostic.repair_summary


@pytest.mark.parametrize("extra_number_field", ["reason", "condition"])
def test_overlapping_fact_failures_keep_both_fields_editable_in_the_only_repair(
    extra_number_field,
):
    source = recorded_source()
    source = source.model_copy(update={"findings": source.findings[:1]})
    value = conditional_payload(source)
    entry = value["assessments"]["IT_INFRA"]["finding7815"]
    entry["reason"] = "삼성전자의 스마트폰 출고가 인상 사건의 업무 연결 조건을 확인한다."
    entry["condition"] = "삼성전자의 스마트폰 출고가 인상이 해당 업무에 연결되는 경우"
    entry[extra_number_field] = entry[extra_number_field].replace("삼성전자의", "삼성전자의 9999년")
    before = deepcopy(value)
    raw = response(value, source)

    def validate(candidate):
        draft = validate_draft(candidate, source)
        _validated_map_output(
            replace(candidate, text=draft.mapped.model_dump_json(by_alias=True)),
            source,
            native_assessments=draft.evidence,
        )
        return draft

    with pytest.raises(ReportAssessmentValidationError) as caught:
        validate(raw)
    error = caught.value
    assert error.native_prose_repairs[7815][1] == ("reason", "decision.connection.condition")
    assert "근거에서 확인되지 않는 숫자: 9999" in str(error)
    assert "근거에서 확인되지 않는 기업명: 삼성전자" in str(error)
    assert error.error_kinds == ("report_fact_mismatch",)
    engine = object.__new__(ReportInsightService)
    repair = engine._repair_call(
        draft_prompt(source), draft_schema(source), raw.text, error, validate
    )

    corrected = deepcopy(value)
    repaired = corrected["assessments"]["IT_INFRA"]["finding7815"]
    repaired["reason"] = "스마트폰 출고가 인상 사건의 업무 연결 조건을 확인한다."
    # Fixing only the more detailed failure must still reject the repeated
    # unsupported company; exposing both fields never relaxes acceptance.
    with pytest.raises(ReportAssessmentValidationError, match="기업명: 삼성전자"):
        repair.validate(response(corrected, source))
    repaired["condition"] = "스마트폰 출고가 인상이 해당 업무에 연결되는 경우"
    candidate = response(corrected, source)
    Draft202012Validator(repair.response_schema).validate(json.loads(candidate.text))
    result = repair.validate(candidate)
    assert result.evidence["IT_INFRA"][7815].reason == repaired["reason"]
    assert result.evidence["IT_INFRA"][7815].condition == repaired["condition"]
    assert result.evidence["IT_INFRA"][7815].relation_basis.claim_id == "7815:2"
    assert value == before


def test_mismatched_native_context_cannot_change_public_prose_semantics():
    source = recorded_source()
    value = conditional_payload(source)
    value["assessments"]["IT_INFRA"]["finding7815"]["reason"] = (
        "삼성전자의 스마트폰 출고가 인상 사건을 검토한다."
    )
    draft = validate_draft(response(value, source), source)
    assessment = draft.mapped.insights[0].assessments[0]
    claims, evidence = _source_context(source)
    foreign = draft.evidence["IT_INFRA"][7815].model_copy(update={"reason": "다른 설명"})
    errors = _assessment_prose_errors(
        assessment, foreign, assessment.basis_claim_ids, evidence, claims, source
    )
    assert errors
    assert all(field == "reason" for field, _ in errors)
    assert any("기업명: 삼성전자" in str(error) for _, error in errors)


def construction_condition_payload(condition):
    source = request(
        audiences=("IT_INFRA",),
        text="삼성전자는 공장을 건설할 계획이다. 공급 수요는 추후 확인한다.",
    )
    value = conditional_payload(source)
    value["assessments"]["IT_INFRA"]["finding101"].update(
        reason="공장 건설 계획의 업무 연결 조건을 확인한다.",
        condition=condition,
    )
    return source, value


@pytest.mark.parametrize(
    "condition",
    [
        "삼성전자는 공장을 완공했고 공급 수요가 늘어날 경우",
        "삼성전자는 공장을 완공했으며 공급 수요가 늘어날 경우",
        "삼성전자는 공장을 완공했다, 공급 수요가 늘어날 경우",
        "삼성전자는 공장을 완공했다. 공급 수요가 늘어날 경우",
    ],
)
def test_native_condition_cannot_hide_asserted_fact_before_its_final_premise(condition):
    source, value = construction_condition_payload(condition)
    diagnostic = rejected_projection(source, value)
    assert diagnostic.error_kinds == ("report_fact_mismatch",)
    assert diagnostic.native_prose_repairs[101][1] == ("decision.connection.condition",)
    assert "event_state" in diagnostic.fact_repair_kinds
    assert "근거에서 확인되지 않는 완료·착수·계약·중단 사실" in str(diagnostic)


@pytest.mark.parametrize(
    "condition",
    [
        "삼성전자가 공장을 완공할 경우",
        "삼성전자가 공장을 완공했다면",
        "공급 수요가 늘어날 경우",
    ],
)
def test_single_native_hypothetical_premise_still_validates(condition):
    source, value = construction_condition_payload(condition)
    raw = response(value, source)
    draft = validate_draft(raw, source)
    output = _validated_map_output(
        replace(raw, text=draft.mapped.model_dump_json(by_alias=True)),
        source,
        native_assessments=draft.evidence,
    )
    assert output == draft.mapped
    assert draft.evidence["IT_INFRA"][101].condition == condition
