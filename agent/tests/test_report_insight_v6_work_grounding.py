"""Source-absent work procedures must not be copied from role examples.

Real v5 failures are exercised with unrelated finding IDs. These guards are
deliberately narrower than role relevance: ordinary cost-review interpretations
and statements that a procedure's existence is unknown remain valid.
"""

import pytest
from test_report_insight_assessment import payload, request, validate_flat

from app.core.errors import OutputValidationError
from app.llm.report_insight_assessment import ReportAssessmentDraftValidationError
from app.llm.report_insight_work_grounding import (
    validate_work_synthesis,
    work_prose_problems,
)
from app.schemas.report_insight import (
    CLAIMLESS_ASSESSMENT_REASON,
    ReportInsightReduceOutput,
    ReportInsightRequest,
)

NEOCLOUD_SOURCE = "네오클라우드 사업에 대한 비중 확대가 필요하다고 추천한다."
AI_CAPEX_SOURCE = (
    "미국 금리 인상에도 엔비디아, 마이크로소프트, 아마존 등은 AI 설비 투자를 계속한다."
)
PRICE_SOURCE = "메모리 가격이 상승했다. 실제 구매 수량과 조달 비용 변동은 공개되지 않았다."
PROCEDURE_SOURCE = (
    "고객은 해당 메모리 규격의 승인 요건을 제시했다. "
    "냉각 모듈의 호환성 검증 결과를 확인한 뒤 운영팀이 도입 승인을 판단한다."
)
ENGLISH_PROCEDURE_SOURCE = (
    "The operations team prepares compatibility testing of the cooling module. "
    "Customer approval of the specification is required before deployment."
)


def _source(text, *, finding_id=4901, audience="IT_INFRA", linked_text=None):
    source = request(ids=(finding_id,), audiences=(audience,), text=text)
    if linked_text is None:
        return source
    body = source.model_dump(mode="json", by_alias=True)
    body["findings"][0]["sentences"][0]["text"] = linked_text
    return ReportInsightRequest.model_validate(body)


def _reduce(source, *, overview="알려진 사건에 한정해 업무 판단 범위를 확인한다."):
    audience = source.audiences[0]
    refs = [source.findings[0].claims[0].id]
    return {
        "insights": [
            {
                "audience": audience,
                "headline": "원문 사건에 맞춰 업무 판단 범위를 확인한다.",
                "overview": [
                    {
                        "text": overview,
                        "basisClaimIds": refs,
                        "assumption": "원문과 같은 대상에 한정한다.",
                    }
                ],
                "implications": [
                    {
                        "text": "원문 사건을 기준으로 판단한다.",
                        "mechanism": "같은 대상의 알려진 변화를 업무 검토에 반영한다.",
                        "basisClaimIds": refs,
                        "assumption": "원문과 같은 대상에 한정한다.",
                        "falsifiedBy": "같은 대상에 변화가 없다는 원문이 확인되는 경우",
                    }
                ],
                "watchItems": [
                    {
                        "topic": "원문 사건의 변화",
                        "indicator": "같은 대상의 변화가 확인되는 원문",
                        "trigger": "새로운 변화가 확인되는 경우",
                        "basisClaimIds": refs,
                    }
                ],
            }
        ]
    }


def _validate_reduce(value, source, *, allowed=None):
    permitted = allowed or {
        audience: [claim.id for finding in source.findings for claim in finding.claims]
        for audience in source.audiences
    }
    return validate_work_synthesis(
        ReportInsightReduceOutput.model_validate(value), source, permitted
    )


@pytest.mark.parametrize(
    "source,prose,expected",
    [
        (
            NEOCLOUD_SOURCE,
            "네오클라우드 사업에 대한 비중 확대는 고객 승인 전에는 공급 가능 여부를 보류한다.",
            "approval_prerequisite",
        ),
        (
            NEOCLOUD_SOURCE,
            "해당 규격의 고객 승인 전에는 공급 가능 여부를 보류하고 인증팀 검증 준비를 조정한다.",
            "organization_prerequisite",
        ),
        (
            NEOCLOUD_SOURCE,
            "고객이 해당 메모리 규격을 승인하는 경우",
            "specification_procedure",
        ),
        (
            AI_CAPEX_SOURCE,
            "해당 도입안의 냉각 검증 전에는 도입 승인 판단을 보류하고 운영팀 검증 준비를 조정한다.",
            "cooling_procedure",
        ),
        (
            AI_CAPEX_SOURCE,
            "냉각 모듈의 검증 결과에 따라 도입 승인을 판단한다.",
            "physical_module",
        ),
        (
            AI_CAPEX_SOURCE,
            "해당 도입안의 냉각 호환성 검증을 통과하는 경우",
            "compatibility_procedure",
        ),
        (
            NEOCLOUD_SOURCE,
            "고객 승인 전에는 적용 판단을 보류한다. 승인 결과는 미정이다.",
            "approval_prerequisite",
        ),
        (
            NEOCLOUD_SOURCE,
            "고객 승인 여부는 미확인이고 냉각 모듈 검증을 준비한다.",
            "cooling_procedure",
        ),
        (
            NEOCLOUD_SOURCE,
            "냉각 모듈 검증을 진행하되 고객 승인 여부는 미확인이다.",
            "cooling_procedure",
        ),
        (
            NEOCLOUD_SOURCE,
            "냉각 모듈 검증을 준비하며 고객 승인 여부는 미확인이다.",
            "cooling_procedure",
        ),
    ],
)
def test_copied_concrete_procedure_is_rejected_even_when_its_outcome_is_unknown(
    source, prose, expected
):
    assert expected in work_prose_problems(prose, source)


@pytest.mark.parametrize(
    "prose",
    [
        "고객 승인 여부가 확인되지 않아 적용 판단 보류",
        "고객 승인 절차가 있는지는 알 수 없어 적용 판단을 보류한다.",
        "원문에 냉각 검증/승인 요건이 명시되지 않았다.",
        "규격 승인 요건은 알 수 없다.",
        "고객 승인 요건이 필요한지는 확인되지 않았다.",
    ],
)
def test_only_unknown_existence_or_need_of_a_procedure_does_not_invent_that_procedure(prose):
    assert work_prose_problems(prose, AI_CAPEX_SOURCE) == ()


@pytest.mark.parametrize(
    "source,prose",
    [
        (PRICE_SOURCE, "가격 상승에 맞춰 조달 비용을 검토하되 실제 비용 변동은 보류한다."),
        (PRICE_SOURCE, "운영팀이 조달 비용 변동을 재검토해야 한다."),
        (PROCEDURE_SOURCE, "고객 승인 전에는 도입 판단을 보류한다."),
        (PROCEDURE_SOURCE, "운영팀이 냉각 모듈 검증 준비를 조정한다."),
        (PROCEDURE_SOURCE, "고객이 해당 메모리 규격을 승인하는 경우"),
        (ENGLISH_PROCEDURE_SOURCE, "고객 승인 전에는 도입 판단을 보류한다."),
        (ENGLISH_PROCEDURE_SOURCE, "운영팀이 냉각 모듈의 호환성 검증 준비를 조정한다."),
        (ENGLISH_PROCEDURE_SOURCE, "해당 규격의 고객 승인을 확인한다."),
    ],
)
def test_actual_source_procedures_and_generic_work_interpretations_are_preserved(source, prose):
    assert work_prose_problems(prose, source) == ()


@pytest.mark.parametrize(
    "section,field",
    [
        ("headline", None),
        ("overview", "text"),
        ("overview", "assumption"),
        ("implications", "text"),
        ("implications", "mechanism"),
        ("implications", "assumption"),
        ("implications", "falsifiedBy"),
        ("watchItems", "topic"),
        ("watchItems", "indicator"),
        ("watchItems", "trigger"),
    ],
)
def test_reduce_checks_every_public_prose_field_for_source_absent_work(section, field):
    source = _source(AI_CAPEX_SOURCE)
    value = _reduce(source)
    unsupported = "운영팀이 냉각 모듈 검증 준비를 조정한다."
    if field is None:
        value["insights"][0][section] = unsupported
    else:
        value["insights"][0][section][0][field] = unsupported
    with pytest.raises(OutputValidationError) as caught:
        _validate_reduce(value, source)
    assert "report_work_cooling_procedure_unsupported" in caught.value.error_kinds
    assert section in str(caught.value)


@pytest.mark.parametrize("finding_id", [4901, 6307])
@pytest.mark.parametrize("linked_text", [PROCEDURE_SOURCE, ENGLISH_PROCEDURE_SOURCE])
def test_reduce_support_can_come_from_the_cited_claims_linked_sentence(finding_id, linked_text):
    source = _source(AI_CAPEX_SOURCE, finding_id=finding_id, linked_text=linked_text)
    before = source.model_dump_json(by_alias=True)
    value = _reduce(source, overview="운영팀이 냉각 모듈 검증 준비를 조정한다.")
    _validate_reduce(value, source)
    assert source.model_dump_json(by_alias=True) == before


def test_procedure_in_another_allowed_claim_cannot_support_an_items_own_citation():
    source = request(ids=(4901, 6307), audiences=("IT_INFRA",), text=AI_CAPEX_SOURCE)
    body = source.model_dump(mode="json", by_alias=True)
    body["findings"][1]["claims"][0]["text"] = PROCEDURE_SOURCE
    body["findings"][1]["sentences"][0]["text"] = PROCEDURE_SOURCE
    source = ReportInsightRequest.model_validate(body)
    value = _reduce(source, overview="운영팀이 냉각 모듈 검증 준비를 조정한다.")
    with pytest.raises(OutputValidationError, match=r"overview\[0\]\.text"):
        _validate_reduce(value, source)
    value["insights"][0]["overview"][0]["basisClaimIds"] = ["6307:0"]
    _validate_reduce(value, source)


@pytest.mark.parametrize("relation", ["DIRECT", "UNRELATED"])
def test_map_cannot_declare_a_decided_relation_with_an_undecidable_work_reason(relation):
    source = _source(NEOCLOUD_SOURCE, audience="CHIP_MAKER")
    value = payload(source, relation=relation)
    value["assessments"]["CHIP_MAKER"]["finding4901"]["reason"] = (
        "원문에 사건은 있으나 해당 관점 업무에 이어지는 대상과 전제를 판단할 수 없다."
    )
    with pytest.raises(ReportAssessmentDraftValidationError, match="업무 관계의 판단 불가"):
        validate_flat(value, source)


def test_map_can_keep_direct_work_relevance_while_deferring_the_size_of_the_effect():
    source = _source(PRICE_SOURCE, audience="IT_INFRA")
    value = payload(source)
    assessment = value["assessments"]["IT_INFRA"]["finding4901"]
    assessment.update(
        impactScope="UNDETERMINED",
        impactBasis=None,
        urgencyState="UNDETERMINED",
        urgencyBasis=None,
        reason=(
            "가격은 조달 판단에 직접 관련되지만, "
            "가격 상승과 실제 조달 비용 변동의 관계 판단은 보류한다."
        ),
    )
    result = validate_flat(value, source)
    public = result.mapped.insights[0].assessments[0]
    assert public.axes.directness == 3
    assert public.axes.impact is None
    assert public.axes.urgency is None


@pytest.mark.parametrize("field", ["reason", "condition"])
def test_map_rejects_copied_concrete_work_in_reason_and_conditional_connection(field):
    source = _source(AI_CAPEX_SOURCE)
    value = payload(source, relation="CONDITIONAL" if field == "condition" else "DIRECT")
    value["assessments"]["IT_INFRA"]["finding4901"][field] = (
        "해당 도입안의 냉각 모듈 검증을 통과하는 경우"
    )
    with pytest.raises(ReportAssessmentDraftValidationError, match="cooling_procedure"):
        validate_flat(value, source)


@pytest.mark.parametrize(
    "reason",
    [
        "고객 승인 전에는 도입 판단을 보류한다. 승인 결과는 미정이다.",
        "원문에 사건은 있으나 운영팀의 냉각 모듈 검증 결과를 알 수 없어 판단을 보류한다.",
    ],
)
def test_null_basis_unknown_map_cannot_hide_an_invented_procedure_in_the_reason(reason):
    source = _source(AI_CAPEX_SOURCE)
    value = payload(source, relation="UNDETERMINED")
    value["assessments"]["IT_INFRA"]["finding4901"]["reason"] = reason
    with pytest.raises(ReportAssessmentDraftValidationError, match="지원하지 않는 구체 업무"):
        validate_flat(value, source)


@pytest.mark.parametrize(
    "text,reason",
    [
        (
            AI_CAPEX_SOURCE,
            "원문은 있지만 관점의 업무 연결 조건과 범위를 판단할 정보가 부족하다.",
        ),
        (AI_CAPEX_SOURCE, "고객 승인 여부가 확인되지 않아 적용 판단 보류"),
        (PROCEDURE_SOURCE, "냉각 모듈 검증 결과가 미정이라 업무 영향의 범위는 판단할 수 없다."),
    ],
)
def test_legitimate_all_null_map_preserves_unknown_axes_without_inventing_a_citation(text, reason):
    source = _source(text)
    value = payload(source, relation="UNDETERMINED")
    value["assessments"]["IT_INFRA"]["finding4901"]["reason"] = reason
    result = validate_flat(value, source)
    public = result.mapped.insights[0].assessments[0]
    assert public.axes.directness is None
    assert public.axes.impact is None
    assert public.axes.urgency is None
    assert public.basis_claim_ids == []
    assert public.reason == reason


def test_claimless_map_keeps_the_existing_fixed_all_null_contract():
    source = _source(AI_CAPEX_SOURCE)
    value = payload(source, relation="UNDETERMINED")
    value["assessments"]["IT_INFRA"]["finding4901"]["reason"] = CLAIMLESS_ASSESSMENT_REASON
    source = source.model_copy(
        update={"findings": [source.findings[0].model_copy(update={"claims": []})]}
    )
    result = validate_flat(value, source)
    public = result.mapped.insights[0].assessments[0]
    assert public.basis_claim_ids == []
    assert public.reason == CLAIMLESS_ASSESSMENT_REASON
    assert public.axes.directness is None
