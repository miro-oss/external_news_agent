import json

import pytest

from app.core.config import Settings
from app.core.errors import AgentError
from app.llm.base import ProviderResponse, ProviderUsage
from app.llm.report_service import ReportWriterService
from app.schemas.report import ReportRequest


class FakeProvider:
    def __init__(self, *responses: ProviderResponse) -> None:
        self.responses = list(responses)
        self.prompts: list[str] = []

    def generate(
        self, *, system_instruction: str, prompt: str, response_schema: dict
    ) -> ProviderResponse:
        assert "sourceFindingIds" in system_instruction
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
                    "keyPoints": ["양산 일정이 앞당겨졌다."],
                    "intent": "생산 계획 발표",
                    "sentiment": "positive",
                    "riskLevel": "high",
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
            "perspective": "TECHNOLOGY",
        }
    )


def provider_response(raw: str, input_tokens: int = 10, output_tokens: int = 5):
    return ProviderResponse(
        text=raw,
        provider="gemini",
        model="configured-model",
        usage=ProviderUsage(input_tokens=input_tokens, output_tokens=output_tokens),
    )


def valid_output(source_finding_ids: list[int] | None = None) -> str:
    return json.dumps(
        {
            "title": "2026-08-10 HBM 뉴스 모니터링 보고서",
            "executiveSummary": ["HBM4 양산 일정이 앞당겨졌다."],
            "importantEvents": [
                {
                    "title": "HBM4 양산 일정 단축",
                    "summaryKo": "HBM4 양산 일정이 앞당겨졌다.",
                    "significance": "공급 일정에 영향을 줄 수 있다.",
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
    assert "본문 수집에 실패한 기사가 1건" in response.markdown_body
    assert response.meta.prompt_version == "report.ko.v1"
    assert response.meta.mock is False


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
    assert len(provider.prompts) == 2


def test_mock_report_is_deterministic_and_keeps_structured_sections() -> None:
    response = ReportWriterService(Settings()).write(request())

    assert response.meta.provider == "mock"
    assert response.executive_summary == ["HBM4 양산 일정이 앞당겨졌다."]
    assert response.important_events[0].source_finding_ids == [501]
    assert response.watch_items == []
    assert response.markdown_body.startswith("# 2026-08-10 HBM 뉴스 모니터링 보고서")
