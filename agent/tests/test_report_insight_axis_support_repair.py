"""Category support failures receive a bounded repair without freezing the error."""

from copy import deepcopy

import pytest
from test_report_insight_assessment import payload, request, response
from test_report_insight_work_repair_actions import full_validate

from app.core.config import Settings
from app.llm import report_insight_service as service
from app.llm.report_insight_assessment import (
    ReportAssessmentDraftValidationError,
    draft_prompt,
    draft_schema,
    validate_draft,
)


def case(kind):
    source = request(
        ids=(101,),
        audiences=("IT_INFRA",),
        text=(
            "메모리 가격은 내년에 상승할 것으로 전망된다."
            if kind == "forecast"
            else "직원들이 새로운 본사로 이동한다."
        ),
    )
    value = payload(source)
    target = value["assessments"]["IT_INFRA"]["finding101"]
    target.update(
        work="SYSTEM_PROCUREMENT" if kind == "forecast" else "DEPLOYMENT_OPERATIONS",
        reason=(
            "시장 수급 전망은 시스템 조달과 연결된다."
            if kind == "forecast"
            else "본사 이동은 시스템 도입·운영과 연결된다."
        ),
        impactScope="PROJECT_CHANGE" if kind == "forecast" else "UNDETERMINED",
        impactBasis=target["impactBasis"] if kind == "forecast" else None,
        urgencyState="SCHEDULED_PREPARATION" if kind == "forecast" else "UNDETERMINED",
        urgencyBasis=target["urgencyBasis"] if kind == "forecast" else None,
    )
    return source, value


@pytest.mark.parametrize("kind", ["forecast", "relocation"])
def test_selected_category_failure_has_typed_path_refs_and_does_not_freeze_decisions(kind):
    source, value = case(kind)
    before = deepcopy(value), source.model_dump_json()
    raw = response(value, source)
    with pytest.raises(ReportAssessmentDraftValidationError) as caught:
        validate_draft(raw, source)
    assert caught.value.work_diagnostics
    error = service._native_assessment_repair_errors(raw, source)
    assert error is not None
    assert not error.native_prose_repairs
    repair = service.ReportInsightService(Settings(_env_file=None, mock=True))._repair_call(
        draft_prompt(source),
        draft_schema(source),
        raw.text,
        error,
        lambda candidate: full_validate(candidate, source),
    )
    assert "report_axis_" in repair.prompt
    assert "refs=['101:0']" in repair.prompt
    assert "연결 sentence의 전망·계획·실행 단계" in repair.prompt
    assert "무관·미확인으로 바꾸지" in repair.prompt
    assert bool(error.native_connection_repairs) == (kind == "forecast")
    assert (repair.response_schema == draft_schema(source)) == (kind == "relocation")
    with pytest.raises(ReportAssessmentDraftValidationError):
        repair.validate(raw)

    fixed = deepcopy(value)
    item = fixed["assessments"]["IT_INFRA"]["finding101"]
    item.update(
        impactScope="UNDETERMINED",
        impactBasis=None,
        urgencyState="UNDETERMINED",
        urgencyBasis=None,
    )
    if kind == "relocation":
        item.update(
            relation="CONDITIONAL",
            condition="본사 이동에 IT 시스템 이전이 수반되는 경우",
            reason="본사 이동에 시스템 이전이 포함되는 경우 도입·운영 업무와 연결된다.",
        )
    result = repair.validate(response(fixed, source))
    assert result.evidence["IT_INFRA"][101].relation == (
        "DIRECT" if kind == "forecast" else "CONDITIONAL"
    )
    assert before == (value, source.model_dump_json())


def test_actual_project_schedule_keeps_both_categories_and_no_repair():
    source, value = case("forecast")
    text = "회사는 서버 도입 프로젝트의 자원 배분과 준비 일정을 변경했다."
    source.findings[0].claims[0].text = text
    source.findings[0].sentences[0].text = text
    item = value["assessments"]["IT_INFRA"]["finding101"]
    for field in ("relationBasis", "impactBasis", "urgencyBasis"):
        item[field]["quote"] = text
    item["reason"] = "서버 도입 자원과 준비 일정 변경은 시스템 조달 업무에 직접 영향을 준다."
    raw = response(value, source)
    assert service._native_assessment_repair_errors(raw, source) is None
    assert full_validate(raw, source).evidence["IT_INFRA"][101].impact_scope == "PROJECT_CHANGE"
