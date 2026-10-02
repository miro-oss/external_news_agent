"""Synthetic self-consistency cases; no provider outputs or quality labels."""

import json
from copy import deepcopy

import pytest
from jsonschema import Draft202012Validator
from test_report_insight_assessment import payload, request, response

from app.llm.report_insight_assessment import (
    ReportAssessmentDraftValidationError,
    draft_schema,
    validate_draft,
)
from app.llm.report_insight_assessment_coherence import assessment_coherence_errors
from app.schemas.report_insight_assessment import ReportFindingAssessmentDraft


def assessment(
    reason,
    *,
    relation="DIRECT",
    impact="UNDETERMINED",
    quote="검토 대상이다.",
    work="PROCESS_QUALIFICATION",
):
    basis = {"claimId": "101:0", "quote": quote}
    related = relation not in {"UNRELATED", "UNDETERMINED"}
    return ReportFindingAssessmentDraft.model_validate(
        {
            "findingId": 101,
            "work": work if related else None,
            "relation": relation,
            "relationBasis": None if relation == "UNDETERMINED" else deepcopy(basis),
            "condition": (
                "해당 제품을 공정 검증 대상으로 사용하는 경우"
                if relation in {"CONDITIONAL", "BACKGROUND"}
                else None
            ),
            "impactScope": impact,
            "impactBasis": None if impact == "UNDETERMINED" else deepcopy(basis),
            "urgencyState": "UNDETERMINED",
            "urgencyBasis": None,
            "reason": reason,
        },
        strict=True,
    )


@pytest.mark.parametrize(
    "reason",
    [
        "해당 업무와 직접 관련 없음.",
        "이 관점의 업무와 직접적인 관련이 없습니다.",
        "업무 관련성은 없다.",
        "검토 대상은 존재한다. 다만 해당 업무와 직접 연결되지 않는다.",
        "관련 사실은 있다; 해당 업무와 관련이 없다.",
    ],
)
def test_direct_rejects_explicit_current_work_nonrelation(reason):
    errors = assessment_coherence_errors(assessment(reason))
    assert len(errors) == 1
    assert errors[0].startswith("connection.relation:")


@pytest.mark.parametrize(
    "impact", ["CORE_CONSTRAINT", "PROJECT_CHANGE", "LIMITED_PREPARATION", "NO_CHANGE"]
)
@pytest.mark.parametrize(
    "reason",
    [
        "영향 불명확.",
        "해당 관점의 업무 영향 범위 자체는 미확인이다.",
        "연결 업무는 있다, 영향 범위를 판단할 수 없다.",
        "현재로서는 영향은 명확하지 않다.",
        "영향은 불명확하여 판정을 보류한다.",
    ],
)
def test_known_impact_rejects_whole_axis_unknown_declaration(impact, reason):
    errors = assessment_coherence_errors(assessment(reason, impact=impact))
    assert len(errors) == 1
    assert errors[0].startswith("effect.impactScope:")


@pytest.mark.parametrize(
    "reason",
    [
        "해당 업무와 직접 관련이 없다고 볼 수 없다.",
        "해당 업무와 직접 관련이 없지는 않다.",
        "해당 업무와 직접 관련이 없다면 다시 검토한다.",
        "해당 업무와 직접 관련 없음 여부를 검토한다.",
        "이전에는 해당 업무와 직접 관련이 없었지만 현재는 연결된다.",
        "다른 업무와 직접 관련이 없다.",
        "조달 업무와 직접 관련이 없으나 공정 검증 업무와 연결된다.",
        "해당 업무와 직접 관련 없음?",
        '원문은 "해당 업무와 직접 관련 없음"이라고 설명한다.',
        "‘해당 업무와 직접 관련 없음’이라는 표현을 검토한다.",
    ],
)
def test_direct_does_not_treat_qualified_or_nonasserted_negation_as_global_nonrelation(reason):
    assert assessment_coherence_errors(assessment(reason)) == []


@pytest.mark.parametrize(
    "reason",
    [
        "변경은 없다. 정량적인 영향 규모는 불명확하다.",
        "영향 금액은 미확인이다.",
        "자사 손익에 미치는 영향은 불명확하다.",
        "추가 영향은 불명확하다.",
        "장기 영향 범위는 미확인이다.",
        "영향은 없다.",
        "영향 범위가 불명확하다는 뜻은 아니다.",
        "영향 범위는 미확인이 아니다.",
        "영향은 명확하지 않다고 할 수 없다.",
        "영향은 불명확해 보이는 것이 아니다.",
        "영향 범위가 불명확할 경우 다시 검토한다.",
        "영향 범위는 미확인인가?",
        "시급성은 불명확하다.",
        "업무 영향의 정량 규모를 확인할 수 없다.",
        '원문은 "영향 범위는 미확인이다."라고 설명한다.',
        "현재 영향이 없다는 설명과 ‘향후 영향은 불명확’이라는 인용을 구분한다.",
        "`영향 불명확`이라는 표시를 검토한다.",
        "「영향 불명확」이라는 표기는 원문 표현이다.",
        '"영향 범위는 미확인이다.',
    ],
)
def test_known_impact_preserves_narrower_unknowns_quotes_questions_and_negated_unknown(reason):
    assert assessment_coherence_errors(assessment(reason, impact="NO_CHANGE")) == []


@pytest.mark.parametrize("relation", ["CONDITIONAL", "BACKGROUND", "UNRELATED", "UNDETERMINED"])
def test_non_direct_relation_is_never_promoted_or_rejected_for_lacking_direct_connection(relation):
    entry = assessment("해당 업무와 직접 관련이 없다.", relation=relation)
    before = entry.model_dump()
    assert assessment_coherence_errors(entry) == []
    assert entry.model_dump() == before


def test_unknown_impact_is_preserved_and_does_not_erase_known_work():
    entry = assessment("해당 업무와 연결되지만 영향 범위는 미확인이다.")
    before = entry.model_dump()
    assert assessment_coherence_errors(entry) == []
    assert entry.impact_scope == "UNDETERMINED"
    assert entry.impact_basis is None
    assert entry.relation == "DIRECT"
    assert entry.model_dump() == before


def test_source_quote_is_not_an_assessment_reason_and_never_changes_the_decision():
    entry = assessment(
        "업무 조건은 변경되지 않았다.",
        impact="NO_CHANGE",
        quote="해당 업무와 직접 관련 없음. 영향 범위는 미확인이다.",
    )
    before = entry.model_dump()
    assert assessment_coherence_errors(entry) == []
    assert entry.model_dump() == before


def test_quoted_span_does_not_hide_a_separate_unquoted_contradiction():
    entry = assessment('원문에는 "영향 불명확"이 있다. 영향 범위는 미확인이다.', impact="NO_CHANGE")
    assert len(assessment_coherence_errors(entry)) == 1


def test_both_contradictions_are_reported_once_without_mutating_any_field():
    entry = assessment(
        "해당 업무와 직접 관련 없음. 영향 범위는 미확인이다. 영향 불명확.",
        impact="NO_CHANGE",
    )
    before = entry.model_dump()
    errors = assessment_coherence_errors(entry)
    assert len(errors) == 2
    assert errors[0].startswith("connection.relation:")
    assert errors[1].startswith("effect.impactScope:")
    assert entry.model_dump() == before


@pytest.mark.parametrize(
    "work,reason",
    [
        ("SYSTEM_PROCUREMENT", "시스템 조달과 직접적 연관성은 없다."),
        ("POWER_COOLING", "전력·냉각과 직접 관련이 없다."),
        ("NETWORK", "네트워크와 관련된 내용이 아니다."),
        ("COMPATIBILITY", "호환성과 직접적인 관련은 없다."),
        ("DEPLOYMENT_OPERATIONS", "배포 및 운영과 관련이 없다."),
        ("DELIVERY_INSTALLATION", "납품·설치와 직접 관련이 없다."),
    ],
)
def test_explicit_negation_of_selected_work_is_rejected_without_classifying_source(work, reason):
    errors = assessment_coherence_errors(assessment(reason, work=work))
    assert len(errors) == 1
    assert errors[0].startswith("connection.relation:")


@pytest.mark.parametrize(
    "reason",
    [
        "호환성과 직접적 연관성은 없다.",
        "시스템 조달 비용과 직접 관련이 없다.",
        "시스템 조달과 관련이 없다는 뜻은 아니다.",
        "시스템 조달과 관련된 내용이 아니라고 할 수 없다.",
        '원문은 "시스템 조달과 직접적 연관성은 없다"고 설명한다.',
    ],
)
def test_other_work_subscope_and_negated_work_absence_do_not_reject_selected_work(reason):
    assert assessment_coherence_errors(assessment(reason, work="SYSTEM_PROCUREMENT")) == []


@pytest.mark.parametrize(
    "reason",
    [
        "관련 업무와 배경으로 연결 가능하나 영향 범위는 명확하지 않음.",
        "해당 업무의 근거가 있으며 영향 범위는 명확하지 않다.",
        "해당 업무와 연결되기는 하나 영향 범위를 판단할 수 없다.",
        "업무 관계는 알 수 있으나 영향 범위는 미확인이다.",
    ],
)
def test_conjunction_does_not_hide_whole_impact_unknown(reason):
    errors = assessment_coherence_errors(assessment(reason, impact="NO_CHANGE"))
    assert len(errors) == 1
    assert errors[0].startswith("effect.impactScope:")


def test_negative_work_content_and_unknown_impact_are_separate_clauses():
    entry = assessment(
        "원문은 대상 소개이며, 시스템 조달과 관련된 내용이 아니며 영향 범위는 명확하지 않다.",
        impact="NO_CHANGE",
        work="SYSTEM_PROCUREMENT",
    )
    assert len(assessment_coherence_errors(entry)) == 2


def test_native_schema_valid_contradiction_reports_only_server_owned_failed_finding():
    source = request(ids=(101, 102), text="제조사는 공정 검증 조건이 변경되지 않았다고 밝혔다.")
    value = payload(source)
    for entry in value["assessments"]["CHIP_MAKER"].values():
        entry.update(
            impactScope="NO_CHANGE",
            urgencyState="UNDETERMINED",
            urgencyBasis=None,
            reason="공정 검증 조건은 변경되지 않았다.",
        )
    value["assessments"]["CHIP_MAKER"]["finding101"]["reason"] = "영향 범위는 미확인이다."
    before = deepcopy(value)
    native = response(value, source)
    Draft202012Validator(draft_schema(source)).validate(json.loads(native.text))
    with pytest.raises(ReportAssessmentDraftValidationError) as caught:
        validate_draft(native, source)
    assert caught.value.failed_finding_ids == (101,)
    assert "effect.impactScope" in str(caught.value)
    assert value == before


def test_native_valid_unknown_impact_remains_null_with_known_connection():
    source = request(text="제조사는 공정 검증 계획을 소개했다.")
    value = payload(source)
    entry = value["assessments"]["CHIP_MAKER"]["finding101"]
    entry.update(
        impactScope="UNDETERMINED",
        impactBasis=None,
        urgencyState="UNDETERMINED",
        urgencyBasis=None,
        reason="공정 검증 업무와 연결된다. 영향 범위는 미확인이다.",
    )
    before = deepcopy(value)
    native = response(value, source)
    Draft202012Validator(draft_schema(source)).validate(json.loads(native.text))
    result = validate_draft(native, source)
    public = result.mapped.insights[0].assessments[0]
    assert public.axes.directness == 3
    assert public.axes.impact is None
    assert result.evidence["CHIP_MAKER"][101].impact_basis is None
    assert value == before
