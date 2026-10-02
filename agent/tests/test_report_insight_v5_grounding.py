"""Regression cases for pseudo connections, source ownership and stage context."""

import json
from copy import deepcopy

import pytest
from jsonschema import Draft202012Validator
from test_report_insight_assessment import (
    framed,
    payload,
    request,
    response,
    validate_flat,
)
from test_report_insight_v4_pipeline import V4Provider, generate, stages

from app.core.errors import AgentError
from app.llm.report_insight_assessment import (
    ReportAssessmentDraftValidationError,
    draft_schema,
    validate_draft,
)
from app.llm.report_insight_instructions import report_stage_instruction
from app.llm.report_insight_service import _decision_candidates

ROLES = ["CHIP_MAKER", "EQUIPMENT_MAKER", "MARKET_INVESTOR", "IT_INFRA"]


@pytest.mark.parametrize(
    "condition",
    [
        "원문에 구체적 업무 연결 조건이 명시되지 않음",
        "구체적 업무 연결 조건이 명확하지 않음",
        "원문에 구체적인 연결 조건이 제시되지 않음",
        "업무 연결 경로가 미확인",
        "원문 정보가 부족함",
        "미확인",
        "불명.",
        "판단 보류",
        "알 수 없음",
    ],
)
def test_missing_metadata_cannot_mint_a_background_work_connection(condition):
    source = request(text="지역 문화센터는 생활 강좌 모집을 시작했다.")
    candidate = payload(source, relation="BACKGROUND")
    candidate["assessments"]["CHIP_MAKER"]["finding101"]["condition"] = condition
    with pytest.raises(ReportAssessmentDraftValidationError, match="구체적 전제"):
        validate_flat(candidate, source)
    assert source.findings[0].claims[0].text == "지역 문화센터는 생활 강좌 모집을 시작했다."


@pytest.mark.parametrize(
    "condition",
    [
        "고객이 해당 공정을 검증 대상으로 채택하는 경우",
        "해당 계측 장비의 검증 결과가 같은 공정 승인에 필요한 경우",
        "계약 제품의 냉각 요건이 해당 서버 도입안에 적용되는 경우",
        "공급 범위가 명확하지 않은 계약에 고객의 추가 검증 승인이 필요한 경우",
    ],
)
def test_concrete_prerequisite_is_not_rejected_for_containing_uncertainty(condition):
    source = request(
        text="제조사는 고객 승인에 따른 공정 검증과 서버 도입안의 냉각 요건을 점검한다. "
        "생산라인 전체의 가동 중단이 현재 계속된다."
    )
    candidate = payload(source, relation="CONDITIONAL")
    candidate["assessments"]["CHIP_MAKER"]["finding101"]["condition"] = condition
    validated = validate_flat(candidate, source)
    assert validated.evidence["CHIP_MAKER"][101].condition == condition


def test_pseudo_connection_failure_is_not_silently_demoted_or_hidden_by_synthesis():
    source = request()

    def bad_connection(stage, occurrence, _, value):
        if stage.startswith("MAP"):
            for item in value["assessments"]["CHIP_MAKER"].values():
                item["condition"] = "원문에 구체적 업무 연결 조건이 명시되지 않음"
        return value

    provider = V4Provider(source, relation="BACKGROUND", hook=bad_connection)
    with pytest.raises(AgentError) as caught:
        generate(provider, source)
    assert caught.value.code == "SCHEMA_VIOLATION"
    assert stages(provider) == ["MAP-001", "MAP-001"]
    assert caught.value.details["usage"]["inputTokens"] == 22


@pytest.mark.parametrize("audience", ROLES)
@pytest.mark.parametrize("stage", ["MAP", "REVIEW", "REDUCE"])
def test_stage_instruction_only_teaches_requested_role_without_fictional_response_examples(
    audience, stage
):
    instruction = report_stage_instruction([audience], stage)
    assert len(instruction) < 3500
    assert audience in instruction
    assert all(role not in instruction for role in ROLES if role != audience)
    assert '"assessments":' not in instruction
    assert '"insights":' not in instruction
    assert '"finding11"' not in instruction
    assert "가상 원문" not in instruction
    if stage == "REDUCE":
        assert "report-importance.v6" not in instruction
    else:
        assert "report-importance.v6" in instruction


def test_customer_supply_contract_guidance_preserves_direct_relationship_without_invented_specs():
    instruction = report_stage_instruction(["CHIP_MAKER"], "MAP")
    assert "실제 고객 공급 계약" in instruction
    assert "공정·수율이 없다는 이유로 그 계약을 무관 처리하지 않는다" in instruction
    assert "계약은 생산 증가·규격 승인·납품 완료를 뜻하지 않는다" in instruction


def test_reassessment_context_is_independent_and_preserves_source_identity_and_time():
    source = request(ids=(101, 102))
    original = source.model_dump_json(by_alias=True)
    provider = V4Provider(source)
    result = generate(provider, source)
    review = next(
        call for call in provider.calls if "REVIEW" in call["response_schema"]["description"]
    )
    context = framed(review["prompt"])
    assert "previousDraft" not in context
    assert context["reportReferenceDate"] == "2026-09-25"
    assert [item["claims"][0]["id"] for item in context["findings"]] == ["101:0", "102:0"]
    assert source.model_dump_json(by_alias=True) == original
    assert result.meta.prompt_version == "report-insight.ko.v9"


def test_native_contract_selects_category_before_fields_without_changing_accepted_wire():
    source = request()
    schema = draft_schema(source)
    record = schema["properties"]["assessments"]["properties"]["CHIP_MAKER"]["properties"][
        "finding101"
    ]["properties"]
    for field, category in (
        ("connection", "relation"),
        ("effect", "impactScope"),
        ("timing", "urgencyState"),
    ):
        for branch in record[field]["anyOf"]:
            assert list(branch["properties"])[0] == category
    native = response(payload(source), source)
    Draft202012Validator(schema).validate(json.loads(native.text))
    assert validate_draft(native, source).mapped.insights[0].assessments[0].axes.directness == 3


def test_reduce_candidates_preserve_distinct_owner_and_literal_evidence_without_public_reasons():
    source = request(ids=(101, 102), text='제조사 M은 "검증  준비" 계획을 발표했다.')
    values = payload(source)
    values["assessments"]["CHIP_MAKER"]["finding102"]["work"] = "CUSTOMER_REQUIREMENTS"
    validated = validate_flat(values, source)
    original = source.model_dump_json(by_alias=True)
    candidates = _decision_candidates(source, validated, {"CHIP_MAKER": ("101:0", "102:0")})
    assert [group["work"] for group in candidates["CHIP_MAKER"]] == [
        "PROCESS_QUALIFICATION",
        "CUSTOMER_REQUIREMENTS",
    ]
    first = candidates["CHIP_MAKER"][0]["findings"][0]
    assert first["connectionBasis"] == {
        "claimId": "101:0",
        "sourceSpanId": "s101_0_0",
        "text": '제조사 M은 "검증  준비" 계획을 발표했다.',
        "claimType": "FACT",
        "attributedTo": None,
    }
    assert "reason" not in first
    assert first["relation"] == "DIRECT"
    assert source.model_dump_json(by_alias=True) == original


def test_reduce_input_hides_title_ranking_metadata_and_never_leaks_unretrieved_proofs():
    source = request(ids=(101, 102))
    provider = V4Provider(source)
    generate(provider, source)
    reduce_context = framed(provider.calls[-1]["prompt"])
    assert "assessedPriorities" not in reduce_context
    evidence = reduce_context["retrievedEvidence"][0]["evidence"]
    assert all(
        not {"articleTitle", "canonicalUrl", "topicName", "score"} & item.keys()
        for item in evidence
    )
    permitted = {item["claimId"] for item in evidence}
    for group in reduce_context["decisionCandidates"]["CHIP_MAKER"]:
        for finding in group["findings"]:
            for key in ("connectionBasis", "impactBasis", "urgencyBasis"):
                assert finding[key] is None or finding[key]["claimId"] in permitted


def test_an_unretrieved_connection_cannot_become_a_reduce_candidate():
    source = request(ids=(101, 102))
    validated = validate_flat(payload(source), source)
    candidates = _decision_candidates(source, validated, {"CHIP_MAKER": ("102:0",)})
    assert [
        finding["findingId"] for group in candidates["CHIP_MAKER"] for finding in group["findings"]
    ] == [102]
    copy = deepcopy(candidates)
    copy["CHIP_MAKER"][0]["findings"][0]["connectionBasis"]["text"] = "변경"
    assert source.findings[1].claims[0].text != "변경"


def test_priority_navigation_uses_existing_score_then_original_equal_score_order():
    source = request(ids=(103, 101, 102))
    values = payload(source)
    for finding_id in (103, 101):
        draft = values["assessments"]["CHIP_MAKER"][f"finding{finding_id}"]
        draft["impactScope"] = "UNDETERMINED"
        draft["impactBasis"] = None
    validated = validate_flat(values, source)
    candidates = _decision_candidates(
        source, validated, {"CHIP_MAKER": ("103:0", "101:0", "102:0")}
    )["CHIP_MAKER"][0]["findings"]
    assert [finding["findingId"] for finding in candidates] == [102, 103, 101]
    assert [finding["priorityRank"] for finding in candidates] == [1, 2, 3]
    assert [finding["importanceGrade"] for finding in candidates] == [
        "high",
        "unavailable",
        "unavailable",
    ]
    assert candidates[0]["importanceScore"] == pytest.approx(3)
    assert candidates[1]["relation"] == "DIRECT"
    assert candidates[1]["importanceScore"] is None


@pytest.mark.parametrize(
    "text",
    [
        "원문에 구체적 업무 연결 조건이 명시되지 않음",
        "원문에 구체적 업무 연결 조건이 명확하지 않아 관계 판단을 보류합니다.",
        "원문은 있지만 관점 업무 연결 조건·범위를 판단할 정보가 부족합니다.",
        "구체적 업무 연결 조건이 명확하지 않습니다.",
    ],
)
def test_v5_metadata_only_overview_is_a_failure_not_a_successful_placeholder(text):
    source = request()

    def placeholder(stage, _, __, value):
        if stage.startswith("REDUCE"):
            value["insights"][0]["overview"][0]["text"] = text
        return value

    provider = V4Provider(source, hook=placeholder)
    with pytest.raises(AgentError) as caught:
        generate(provider, source)
    assert caught.value.code == "SCHEMA_VIOLATION"
    assert stages(provider) == ["MAP-001", "REVIEW-001", "REDUCE-001", "REDUCE-001"]
    assert "report_synthesis_metadata_only" in caught.value.__cause__.error_kinds


@pytest.mark.parametrize(
    "text",
    [
        "공급 계약은 확인됐지만 공급 수량과 납기는 미정이므로 고객 대응 범위를 보류한다.",
        "원문 정보는 부족하지만 실제 공급 계약이 확인돼 고객 대응을 검토한다.",
        "‘고객과 공급 계약을 체결했다’는 원문에 따라 공급 준비를 검토하되 수량은 보류한다.",
    ],
)
def test_known_fact_with_missing_scope_or_quoted_source_is_not_metadata_only(text):
    source = request(text="제조사는 고객과 공급 계약을 체결했다. 공급 수량과 납기는 미정이다.")

    def known_contract(stage, _, __, value):
        if stage.startswith("REDUCE"):
            value["insights"][0]["headline"] = "공급 약정에 맞춰 고객 대응을 검토한다."
            value["insights"][0]["overview"][0]["text"] = text
            value["insights"][0]["overview"][0]["assumption"] = "같은 공급 계약의 범위일 때"
        return value

    provider = V4Provider(source, hook=known_contract)
    assert generate(provider, source).insights[0].overview[0].text == text
    assert stages(provider) == ["MAP-001", "REVIEW-001", "REDUCE-001"]
