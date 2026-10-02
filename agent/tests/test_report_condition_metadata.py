"""Evaluation bookkeeping and rubric definitions cannot act as business premises."""

from copy import deepcopy

import pytest
from jsonschema import Draft202012Validator
from test_report_insight_assessment import draft_schema, payload, request, response
from test_report_insight_v4_pipeline import V4Provider, generate, stages

from app.core.errors import AgentError
from app.llm.report_insight_assessment import (
    ReportAssessmentDraftValidationError,
    draft_to_wire,
    validate_draft,
)
from app.schemas.report_insight import ReportInsightRequest

METADATA_CONDITION = (
    "claimId/ sourceSpanId가 해당 원문 내용과 일치하며, "
    "관련 업무의 구체적 영향 범위와 시점이 명확히 확인됨."
)
DEFINITION_CONDITION = "원문 사건·조건이 해당 관점 업무 자체다."
API_SOURCE = (
    "고객은 공정 검증 API의 claimId와 sourceSpanId 필드가 원문 내용과 일치하는지 "
    "확인한 뒤 해당 공정의 검증 결과를 수신한다."
)


@pytest.mark.parametrize("relation", ["CONDITIONAL", "BACKGROUND"])
@pytest.mark.parametrize(
    "condition",
    [
        METADATA_CONDITION,
        "claimId와 sourceSpanId가 원문과 일치함.",
        "sourceSpanId / claimId가 해당 원문 문장과 일치합니다.",
        "claimId가 원문 근거와 일치한다.",
        DEFINITION_CONDITION,
        "원문의 사건과 조건은 해당 관점의 업무 자체입니다.",
        "/claims=[]",
        "claims = [ ]",
        "/ claim = [] .",
    ],
)
def test_native_evaluation_only_conditions_fail_without_mutation(relation, condition):
    source = request(ids=(101, 102))
    candidate = payload(source, relation=relation)
    candidate["assessments"]["CHIP_MAKER"]["finding101"]["condition"] = condition
    original = deepcopy(candidate)
    original_source = source.model_dump_json(by_alias=True)
    # The shape remains valid; semantic contract validation owns this failure.
    Draft202012Validator(draft_schema(source)).validate(draft_to_wire(candidate, source))

    with pytest.raises(ReportAssessmentDraftValidationError, match="구체적 전제") as caught:
        validate_draft(response(candidate, source), source)

    assert caught.value.failed_finding_ids == (101,)
    assert candidate == original
    assert source.model_dump_json(by_alias=True) == original_source


@pytest.mark.parametrize(
    "condition",
    [
        "고객이 해당 공정을 검증 대상으로 채택하는 경우",
        "‘claimId/sourceSpanId가 원문과 일치함’이라는 설명과 별개로 고객 승인이 필요한 경우",
        "claimId/sourceSpanId가 원문과 일치한다는 뜻은 아니다. 고객 승인이 필요한 경우",
        "claimId/sourceSpanId가 원문과 일치하지 않는 경우 고객의 검증 승인이 필요한 경우",
        "claimId/sourceSpanId가 원문과 일치하며 고객의 검증 승인이 필요한 경우",
        "claimId/sourceSpanId가 원문과 일치함; 고객의 검증 승인이 필요한 경우",
        "‘원문 사건·조건이 해당 관점 업무 자체다.’라는 설명과 별개로 고객 승인이 필요한 경우",
        "원문 사건·조건이 해당 관점 업무 자체라는 뜻은 아니다. 고객 승인이 필요한 경우",
        "원문 사건·조건이 해당 관점 업무 자체가 아닌 것은 아니다. 고객 승인이 필요한 경우",
        "원문 사건·조건이 해당 관점 업무 자체다. 고객이 해당 공정을 채택하는 경우",
        "`/claims=[]`라는 표기와 별개로 고객이 해당 공정을 채택하는 경우",
        "'/claims=[]'는 원문의 JSON 표기를 인용한 것이다.",
        "/claims=[]가 아니다. 고객이 해당 공정을 채택하는 경우",
        '고객이 해당 공정을 채택하는 경우. 예시 JSON: {"claims": []}',
        "/claims=[]; 고객의 검증 승인이 필요한 경우",
    ],
)
def test_quotes_negation_and_concrete_prerequisites_are_preserved(condition):
    source = request(text="제조사는 고객의 공정 검증 승인에 따라 계약의 납품 일정을 정한다.")
    candidate = payload(source, relation="CONDITIONAL")
    record = candidate["assessments"]["CHIP_MAKER"]["finding101"]
    record.update(
        condition=condition,
        impactScope="UNDETERMINED",
        impactBasis=None,
        urgencyState="UNDETERMINED",
        urgencyBasis=None,
    )
    original = deepcopy(candidate)

    result = validate_draft(response(candidate, source), source)

    assert result.draft.model_dump(by_alias=True) == original
    assert result.mapped.insights[0].assessments[0].axes.impact is None


@pytest.mark.parametrize("condition", [METADATA_CONDITION, "claimId가 원문과 일치함."])
def test_actual_source_api_fields_are_not_mistaken_for_output_metadata(condition):
    source = request(text=API_SOURCE)
    candidate = payload(source, relation="CONDITIONAL")
    record = candidate["assessments"]["CHIP_MAKER"]["finding101"]
    record.update(
        condition=condition,
        reason="공정 검증 결과 수신에 필요한 API 필드의 일치 여부를 확인한다.",
        impactScope="UNDETERMINED",
        impactBasis=None,
        urgencyState="UNDETERMINED",
        urgencyBasis=None,
    )

    result = validate_draft(response(candidate, source), source)

    assert result.evidence["CHIP_MAKER"][101].condition == condition
    assert result.mapped.insights[0].assessments[0].axes.directness == 2


@pytest.mark.parametrize("condition", ["/claims=[]", "claims = [ ]"])
def test_source_api_empty_field_is_not_a_false_declaration_of_missing_evidence(condition):
    source = request(
        text="연계 API는 claims=[] 상태이면 고객의 공정 검증 결과를 다시 수신하도록 설계됐다."
    )
    candidate = payload(source, relation="CONDITIONAL")
    candidate["assessments"]["CHIP_MAKER"]["finding101"].update(
        condition=condition,
        reason="공정 검증 결과 수신 조건을 원문의 API 응답 상태와 대조한다.",
        impactScope="UNDETERMINED",
        impactBasis=None,
        urgencyState="UNDETERMINED",
        urgencyBasis=None,
    )

    result = validate_draft(response(candidate, source), source)

    assert source.findings[0].claims
    assert result.evidence["CHIP_MAKER"][101].condition == condition
    assert result.mapped.insights[0].assessments[0].axes.impact is None


@pytest.mark.parametrize("location", ["title", "other_finding", "unselected_claim"])
@pytest.mark.parametrize(
    "condition,api_source",
    [
        (METADATA_CONDITION, API_SOURCE),
        ("/claims=[]", "API의 claims=[] 응답에서는 고객의 공정 검증 결과를 다시 수신한다."),
    ],
)
def test_api_exception_needs_the_selected_claim_and_its_linked_sentences(
    location, condition, api_source
):
    source = request(ids=(101, 102))
    raw = source.model_dump(mode="json", by_alias=True)
    if location == "title":
        raw["findings"][0]["articleTitle"] = api_source
    elif location == "other_finding":
        raw["findings"][1]["claims"][0]["text"] += api_source
        raw["findings"][1]["sentences"][0]["text"] += api_source
    else:
        raw["findings"][0]["claims"].append(
            {
                "id": "101:1",
                "text": api_source,
                "claimType": "FACT",
                "attributedTo": None,
                "evidenceSentenceIds": [1],
            }
        )
        raw["findings"][0]["sentences"].append({"index": 1, "text": api_source})
    source = ReportInsightRequest.model_validate(raw)
    candidate = payload(source, relation="CONDITIONAL")
    candidate["assessments"]["CHIP_MAKER"]["finding101"]["condition"] = condition

    with pytest.raises(ReportAssessmentDraftValidationError, match="구체적 전제") as caught:
        validate_draft(response(candidate, source), source)

    assert caught.value.failed_finding_ids == (101,)


@pytest.mark.parametrize("condition", [METADATA_CONDITION, DEFINITION_CONDITION, "/claims=[]"])
def test_persistent_pseudo_premise_uses_one_repair_then_fails_without_regrading(condition):
    source = request()

    def add_pseudo_premise(stage, occurrence, _, value):
        if stage.startswith("MAP"):
            for record in value["assessments"]["CHIP_MAKER"].values():
                record["condition"] = condition
        return value

    provider = V4Provider(source, relation="CONDITIONAL", hook=add_pseudo_premise)

    with pytest.raises(AgentError) as caught:
        generate(provider, source)

    assert caught.value.code == "SCHEMA_VIOLATION"
    assert stages(provider) == ["MAP-001", "MAP-001"]
    assert caught.value.details["usage"]["inputTokens"] == 22
