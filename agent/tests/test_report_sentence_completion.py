import json

import pytest
from test_report_service import FakeProvider, provider_response, request, valid_output

from app.core.config import Settings
from app.core.errors import AgentError
from app.llm.report_service import ReportWriterService, _limit_unsupported_significance
from app.schemas.report import ReportOutput, ReportRequest

TRUNCATED_APPLE_SUMMARY = (
    "애플이 CXMT D램 시험을 검토하는 가운데 AI용 HBM·서버 D램 수요가 기존 구매자 우위를 "
    "흔들어 범용 D램 공급 공백과 중국 내수 대체가 향후 경쟁 구도를 바꿀 가능성이 커"
)
COMPLETE_HUNDRED_CHAR_SUMMARY = (TRUNCATED_APPLE_SUMMARY + "졌다.").replace("기존 ", "")
LONG_SUMMARY = (
    "회사는 "
    + "HBM4 공급 일정과 고객별 생산 조건을 검토하면서 " * 6
    + "양산 계획을 확정하지 않았다."
)


def report_request(
    summary: str,
    *,
    key_point: str | None = None,
    claim_type: str = "FACT",
    attributed_to: str | None = None,
) -> ReportRequest:
    payload = request().model_dump(by_alias=True, mode="json")
    finding = payload["findings"][0]
    finding["summaryKo"] = summary
    finding["keyPoints"] = (
        [
            {
                "text": key_point,
                "evidence": [0],
                "groundedness": "grounded",
                "groundingReason": None,
                "claimType": claim_type,
                "attributedTo": attributed_to,
            }
        ]
        if key_point is not None
        else []
    )
    return ReportRequest.model_validate(payload)


def output_with_summary(summary: str) -> str:
    output = json.loads(valid_output())
    output["executiveSummary"] = [summary]
    output["importantEvents"][0]["summaryKo"] = summary
    output["importantEvents"][0]["significance"] = summary
    return json.dumps(output, ensure_ascii=False)


@pytest.mark.parametrize("field", ["executiveSummary", "importantEvents"])
def test_repairs_actual_hundred_character_fragment_before_accepting_report(field: str) -> None:
    assert len(TRUNCATED_APPLE_SUMMARY) == 100
    output = json.loads(valid_output())
    if field == "executiveSummary":
        output[field] = [TRUNCATED_APPLE_SUMMARY]
    else:
        output[field][0]["summaryKo"] = TRUNCATED_APPLE_SUMMARY
    provider = FakeProvider(
        provider_response(json.dumps(output, ensure_ascii=False), input_tokens=11, output_tokens=3),
        provider_response(valid_output(), input_tokens=7, output_tokens=5),
    )

    response = ReportWriterService(Settings(AGENT_MOCK=False), provider).write(request())

    assert len(provider.prompts) == 2
    assert "validation-error" in provider.prompts[1]
    assert response.executive_summary == ["HBM4 양산 일정이 앞당겨졌다."]
    assert response.important_events[0].summary_ko == "HBM4 양산 일정이 앞당겨졌다."
    assert response.meta.input_tokens == 18
    assert response.meta.output_tokens == 8


def test_repeated_incomplete_output_fails_with_accumulated_usage() -> None:
    raw = output_with_summary(TRUNCATED_APPLE_SUMMARY)
    provider = FakeProvider(
        provider_response(raw, input_tokens=11, output_tokens=3),
        provider_response(raw, input_tokens=7, output_tokens=5),
    )

    with pytest.raises(AgentError) as caught:
        ReportWriterService(Settings(AGENT_MOCK=False), provider).write(request())

    assert caught.value.code == "SCHEMA_VIOLATION"
    assert caught.value.details["usage"]["inputTokens"] == 18
    assert caught.value.details["usage"]["outputTokens"] == 8
    assert len(provider.prompts) == 2


@pytest.mark.parametrize("field", ["executiveSummary", "importantEvents"])
@pytest.mark.parametrize(
    "fragment",
    ["AI용 HBM 서버 D램 수요", "삼성전자와 SK하이닉스보다", "삼성전자와 SK하이닉스보다."],
)
def test_korean_noun_and_comparison_fragments_trigger_repair(field: str, fragment: str) -> None:
    output = json.loads(valid_output())
    if field == "executiveSummary":
        output[field] = [fragment]
    else:
        output[field][0]["summaryKo"] = fragment
    provider = FakeProvider(
        provider_response(json.dumps(output, ensure_ascii=False)),
        provider_response(valid_output()),
    )

    response = ReportWriterService(Settings(AGENT_MOCK=False), provider).write(request())

    assert len(provider.prompts) == 2
    assert "validation-error" in provider.prompts[1]
    assert response.executive_summary == ["HBM4 양산 일정이 앞당겨졌다."]
    assert response.important_events[0].summary_ko == "HBM4 양산 일정이 앞당겨졌다."


@pytest.mark.parametrize("mock", [True, False])
@pytest.mark.parametrize(
    "summary",
    ["이 수치는 공급 과잉을 가늠할 정보다.", "CXMT는 애플의 새로운 공급 후보다."],
)
def test_complete_copula_endings_are_preserved_without_repair(mock: bool, summary: str) -> None:
    provider = None if mock else FakeProvider(provider_response(output_with_summary(summary)))

    response = ReportWriterService(Settings(AGENT_MOCK=mock), provider).write(
        report_request(summary)
    )

    assert response.executive_summary == [summary]
    assert response.important_events[0].summary_ko == summary
    if provider is not None:
        assert len(provider.prompts) == 1


def test_complete_sentence_at_exact_length_limit_is_preserved_without_repair() -> None:
    summary = COMPLETE_HUNDRED_CHAR_SUMMARY
    assert len(summary) == 100
    provider = FakeProvider(provider_response(output_with_summary(summary)))

    response = ReportWriterService(Settings(AGENT_MOCK=False), provider).write(
        report_request(summary)
    )

    assert response.executive_summary == [summary]
    assert response.important_events[0].summary_ko == summary
    assert summary in response.markdown_body
    assert len(provider.prompts) == 1


@pytest.mark.parametrize("mock", [True, False])
def test_long_finding_fallback_uses_whole_short_key_point(mock: bool) -> None:
    short = "회사는 HBM4 공급 일정의 검토를 진행했다."
    provider = (
        None
        if mock
        else FakeProvider(
            provider_response(
                output_with_summary("애플은 신규 노트북 가격을 30% 인상했다.")
            )
        )
    )

    response = ReportWriterService(Settings(AGENT_MOCK=mock), provider).write(
        report_request(LONG_SUMMARY, key_point=short)
    )

    assert response.executive_summary == [short]
    assert response.important_events[0].summary_ko == short
    if mock:
        assert response.important_events[0].significance == LONG_SUMMARY
    else:
        assert len(provider.prompts) == 1


def test_long_findings_without_short_candidates_keep_distinct_events_and_full_details() -> None:
    first = report_request(LONG_SUMMARY).findings[0]
    second_summary = LONG_SUMMARY.replace("회사는", "다른 회사는")
    second = first.model_copy(
        update={
            "id": 502,
            "article_id": 1025,
            "article_title": "다른 회사의 HBM4 공급 일정 검토",
            "canonical_url": "https://example.com/1025",
            "summary_ko": second_summary,
        }
    )
    payload = request().model_copy(update={"findings": [first, second]})

    response = ReportWriterService(Settings(AGENT_MOCK=True)).write(payload)

    assert response.executive_summary
    for summary in response.executive_summary:
        assert len(summary) <= 100
        assert summary.endswith(("다.", "요."))
        assert summary != LONG_SUMMARY[:100]
    assert [event.source_finding_ids for event in response.important_events] == [[501], [502]]
    assert [event.significance for event in response.important_events] == [
        LONG_SUMMARY,
        second_summary,
    ]
    for event in response.important_events:
        assert len(event.summary_ko) <= 150
        assert event.summary_ko.endswith(("다.", "요."))
        assert event.summary_ko != event.significance[:150]
    assert LONG_SUMMARY in response.markdown_body
    assert second_summary in response.markdown_body


def test_summary_notice_cannot_be_replaced_by_unrelated_finding_with_shared_subject() -> None:
    first = report_request(LONG_SUMMARY).findings[0]
    office_summary = "회사는 신규 사옥을 열었다."
    second = first.model_copy(
        update={
            "id": 502,
            "article_id": 1025,
            "article_title": "신규 사옥 개소",
            "canonical_url": "https://example.com/1025",
            "summary_ko": office_summary,
        }
    )

    response = ReportWriterService(Settings(AGENT_MOCK=True)).write(
        request().model_copy(update={"findings": [first, second]})
    )

    assert len(response.executive_summary) == 2
    notice, office = response.executive_summary
    assert notice != office_summary
    assert len(notice) <= 100
    assert notice.endswith(("다.", "요."))
    assert office == office_summary
    assert [event.source_finding_ids for event in response.important_events] == [[501], [502]]
    first_event = response.important_events[0]
    assert first_event.summary_ko == notice
    assert first_event.significance == LONG_SUMMARY
    assert response.important_events[1].significance == office_summary


def test_same_finding_keeps_distinct_long_event_details_when_summaries_use_same_notice() -> None:
    construction = (
        "회사는 "
        + "신규 공장 건설 부지와 생산 설비의 투자 조건을 검토하면서 " * 6
        + "공장 건설 계획을 확정하지 않았다."
    )
    acquisition = (
        "회사는 "
        + "경쟁사 인수 비용과 계약 조건에 대한 실사 결과를 검토하면서 " * 6
        + "경쟁사 인수 계획을 확정하지 않았다."
    )
    assert len(construction) > 150
    assert len(acquisition) > 150
    report = report_request(construction, key_point=acquisition)
    output = ReportOutput.model_validate(json.loads(valid_output()))
    event = output.important_events[0]
    # Exercise whole grounding replacements before applying display limits.
    output = output.model_copy(
        update={
            "important_events": [
                event.model_copy(
                    update={
                        "title": "신규 공장 건설 계획 검토",
                        "summary_ko": construction,
                        "significance": construction,
                    }
                ),
                event.model_copy(
                    update={
                        "title": "경쟁사 인수 계획 검토",
                        "summary_ko": acquisition,
                        "significance": acquisition,
                    }
                ),
            ]
        }
    )
    settings = Settings(AGENT_MOCK=True)

    edited = _limit_unsupported_significance(
        output,
        report,
        grounded_overlap=settings.evidence_grounded_overlap,
        weak_overlap=settings.evidence_weak_overlap,
    )

    assert len(edited.important_events) == 2
    first, second = edited.important_events
    assert first.summary_ko == second.summary_ko
    assert len(first.summary_ko) <= 150
    assert first.summary_ko.endswith(("다.", "요."))
    assert [event.source_finding_ids for event in edited.important_events] == [[501], [501]]
    assert [event.significance for event in edited.important_events] == [construction, acquisition]


def test_short_forecast_fallback_preserves_future_qualifier() -> None:
    forecast = "회사는 HBM4 양산을 시작할 예정이다."
    provider = FakeProvider(
        provider_response(output_with_summary("회사는 HBM4 양산을 시작했다."))
    )

    response = ReportWriterService(Settings(AGENT_MOCK=False), provider).write(
        report_request(LONG_SUMMARY, key_point=forecast, claim_type="FORECAST")
    )

    assert response.executive_summary == [forecast]
    assert response.important_events[0].summary_ko == forecast
    assert response.important_events[0].significance == forecast
    assert len(provider.prompts) == 1


def test_opinion_length_is_checked_after_adding_attribution() -> None:
    opinion = COMPLETE_HUNDRED_CHAR_SUMMARY
    attributed = f"김 연구원은 {opinion}"
    assert len(opinion) == 100
    assert 100 < len(attributed) <= 150
    source = opinion + " 후속 수요와 공급 여건에 대한 추가 검토가 필요하다." * 3

    response = ReportWriterService(Settings(AGENT_MOCK=True)).write(
        report_request(
            source,
            key_point=opinion,
            claim_type="OPINION",
            attributed_to="김 연구원",
        )
    )

    assert response.executive_summary
    for summary in response.executive_summary:
        assert len(summary) <= 100
        assert summary.endswith(("다.", "요."))
        assert summary != opinion
        assert summary != attributed[:100]
    assert response.important_events[0].summary_ko == attributed
    assert response.important_events[0].significance == attributed


def test_short_opinion_keeps_attribution_in_both_summary_fields() -> None:
    opinion = "HBM4 시장 수요가 개선될 것이라는 해석이다."
    attributed = f"김 연구원은 {opinion}"

    response = ReportWriterService(Settings(AGENT_MOCK=True)).write(
        report_request(
            LONG_SUMMARY,
            key_point=opinion,
            claim_type="OPINION",
            attributed_to="김 연구원",
        )
    )

    assert response.executive_summary == [attributed]
    assert response.important_events[0].summary_ko == attributed


def test_long_forecast_never_loses_qualifier_to_fit_summary_limit() -> None:
    forecast = (
        "회사는 "
        + "HBM4 공급 일정과 고객별 생산 조건을 검토하면서 " * 6
        + "양산을 시작할 예정이다."
    )

    response = ReportWriterService(Settings(AGENT_MOCK=True)).write(
        report_request(forecast, key_point=forecast, claim_type="FORECAST")
    )

    assert response.executive_summary
    for summary in response.executive_summary:
        assert len(summary) <= 100
        assert summary.endswith(("다.", "요."))
        assert summary != forecast[:100]
    event = response.important_events[0]
    assert len(event.summary_ko) <= 150
    assert event.summary_ko.endswith(("다.", "요."))
    assert event.summary_ko != forecast[:150]
    assert event.significance == forecast
