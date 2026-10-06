"""The recorded v17 failed compatibility repair keeps rejection and gets an action."""

import json
from copy import deepcopy
from dataclasses import replace

import pytest
from test_report_insight_assessment import framed, payload, request, response

from app.core.config import Settings
from app.core.errors import AgentError
from app.llm import report_insight_service as service
from app.llm.report_insight_assessment import (
    ReportAssessmentDraftValidationError,
    draft_prompt,
    draft_schema,
    validate_draft,
)


def recorded_source():
    source = request(
        ids=(7790,),
        audiences=("IT_INFRA",),
        text=(
            "[서울=뉴시스]이주영 기자 = 업스테이지가 적은 컴퓨팅 자원으로도 높은 성능을 "
            "낼 수 있는 소형 인공지능(AI) 모델 '솔라 미니 4'를 공개했다."
        ),
    )
    return source


def recorded_value(source, *, repaired=False):
    value = payload(source, relation="CONDITIONAL")
    item = value["assessments"]["IT_INFRA"]["finding7790"]
    item.update(
        work="COMPATIBILITY" if repaired else "SYSTEM_PROCUREMENT",
        impactScope="LIMITED_PREPARATION",
        urgencyState="MONITOR" if repaired else "SCHEDULED_PREPARATION",
        reason=(
            "기사의 주장(경량 소형 모델 공개)은 자사 인프라로 배포할 경우 호환성 검증이나 "
            "운영 준비가 필요하다는 점에서 IT 업무와 연결된다. 다만 자사 배포를 전제로 "
            "하는 구체적 검증 요건(어떤 하드웨어·환경에서 운용 가능한지 등)은 기사에 "
            "제시되지 않아 이 전제를 확인해야 한다."
            if repaired
            else "업스테이지가 적은 컴퓨팅 자원으로 높은 성능을 내는 소형 모델 '솔라 미니 4'를 "
            "공개한 사실은 조직의 시스템 조달 검토와 연관될 수 있다. 모델 도입을 위해서는 "
            "내부 검증·테스트라는 구체적 전제가 필요하다. (claim:7790:0 / sentence 선택)"
        ),
        condition=(
            "해당 모델을 자사 인프라에 실제로 배포하려면 기존 하드웨어와 소프트웨어에서 "
            "운용 가능한지 검증이 선행되어야 한다."
            if repaired
            else "해당 모델을 자사 서비스에 적용하려면 성능·호환성 검증과 파일럿 테스트가 필요하다."
        ),
    )
    return value


def full_validate(raw, source):
    draft = validate_draft(raw, source)
    service._validated_map_output(
        replace(raw, text=draft.mapped.model_dump_json(by_alias=True)),
        source,
        native_assessments=draft.evidence,
    )
    return draft


@pytest.mark.parametrize("repaired", [False, True])
def test_recorded_v17_attempts_still_reject_unsupported_procedure(repaired):
    source = recorded_source()
    value = recorded_value(source, repaired=repaired)
    before = deepcopy(value), source.model_dump_json()
    with pytest.raises(ReportAssessmentDraftValidationError, match="compatibility_procedure"):
        validate_draft(response(value, source), source)
    assert before == (value, source.model_dump_json())


def test_recorded_initial_repair_projects_typed_action_without_losing_public_error():
    source = recorded_source()
    raw = response(recorded_value(source), source)
    error = service._native_assessment_repair_errors(raw, source)
    audit = str(error), error.repair_diagnostics, error.error_kinds
    prompt = service._report_insight_repair_prompt(draft_prompt(source), raw.text, error)
    actions = prompt.split("<validation-error>", 1)[1].split("</validation-error>", 1)[0]

    assert "report_work_compatibility_procedure_unsupported" in actions
    assert "nativeFields=decision.connection.condition refs=['7790:0']" in actions
    assert "report_fact_mismatch" in actions
    assert "검증 절차 자체" in prompt
    assert "실제 사용·적용 여부" in prompt
    assert "원문에 명시된 검증 절차는 유지" in prompt
    assert "특정 범주나 null로 일괄 전환하지" in prompt
    assert audit == (str(error), error.repair_diagnostics, error.error_kinds)
    assert "근거에서 확인되지 않는 숫자: 0, 7790" in str(error)
    assert framed(prompt) == framed(draft_prompt(source))


def test_repair_contract_requires_model_to_replace_gate_and_keeps_full_validation():
    source = recorded_source()
    value = recorded_value(source)
    raw = response(value, source)
    error = service._native_assessment_repair_errors(raw, source)
    repair = service.ReportInsightService(Settings(_env_file=None, mock=True))._repair_call(
        draft_prompt(source),
        draft_schema(source),
        raw.text,
        error,
        lambda candidate: full_validate(candidate, source),
    )
    with pytest.raises(ReportAssessmentDraftValidationError, match="compatibility_procedure"):
        repair.validate(response(recorded_value(source, repaired=True), source))

    fixed = deepcopy(value)
    target = fixed["assessments"]["IT_INFRA"]["finding7790"]
    target.update(
        work="DEPLOYMENT_OPERATIONS",
        reason="소형 모델을 내부 서비스에 실제 적용하는 경우 도입·운영 업무와 연결된다.",
        condition="해당 소형 모델을 내부 서비스에서 사용하는 경우",
        impactScope="UNDETERMINED",
        impactBasis=None,
        urgencyState="UNDETERMINED",
        urgencyBasis=None,
    )
    output = repair.validate(response(fixed, source))
    assert output.evidence["IT_INFRA"][7790].relation == "CONDITIONAL"
    assert output.evidence["IT_INFRA"][7790].work == "DEPLOYMENT_OPERATIONS"
    assert output.mapped.insights[0].assessments[0].axes.directness == 2
    assert json.loads(raw.text) == json.loads(response(value, source).text)


def test_source_supported_compatibility_work_remains_valid_and_has_no_work_repair_action():
    source = request(
        ids=(101,),
        audiences=("IT_INFRA",),
        text="회사는 기존 시스템과의 호환성 검증을 완료했다고 밝혔다.",
    )
    value = payload(source)
    value["assessments"]["IT_INFRA"]["finding101"].update(
        work="COMPATIBILITY",
        reason="완료된 호환성 검증은 호환성 업무와 직접 연결된다.",
        impactScope="UNDETERMINED",
        impactBasis=None,
        urgencyState="UNDETERMINED",
        urgencyBasis=None,
    )
    full_validate(response(value, source), source)


@pytest.mark.parametrize("repeat_unsupported_gate", [False, True])
def test_bounded_mock_call_delivers_action_and_does_not_accept_repeated_gate(
    repeat_unsupported_gate,
):
    source = recorded_source()
    corrected = recorded_value(source, repaired=True)
    if not repeat_unsupported_gate:
        corrected["assessments"]["IT_INFRA"]["finding7790"].update(
            work="DEPLOYMENT_OPERATIONS",
            reason="소형 모델을 내부 서비스에 실제 적용하는 경우 도입·운영 업무와 연결된다.",
            condition="해당 소형 모델을 내부 서비스에서 사용하는 경우",
            impactScope="UNDETERMINED",
            impactBasis=None,
            urgencyState="UNDETERMINED",
            urgencyBasis=None,
        )

    class ReplayProvider:
        def __init__(self):
            self.calls = []

        def generate(self, **kwargs):
            self.calls.append(kwargs)
            return response(recorded_value(source) if len(self.calls) == 1 else corrected, source)

    def validate(raw):
        error = service._native_assessment_repair_errors(raw, source)
        if error is not None:
            raise error
        return full_validate(raw, source)

    provider = ReplayProvider()
    engine = service.ReportInsightService(Settings(_env_file=None, mock=False))

    def call():
        return engine._call(
            provider,
            instruction="Offline recorded MAP replay",
            prompt=draft_prompt(source),
            schema=draft_schema(source),
            validate=validate,
            stage="MAP-003",
        )

    if repeat_unsupported_gate:
        with pytest.raises(AgentError) as caught:
            call()
        assert caught.value.code == "SCHEMA_VIOLATION"
        assert caught.value.details["validationFailure"]["stage"] == "MAP-003"
        assert caught.value.details["validationFailure"]["attempt"] == 2
    else:
        assert call().output.evidence["IT_INFRA"][7790].relation == "CONDITIONAL"
    assert len(provider.calls) == 2
    assert "report_work_compatibility_procedure_unsupported" in provider.calls[1]["prompt"]
    assert provider.calls[1]["response_schema"] == provider.calls[0]["response_schema"]
