"""Protect topic input and grounded editorial order; mocked output is not an LLM quality score."""

import json

import pytest
from test_report_service import provider_response, request
from test_weekly_report import daily, output, topic_payload, write

from app.core.config import Settings
from app.llm.report_service import ReportWriterService
from app.schemas.report import ReportRequest


class Provider:
    def __init__(self, result):
        self.result = result
        self.calls = []

    def generate(self, **kwargs):
        self.calls.append(kwargs)
        return provider_response(json.dumps(self.result, ensure_ascii=False))


@pytest.mark.parametrize(
    ("topic", "fact", "forecast"),
    [
        (
            "AI·LLM 기술 및 산업 동향",
            "연구진이 언어모델 시제품을 공개했다.",
            "분석기관은 데이터센터 투자가 증가할 것으로 전망했다.",
        ),
        (
            "의료기기 정책 동향",
            "정부가 의료기기 심사 지침을 개정했다.",
            "분석기관은 의료기기 수출이 증가할 것으로 전망했다.",
        ),
    ],
)
def test_topic_and_grounded_editorial_order_survive_score_order_and_claim_validation(
    topic, fact, forecast
):
    payload = request().model_dump(by_alias=True, mode="json")
    payload["run"]["topics"] = [topic]
    template = payload["findings"][0]
    findings = []
    for id_, text, score, claim_type in [(501, forecast, 100, "FORECAST"), (502, fact, 30, "FACT")]:
        finding = json.loads(json.dumps(template))
        finding.update(id=id_, articleId=id_ + 1000, articleTitle=text, summaryKo=text)
        finding["sensitivity"].update(score=score, level="high" if score == 100 else "low")
        finding["keyPoints"][0].update(text=text, claimType=claim_type)
        findings.append(finding)
    payload["findings"] = findings
    generated = {
        "title": f"{topic} 보고서",
        "executiveSummary": [fact],
        "importantEvents": [{
            "title": fact,
            "summaryKo": fact,
            "significance": "세계 시장의 경쟁 구도가 완전히 바뀐다.",
            "sourceFindingIds": [502],
        }],
        "watchItems": [{"topic": "후속 전망 확인", "reason": forecast, "sourceFindingIds": [501]}],
        "sourceNotes": [],
    }
    provider = Provider(generated)
    response = ReportWriterService(Settings(AGENT_MOCK=False), provider).write(
        ReportRequest.model_validate(payload)
    )

    framed = provider.calls[0]["prompt"].split("<report-input>\n", 1)[1]
    framed = framed.split("\n</report-input>")[0]
    sent = json.loads(framed)
    assert sent["run"]["topics"] == [topic]
    assert [finding["id"] for finding in sent["findings"]] == [501, 502]
    assert "run.topics" in provider.calls[0]["system_instruction"]
    assert "반도체와 관련 제조 산업" not in provider.calls[0]["system_instruction"]
    assert response.executive_summary == [fact]
    assert [event.source_finding_ids for event in response.important_events] == [[502]]
    assert response.important_events[0].significance == fact
    assert response.watch_items[0].reason == forecast
    assert response.watch_items[0].source_finding_ids == [501]
    assert len(provider.calls) == 1


def test_weekly_preserves_editorial_priority_and_exact_saved_claims_for_non_semiconductor_topic():
    earlier = daily(text="분석기관은 데이터센터 투자가 증가할 것으로 전망했다.")
    earlier["structuredContent"]["importantEvents"][0]["title"] = "데이터센터 투자 전망"
    later = daily(1, 502, 20, "정부가 공공 언어모델 서비스를 공개했다.")
    later["structuredContent"]["importantEvents"][0]["title"] = "공공 언어모델 서비스 공개"
    data = topic_payload(earlier, later)
    data["topicName"] = "AI·LLM 기술 및 산업 동향"
    events = [source["structuredContent"]["importantEvents"][0] for source in [later, earlier]]
    response, provider = write(data, output(
        executiveSummary=[events[0]["summaryKo"]], importantEvents=events
    ))

    assert [event.source_finding_ids for event in response.important_events] == [[502], [501]]
    assert response.executive_summary == [events[0]["summaryKo"]]
    assert response.important_events[1].summary_ko.endswith("전망했다.")
    assert response.title.startswith(data["topicName"])
    assert len(provider.calls) == 1
    assert response.meta.prompt_version == "weekly-report.ko.v3"


def test_empty_topic_mock_does_not_assume_a_semiconductor_report():
    payload = request().model_dump(by_alias=True, mode="json")
    payload["run"]["topics"] = []
    response = ReportWriterService(Settings(AGENT_MOCK=True)).write(
        ReportRequest.model_validate(payload)
    )
    assert "반도체" not in response.title
    assert "일반" in response.title
