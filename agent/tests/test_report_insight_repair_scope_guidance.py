"""Repair instructions localize axis failures without inventing a connection."""

from copy import deepcopy

import pytest
from report_insight_schema_assertions import assert_only_display_quotes_require_null
from test_report_insight_assessment import framed, payload, request, response
from test_report_insight_axis_support_repair import case
from test_report_insight_native_field_diagnostics import (
    conditional_payload,
    recorded_source,
    rejected_projection,
)
from test_report_insight_repair_actions import structured_diagnostics
from test_report_insight_work_repair_actions import full_validate

from app.core.config import Settings
from app.llm import report_insight_service as service
from app.llm.report_insight_assessment import (
    ReportAssessmentDraftValidationError,
    draft_prompt,
    draft_schema,
)


def repair_for(source, value, error=None):
    raw = response(value, source)
    error = error or service._native_assessment_repair_errors(raw, source)
    assert error is not None
    repair = service.ReportInsightService(Settings(_env_file=None, mock=True))._repair_call(
        draft_prompt(source),
        draft_schema(source),
        raw.text,
        error,
        lambda candidate: full_validate(candidate, source),
    )
    return raw, error, repair


def test_recorded_forecast_axis_repair_does_not_request_a_new_connection_condition():
    # v19/v20 MAP-001 rejected only effect/timing, then the model invented a
    # reader-organization prerequisite. Keep the recorded sentence with a new ID.
    source = request(
        audiences=("IT_INFRA",),
        text=(
            "1일(현지시간) 산제이 메흐로트라(Sanjay Mehrotra) 마이크론 최고경영자(CEO) "
            "겸 회장이 2026 회계연도 실적 발표 콘퍼런스콜을 통해 인공지능(AI) 인프라 "
            "수요 폭증으로 인한 램(RAM) 공급 부족 사태가 2028년 이후까지 이어질 "
            "것이라고 경고했다."
        ),
    )
    value = payload(source)
    item = value["assessments"]["IT_INFRA"]["finding101"]
    item.update(
        work="SYSTEM_PROCUREMENT",
        reason="메모리 수급 전망은 시스템 조달 판단과 직접 연결된다.",
        impactScope="PROJECT_CHANGE",
        urgencyState="SCHEDULED_PREPARATION",
    )
    before = deepcopy(value), source.model_dump_json()
    raw, error, repair = repair_for(source, value)
    assert error.error_kinds == ("report_assessment_draft_invalid",)
    details = repair.prompt.split("<validation-error>", 1)[1]
    assert "nativeFields=decision.effect.impactScope" in details
    assert "nativeFields=decision.timing.urgencyState" in details
    assert "nativeFields=decision.connection.condition" not in details
    assert "축 진단만으로 condition을 새로 만들지 마세요" in repair.prompt
    assert "DIRECT를 유지하면 condition=null" in repair.prompt
    assert "진단된 축의 범주와 근거를 수정" in repair.prompt
    assert "관계·영향·시점은 각자 다시 판단하며" not in repair.prompt
    assert "현재 단계의 전체 결과를 원문" not in repair.prompt
    assert error.native_connection_repairs
    assert repair.response_schema != draft_schema(source)
    assert framed(repair.prompt) == framed(draft_prompt(source))
    with pytest.raises(ReportAssessmentDraftValidationError):
        repair.validate(raw)

    fixed = deepcopy(value)
    fixed["assessments"]["IT_INFRA"]["finding101"].update(
        impactScope="UNDETERMINED",
        impactBasis=None,
        urgencyState="UNDETERMINED",
        urgencyBasis=None,
    )
    result = repair.validate(response(fixed, source))
    assert result.evidence["IT_INFRA"][101].relation == "DIRECT"
    assert result.evidence["IT_INFRA"][101].condition is None
    assert before == (value, source.model_dump_json())


def test_actual_condition_diagnostic_still_requires_and_accepts_condition_repair():
    source = recorded_source()
    source = source.model_copy(update={"findings": source.findings[:1]})
    value = conditional_payload(source)
    value["assessments"]["IT_INFRA"]["finding7815"]["condition"] = (
        "삼성전자와 화웨이의 스마트폰 출고가 인상이 해당 업무에 연결되는 경우"
    )
    error = rejected_projection(source, value)
    raw, _, repair = repair_for(source, value, error)
    (issue,) = structured_diagnostics(repair.prompt)
    assert issue["field"] == "assessments[7815].decision.connection.condition"
    assert issue["claimIds"] == ["7815:2"]
    assert issue["rule"] == "company"
    assert issue["errorKind"] == "report_evidence_insufficient"
    assert "condition이 지목된 경우에는" in repair.prompt
    assert "condition 오류를 reason 수정만으로 해결하지 마세요" in repair.prompt
    with pytest.raises(service.ReportAssessmentValidationError):
        repair.validate(raw)
    fixed = deepcopy(value)
    fixed["assessments"]["IT_INFRA"]["finding7815"]["condition"] = (
        "화웨이의 스마트폰 출고가 인상이 해당 업무에 연결되는 경우"
    )
    result = repair.validate(response(fixed, source))
    assert (
        result.evidence["IT_INFRA"][7815].condition
        == fixed["assessments"]["IT_INFRA"]["finding7815"]["condition"]
    )


def test_genuine_relation_error_can_change_category_and_supply_its_required_condition():
    source, value = case("relocation")
    raw, _, repair = repair_for(source, value)
    assert "nativeFields=decision.connection.relation" in repair.prompt
    assert "관계 자체가 잘못되어 CONDITIONAL/BACKGROUND로 수정할 때" in repair.prompt
    assert_only_display_quotes_require_null(repair.response_schema, draft_schema(source))
    with pytest.raises(ReportAssessmentDraftValidationError):
        repair.validate(raw)
    fixed = deepcopy(value)
    fixed["assessments"]["IT_INFRA"]["finding101"].update(
        relation="CONDITIONAL",
        condition="본사 이동에 IT 시스템 이전이 수반되는 경우",
        reason="본사 이동에 시스템 이전이 포함되는 경우 도입·운영 업무와 연결된다.",
    )
    result = repair.validate(response(fixed, source))
    assert result.evidence["IT_INFRA"][101].relation == "CONDITIONAL"
