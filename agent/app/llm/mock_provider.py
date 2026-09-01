from app.core.config import Settings
from app.core.evidence import cross_source_signal
from app.core.sentences import split_sentences_with_meta
from app.schemas.analyze import (
    AnalyzeRequest,
    AnalyzeResponse,
    Classification,
    ConflictObservation,
    CrossSource,
    Entities,
    EvidenceBullet,
    MemberStance,
    PerspectiveTag,
    ResponseMeta,
    Section,
    SoleSourceObservation,
)


class MockAnalyzeProvider:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    def analyze(self, request: AnalyzeRequest, *, input_truncated: bool = False) -> AnalyzeResponse:
        article = request.article
        material = article.body_text or article.title
        split = split_sentences_with_meta(material, self._settings.max_sentences)
        sentences = split.sentences or [article.title]
        groundedness = "grounded" if article.body_text.strip() else "weak"
        summary = _summary(article.title)
        cross_source, promote_candidates, member_stances = _cross_source(request)

        return AnalyzeResponse(
            sentences=sentences,
            sections=[
                Section(
                    heading="핵심",
                    bullets=[
                        EvidenceBullet(
                            text=article.title[:80],
                            evidence_sentence_ids=[1],
                            groundedness=groundedness,
                            confidence=1.0 if groundedness == "grounded" else 0.5,
                            claim_type="FACT",
                            attributed_to=None,
                        )
                    ],
                )
            ],
            summary_ko=summary,
            classification=Classification(
                intent="산업 동향 보도",
                sentiment="neutral",
                risk_level="low",
                relevance="reference",
                category="제품/공정",
            ),
            entities=Entities(companies=[], products=[], technologies=[]),
            perspective_tags=[
                PerspectiveTag(
                    audience="CHIP_MAKER",
                    relevance="low",
                    hook=article.title,
                    evidence_sentence_ids=[1],
                ),
                PerspectiveTag(
                    audience="EQUIPMENT_MAKER",
                    relevance="none",
                    hook=None,
                    evidence_sentence_ids=[],
                ),
                PerspectiveTag(
                    audience="MARKET_INVESTOR",
                    relevance="none",
                    hook=None,
                    evidence_sentence_ids=[],
                ),
                PerspectiveTag(
                    audience="IT_INFRA",
                    relevance="none",
                    hook=None,
                    evidence_sentence_ids=[],
                ),
            ],
            cross_source=cross_source,
            promote_candidates=promote_candidates,
            member_stances=member_stances,
            meta=ResponseMeta(
                provider="mock",
                model="mock",
                prompt_version="analyze.mock.v5",
                input_tokens=0,
                output_tokens=0,
                cost_usd=0,
                credits=0,
                mock=True,
                truncated=input_truncated or split.truncated,
            ),
        )


def _summary(title: str) -> str:
    summary = title[:120]
    if len(summary) >= 10:
        return summary
    return f"{summary} 관련 기사입니다."


def _cross_source(
    request: AnalyzeRequest,
) -> tuple[CrossSource, list[int], list[MemberStance]]:
    if not request.issue_members:
        return CrossSource.empty(), [], []

    reference_text = "\n".join(
        value
        for value in (
            request.article.title,
            request.article.summary,
        )
        if value
    )
    conflicts = []
    sole_source = []
    member_stances = []
    promote_candidates = []
    has_distinct_observation = False
    for member in request.issue_members:
        candidate_text = "\n".join(
            value for value in (member.title, member.summary) if value
        )
        signal = cross_source_signal(reference_text, candidate_text)
        member_stances.append(
            MemberStance(
                article_id=member.id,
                stance=signal.stance,
                confidence=signal.confidence,
            )
        )
        if signal.polarity_mismatch or signal.number_mismatch:
            conflicts.append(
                ConflictObservation(
                    article_ids=[request.article.id, member.id],
                    text=f"대표 기사와 {member.title}의 수치 또는 결론이 다릅니다."[:500],
                )
            )
            if signal.promotion_eligible and not promote_candidates:
                promote_candidates.append(member.id)
            has_distinct_observation = True
        elif signal.promotion_eligible:
            sole_source.append(
                SoleSourceObservation(article_id=member.id, text=member.title[:500])
            )
            has_distinct_observation = True

    consensus = [] if has_distinct_observation else [request.article.title]
    return (
        CrossSource(
            consensus=consensus,
            sole_source=sole_source,
            conflicts=conflicts,
            missing_stakeholders=[],
        ),
        promote_candidates,
        member_stances,
    )
