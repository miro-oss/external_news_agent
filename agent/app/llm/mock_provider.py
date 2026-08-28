from app.core.config import Settings
from app.core.sentences import split_sentences_with_meta
from app.schemas.analyze import (
    AnalyzeRequest,
    AnalyzeResponse,
    Classification,
    Entities,
    EvidenceBullet,
    PerspectiveTag,
    ResponseMeta,
    Section,
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
            meta=ResponseMeta(
                provider="mock",
                model="mock",
                prompt_version="analyze.mock.v2",
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
