"""Project explicit topic connections into a review, without accepting a model verdict."""

from app.core.errors import OutputValidationError
from app.schemas.feedback import (
    FeedbackEvidence,
    FeedbackReviewInput,
    FeedbackReviewOutput,
    FeedbackTopic,
    FeedbackTopicReviewOutput,
)

_RELATIONS = {
    "SUBSTANTIVE": "실질적 관련",
    "MENTION_ONLY": "단순 언급",
    "DIFFERENT_SUBJECT": "주제 범위 밖",
    "EXCLUDED": "제외 조건 해당",
    "UNCERTAIN": "판단 불가",
}
_SUMMARIES = {
    "NOT_CONFIRMED": "제공된 모든 주제에서 실질적 연결을 확인해 주제 불일치를 확인하지 못했습니다.",
    "CONFIRMED_ERROR": (
        "제공된 모든 주제에서 검토 대상 사건이 "
        "수집 범위에 맞지 않는 것으로 판단했습니다."
    ),
    "INSUFFICIENT_EVIDENCE": (
        "주제별 판단이 다르거나 근거가 부족해 "
        "전체 사건의 주제 불일치 판정을 보류했습니다."
    ),
}


def topic_scope_fits_diagnosis(topics: list[FeedbackTopic]) -> bool:
    # Even one-character rationales cannot make an oversized set of names fit.
    # Reserve the longest fixed labels before paying for an impossible repair.
    longest_relation = max(_RELATIONS.values(), key=len)
    minimum = "\n".join([
        max(_SUMMARIES.values(), key=len),
        *(f"- {topic.name} [{longest_relation}]: 가" for topic in topics),
        "개선·확인 방향: 가",
    ])
    return len(minimum) <= 2000


def _passages(text: str):
    # Preserve source spelling, without cutting a UTF-16 surrogate pair. The Java
    # persistence boundary measures the same 300-unit bound with String.length().
    for paragraph in text.splitlines():
        remaining = paragraph.strip()
        while remaining:
            units = 0
            end = 0
            for char in remaining:
                width = 2 if ord(char) > 0xFFFF else 1
                if units + width > 300:
                    break
                units += width
                end += 1
            if end < len(remaining):
                sentence_end = remaining.rfind(". ", 0, end)
                word_end = remaining.rfind(" ", 0, end)
                if sentence_end > 0:
                    end = sentence_end + 1
                elif word_end > 0:
                    end = word_end
            passage = remaining[:end].strip()
            if passage:
                yield passage
            remaining = remaining[end:].lstrip()


def build_topic_review_input(
    request: FeedbackReviewInput,
) -> tuple[dict, dict[tuple[int, int], FeedbackEvidence]]:
    """Number the complete source text so the provider selects rather than rewrites it."""
    data = request.model_dump(mode="json", by_alias=True)
    catalog = {}
    articles = []
    for article in request.articles:
        passages = list(_passages(article.title)) + list(_passages(article.content))
        for index, text in enumerate(passages):
            catalog[(article.id, index)] = FeedbackEvidence(article_id=article.id, quote=text)
        articles.append({
            "id": article.id,
            "title": article.title,
            "url": article.url,
            "passages": [{"id": index, "text": text} for index, text in enumerate(passages)],
        })
    data["articles"] = articles
    return data, catalog


def project_topic_review(
    topics: list[FeedbackTopic],
    result: FeedbackTopicReviewOutput,
    catalog: dict[tuple[int, int], FeedbackEvidence],
) -> FeedbackReviewOutput:
    expected = {topic.id for topic in topics}
    actual = [assessment.topic_id for assessment in result.assessments]
    if len(actual) != len(expected) or set(actual) != expected:
        raise OutputValidationError(
            "입력된 모든 주제를 중복·추가·누락 없이 한 번씩 평가해야 합니다.",
            error_kinds=("feedback_topic_coverage",),
        )

    by_id = {assessment.topic_id: assessment for assessment in result.assessments}
    evidence = {}
    lines = []
    for topic in topics:
        assessment = by_id[topic.id]
        if assessment.relation != "UNCERTAIN" and not assessment.evidence:
            raise OutputValidationError(
                "판단 불가 외의 주제 판정에는 해당 판단을 뒷받침하는 원문 인용이 필요합니다.",
                error_kinds=("feedback_topic_evidence_missing",),
            )
        if not assessment.rationale.strip():
            raise OutputValidationError(
                "주제와 검토 대상 사건의 연결 관계를 설명해야 합니다.",
                error_kinds=("feedback_topic_rationale_missing",),
            )
        if bool(assessment.counter_evidence) != bool(assessment.counterpoint.strip()):
            raise OutputValidationError(
                "반대 근거와 그 근거에 대한 설명을 함께 제공해야 합니다.",
                error_kinds=("feedback_topic_counterpoint_missing",),
            )
        for reference in [*assessment.evidence, *assessment.counter_evidence]:
            item = catalog.get((reference.article_id, reference.passage_id))
            if item is None:
                raise OutputValidationError(
                    "근거는 해당 articles.id에 제공된 passages.id만 참조해야 합니다.",
                    error_kinds=("feedback_passage_not_in_article",),
                )
            evidence[(item.article_id, item.quote)] = item
        lines.append(f"- {topic.name} [{_RELATIONS[assessment.relation]}]: {assessment.rationale}")
        if assessment.counterpoint.strip():
            lines.append(f"  반대 근거 검토: {assessment.counterpoint}")

    relations = {assessment.relation for assessment in result.assessments}
    if relations == {"SUBSTANTIVE"}:
        verdict = "NOT_CONFIRMED"
    elif relations <= {"MENTION_ONLY", "DIFFERENT_SUBJECT", "EXCLUDED"}:
        verdict = "CONFIRMED_ERROR"
    else:
        verdict = "INSUFFICIENT_EVIDENCE"

    if not result.improvement.strip():
        raise OutputValidationError(
            "검토 결과에 따른 개선 또는 추가 확인 방향을 설명해야 합니다.",
            error_kinds=("feedback_topic_improvement_missing",),
        )
    # Keep every assessment and citation. Oversized projections go through the existing
    # bounded repair path; never silently omit a topic or relax the public response limits.
    return FeedbackReviewOutput(
        verdict=verdict,
        diagnosis="\n".join([
            _SUMMARIES[verdict], *lines, f"개선·확인 방향: {result.improvement}"
        ]),
        evidence=list(evidence.values()),
        proposed_policy=None,
    )
