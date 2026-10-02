"""Recorded reason contradictions and narrow-scope controls; no live API calls."""

import json
from copy import deepcopy

import pytest
from jsonschema import Draft202012Validator
from test_report_insight_assessment import payload, request, response
from test_report_insight_assessment_coherence import assessment

from app.llm.report_insight_assessment import (
    ReportAssessmentDraftValidationError,
    draft_schema,
    validate_draft,
)
from app.llm.report_insight_assessment_coherence import assessment_coherence_errors

# Exact model-written reasons from MAP attempts 14/16 in the local 50cd847
# measurement. These are contradiction regressions, not source quality labels.
RECORDED_REASONS = {
    7790: (
        "업스테이지의 '솔라 미니 4' 공개는 성능 평가 결과에 따른 것으로, "
        "구체적 영향 대상이나 일정이 명확하지 않기 때문에 영향 범위와 시점은 미확인입니다."
    ),
    7792: (
        "LG화학의 육군 AI 역량 강화 교육은 구체적 영향 대상과 일정이 명확하지 않기 때문에 "
        "영향 범위와 시점은 미확인입니다."
    ),
    7793: (
        "생명과학 제조기업의 디지털 전환과 스마트 제조 기술 도입은 "
        "구체적 영향 대상과 일정이 명확하지 않기 때문에 영향 범위와 시점은 미확인입니다."
    ),
    7805: (
        "기사 내용이 LG CNS의 북미·아태 지역 사업 확대 계획과 미국 투자사와의 공동 투자 계획을 "
        "직접 연결하고 있으나, 구체적 영향 범위와 시점은 명확하지 않음."
    ),
    7807: (
        "오픈AI의 추론 정보 추출 공격 탐지와 관련된 사건은 구체적 영향 대상이나 일정이 "
        "명확하지 않기 때문에 영향 범위와 시점은 미확인입니다."
    ),
    7811: (
        "기사 내용이 미국 제조업 지표와 금리 변동이 증시에 영향을 미쳤음을 보여주지만, "
        "구체적 영향 범위와 시점은 불확실하다."
    ),
    7814: (
        "생성형 AI 기반의 AI 쇼핑 어시스턴트 도입은 배경적 사건으로, "
        "구체적 변경 대상이나 일정이 명확하지 않기 때문에 영향 범위와 시점은 미확인입니다."
    ),
    7818: (
        "기사 내용이 이재근 그룹 회장 내정자의 AI·디지털 전환 추진과 핵심 인사들의 역할을 "
        "보여주지만, 구체적 영향 범위와 시점은 명확하지 않다."
    ),
}


@pytest.mark.parametrize("reason", RECORDED_REASONS.values(), ids=RECORDED_REASONS)
@pytest.mark.parametrize(
    "impact", ["CORE_CONSTRAINT", "PROJECT_CHANGE", "LIMITED_PREPARATION", "NO_CHANGE"]
)
def test_recorded_whole_impact_unknown_contradicts_known_category_without_mutation(reason, impact):
    entry = assessment(reason, impact=impact)
    before = entry.model_dump()
    errors = assessment_coherence_errors(entry)
    assert len(errors) == 1
    assert errors[0].startswith("effect.impactScope:")
    assert entry.model_dump() == before


@pytest.mark.parametrize("reason", RECORDED_REASONS.values(), ids=RECORDED_REASONS)
def test_same_recorded_reason_is_coherent_with_unknown_impact(reason):
    entry = assessment(reason)
    before = entry.model_dump()
    assert assessment_coherence_errors(entry) == []
    assert entry.model_dump() == before
    assert entry.impact_scope == "UNDETERMINED"
    assert entry.impact_basis is None


@pytest.mark.parametrize(
    "reason",
    [
        "영향 범위와 시점은 미확인입니다.",
        "해당 업무의 구체적인 영향 범위와 적용 시점은 불확실합니다.",
        "관점의 구체적 영향 범위 및 시점을 확인할 수 없다.",
        "변경 대상을 알 수 없기 때문에 영향 범위는 미확인이다.",
        "일정이 없기 때문에 영향 범위와 시점은 명확하지 않다.",
        '원문에는 "영향 범위와 시점은 미확인"이 있다. 구체적 영향 범위는 미확인이다.',
    ],
)
def test_explicit_compound_axis_and_causal_conclusion_are_checked(reason):
    errors = assessment_coherence_errors(assessment(reason, impact="NO_CHANGE"))
    assert len(errors) == 1
    assert errors[0].startswith("effect.impactScope:")


@pytest.mark.parametrize(
    "reason",
    [
        "영향 범위는 생산 계약의 일정 변경이며 정확한 금액과 시점은 미확인이다.",
        "전체 영향 범위는 확인됐고 정량 규모만 미확인이다.",
        "독자 회사의 전체 영향 범위는 미확인이지만 원문 프로젝트 범위는 확인됐다.",
        "해당 업무의 정량적인 영향 범위와 시점은 미확인이다.",
        "정확한 영향 금액을 알 수 없기 때문에 정량적 영향 규모는 미확인이다.",
        "구체적 영향 금액과 시점은 미확인입니다.",
        "다른 업무의 구체적 영향 범위와 시점은 불확실하다.",
        "후속 사건의 영향 범위와 시점은 미확인이다.",
        "다른 업무는 근거가 없기 때문에 영향 범위와 시점은 미확인이다.",
        "다만 다른 업무의 자료가 없기 때문에 영향 범위와 시점은 미확인이다.",
        "시스템 조달은 근거가 없기 때문에 영향 범위와 시점은 미확인이다.",
        "자사 손익은 근거가 없기 때문에 영향 범위와 시점은 미확인이다.",
        "독자 회사의 손익은 원문에 없기 때문에 영향 범위와 시점은 미확인이다.",
        "고객사의 전체 손익은 알 수 없기 때문에 영향 범위와 시점은 미확인이다.",
        "정량적인 비용을 계산할 수 없기 때문에 영향 범위와 시점은 미확인이다.",
        "별도의 프로젝트는 근거가 없기 때문에 영향 범위와 시점은 불확실하다.",
        "시스템 조달 비용의 영향 범위와 시점은 미확인이다.",
        "원자재 가격은 영향 범위에서 제외하고 확인된 납품 일정 변경만 평가한다.",
        "고객 회사 전체 영향은 제외하지만 특정 프로젝트의 자원 변경을 판정한다.",
        "해당 사건은 영향 범위에 포함되지 않는다.",
        "영향 범위에 포함되지 않는 사건의 시점은 미확인이다.",
        "영향 범위와 시점은 미확인이라는 뜻은 아니다.",
        "영향 범위와 시점은 미확인이 아니다.",
        "영향 범위와 시점은 불확실하다고 할 수 없다.",
        "구체적 영향 범위와 시점은 명확하지 않다고 볼 수 없다.",
        "근거가 적기 때문에 영향 범위와 시점은 미확인이라는 해석은 타당하지 않다.",
        "영향 범위와 시점이 미확인일 경우 다시 판단한다.",
        "영향 범위와 시점은 미확인인가?",
        "영향 범위와 시점은 불확실할 수도 있다.",
        "근거가 적기 때문에 영향 범위와 시점이 미확인이라면 다시 판단한다.",
        '원문은 "영향 범위와 시점은 미확인입니다."라고 설명한다.',
        "원문은 ‘구체적 영향 범위와 시점은 불확실하다’라고 설명한다.",
        "`근거가 없기 때문에 영향 범위와 시점은 미확인이다`라는 인용이다.",
        '"영향 범위와 시점은 미확인입니다.',
    ],
)
def test_narrower_impact_and_nonasserted_unknown_are_preserved(reason):
    entry = assessment(reason, impact="NO_CHANGE")
    before = entry.model_dump()
    assert assessment_coherence_errors(entry) == []
    assert entry.model_dump() == before


@pytest.mark.parametrize(
    "subject,work", [("시스템 조달", "SYSTEM_PROCUREMENT"), ("공정 검증", "PROCESS_QUALIFICATION")]
)
def test_causal_subject_of_the_selected_work_does_not_hide_whole_impact_unknown(subject, work):
    entry = assessment(
        f"{subject}은 변경 근거가 없기 때문에 영향 범위와 시점은 미확인이다.",
        impact="NO_CHANGE",
        work=work,
    )
    errors = assessment_coherence_errors(entry)
    assert len(errors) == 1
    assert errors[0].startswith("effect.impactScope:")


def test_server_reports_exact_failed_ids_and_keeps_valid_native_entries_unchanged():
    source = request(
        ids=(*RECORDED_REASONS, 7900),
        text="제조사는 공정 검증 조건이 변경되지 않았다고 밝혔다.",
    )
    value = payload(source)
    for entry in value["assessments"]["CHIP_MAKER"].values():
        entry.update(
            impactScope="NO_CHANGE",
            urgencyState="UNDETERMINED",
            urgencyBasis=None,
            reason=RECORDED_REASONS.get(entry["findingId"], "공정 검증 조건은 변경되지 않았다."),
        )
    before = deepcopy(value)
    native = response(value, source)
    Draft202012Validator(draft_schema(source)).validate(json.loads(native.text))
    with pytest.raises(ReportAssessmentDraftValidationError) as caught:
        validate_draft(native, source)
    assert caught.value.failed_finding_ids == tuple(RECORDED_REASONS)
    assert "effect.impactScope:" in str(caught.value)
    assert value == before
