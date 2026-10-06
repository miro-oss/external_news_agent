"""Recorded MAP/REVIEW placeholders must not substitute for a business premise."""

from copy import deepcopy

import pytest
from test_report_insight_assessment import payload, request, response

from app.llm.report_insight_assessment import (
    ReportAssessmentDraftValidationError,
    _metadata_only_condition,
    validate_draft,
)

# Exact selected claims, linked sentences and condition/reason text from the
# final 8f9e9a5 IT run: MAP attempt 4 / REVIEW attempt 10. The fixtures are
# deliberately self-contained; ignored local measurement files are not needed.
RECORDED_PLACEHOLDERS = [
    pytest.param(
        7814,
        "7814:2",
        "동원F&B는 생성형 AI 기반의 AI 쇼핑 어시스턴트를 도입했다.",
        "동원F&B가 운영하는 식품 전문 온라인몰 동원몰은 생성형 AI 기반의 AI 쇼핑 "
        "어시스턴트를 도입해 쇼핑 전 과정의 편의성을 대폭 끌어올린다고 2일 밝혔다.",
        "CONDITIONAL",
        "DEPLOYMENT_OPERATIONS",
        "원문 사건을 해당 업무로 연결하는 구체적인 미확인 조건이 필요하다.",
        "생성형 AI 기반의 AI 쇼핑 어시스턴트 도입이 쇼핑 전 과정의 편의성을 높인 것으로 "
        "보이나, 구체적 영향 범위와 시점이 명확하지 않기 때문에 조건부 연결로 판단한다.",
        id="recorded-map-7814",
    ),
    pytest.param(
        7802,
        "7802:0",
        "브로드컴이 앤트로픽에 최대 420억달러 대출을 지원한다.",
        "[디지털데일리 김문기기자] 브로드컴이 기업공개를 추진 중인 인공지능 스타트업 "
        "앤트로픽의 인프라 구축 비용을 지원하기 위해 최대 420억달러 규모의 전환사채 "
        "대출을 제공한다.",
        "BACKGROUND",
        "SYSTEM_PROCUREMENT",
        "미확인 업무 연결 조건입니다.",
        "기사 내용이 브로드컴의 앤트로픽 대출 지원과 관련되어 있으나, 구체적 업무 연결이나 "
        "영향 범위가 명확하지 않으며, 조건이 미확인 상태입니다.",
        id="recorded-review-7802",
    ),
]


@pytest.mark.parametrize(
    "finding_id,claim_id,claim,sentence,relation,work,condition,reason", RECORDED_PLACEHOLDERS
)
def test_recorded_native_placeholder_fails_without_rewriting_the_assessment(
    finding_id, claim_id, claim, sentence, relation, work, condition, reason
):
    source = request(ids=(finding_id,), audiences=("IT_INFRA",), text=claim)
    source.findings[0].claims[0].id = claim_id
    source.findings[0].sentences[0].text = sentence
    candidate = payload(source, relation=relation)
    candidate["assessments"]["IT_INFRA"][f"finding{finding_id}"].update(
        work=work,
        condition=condition,
        impactScope="UNDETERMINED",
        impactBasis=None,
        urgencyState="UNDETERMINED",
        urgencyBasis=None,
        reason=reason,
    )
    original = deepcopy(candidate)
    original_source = source.model_dump_json(by_alias=True)

    with pytest.raises(ReportAssessmentDraftValidationError, match="구체적 전제") as caught:
        validate_draft(response(candidate, source), source)

    assert caught.value.failed_finding_ids == (finding_id,)
    assert candidate == original
    assert source.model_dump_json(by_alias=True) == original_source


@pytest.mark.parametrize(
    "condition",
    [
        "미확인 업무 연결 조건입니다.",
        "미확인 업무 연결 조건이다.",
        "구체적인 미확인 업무 연결 조건임",
        "미확인 업무 연결 조건이 필요하다.",
        "구체적 미확인 업무 연결 조건은 필요합니다.",
        "원문 사건을 해당 업무로 연결하는 구체적인 미확인 조건이 필요하다.",
        "원문의 사건을 업무로 연결하는 구체적 미확인 조건은 필요합니다.",
        "원문 사건과 해당 업무를 연결하는 미확인 조건이 필요함.",
    ],
)
def test_complete_placeholder_predicates_are_not_business_conditions(condition):
    assert _metadata_only_condition(condition, "시스템 도입 계획이 발표됐다.")


@pytest.mark.parametrize(
    "condition",
    [
        "미확인 업무 연결 조건이 아니라 고객의 공정 검증 승인이 필요한 경우",
        "미확인 업무 연결 조건이 필요하지 않다는 뜻은 아니다.",
        "‘미확인 업무 연결 조건입니다.’라는 설명과 별개로 고객의 공정 검증 승인이 필요한 경우",
        "미확인 업무 연결 조건입니다. 고객의 공정 검증 승인이 필요한 경우",
        "원문 사건을 해당 업무로 연결하는 구체적인 미확인 조건이 필요하다. "
        "고객의 공정 검증 승인이 필요한 경우",
        "원문 사건을 해당 업무로 연결하는 구체적인 미확인 조건이 필요한 경우",
        "해당 계약의 납품을 진행하려면 고객의 공정 검증 승인이 필요하다.",
        "공급 범위가 미확인인 계약에 고객의 공정 검증 승인이 필요한 경우",
    ],
)
def test_quotes_negation_and_added_business_prerequisites_remain_unchanged(condition):
    source = request(text="제조사는 고객의 공정 검증 승인에 따라 계약의 납품 일정을 정한다.")
    candidate = payload(source, relation="CONDITIONAL")
    candidate["assessments"]["CHIP_MAKER"]["finding101"].update(
        condition=condition,
        impactScope="UNDETERMINED",
        impactBasis=None,
        urgencyState="UNDETERMINED",
        urgencyBasis=None,
    )
    original = deepcopy(candidate)

    validated = validate_draft(response(candidate, source), source)

    assert validated.draft.model_dump(by_alias=True) == candidate == original
    assert validated.evidence["CHIP_MAKER"][101].condition == condition
