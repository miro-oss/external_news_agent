"""Semantic report guard regressions, independent of live model quality scores."""

import json
from copy import deepcopy
from datetime import date
from types import SimpleNamespace

import pytest
from test_report_insight import output, request_body

from app.llm.base import ProviderResponse, ProviderUsage
from app.llm.report_insight_guard import (
    has_blanket_insufficient_headline,
    report_reference_date,
    validate_report_time,
)
from app.llm.report_insight_service import _validate_prose, _validated_output
from app.schemas.report_insight import ReportInsightRequest


def validate_prose(value, source, **kwargs):
    _validate_prose(
        [value], ["501:0"], {"501:0": source}, {"501:0": SimpleNamespace(text=source)}, **kwargs
    )


@pytest.mark.parametrize(
    "value", ["이 관점의 관련 근거가 부족합니다.", "관련 근거 부족", "해당 관점의 관련 근거는 없음"]
)
def test_blanket_insufficiency_identifies_the_empty_fallback(value):
    assert has_blanket_insufficient_headline(value)


def test_specific_missing_condition_does_not_erase_other_related_evidence():
    payload = output()
    payload["insights"][0]["headline"] = (
        "검증 준비 일정 관련 근거가 부족해 목표 조건 확인이 필요하다."
    )
    assert not has_blanket_insufficient_headline(payload["insights"][0]["headline"])
    _validated_output(
        ProviderResponse(json.dumps(payload), "openai", "offline", ProviderUsage()),
        ReportInsightRequest.model_validate(request_body()),
    )


@pytest.mark.parametrize(
    "value",
    [
        "소비자 광고는 장비 검증이나 인증 일정과 관련이 없다.",
        "소비자 광고는 생산·공정·인증·운영 판단과 관련 없음.",
        "소비자 광고는 인프라 조달·운영과 관련 없는 마케팅 활동이다.",
        "소비자 광고는 생산 중단과 관련 없으며 시장 기대에 영향을 미치지 않는다.",
    ],
)
def test_audience_relevance_negation_does_not_reverse_the_article_fact(value):
    validate_prose(value, "유통사는 소비자 스마트폰 할인 행사를 안내했다.")


@pytest.mark.parametrize(
    "value",
    [
        "계약 금액 정보 부재로 신규 설비 발주 규모 및 일정 파악이 어렵다.",
        "신규 설비 발주 계약 금액 미제공은 인프라 투자 규모와 일정 판단에 제약이 있다.",
        "신규 설비 발주 계약 금액 정보 부재로 인프라 조달 규모 판단이 불가능하다.",
    ],
)
def test_missing_information_synonyms_are_not_positive_contract_assertions(value):
    validate_prose(value, "자료에는 신규 설비 발주 계약 금액이 제공되지 않았다.")


@pytest.mark.parametrize(
    "value",
    [
        "양산 목표를 2027년으로 발표했으나 검증 준비 일정이 확정되지 않아 판단이 어렵다.",
        "양산 목표와 검증 준비 발표는 계획이지만 검증 완료는 확인되지 않아 평가가 제한된다.",
        "양산 목표 발표만으로 설치 진행 여부를 확정할 수 없다.",
    ],
)
def test_epistemic_limits_do_not_assert_cancellation_or_completed_installation(value):
    validate_prose(value, "기업은 2027년 양산을 목표로 장비 검증을 준비한다고 발표했다.")


def test_negative_certification_synonym_retains_original_uncompleted_state():
    validate_prose(
        "고객사의 인증 미완료 발표는 인증 일정의 불확실성을 높일 수 있다.",
        "고객사는 인증이 아직 완료되지 않았다고 발표했다.",
    )


@pytest.mark.parametrize(
    "topic", ["반도체 설비 투자", "공정 인증 완료 여부", "장비 발주 계약 금액"]
)
def test_topic_labels_do_not_assert_events(topic):
    validate_prose(topic, "자료에는 신규 설비 발주 계약 금액이 제공되지 않았다.", topic=True)


@pytest.mark.parametrize(
    ("value", "source"),
    [
        ("계약이 없으므로 확인이 필요하다.", "장비 계약을 체결했다."),
        ("발주가 중단되어 검토가 필요하다.", "장비 발주 계약을 체결했다."),
        ("인증 미완료를 발표했다.", "인증을 완료했다."),
        ("인증을 완료했다.", "인증이 아직 완료되지 않았다."),
        ("완료 여부가 중요하며 인증을 완료했다.", "인증이 아직 완료되지 않았다."),
        ("관련 없으며 계약이 취소됐다.", "계약을 체결했다."),
        ("자료에 계약 금액이 공개됐다.", "자료에 계약 금액이 공개되지 않았다."),
    ],
)
def test_analytical_prefix_or_synonym_does_not_hide_reversed_facts(value, source):
    with pytest.raises(ValueError, match="사실값"):
        validate_prose(value, source)


@pytest.mark.parametrize(
    "value",
    ["근거 없는 계약 금액은 200억원이다.", "2028년 양산 예정이다.", "삼성전자가 양산을 계획했다."],
)
def test_scoped_negation_guard_preserves_number_date_and_entity_guards(value):
    with pytest.raises(ValueError, match="사실값"):
        validate_prose(value, "기업은 2027년 양산 계획을 발표했다.")


def test_another_findings_short_actor_cannot_be_cited_through_the_current_finding():
    with pytest.raises(ValueError, match="사실값"):
        validate_prose(
            "A사가 완료를 발표했으나 고객 C사는 인증 미완료를 발표했다.",
            "A사는 공정 인증을 완료했다고 발표했다.",
        )


def test_another_contract_cannot_be_smuggled_into_an_assessment_as_a_bare_fact():
    with pytest.raises(ValueError, match="사실값"):
        validate_prose(
            "양산 계획 발표이며 장비 계약은 별도 사실이다.", "양산을 계획한다고 발표했다."
        )


def dated_request(
    source="공정 인증 검증 기한을 2024년 10월로 발표했다.",
    *,
    report_date="2026-09-25",
    report_end_date=None,
    published_at="2026-09-24",
):
    body = request_body()
    body["report"].update(reportDate=report_date, reportEndDate=report_end_date)
    body["findings"][0]["publishedAt"] = published_at
    body["findings"][0]["claims"][0].update(text=source, claimType="FACT")
    body["findings"][0]["sentences"][0]["text"] = source
    return ReportInsightRequest.model_validate(body)


@pytest.mark.parametrize(
    "value",
    ["2024년 10월 기한이 임박했다.", "검증 마감이 다가오고 있다.", "검증 일정이 가까워지고 있다."],
)
def test_past_month_deadline_cannot_be_described_as_upcoming(value):
    with pytest.raises(ValueError, match="이미 지난"):
        validate_report_time(value, ["501:0"], dated_request())


def test_past_deadline_alone_cannot_receive_the_highest_urgency():
    with pytest.raises(ValueError, match="urgency=3"):
        validate_report_time("일정에 영향을 줄 수 있다.", ["501:0"], dated_request(), urgency=3)


def test_past_deadline_implementation_watch_is_permitted():
    validate_report_time(
        "지난 검증 기한의 실제 이행 여부를 확인해야 한다.", ["501:0"], dated_request(), urgency=1
    )


def test_current_unresolved_state_can_support_urgent_response_after_old_deadline():
    request = dated_request("2024년 10월 검증 기한이 지났으며 현재 생산 중단 상태가 지속되고 있다.")
    validate_report_time("현재 생산 중단 상황에 대응할 필요가 있다.", ["501:0"], request, urgency=3)
    with pytest.raises(ValueError, match="이미 지난"):
        validate_report_time("2024년 10월 기한이 임박했다.", ["501:0"], request, urgency=3)


def test_month_only_deadline_is_not_past_before_month_end():
    request = dated_request("검증 기한은 2026년 9월이다.")
    validate_report_time("검증 기한이 임박했다.", ["501:0"], request, urgency=3)


def test_report_end_date_takes_priority_over_start_date():
    request = dated_request("검증 기한은 2026년 9월 27일이다.", report_end_date="2026-09-30")
    assert report_reference_date(request).isoformat() == "2026-09-30"
    with pytest.raises(ValueError, match="이미 지난"):
        validate_report_time("검증 기한이 임박했다.", ["501:0"], request)


def test_published_date_fallback_is_fixed_by_the_request_and_relative_date_uses_article():
    request = dated_request("인증 기한은 내일이다.", report_date=None, published_at="2026-09-20")
    other = deepcopy(request.findings[0])
    other.id = 502
    other.article_id = 11
    other.published_at = date(2026, 9, 25)
    other.claims[0].id = "502:0"
    request.findings.append(other)
    assert report_reference_date(request).isoformat() == "2026-09-25"
    with pytest.raises(ValueError, match="이미 지난"):
        validate_report_time("인증 기한이 임박했다.", ["501:0"], request)


def test_missing_all_dates_does_not_use_the_machine_clock():
    request = dated_request(report_date=None, published_at=None)
    assert report_reference_date(request) is None
    validate_report_time("기한이 임박했다.", ["501:0"], request)


def test_a_future_deadline_does_not_make_explicitly_mentioned_old_deadline_upcoming():
    request = dated_request("2024년 10월 검증 기한이 있었으며 재검증 마감은 2027년 10월이다.")
    validate_report_time("재검증 마감이 다가온다.", ["501:0"], request, urgency=3)
    with pytest.raises(ValueError, match="이미 지난"):
        validate_report_time("2024년 10월 기한이 임박했다.", ["501:0"], request)


def test_service_final_validation_applies_date_guard_to_map_axes_and_headline():
    request = dated_request()
    candidate = output()
    insight = candidate["insights"][0]
    insight.update(
        headline="검증 일정 판단이 필요하다.", overview=[], implications=[], watchItems=[]
    )
    insight["assessments"][0].update(
        reason="공정 인증 검증 기한이 발표되어 일정 판단에 영향을 준다."
    )
    insight["assessments"][0]["axes"]["urgency"] = 3
    with pytest.raises(ValueError, match="urgency=3"):
        _validated_output(
            ProviderResponse(
                text=json.dumps(candidate), provider="test", model="test", usage=ProviderUsage()
            ),
            request,
        )
    insight["assessments"][0]["axes"]["urgency"] = 1
    insight["headline"] = "2024년 10월 검증 기한이 임박했다."
    with pytest.raises(ValueError, match="이미 지난"):
        _validated_output(
            ProviderResponse(
                text=json.dumps(candidate), provider="test", model="test", usage=ProviderUsage()
            ),
            request,
        )


def test_related_final_output_must_not_omit_report_synthesis_but_map_shell_may():
    request = ReportInsightRequest.model_validate(request_body())
    candidate = output()
    candidate["insights"][0].update(overview=[], implications=[], watchItems=[])
    provider_response = ProviderResponse(
        text=json.dumps(candidate), provider="test", model="test", usage=ProviderUsage()
    )
    with pytest.raises(ValueError, match="종합 항목"):
        _validated_output(provider_response, request)
    _validated_output(provider_response, request, require_synthesis=False)


def test_related_final_output_cannot_claim_its_retrieved_evidence_is_absent():
    request = ReportInsightRequest.model_validate(request_body())
    candidate = output()
    candidate["insights"][0]["headline"] = "이 관점의 관련 근거가 부족합니다."
    response = ProviderResponse(
        text=json.dumps(candidate), provider="test", model="test", usage=ProviderUsage()
    )
    with pytest.raises(ValueError, match="관련 근거 부족"):
        _validated_output(response, request)


@pytest.mark.parametrize(
    "value",
    [
        "계약이 없으면 발주 조건을 다시 검토할 필요가 있다.",
        "계약이 취소될 경우 발주 조건이 달라질 수 있다.",
        "인증 미완료 여부를 확인할 필요가 있다.",
    ],
)
def test_explicit_negative_hypothetical_or_status_question_is_not_a_fact_reversal(value):
    validate_prose(value, "장비 발주 계약을 체결했고 인증을 완료했다.")


def test_cross_source_conflict_must_cite_both_sides_or_an_explicit_source_conflict():
    from app.llm.report_insight_guard import validate_report_citations

    with pytest.raises(ValueError, match="양쪽 claim"):
        validate_report_citations(
            "인증 상충으로 고객 계약 진행에 차질이 생길 수 있다.",
            ["501:0"],
            "고객사는 인증이 아직 완료되지 않았다고 발표했다.",
        )
    validate_report_citations(
        "인증 완료 여부의 보도가 상충하므로 판단을 보류해야 한다.",
        ["501:0", "502:0"],
        "인증을 완료했다고 발표했다.\n인증이 아직 완료되지 않았다고 발표했다.",
    )
    validate_report_citations(
        "원문상 상충된 인증 결과를 확인해야 한다.",
        ["501:0"],
        "고객사는 양쪽 인증 발표가 상충된다고 밝혔다.",
    )
    validate_report_citations(
        "인증 발표가 상충할 경우 다시 검토한다.", ["501:0"], "인증을 완료했다.", conditional=True
    )
    validate_report_citations("인증 발표 상충 여부", ["501:0"], "인증을 완료했다.", topic=True)


@pytest.mark.parametrize(
    "source",
    [
        "A사는 P공정 생산라인 가동을 즉시 중단한다고 발표했다.",
        "A사는 P공정 생산라인 가동이 즉시 중단된다고 발표했다.",
        "A사는 P공정 생산라인 가동을 잠정적으로 중단한다고 발표했다.",
    ],
)
def test_production_halt_summary_keeps_polarity_across_particles_and_adverbs(source):
    validate_prose(
        "생산라인 가동 중단 발표는 인프라 운영 판단에 직접적 영향을 미치지 않으며, 관련성 낮음.",
        source,
    )


def test_alias_normalization_does_not_allow_operating_assertion_for_halted_line():
    with pytest.raises(ValueError, match="사실값"):
        validate_prose(
            "생산라인 가동을 했다.",
            "A사는 P공정 생산라인 가동을 즉시 중단한다고 발표했다.",
        )


def test_final_output_without_overview_preserves_substantive_implications_and_watch():
    request = ReportInsightRequest.model_validate(request_body())
    candidate = output()
    candidate["insights"][0]["overview"] = []
    response = ProviderResponse(
        text=json.dumps(candidate), provider="test", model="test", usage=ProviderUsage()
    )
    result = _validated_output(response, request)
    assert result.insights[0].overview == []
    assert result.insights[0].implications
    assert result.insights[0].watch_items
