"""Numbered evidence keeps every source character and the persistence quote bounds."""

import pytest

from app.llm.feedback_topic_review import build_topic_review_input, project_topic_review
from app.schemas.feedback import FeedbackReviewRequest, FeedbackTopicReviewOutput


def request(content):
    return FeedbackReviewRequest.model_validate({
        "idempotencyKey": "passage-regression",
        "plan": "FREE",
        "topic": {"id": 7, "name": "AI 기술"},
        "issue": {"id": 9, "title": "기술 도입"},
        "articles": [{"id": 10, "title": "원문 제목", "content": content}],
        "feedback": {"category": "TOPIC_MISMATCH", "comment": "연결 근거를 확인해 주세요."},
    })


@pytest.mark.parametrize("content", [
    "  지원 정책을 발표했다.\n\nAI 기능도 도입했다.\r\n  ",
    "가" * 9999,
    "짧은 문장. " * 700,
    "단어 " * 1000,
    "🚀" * 1000 + "끝",
    "한글🚀e\u0301." * 500,
    "\n \t\r\n",
], ids=["paragraphs", "long-word", "sentences", "words", "emoji", "mixed-unicode", "blank"])
def test_passages_preserve_all_non_whitespace_text_and_exact_source_spelling(content):
    original = request(content)
    validated_content = original.articles[0].content
    data, catalog = build_topic_review_input(original)
    article = data["articles"][0]
    assert "content" not in article
    assert article["passages"][0] == {"id": 0, "text": "원문 제목"}
    body = article["passages"][1:]
    assert "".join("".join(p["text"].split()) for p in body) == "".join(content.split())
    assert [p["id"] for p in article["passages"]] == list(range(len(catalog)))
    for passage in body:
        quote = passage["text"]
        assert quote.strip() and quote in content
        assert len(quote.encode("utf-16-le")) // 2 <= 300
        assert catalog[(10, passage["id"])].quote == quote
    assert original.articles[0].content == validated_content


def test_model_cannot_change_korean_particle_when_selecting_evidence():
    # A live review rewrote '이' as '은', failing exact-source validation twice.
    # Selection restores the original spelling without fuzzy matching or editing it.
    content = "창작플랫폼이 창작자 수익 기반을 확대한다는 평가가 나온다."
    original = request(content)
    _, catalog = build_topic_review_input(original)
    provider_output = FeedbackTopicReviewOutput.model_validate({
        "assessments": [{
            "topicId": 7,
            "relation": "DIFFERENT_SUBJECT",
            "rationale": "창작자 지원 정책이 검토 대상이며 기술 적용 근거는 확인되지 않습니다.",
            "evidence": [{"articleId": 10, "passageId": 1}],
            "counterEvidence": [],
            "counterpoint": "",
        }],
        "improvement": "해당 사건의 구체적인 기술 적용 근거를 확인합니다.",
    })
    result = project_topic_review([original.topic], provider_output, catalog)
    assert result.evidence[0].quote == content
    assert "창작플랫폼은" not in result.evidence[0].quote
