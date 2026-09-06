import json

import pytest
from pydantic import ValidationError

from app.core.config import Settings
from app.core.errors import AgentError
from app.llm.base import ProviderResponse, ProviderUsage
from app.llm.report_service import ReportWriterService
from app.schemas.report import ReportOutput, ReportRequest


class FakeProvider:
    def __init__(self, *responses: ProviderResponse) -> None:
        self.responses = list(responses)
        self.prompts: list[str] = []

    def generate(
        self, *, system_instruction: str, prompt: str, response_schema: dict
    ) -> ProviderResponse:
        assert "sourceFindingIds" in system_instruction
        assert "각 절이 서로 다른 finding 하나만으로도 독립적으로 확인" in system_instruction
        assert "executiveSummary는 최대 3개 항목" in system_instruction
        assert "summaryKo는 공백 포함 150자 이하" in system_instruction
        assert "인덱스 숫자를 노출하지 않는다" in system_instruction
        assert "ungrounded인 주장은 보고서 근거로 사용하지 않는다" in system_instruction
        assert response_schema["additionalProperties"] is False
        self.prompts.append(prompt)
        return self.responses.pop(0)


def request() -> ReportRequest:
    return ReportRequest.model_validate(
        {
            "idempotencyKey": "run:42:report",
            "plan": "FREE",
            "run": {
                "id": 42,
                "startedAt": "2026-08-10T09:00:00+09:00",
                "finishedAt": "2026-08-10T09:03:00+09:00",
                "topics": ["HBM"],
            },
            "findings": [
                {
                    "id": 501,
                    "articleId": 1024,
                    "articleTitle": "HBM4 양산 일정 단축",
                    "canonicalUrl": "https://example.com/1024",
                    "sourceName": "Example News",
                    "changeType": "NEW",
                    "summaryKo": "HBM4 양산 일정이 앞당겨졌다.",
                    "keyPoints": [
                        {
                            "text": "양산 일정이 앞당겨졌다.",
                            "evidence": [0],
                            "groundedness": "grounded",
                            "groundingReason": None,
                            "claimType": "FACT",
                            "attributedTo": None,
                        }
                    ],
                    "intent": "생산 계획 발표",
                    "sentiment": "positive",
                    "sensitivity": {
                        "score": 100,
                        "level": "high",
                        "axes": {
                            "customerMove": {"score": 3, "evidenceSentenceIds": [0]},
                            "dealSignal": {"score": None, "evidenceSentenceIds": []},
                            "competitorThreat": {"score": 3, "evidenceSentenceIds": [0]},
                            "industryShift": {"score": 3, "evidenceSentenceIds": [0]},
                        },
                    },
                    "relevance": "important",
                    "category": "제품/공정",
                    "fetchStatus": "FULLTEXT",
                }
            ],
            "events": [],
            "sourceStats": {
                "collected": 5,
                "blocked": 2,
                "failed": 1,
                "paywalled": 1,
                "stubExcluded": 3,
            },
            "sourceNotes": [
                "수집 제약: STUB 분석 3건 제외, 페이월 1건, 접근 제한 1건, 수집 실패 1건."
            ],
        }
    )


def provider_response(raw: str, input_tokens: int = 10, output_tokens: int = 5):
    return ProviderResponse(
        text=raw,
        provider="openai",
        model="configured-model",
        usage=ProviderUsage(input_tokens=input_tokens, output_tokens=output_tokens),
    )


def valid_output(
    source_finding_ids: list[int] | None = None,
    *,
    significance: str = "HBM4 양산 일정이 앞당겨졌다.",
) -> str:
    return json.dumps(
        {
            "title": "2026-08-10 HBM 뉴스 모니터링 보고서",
            "executiveSummary": ["HBM4 양산 일정이 앞당겨졌다."],
            "importantEvents": [
                {
                    "title": "HBM4 양산 일정 단축",
                    "summaryKo": "HBM4 양산 일정이 앞당겨졌다.",
                    "significance": significance,
                    "sourceFindingIds": source_finding_ids or [501],
                }
            ],
            "watchItems": [],
            "sourceNotes": ["제공된 finding만 사용했다."],
        },
        ensure_ascii=False,
    )


def test_generates_structured_report_and_deterministic_markdown() -> None:
    provider = FakeProvider(provider_response(valid_output()))

    response = ReportWriterService(Settings(AGENT_MOCK=False), provider).write(request())

    assert response.title == "2026-08-10 HBM 뉴스 모니터링 보고서"
    assert response.executive_summary == ["HBM4 양산 일정이 앞당겨졌다."]
    assert response.important_events[0].source_finding_ids == [501]
    assert "## 경영진 요약" in response.markdown_body
    assert "[HBM4 양산 일정 단축](<https://example.com/1024>)" in response.markdown_body
    assert "STUB 분석 3건" in response.markdown_body
    assert "페이월" in response.markdown_body
    assert "수집 실패 1건" in response.markdown_body
    assert response.meta.prompt_version == "report.ko.v1.4"
    assert response.meta.mock is False


def test_replaces_unsupported_significance_without_another_provider_call() -> None:
    provider = FakeProvider(
        provider_response(valid_output(significance="공급망 병목이 완전히 해결된다."))
    )

    response = ReportWriterService(Settings(AGENT_MOCK=False), provider).write(request())

    assert len(provider.prompts) == 1
    assert response.important_events[0].significance == "HBM4 양산 일정이 앞당겨졌다."


def test_ungrounded_key_point_cannot_support_report_significance() -> None:
    payload = request().model_dump(by_alias=True, mode="json")
    payload["findings"][0]["keyPoints"].append(
        {
            "text": "공급망 병목이 완전히 해결된다.",
            "evidence": [1],
            "groundedness": "ungrounded",
            "groundingReason": "근거에서 확인되지 않습니다.",
            "claimType": "FACT",
            "attributedTo": None,
        }
    )
    provider = FakeProvider(
        provider_response(valid_output(significance="공급망 병목이 완전히 해결된다."))
    )

    response = ReportWriterService(Settings(AGENT_MOCK=False), provider).write(
        ReportRequest.model_validate(payload)
    )

    assert response.important_events[0].significance == "HBM4 양산 일정이 앞당겨졌다."


def test_revalidates_forecast_written_as_completed_fact() -> None:
    payload = request().model_dump(by_alias=True, mode="json")
    payload["findings"][0]["summaryKo"] = "회사는 HBM4 양산을 시작할 예정이다."
    payload["findings"][0]["keyPoints"] = [
        {
            "text": "회사는 HBM4 양산을 시작할 예정이다.",
            "evidence": [0],
            "groundedness": "grounded",
            "groundingReason": None,
            "claimType": "FORECAST",
            "attributedTo": None,
        }
    ]
    provider = FakeProvider(
        provider_response(valid_output(significance="회사는 HBM4 양산을 시작했다."))
    )

    response = ReportWriterService(Settings(AGENT_MOCK=False), provider).write(
        ReportRequest.model_validate(payload)
    )

    assert response.important_events[0].significance == ("회사는 HBM4 양산을 시작할 예정이다.")


def test_revalidates_every_related_key_point_before_accepting_claim() -> None:
    payload = request().model_dump(by_alias=True, mode="json")
    payload["findings"][0]["summaryKo"] = "회사는 HBM4 양산을 시작했다."
    payload["findings"][0]["keyPoints"] = [
        {
            "text": "회사는 HBM4 양산을 시작했다.",
            "evidence": [0],
            "groundedness": "grounded",
            "groundingReason": None,
            "claimType": "FACT",
            "attributedTo": None,
        },
        {
            "text": "회사는 HBM4 양산을 시작할 예정이다.",
            "evidence": [0],
            "groundedness": "grounded",
            "groundingReason": "향후 계획입니다.",
            "claimType": "FORECAST",
            "attributedTo": None,
        },
    ]
    provider = FakeProvider(
        provider_response(valid_output(significance="회사는 HBM4 양산을 시작했다."))
    )

    response = ReportWriterService(Settings(AGENT_MOCK=False), provider).write(
        ReportRequest.model_validate(payload)
    )

    assert response.important_events[0].significance == ("회사는 HBM4 양산을 시작할 예정이다.")


def test_editor_removes_duplicate_report_items() -> None:
    raw = json.loads(valid_output())
    raw["executiveSummary"].append(raw["executiveSummary"][0])
    raw["importantEvents"].append(dict(raw["importantEvents"][0]))
    provider = FakeProvider(provider_response(json.dumps(raw, ensure_ascii=False)))

    response = ReportWriterService(Settings(AGENT_MOCK=False), provider).write(request())

    assert response.executive_summary == ["HBM4 양산 일정이 앞당겨졌다."]
    assert len(response.important_events) == 1


def test_reinserts_opinion_attribution_during_final_validation() -> None:
    payload = request().model_dump(by_alias=True, mode="json")
    payload["findings"][0]["summaryKo"] = "시장 수요가 개선될 것이라는 해석이다."
    payload["findings"][0]["keyPoints"] = [
        {
            "text": "시장 수요가 개선될 것이라는 해석이다.",
            "evidence": [0],
            "groundedness": "grounded",
            "groundingReason": "발화 주체와 함께 확인됩니다.",
            "claimType": "OPINION",
            "attributedTo": "김 연구원",
        }
    ]
    provider = FakeProvider(
        provider_response(valid_output(significance="시장 수요가 개선될 것이라는 해석이다."))
    )

    response = ReportWriterService(Settings(AGENT_MOCK=False), provider).write(
        ReportRequest.model_validate(payload)
    )

    assert response.important_events[0].significance.startswith("김 연구원은")


def test_fallback_candidate_preserves_opinion_attribution() -> None:
    payload = request().model_dump(by_alias=True, mode="json")
    payload["findings"][0]["keyPoints"] = [
        {
            "text": "시장 수요가 개선될 것이라는 해석이다.",
            "evidence": [0],
            "groundedness": "grounded",
            "groundingReason": "발화 주체와 함께 확인됩니다.",
            "claimType": "OPINION",
            "attributedTo": "김 연구원",
        }
    ]
    raw = json.loads(valid_output())
    raw["importantEvents"][0]["summaryKo"] = (
        "시장 공급망 병목 해소 및 신규 고객 확보로 매출이 크게 증가했다."
    )
    provider = FakeProvider(provider_response(json.dumps(raw, ensure_ascii=False)))

    response = ReportWriterService(Settings(AGENT_MOCK=False), provider).write(
        ReportRequest.model_validate(payload)
    )

    assert response.important_events[0].summary_ko.startswith("김 연구원은")


def test_repairs_unknown_finding_reference_once_and_accumulates_usage() -> None:
    provider = FakeProvider(
        provider_response(valid_output([999]), input_tokens=10, output_tokens=3),
        provider_response(valid_output(), input_tokens=4, output_tokens=5),
    )

    response = ReportWriterService(Settings(AGENT_MOCK=False), provider).write(request())

    assert len(provider.prompts) == 2
    assert "validation-error" in provider.prompts[1]
    assert response.meta.input_tokens == 14
    assert response.meta.output_tokens == 8


def test_fails_after_exactly_one_repair_for_invalid_output() -> None:
    provider = FakeProvider(
        provider_response(valid_output([999])),
        provider_response(valid_output([999])),
    )

    with pytest.raises(AgentError) as caught:
        ReportWriterService(Settings(AGENT_MOCK=False), provider).write(request())

    assert caught.value.code == "SCHEMA_VIOLATION"
    assert caught.value.details == {
        "usage": {
            "inputTokens": 20,
            "outputTokens": 10,
            "costUsd": 0.0,
            "credits": 0.0,
        },
        "truncated": False,
    }
    assert len(provider.prompts) == 2


def test_mock_report_is_deterministic_and_keeps_structured_sections() -> None:
    response = ReportWriterService(Settings()).write(request())

    assert response.meta.provider == "mock"
    assert response.executive_summary == ["HBM4 양산 일정이 앞당겨졌다."]
    assert response.important_events[0].source_finding_ids == [501]
    assert response.important_events[0].significance == "HBM4 양산 일정이 앞당겨졌다."
    assert response.watch_items == []
    assert response.markdown_body.startswith("# 2026-08-10 HBM 뉴스 모니터링 보고서")


@pytest.mark.parametrize("mock", [True, False])
def test_daily_report_uses_collection_date_and_keeps_evidence_references(mock: bool) -> None:
    payload = request().model_dump(by_alias=True, mode="json")
    payload["run"].update(
        id=None,
        reportScope="DAILY",
        reportId=77,
        reportDate="2026-08-10",
        startedAt="2026-08-10T00:00:00+09:00",
        finishedAt="2026-08-11T00:00:00+09:00",
    )
    provider = None if mock else FakeProvider(provider_response(valid_output()))
    response = ReportWriterService(Settings(AGENT_MOCK=mock), provider).write(
        ReportRequest.model_validate(payload)
    )
    assert response.title == "2026-08-10 일일 통합 뉴스 보고서"
    assert response.markdown_body.startswith(f"# {response.title}\n")
    assert sum(line.startswith("# ") for line in response.markdown_body.splitlines()) == 1
    assert response.important_events[0].source_finding_ids == [501]
    assert response.source_notes == request().source_notes


@pytest.mark.parametrize(
    "context",
    [
        {"id": None},
        {"reportScope": "DAILY", "reportId": 77, "reportDate": "2026-08-10"},
        {"id": None, "reportScope": "DAILY", "reportDate": "2026-08-10"},
        {"id": None, "reportScope": "DAILY", "reportId": 77},
        {"reportScope": "RUN", "reportDate": "2026-08-10"},
    ],
)
def test_rejects_mixed_run_and_daily_context(context: dict) -> None:
    payload = request().model_dump(by_alias=True, mode="json")
    payload["run"].update(context)
    with pytest.raises(ValidationError):
        ReportRequest.model_validate(payload)


def test_mock_report_preserves_source_notes_order_wording_and_duplicates() -> None:
    payload = request().model_dump(by_alias=True, mode="json")
    payload["sourceNotes"] = ["첫 줄  두 칸\n유지", "중복 메모", "중복 메모"]
    report_request = ReportRequest.model_validate(payload)

    response = ReportWriterService(Settings()).write(report_request)

    assert response.source_notes == report_request.source_notes


@pytest.mark.parametrize(
    "canonical_url",
    [
        "https://example.com/path\nnext",
        "https://example.com/<unsafe>",
        "https:///missing-host",
    ],
)
def test_rejects_unsafe_canonical_url(canonical_url: str) -> None:
    payload = request().model_dump(by_alias=True, mode="json")
    payload["findings"][0]["canonicalUrl"] = canonical_url

    with pytest.raises(ValidationError):
        ReportRequest.model_validate(payload)


def test_rejects_more_than_fifty_findings() -> None:
    payload = request().model_dump(by_alias=True, mode="json")
    payload["findings"] = [
        {**payload["findings"][0], "id": index, "articleId": index + 1000} for index in range(1, 52)
    ]

    with pytest.raises(ValidationError):
        ReportRequest.model_validate(payload)


@pytest.mark.parametrize("evidence", [[-1], [0, 0], []])
def test_rejects_invalid_report_key_point_evidence(evidence: list[int]) -> None:
    payload = request().model_dump(by_alias=True, mode="json")
    payload["findings"][0]["keyPoints"][0]["evidence"] = evidence

    with pytest.raises(ValidationError):
        ReportRequest.model_validate(payload)


@pytest.mark.parametrize(
    "mutate",
    [
        lambda output: output["executiveSummary"].extend(
            ["두 번째 요약", "세 번째 요약", "네 번째 요약"]
        ),
        lambda output: output["executiveSummary"].__setitem__(0, "가" * 101),
        lambda output: output["importantEvents"][0].update({"summaryKo": "가" * 151}),
    ],
)
def test_rejects_report_output_outside_readable_length(mutate) -> None:
    output = json.loads(valid_output())
    mutate(output)

    with pytest.raises(ValidationError):
        ReportOutput.model_validate(output)


def test_mock_report_truncates_executive_summary_to_one_hundred_characters() -> None:
    payload = request().model_dump(by_alias=True, mode="json")
    payload["findings"][0]["summaryKo"] = "가" * 200

    response = ReportWriterService(Settings()).write(ReportRequest.model_validate(payload))

    assert len(response.executive_summary[0]) == 100
    assert len(response.important_events[0].summary_ko) == 150


def test_mock_report_does_not_repeat_important_finding_in_watch_items() -> None:
    payload = request().model_dump(by_alias=True, mode="json")
    payload["findings"][0]["relevance"] = "watch"

    response = ReportWriterService(Settings()).write(ReportRequest.model_validate(payload))

    assert response.important_events[0].source_finding_ids == [501]
    assert response.watch_items == []


def test_mock_report_also_revalidates_forecast_wording() -> None:
    payload = request().model_dump(by_alias=True, mode="json")
    payload["findings"][0]["summaryKo"] = "회사는 HBM4 양산을 시작했다."
    payload["findings"][0]["keyPoints"] = [
        {
            "text": "회사는 HBM4 양산을 시작할 예정이다.",
            "evidence": [0],
            "groundedness": "grounded",
            "groundingReason": "향후 계획입니다.",
            "claimType": "FORECAST",
            "attributedTo": None,
        }
    ]

    response = ReportWriterService(Settings()).write(ReportRequest.model_validate(payload))

    assert response.executive_summary == ["회사는 HBM4 양산을 시작할 예정이다."]
    assert response.important_events[0].summary_ko.endswith("예정이다.")


def test_markdown_escapes_markdown_metacharacters_without_html_entities() -> None:
    payload = request().model_dump(by_alias=True, mode="json")
    payload["findings"][0]["articleTitle"] = "TSMC & *삼성* [HBM]"
    response = ReportWriterService(Settings()).write(ReportRequest.model_validate(payload))

    assert "&amp;" not in response.markdown_body
    assert "TSMC & \\*삼성\\* \\[HBM\\]" in response.markdown_body


def test_uses_report_specific_provider_budget(monkeypatch: pytest.MonkeyPatch) -> None:
    captured: dict[str, float | int] = {}
    provider = FakeProvider(provider_response(valid_output()))

    def provider_for_report(settings: Settings, _: str) -> FakeProvider:
        captured["tokens"] = settings.max_output_tokens
        captured["timeout"] = settings.provider_timeout_seconds
        return provider

    monkeypatch.setattr("app.llm.report_service.get_analyze_provider", provider_for_report)
    settings = Settings(
        AGENT_MOCK=False,
        AGENT_REPORT_MAX_OUTPUT_TOKENS=12_000,
        AGENT_REPORT_PROVIDER_TIMEOUT_SECONDS=90,
    )

    ReportWriterService(settings).write(request())

    assert captured == {"tokens": 12_000, "timeout": 90.0}
