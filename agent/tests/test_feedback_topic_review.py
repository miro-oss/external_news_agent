"""Contract regressions using synthetic articles and scripted model assessments.

These tests verify routing, evidence validation, and verdict projection; scripted
assessments do not establish the semantic accuracy of a live model.
"""

import json
from copy import deepcopy
from decimal import Decimal

import pytest
from fastapi.testclient import TestClient
from jsonschema import Draft202012Validator
from pydantic_ai.profiles.openai import OpenAIJsonSchemaTransformer

from app.core.config import Settings, get_settings
from app.core.errors import AgentError
from app.llm.base import ProviderResponse, ProviderUsage
from app.llm.feedback_service import FeedbackService
from app.main import create_app
from app.schemas.feedback import FeedbackEventReviewRequest, FeedbackReviewRequest

CREATOR_SUPPORT = "플랫폼은 웹툰 작가에게 창작 지원금과 수익배분 혜택을 제공한다."
AI_BACKGROUND = "회사는 AI 시대에도 창작자와 동반 성장하겠다고 밝혔다."
AI_FEATURE = "새 생성형 AI 모델은 작가의 스케치를 바탕으로 배경 이미지를 생성한다."
PROMPT_VERSION = "feedback-topic-review.ko.v1"


class ScriptedProvider:
    def __init__(self, *outputs):
        self.outputs = outputs
        self.calls = []

    def generate(self, **kwargs):
        self.calls.append(kwargs)
        output = self.outputs[len(self.calls) - 1]
        return ProviderResponse(
            text=json.dumps(output, ensure_ascii=False),
            provider="openai",
            model="scripted-test-provider",
            usage=ProviderUsage(cost_usd=Decimal("0.001")),
        )


def topic(topic_id=7, name="AI·LLM 기술 및 산업 동향"):
    return {
        "id": topic_id,
        "name": name,
        "keywords": ["인공지능", "LLM"],
        "negativeKeywords": [],
    }


def review_request(*, event=True, content=CREATOR_SUPPORT):
    payload = {
        "idempotencyKey": "synthetic-topic-review",
        "plan": "FREE",
        "articles": [
            {"id": 10, "title": "창작자를 위한 새 지원 정책", "content": content, "url": ""}
        ],
        "feedback": {
            "category": "TOPIC_MISMATCH",
            "comment": "이 창작자 지원 소식이 AI 기술 주제에 맞는지 확인해 주세요.",
            "allowPersonalization": False,
        },
        "activePolicies": [],
    }
    if event:
        payload.update(
            topics=[topic()],
            event={
                "key": "b" * 64,
                "title": "창작자 지원 정책 발표",
                "summary": "플랫폼이 웹툰 작가 지원 정책을 발표했다.",
                "significance": "웹툰 산업의 창작 환경에 영향을 줄 수 있다.",
                "sourceFindingIds": [101],
            },
        )
        return FeedbackEventReviewRequest.model_validate(payload)
    payload.update(
        topic=topic(),
        issue={
            "id": 9,
            "title": "창작자 지원 정책 발표",
            "summary": "플랫폼이 웹툰 작가 지원 정책을 발표했다.",
        },
    )
    return FeedbackReviewRequest.model_validate(payload)


def assessment(relation, *, topic_id=7, passage_id=1, rationale=None):
    return {
        "topicId": topic_id,
        "relation": relation,
        "rationale": rationale or "기사의 실제 사건과 이 주제의 범위를 대조한 결과입니다.",
        "evidence": [] if passage_id is None else [{"articleId": 10, "passageId": passage_id}],
        "counterEvidence": [],
        "counterpoint": "",
    }


def output(*assessments):
    return {
        "assessments": list(assessments),
        "improvement": "기업 이름이나 산업적 중요도와 별개로 실제 기술 연결을 확인합니다.",
    }


def service(provider, *, repairs=0):
    return FeedbackService(
        Settings(AGENT_MOCK=False, AGENT_SCHEMA_REPAIR_ATTEMPTS=repairs), provider
    )


@pytest.mark.parametrize("event", [True, False], ids=["report-event", "legacy-issue"])
@pytest.mark.parametrize(
    ("content", "relation", "expected"),
    [
        (CREATOR_SUPPORT, "DIFFERENT_SUBJECT", "CONFIRMED_ERROR"),
        (
            CREATOR_SUPPORT + AI_BACKGROUND,
            "MENTION_ONLY",
            "CONFIRMED_ERROR",
        ),
        (AI_FEATURE, "SUBSTANTIVE", "NOT_CONFIRMED"),
    ],
    ids=["creator-support", "ai-background-only", "actual-ai-feature"],
)
def test_topic_assessment_drives_verdict_for_both_feedback_entry_points(
    event, content, relation, expected
):
    wire = output(assessment(relation))
    provider = ScriptedProvider(wire)
    result = service(provider).review(review_request(event=event, content=content))
    assert result.verdict == expected
    assert result.proposed_policy is None
    assert result.meta.prompt_version == PROMPT_VERSION
    assert result.meta.cost_usd == 0.001
    assert "AI·LLM 기술 및 산업 동향" in result.diagnosis
    assert wire["assessments"][0]["rationale"] in result.diagnosis
    assert wire["improvement"] in result.diagnosis
    assert result.evidence[0].quote == content
    submitted = json.loads(
        provider.calls[0]["prompt"].split("<feedback-input>")[1].split("</feedback-input>")[0]
    )
    assert "content" not in submitted["articles"][0]
    assert submitted["articles"][0]["passages"] == [
        {"id": 0, "text": "창작자를 위한 새 지원 정책"},
        {"id": 1, "text": content},
    ]
    schema = OpenAIJsonSchemaTransformer(
        deepcopy(provider.calls[0]["response_schema"]), strict=True
    ).walk()
    Draft202012Validator(schema).validate(wire)
    assert set(schema["properties"]) == {"assessments", "improvement"}


@pytest.mark.parametrize(
    ("relations", "expected"),
    [
        (("SUBSTANTIVE", "SUBSTANTIVE"), "NOT_CONFIRMED"),
        (("DIFFERENT_SUBJECT", "MENTION_ONLY"), "CONFIRMED_ERROR"),
        (("EXCLUDED", "DIFFERENT_SUBJECT"), "CONFIRMED_ERROR"),
        (("DIFFERENT_SUBJECT", "SUBSTANTIVE"), "INSUFFICIENT_EVIDENCE"),
        (("SUBSTANTIVE", "UNCERTAIN"), "INSUFFICIENT_EVIDENCE"),
        (("UNCERTAIN", "UNCERTAIN"), "INSUFFICIENT_EVIDENCE"),
    ],
)
def test_all_topics_are_projected_without_hiding_disagreement(relations, expected):
    request = review_request()
    request.topics.append(type(request.topics[0]).model_validate(topic(8, "웹툰 산업")))
    wire = output(
        assessment(relations[1], topic_id=8),
        assessment(relations[0]),
    )
    result = service(ScriptedProvider(wire)).review(request)
    assert result.verdict == expected
    assert "AI·LLM 기술 및 산업 동향" in result.diagnosis
    assert "웹툰 산업" in result.diagnosis
    assert len(result.evidence) == 1
    assert result.proposed_policy is None


def test_uncertain_scope_can_be_reported_without_invented_evidence():
    result = service(ScriptedProvider(output(assessment("UNCERTAIN", passage_id=None)))).review(
        review_request()
    )
    assert result.verdict == "INSUFFICIENT_EVIDENCE"
    assert result.evidence == []
    assert result.proposed_policy is None


@pytest.mark.parametrize("quote", [" ", "플랫폼이 창작 지원금을 제공한다."])
def test_free_form_quotes_are_rejected_even_with_a_valid_passage_id(quote):
    request = review_request()
    assert " " in request.articles[0].content
    item = assessment("DIFFERENT_SUBJECT")
    item["evidence"][0]["quote"] = quote
    with pytest.raises(AgentError) as error:
        service(ScriptedProvider(output(item))).review(request)
    assert error.value.code == "SCHEMA_VIOLATION"


def test_oversized_topic_names_are_deferred_without_calling_a_provider():
    request = review_request()
    request.topics = [
        type(request.topics[0]).model_validate(topic(i, "가" * 200)) for i in range(1, 11)
    ]
    provider = ScriptedProvider()
    result = service(provider).review(request)
    assert result.verdict == "INSUFFICIENT_EVIDENCE"
    assert result.evidence == []
    assert result.proposed_policy is None
    assert len(result.diagnosis) <= 2000
    assert result.meta.model == "topic-scope-limit"
    assert result.meta.mock
    assert result.meta.input_tokens == 0
    assert result.meta.output_tokens == 0
    assert result.meta.cost_usd == 0
    assert result.meta.credits == 0
    assert provider.calls == []


def test_exclusion_assessment_keeps_the_original_excluded_condition_in_model_input():
    quote = "회사는 생성형 AI 개발자 채용 공고를 게시했다."
    request = review_request(content=quote)
    request.topics[0].negative_keywords = ["채용 공고"]
    provider = ScriptedProvider(output(assessment("EXCLUDED")))
    result = service(provider).review(request)
    submitted = json.loads(
        provider.calls[0]["prompt"].split("<feedback-input>")[1].split("</feedback-input>")[0]
    )
    assert submitted["topics"][0]["negativeKeywords"] == ["채용 공고"]
    assert result.verdict == "CONFIRMED_ERROR"
    assert "제외 조건 해당" in result.diagnosis


@pytest.mark.parametrize(
    "mutation",
    [
        "foreign_topic",
        "duplicate_topic",
        "missing_topic",
        "foreign_article",
        "finding_as_article",
        "invented_quote",
        "uncertain_invented_quote",
        "foreign_passage",
        "negative_passage",
        "missing_passage",
        "foreign_counter_article",
        "foreign_counter_passage",
        "counter_without_explanation",
        "explanation_without_counter",
        "too_many_counter_quotes",
        "missing_evidence",
        "too_many_quotes",
        "unknown_relation",
        "extra_verdict",
        "long_rationale",
        "long_improvement",
    ],
)
def test_topic_review_rejects_unbound_or_incomplete_assessments(mutation):
    request = review_request()
    wire = output(assessment("DIFFERENT_SUBJECT"))
    item = wire["assessments"][0]
    if mutation == "foreign_topic":
        item["topicId"] = 999
    elif mutation == "duplicate_topic":
        wire["assessments"].append(deepcopy(item))
    elif mutation == "missing_topic":
        request.topics.append(type(request.topics[0]).model_validate(topic(8, "웹툰 산업")))
    elif mutation == "foreign_article":
        item["evidence"][0]["articleId"] = 999
    elif mutation == "finding_as_article":
        item["evidence"][0]["articleId"] = 101
    elif mutation in {"invented_quote", "uncertain_invented_quote"}:
        if mutation == "uncertain_invented_quote":
            item["relation"] = "UNCERTAIN"
        item["evidence"][0]["quote"] = "원문에 존재하지 않는 AI 도입 내용"
    elif mutation == "foreign_passage":
        item["evidence"][0]["passageId"] = 999
    elif mutation == "negative_passage":
        item["evidence"][0]["passageId"] = -1
    elif mutation == "missing_passage":
        item["evidence"][0].pop("passageId")
    elif mutation.startswith("foreign_counter"):
        item["counterEvidence"] = [{"articleId": 10, "passageId": 0}]
        item["counterpoint"] = "반대 근거가 결론을 바꾸는지 검토했습니다."
        key = "articleId" if mutation == "foreign_counter_article" else "passageId"
        item["counterEvidence"][0][key] = 999
    elif mutation == "counter_without_explanation":
        item["counterEvidence"] = [{"articleId": 10, "passageId": 0}]
        item["counterpoint"] = " "
    elif mutation == "explanation_without_counter":
        item["counterpoint"] = "인용하지 않은 반대 근거로 결론을 바꿉니다."
    elif mutation == "too_many_counter_quotes":
        item["counterEvidence"] = [{"articleId": 10, "passageId": 0}] * 3
        item["counterpoint"] = "반대 근거를 검토했습니다."
    elif mutation == "missing_evidence":
        item["evidence"] = []
    elif mutation == "too_many_quotes":
        item["evidence"] *= 3
    elif mutation == "unknown_relation":
        item["relation"] = "IMPORTANT"
    elif mutation == "extra_verdict":
        wire["verdict"] = "NOT_CONFIRMED"
    elif mutation == "long_rationale":
        item["rationale"] = "가" * 301
    else:
        wire["improvement"] = "가" * 401
    with pytest.raises(AgentError) as error:
        service(ScriptedProvider(wire)).review(request)
    assert error.value.code == "SCHEMA_VIOLATION"


def test_generic_industry_impact_rebuttal_requires_topic_assessment_before_acceptance():
    old_shape = {
        "verdict": "NOT_CONFIRMED",
        "diagnosis": "창작자 지원 정책은 웹툰 산업에 중요한 영향을 줍니다.",
        "evidence": [{"articleId": 10, "quote": CREATOR_SUPPORT}],
        "proposedPolicy": None,
    }
    with pytest.raises(AgentError) as error:
        service(ScriptedProvider(old_shape)).review(review_request())
    assert error.value.code == "SCHEMA_VIOLATION"

    corrected = output(assessment("DIFFERENT_SUBJECT"))
    provider = ScriptedProvider(old_shape, corrected)
    result = service(provider, repairs=1).review(review_request())
    assert result.verdict == "CONFIRMED_ERROR"
    assert len(provider.calls) == 2
    assert result.meta.cost_usd == 0.002
    assert "<validation-error>" in provider.calls[1]["prompt"]


def test_overlong_projected_diagnosis_is_rejected_instead_of_silently_truncated():
    request = review_request()
    request.topics = [type(request.topics[0]).model_validate(topic(i)) for i in range(1, 8)]
    wire = output(
        *(assessment("DIFFERENT_SUBJECT", topic_id=i, rationale="가" * 300) for i in range(1, 8))
    )
    with pytest.raises(AgentError) as error:
        service(ScriptedProvider(wire)).review(request)
    assert error.value.code == "SCHEMA_VIOLATION"


@pytest.mark.parametrize("topic_count", [5, 6], ids=["ten-citations", "twelve-citations"])
def test_public_evidence_limit_preserves_all_citations_or_rejects_the_output(topic_count):
    request = review_request()
    request.articles = [
        request.articles[0].model_copy(update={
            "id": 10 + i,
            "title": f"지원 프로그램 {i} 발표",
            "content": f"지원 프로그램 {i}의 운영 조건을 공개했다.",
        })
        for i in range(topic_count)
    ]
    quotes = {
        quote for article in request.articles for quote in (article.title, article.content)
    }
    request.topics = [
        type(request.topics[0]).model_validate(topic(i)) for i in range(1, topic_count + 1)
    ]
    wire = output()
    for i in range(topic_count):
        item = assessment("DIFFERENT_SUBJECT", topic_id=i + 1)
        item["evidence"] = [{"articleId": 10 + i, "passageId": 0}]
        item["counterEvidence"] = [{"articleId": 10 + i, "passageId": 1}]
        item["counterpoint"] = "제시된 다른 본문 근거를 함께 검토했습니다."
        wire["assessments"].append(item)
    if topic_count == 5:
        result = service(ScriptedProvider(wire)).review(request)
        assert len(result.evidence) == 10
        assert {item.quote for item in result.evidence} == quotes
    else:
        with pytest.raises(AgentError) as error:
            service(ScriptedProvider(wire)).review(request)
        assert error.value.code == "SCHEMA_VIOLATION"


def test_equal_text_from_different_articles_keeps_both_source_ids():
    request = review_request()
    request.articles.append(request.articles[0].model_copy(update={"id": 20}))
    item = assessment("DIFFERENT_SUBJECT")
    item["evidence"].append({"articleId": 20, "passageId": 1})
    result = service(ScriptedProvider(output(item))).review(request)
    assert {item.article_id for item in result.evidence} == {10, 20}


def test_counterevidence_and_its_explanation_are_preserved_in_the_public_review():
    request = review_request(content=f"{CREATOR_SUPPORT}\n{AI_FEATURE}")
    item = assessment("SUBSTANTIVE", passage_id=2)
    item["counterEvidence"] = [{"articleId": 10, "passageId": 1}]
    item["counterpoint"] = (
        "지원금 내용은 AI 기능의 근거가 아니지만, "
        "별도로 명시된 이미지 생성 기능을 함께 확인했습니다."
    )
    result = service(ScriptedProvider(output(item))).review(request)
    assert result.verdict == "NOT_CONFIRMED"
    assert item["counterpoint"] in result.diagnosis
    assert {item.quote for item in result.evidence} == {CREATOR_SUPPORT, AI_FEATURE}


def test_topic_mock_review_never_calls_provider():
    provider = ScriptedProvider()
    result = FeedbackService(Settings(AGENT_MOCK=True), provider).review(review_request())
    assert result.verdict == "INSUFFICIENT_EVIDENCE"
    assert result.proposed_policy is None
    assert result.meta.mock
    assert result.meta.prompt_version == PROMPT_VERSION
    assert provider.calls == []


def test_http_response_keeps_public_contract_without_internal_assessments(monkeypatch):
    provider = ScriptedProvider(output(assessment("DIFFERENT_SUBJECT")))
    monkeypatch.setattr("app.llm.feedback_service.get_analyze_provider", lambda *_: provider)
    app = create_app()
    app.dependency_overrides[get_settings] = lambda: Settings(
        AGENT_MOCK=False,
        AGENT_SHARED_SECRET="synthetic-test-only",
        AGENT_SCHEMA_REPAIR_ATTEMPTS=0,
    )
    with TestClient(app) as client:
        response = client.post(
            "/v1/feedback/review",
            json=review_request().model_dump(mode="json", by_alias=True),
            headers={"X-Agent-Token": "synthetic-test-only"},
        )
    assert response.status_code == 200
    assert set(response.json()) == {"verdict", "diagnosis", "evidence", "proposedPolicy", "meta"}
    assert response.json()["verdict"] == "CONFIRMED_ERROR"
    assert response.json()["proposedPolicy"] is None
    assert response.json()["meta"]["promptVersion"] == PROMPT_VERSION
