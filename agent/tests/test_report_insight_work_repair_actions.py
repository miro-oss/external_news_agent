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
    assert "원문의 대상·행동·단계에서 새로 작성" in prompt
    assert "실제 사용·적용 여부" in prompt
    assert "원문에 명시된 절차는 유지" in prompt
    assert "근거 밖 개념의 부재를 설명하는 문장은 빼세요" in prompt
    assert "오류 문구를 없애려고 다른 work나 DIRECT로 바꾸지 마세요" in prompt
    assert "호환성 시험·승인·선행 검증" not in prompt
    assert "호환성 검증 절차의 존재·의무" not in actions
    assert "원문에 없는 절차는 완료 여부가 미확인이라는 설명으로도 추가하지 않는다" in prompt
    assert "원문에 명시된 절차와 그 판단 한계는 유지한다" in prompt
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


def test_lab_research_repair_rewrites_source_relation_instead_of_echoing_missing_procedure():
    source = request(
        audiences=("IT_INFRA",),
        text="연구진은 소자의 전기적 특성을 실험실에서 측정했다.",
    )
    before = source.model_dump_json()
    value = payload(source, relation="CONDITIONAL")
    entry = value["assessments"]["IT_INFRA"]["finding101"]
    entry.update(
        work="COMPATIBILITY",
        reason="연구 성과는 부품 호환성 검증이 필요하다는 점에서 IT 업무와 연결된다.",
        condition="이 소자가 실제 운영 시스템에 적용되는 경우",
        impactScope="UNDETERMINED",
        impactBasis=None,
        urgencyState="UNDETERMINED",
        urgencyBasis=None,
    )
    raw = response(value, source)
    error = service._native_assessment_repair_errors(raw, source)
    assert error.error_kinds == ("report_assessment_draft_invalid",)
    engine = object.__new__(service.ReportInsightService)
    original_schema = draft_schema(source)
    repair = engine._repair_call(
        draft_prompt(source),
        original_schema,
        raw.text,
        error,
        lambda candidate: full_validate(candidate, source),
    )
    assert repair.response_schema == original_schema
    with pytest.raises(ReportAssessmentDraftValidationError, match="compatibility_procedure"):
        repair.validate(raw)

    echoed = deepcopy(value)
    echoed["assessments"]["IT_INFRA"]["finding101"].update(
        relation="DIRECT",
        work="DEPLOYMENT_OPERATIONS",
        condition=None,
        reason=(
            "소자의 실험실 측정은 도입·운영 관련 직접 근거이나 원문은 시스템 수준 "
            "호환성 검증 절차를 제시하지 않아 호환성 검증 여부는 미확인이다."
        ),
    )
    with pytest.raises(ReportAssessmentDraftValidationError, match="compatibility_procedure"):
        repair.validate(response(echoed, source))

    grounded = deepcopy(value)
    grounded["assessments"]["IT_INFRA"]["finding101"].update(
        relation="UNDETERMINED",
        work=None,
        relationBasis=None,
        condition=None,
        reason=(
            "실험실에서 측정한 소자의 전기적 특성은 확인되지만 "
            "IT 시스템 조달·운영 업무와의 연결은 판단하기 어렵다."
        ),
    )
    validated = repair.validate(response(grounded, source))
    assert validated.evidence["IT_INFRA"][101].relation == "UNDETERMINED"
    assert validated.mapped.insights[0].assessments[0].basis_claim_ids == []
    assert source.model_dump_json() == before


def test_fact_only_condition_repair_does_not_replace_event_state_with_invented_procedure():
    """A fact repair must not create a new work error on its final allowed try."""
    from app.llm.report_insight_instructions import ASSESSMENT_CONDITION_RULE

    source = request(
        audiences=("IT_INFRA",),
        text="연구진은 메모리 배열에 데이터 이동 셀을 추가하는 구조를 제안했다.",
    )
    value = payload(source, relation="CONDITIONAL")
    entry = value["assessments"]["IT_INFRA"]["finding101"]
    entry.update(
        work="COMPATIBILITY",
        reason="제안된 메모리 구조를 기존 시스템에 적용하는 경우 호환성 업무와 연결된다.",
        condition="제안된 구조의 제품 적용이 이뤄져야 실제 호환성 영향이 확정된다.",
        impactScope="UNDETERMINED",
        impactBasis=None,
        urgencyState="UNDETERMINED",
        urgencyBasis=None,
    )
    raw = response(value, source)
    validate_draft(raw, source)  # Initial failure is public fact state, not a work error.
    with pytest.raises(service.ReportAssessmentValidationError) as caught:
        full_validate(raw, source)
    error = caught.value
    assert error.error_kinds == ("report_fact_mismatch",)
    assert error.native_prose_repairs[101][1] == ("decision.connection.condition",)
    repair = object.__new__(service.ReportInsightService)._repair_call(
        draft_prompt(source),
        draft_schema(source),
        raw.text,
        error,
        lambda candidate: full_validate(candidate, source),
    )
    # The positive condition rule also reaches pure fact retries, even though no
    # compatibility-procedure diagnostic existed in the original response.
    assert ASSESSMENT_CONDITION_RULE in repair.prompt
    assert "report_work_compatibility_procedure_unsupported" not in repair.prompt
    assert ASSESSMENT_CONDITION_RULE in json.dumps(draft_schema(source), ensure_ascii=False)
    assert ASSESSMENT_CONDITION_RULE in json.dumps(repair.response_schema, ensure_ascii=False)
    assert framed(repair.prompt) == framed(draft_prompt(source))
    with pytest.raises(service.ReportAssessmentValidationError):
        repair.validate(raw)

    invented = deepcopy(value)
    invented["assessments"]["IT_INFRA"]["finding101"]["condition"] = (
        "제안된 구조가 실제 메모리 설계 환경에서 호환성 검증을 거쳐야 업무와 연결된다."
    )
    with pytest.raises(
        ReportAssessmentDraftValidationError, match="condition: compatibility_procedure"
    ):
        repair.validate(response(invented, source))

    fixed = deepcopy(value)
    fixed["assessments"]["IT_INFRA"]["finding101"]["condition"] = (
        "제안된 메모리 구조를 실제 운영 시스템의 메모리에 적용하는 경우"
    )
    validated = repair.validate(response(fixed, source))
    result = validated.evidence["IT_INFRA"][101]
    original = validate_draft(raw, source).evidence["IT_INFRA"][101]
    assert result.model_dump(exclude={"condition"}) == original.model_dump(exclude={"condition"})
    assert result.condition == fixed["assessments"]["IT_INFRA"]["finding101"]["condition"]
    assert value["assessments"]["IT_INFRA"]["finding101"] == entry


def test_condition_rule_keeps_source_supported_prerequisite_valid():
    source = request(
        audiences=("IT_INFRA",),
        text=(
            "회사는 해당 메모리를 사용하려면 기존 시스템의 호환성 검증을 "
            "먼저 수행해야 한다고 밝혔다."
        ),
    )
    value = payload(source, relation="CONDITIONAL")
    value["assessments"]["IT_INFRA"]["finding101"].update(
        work="COMPATIBILITY",
        reason="해당 메모리 사용에 필요한 선행 절차는 호환성 업무와 연결된다.",
        condition="해당 메모리를 사용하기 위한 기존 시스템의 호환성 검증이 수행되는 경우",
        impactScope="UNDETERMINED",
        impactBasis=None,
        urgencyState="UNDETERMINED",
        urgencyBasis=None,
    )
    result = full_validate(response(value, source), source)
    assert result.evidence["IT_INFRA"][101].relation == "CONDITIONAL"
    assert "호환성 검증" in result.evidence["IT_INFRA"][101].condition
