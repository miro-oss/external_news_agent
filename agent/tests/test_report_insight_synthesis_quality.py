"""Real synthesis failures and conservative subject/modality regressions."""

from copy import deepcopy

import pytest

from app.core.errors import OutputValidationError
from app.llm.report_insight_synthesis_quality import (
    synthesis_evidence_frames,
    validate_synthesis_quality,
)
from app.schemas.report_insight import ReportInsightReduceAudience, ReportInsightRequest
from tests.test_report_insight import request_body


def source_request(*, claim=None, sentences=None, claim_type="FACT", attribution=None):
    body = request_body(audiences=["EQUIPMENT_MAKER"])
    finding = body["findings"][0]
    finding.update(id=7750, articleTitle="태성 장비 수주 동향")
    texts = sentences or [
        "그로쓰리서치는 태성에 대해 삼성전기의 FC-BGA 투자와 증설이 "
        "중장기 장비 수주 확대를 이끌 것으로 전망했다.",
        "삼성전기는 FC-BGA 증설에 투자할 계획이다.",
    ]
    finding["claims"] = [
        {
            "id": "7750:0",
            "text": claim
            or "그로쓰리서치는 삼성전기의 FC-BGA 투자와 증설이 수주 확대를 이끌 것으로 전망한다.",
            "claimType": claim_type,
            "attributedTo": attribution,
            "evidenceSentenceIds": list(range(len(texts))),
        }
    ]
    finding["sentences"] = [{"index": index, "text": text} for index, text in enumerate(texts)]
    return ReportInsightRequest.model_validate(body)


def insight(*, text=None, implication=None, refs=None, headline="근거에 따른 업무 조건 확인"):
    refs = refs or ["7750:0"]
    return ReportInsightReduceAudience.model_validate(
        {
            "audience": "EQUIPMENT_MAKER",
            "headline": headline,
            "overview": [
                {"text": text, "basisClaimIds": refs, "assumption": "계획이 유지되는 경우"}
            ]
            if text
            else [],
            "implications": [{**implication, "basisClaimIds": refs}] if implication else [],
            "watchItems": [],
        }
    )


def validate(node, request):
    return validate_synthesis_quality(
        node, request, [c.id for f in request.findings for c in f.claims]
    )


def test_real_equipment_overview_does_not_swap_investor_and_recipient_or_realize_the_plan():
    request = source_request()
    node = insight(text="태성의 FC-BGA 투자 확대 및 수주 증가가 현재 관측되고 있다.")

    with pytest.raises(OutputValidationError) as caught:
        validate(node, request)

    assert "report_synthesis_subject_mismatch" in caught.value.error_kinds
    assert "report_synthesis_stage_overreach" in caught.value.error_kinds
    assert "태성" in str(caught.value)
    assert "overview[0].text" in str(caught.value)


def test_matching_investor_still_cannot_turn_forecast_into_observed_investment():
    with pytest.raises(OutputValidationError) as caught:
        validate(insight(text="삼성전기의 FC-BGA 투자가 현재 집행되고 있다."), source_request())
    assert caught.value.error_kinds == ("report_synthesis_stage_overreach",)


@pytest.mark.parametrize(
    "text",
    [
        "삼성전기의 FC-BGA 투자 계획은 태성의 수주 확대 가능성과 연결된다.",
        "삼성전기의 투자가 집행된다면 태성의 장비 수주를 확인할 필요가 있다.",
        "태성의 투자가 집행되는 경우 공급 능력을 추가 확인한다.",
        "삼성전기의 투자 수혜를 태성의 실제 장비 수주로 단정할 수 없다.",
        "SEMCO의 투자 계획이 유지되면 장비 수주를 확인한다.",
        "삼성의 투자 계획을 검토할 필요가 있다.",
    ],
)
def test_plans_conditions_beneficiaries_and_undeclared_shorthand_are_not_false_rejections(text):
    validate(insight(text=text), source_request())


def test_actual_order_in_another_cited_claim_does_not_upgrade_the_investment_event():
    request = source_request()
    body = request.model_dump(by_alias=True, mode="json")
    f = body["findings"][0]
    actual = "수주 증가에 따라 장비 리드타임도 최근 길어졌다."
    f["claims"].append(
        {
            "id": "7750:2",
            "text": actual,
            "claimType": "FACT",
            "attributedTo": None,
            "evidenceSentenceIds": [2],
        }
    )
    f["sentences"].append({"index": 2, "text": actual})
    request = ReportInsightRequest.model_validate(body)
    validate(insight(text="태성의 수주 증가가 현재 관측되고 있다.", refs=["7750:2"]), request)
    with pytest.raises(OutputValidationError) as caught:
        validate(
            insight(text="삼성전기의 투자가 현재 집행되고 있다.", refs=["7750:0", "7750:2"]),
            request,
        )
    assert caught.value.error_kinds == ("report_synthesis_stage_overreach",)


def test_other_actor_realized_investment_does_not_realize_this_actors_plan():
    claim = "삼성전기는 투자를 계획했고 태성은 투자를 완료했다."
    request = source_request(claim=claim, sentences=[claim])
    validate(insight(text="태성의 투자는 완료됐다."), request)
    with pytest.raises(OutputValidationError) as caught:
        validate(insight(text="삼성전기의 투자가 현재 집행되고 있다."), request)
    assert "report_synthesis_stage_overreach" in caught.value.error_kinds


@pytest.mark.parametrize(
    "text",
    [
        "SEMCO의 투자가 현재 집행되고 있다.",
        "Samsung Electro-Mechanics' investment is currently underway.",
    ],
)
def test_explicit_foreign_alias_matches_the_same_subject(text):
    source = "삼성전기(SEMCO)는 투자를 완료했으며 태성(Taesung)은 장비를 공급했다."
    english = "Samsung Electro-Mechanics (SEMCO)'s investment is currently underway."
    request = source_request(claim=source, sentences=[source, english])
    validate(insight(text=text), request)


def test_english_possessive_does_not_transfer_the_investment_to_the_supplier():
    source = "Samsung Electro-Mechanics' investment is underway; Taesung supplies equipment."
    request = source_request(claim=source, sentences=[source])
    with pytest.raises(OutputValidationError) as caught:
        validate(insight(text="Taesung's investment is currently underway."), request)
    assert caught.value.error_kinds == ("report_synthesis_subject_mismatch",)


@pytest.mark.parametrize(
    "text",
    [
        "삼성전기에 의해 투자가 집행됐다.",
        "삼성전기의 투자가 집행됐다.",
        "투자 집행이 현재 관측되고 있다.",
    ],
)
def test_correct_passive_subjects_or_unnamed_actors_are_not_rejected(text):
    source = "삼성전기에 의해 투자가 집행됐다. 태성은 장비를 공급했다."
    validate(insight(text=text), source_request(claim=source, sentences=[source]))


def test_passive_agent_and_investment_owner_can_be_different_without_being_a_reversal():
    source = "삼성전기의 투자 자금은 태성에 의해 집행됐다."
    validate(
        insight(text="태성에 의해 투자가 집행됐다."),
        source_request(claim=source, sentences=[source]),
    )


def test_bare_factual_mention_does_not_claim_the_source_was_only_a_plan():
    source = "삼성전기 투자 확대. 태성 수주 증가."
    validate(
        insight(text="삼성전기의 투자 확대가 현재 관측된다."),
        source_request(claim=source, sentences=[source]),
    )


@pytest.mark.parametrize(
    "mechanism", ["근거 → 결합 → 업무 판단", "source -> event -> decision", "TBD"]
)
def test_complete_mechanism_placeholders_are_rejected(mechanism):
    node = insight(
        implication={
            "text": "계획이 유지되면 검증 준비 조건을 확인한다.",
            "mechanism": mechanism,
            "assumption": "계획이 유지되는 경우",
            "falsifiedBy": "계획이 철회되는 경우",
        }
    )
    with pytest.raises(OutputValidationError) as caught:
        validate(node, source_request())
    assert caught.value.error_kinds == ("report_synthesis_placeholder",)


def power_request(*, actual_effect=False):
    source = "PJM은 인공지능 데이터센터 전력 부족 대응을 위한 경매 계획을 중단했다."
    texts = [source]
    if actual_effect:
        texts.append("PJM의 경매 중단으로 데이터센터 전력 공급 안정성에 영향이 발생했다.")
    return source_request(claim=source, sentences=texts)


def power_implication(**changes):
    data = {
        "text": "PJM의 전력 경매 중단은 데이터센터의 전력 공급에 영향을 미칠 수 있다.",
        "mechanism": "대체 조달 방안이 없다면 데이터센터 전력 공급 안정성에 영향이 생길 수 있다.",
        "assumption": "경매 중단이 전력 공급에 영향을 미치는 것으로 확인됨.",
        "falsifiedBy": "추가 전력 공급 계획 또는 공급량 증대 관련 근거가 없는 경우.",
    }
    return {**data, **changes}


def test_real_market_confirmation_and_reversed_falsification_are_both_reported():
    with pytest.raises(OutputValidationError) as caught:
        validate(insight(implication=power_implication()), power_request())
    assert caught.value.error_kinds == (
        "report_assumption_unconfirmed",
        "report_falsification_direction",
    )


def test_condition_and_alternative_capacity_are_valid_assumption_and_falsification():
    node = insight(
        implication=power_implication(
            assumption="경매 중단이 공급에 영향을 미치고 대체 조달 방안이 없는 경우",
            falsifiedBy="추가 전력 공급 계획이나 대체 공급 확보가 확인되는 경우",
        )
    )
    validate(node, power_request())


def test_actual_cited_supply_effect_can_be_described_as_confirmed():
    node = insight(
        implication=power_implication(
            falsifiedBy="대체 전력 공급 확보가 확인되는 경우",
        )
    )
    validate(node, power_request(actual_effect=True))


def test_conditional_confirmation_and_withdrawn_absence_are_not_false_reversals():
    node = insight(
        implication=power_implication(
            assumption="경매 중단이 전력 공급에 영향을 미치는 것으로 확인되는 경우",
            falsifiedBy="추가 전력 공급이 없다는 판단이 철회되는 경우",
        )
    )
    validate(node, power_request())


def test_absence_of_additional_supply_can_refute_an_explicit_positive_supply_hypothesis():
    node = insight(
        implication=power_implication(
            text="전력 경매 중단이 대체 전력 공급 확대를 촉진할 수 있다.",
            mechanism="대체 조달이 추진된다면 추가 전력 공급이 증가할 수 있다.",
            assumption="경매 중단에 대응해 대체 조달이 추진되는 경우",
        )
    )
    validate(node, power_request())


def test_framing_preserves_claim_types_attribution_sentence_ids_and_source_snapshot():
    request = source_request(claim_type="OPINION", attribution="김 연구원")
    original = deepcopy(request.model_dump(by_alias=True))
    frames = synthesis_evidence_frames(request, ["7750:0"])
    assert request.model_dump(by_alias=True) == original
    assert {frame["claimId"] for frame in frames} == {"7750:0"}
    assert {frame["claimType"] for frame in frames} == {"OPINION"}
    assert {frame["attributedTo"] for frame in frames} == {"김 연구원"}
    assert {frame["evidenceSentenceId"] for frame in frames} == {None, 0, 1}
    assert all(
        event["stage"] == "planned_or_conditional"
        for frame in frames
        for event in frame["parsedEvents"]
    )
    assert synthesis_evidence_frames(request, ["unknown:0"]) == []


def test_real_implication_cannot_use_an_actor_only_present_in_another_claim():
    request = source_request()
    body = request.model_dump(by_alias=True, mode="json")
    extra = deepcopy(body["findings"][0])
    source = "PJM은 전력 부족 대응을 위한 경매 계획을 중단했다."
    extra.update(
        id=7749,
        articleId=11,
        claims=[
            {
                "id": "7749:0",
                "text": source,
                "claimType": "FACT",
                "attributedTo": None,
                "evidenceSentenceIds": [0],
            }
        ],
        sentences=[{"index": 0, "text": source}],
    )
    body["findings"].append(extra)
    request = ReportInsightRequest.model_validate(body)
    node = insight(
        implication={
            "text": "공급망 조건을 검토할 필요가 있다.",
            "mechanism": "PJM의 경매 중단과 태성의 투자 확대가 공급망 안정화를 유도한다.",
            "assumption": "두 사건이 공급에 영향을 미치는 경우",
            "falsifiedBy": "경매 계획이 재개되는 경우",
        },
        refs=["7749:0"],
    )
    with pytest.raises(OutputValidationError) as caught:
        validate(node, request)
    assert caught.value.error_kinds == ("report_synthesis_reference_gap",)
    assert "implications[0].mechanism" in str(caught.value)
    assert "태성" in str(caught.value)
    validate(insight(text="태성의 투자 규모 확인이 필요하다.", refs=["7749:0"]), request)


def test_overview_assumption_cannot_call_an_unobserved_supply_effect_confirmed():
    node = insight(text="PJM은 전력 경매 계획을 중단했다.")
    node.overview[0].assumption = "경매 중단이 전력 공급에 영향을 미치는 것으로 확인됨."
    with pytest.raises(OutputValidationError) as caught:
        validate(node, power_request())
    assert caught.value.error_kinds == ("report_assumption_unconfirmed",)
    assert "overview[0].assumption" in str(caught.value)


def test_surface_processing_is_not_misread_as_a_conditional_suffix():
    source = "삼성전기는 표면 처리 장비 투자를 계획했다. 태성은 장비를 생산한다."
    with pytest.raises(OutputValidationError) as caught:
        validate(
            insight(text="삼성전기의 표면 처리 장비 투자가 현재 집행되고 있다."),
            source_request(claim=source, sentences=[source]),
        )
    assert caught.value.error_kinds == ("report_synthesis_stage_overreach",)


def test_a_denied_auction_halt_does_not_trigger_the_halted_auction_specific_rule():
    source = "PJM은 전력 경매를 중단하지 않았다."
    node = insight(implication=power_implication())
    # General polarity checks live in the existing service, not this narrow rule.
    validate(node, source_request(claim=source, sentences=[source]))
