"""Scope/grounding regressions; scripted providers do not measure live editorial quality."""

import json

import pytest
from test_analyze_service import request as analysis_request
from test_analyze_service import valid_output as analysis_output
from test_report_service import provider_response
from test_report_service import request as report_request
from test_topic_relevance import (
    FakeProvider,
    ai_release_wire,
    request_payload,
    service,
)
from test_topic_relevance import provider_response as relevance_response

from app.core.config import Settings
from app.llm.analyze_service import ArticleAnalyzeService
from app.llm.report_service import ReportWriterService
from app.schemas.analyze import AnalyzeRequest
from app.schemas.report import ReportRequest
from app.schemas.topic_relevance import TopicRelevanceRequest


def decoded_block(prompt, tag):
    return json.loads(prompt.split(f"<{tag}>\n", 1)[1].split(f"\n</{tag}>", 1)[0])


@pytest.mark.parametrize(
    ("query", "fact"),
    [
        ("AI 모델 Agent 기술", "연구팀이 도구 호출을 지원하는 언어모델을 공개했다."),
        (
            "온톨로지 지식 그래프 적용",
            "개발자가 지식 그래프로 문서 관계를 연결하는 검색을 설명했다.",
        ),
        ("AI 데이터센터 투자", "회사가 AI 데이터센터 증설 계획을 발표했다."),
        ("반도체 공정", "회사가 반도체 공정 개선 결과를 발표했다."),
    ],
)
def test_topic_specific_facts_survive_without_manufacturing_interest_or_new_schema(query, fact):
    payload = analysis_request(fact).model_dump(by_alias=True, mode="json")
    payload["article"].update(title=fact, language="ko")
    payload["topic"].update(
        name=query, queryText=query, requiredKeywords=[], optionalKeywords=[],
    )
    generated = json.loads(analysis_output())
    generated.update(summaryKo=fact, entities={"companies": [], "products": [], "technologies": []})
    generated["sections"][0]["bullets"][0].update(text=fact)
    generated["classification"].update(intent="주제의 변화 또는 적용 설명", relevance="important")
    for index, axis in enumerate(generated["classification"]["sensitivity"].values()):
        axis.update(score=0 if index == 0 else None, evidenceSentenceIds=[1] if index == 0 else [])
    for tag in generated["perspectiveTags"]:
        tag.update(relevance="none", hook=None, evidenceSentenceIds=[])
    provider = FakeProvider(provider_response(json.dumps(generated, ensure_ascii=False)))

    response = ArticleAnalyzeService(Settings(), provider).analyze(
        AnalyzeRequest.model_validate(payload)
    )

    sent = decoded_block(provider.calls[0]["prompt"], "article-metadata")
    assert sent["topic"]["queryText"] == query
    instruction = provider.calls[0]["system_instruction"]
    assert instruction.startswith("너는 사용자가 지정한 주제의 자료")
    assert "넓은 '인공지능' 질의를 임의로 기술에만 한정" in instruction
    assert "회사 민감도와 관점 태그는 각 기준에 맞춰 별도로 판정" in instruction
    assert response.summary_ko == fact
    assert response.sections[0].bullets[0].text == fact
    assert response.sections[0].bullets[0].evidence_sentence_ids == [1]
    assert response.classification.sensitivity.customer_move.score == 0
    assert all(tag.relevance == "none" for tag in response.perspective_tags)


@pytest.mark.parametrize(
    ("query", "status", "assessment", "reason"),
    [
        (
            "AI 모델 Agent 알고리즘 기술", "IRRELEVANT", "G",
            "데이터센터 투자만 보도하며 모델이나 방법의 기술 변화는 설명하지 않았다.",
        ),
        (
            "AI 데이터센터 투자", "RELEVANT", "D",
            "AI 데이터센터 증설 투자를 발표했다.",
        ),
        (
            "인공지능", "RELEVANT", "D",
            "AI 사업을 위한 데이터센터 증설 투자를 발표했다.",
        ),
    ],
)
def test_relevance_preserves_explicit_scope_through_independent_review(
    query, status, assessment, reason
):
    payload = request_payload()
    payload["topic"].update(
        name="AI 동향", queryText=query, requiredKeywords=[], optionalKeywords=[],
    )
    payload["articles"] = [{
        "articleId": 501,
        "title": "AI 데이터센터 투자 발표",
        "summary": "회사가 AI 사업을 위한 데이터센터 증설 투자를 발표했다.",
        "bodyText": "회사가 AI 사업을 위한 데이터센터 증설 투자를 발표했다.",
    }]
    request = TopicRelevanceRequest.model_validate(payload)
    wire = ai_release_wire(request, status, assessment, reason)
    wire["decisions"]["article_501"]["evidenceQuotes"] = ["quote_1"]
    provider = FakeProvider(*[
        relevance_response(wire) for _ in range(2 if status == "IRRELEVANT" else 1)
    ])

    response = service(provider).classify(request)

    assert response.decisions[0].status == status
    assert response.decisions[0].evidence_quotes == [payload["articles"][0]["bodyText"]]
    for call in provider.calls:
        assert query in call["prompt"]
        assert "이 한정을 임의로 추가하지 않는다" in call["system_instruction"]
        assert "원리·적용 해설도 포함한다" in call["system_instruction"]


def test_report_keeps_developer_comparison_conditions_and_rejects_invented_savings():
    fact = (
        "개발자 김연구는 문서 10쪽 요약 실험에서 "
        "A 모델이 B 모델보다 토큰을 20% 덜 썼다고 밝혔다."
    )
    payload = report_request().model_dump(by_alias=True, mode="json")
    payload["run"]["topics"] = ["AI 모델·Agent 기술"]
    finding = payload["findings"][0]
    finding.update(
        articleTitle="개발자의 문서 요약 비교", summaryKo=fact, sourceName="개발자 블로그",
    )
    finding["keyPoints"][0].update(text=fact, claimType="OPINION", attributedTo="개발자 김연구")
    generated = {
        "title": "AI 모델·Agent 기술 보고서",
        "executiveSummary": [fact],
        "importantEvents": [{
            "title": "개발자의 문서 요약 비교",
            "summaryKo": fact,
            "significance": "A 모델은 B 모델보다 토큰을 90% 덜 썼다.",
            "sourceFindingIds": [501],
        }],
        "watchItems": [],
        "sourceNotes": [],
    }
    provider = FakeProvider(provider_response(json.dumps(generated, ensure_ascii=False)))

    response = ReportWriterService(Settings(AGENT_MOCK=False), provider).write(
        ReportRequest.model_validate(payload)
    )

    assert response.executive_summary == [fact]
    assert response.important_events[0].summary_ko == fact
    assert response.important_events[0].significance == fact
    assert "90%" not in response.markdown_body
    assert "발표·측정 주체" in provider.calls[0]["system_instruction"]
    assert "단일 공식 원문" in provider.calls[0]["system_instruction"]
