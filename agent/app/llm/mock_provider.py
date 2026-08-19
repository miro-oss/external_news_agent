from app.core.config import Settings
from app.core.sentences import split_sentences
from app.schemas.analyze import (
    AnalyzeRequest,
    AnalyzeResponse,
    Classification,
    Entities,
    EvidenceBullet,
    ResponseMeta,
    Section,
)


class MockAnalyzeProvider:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    def analyze(self, request: AnalyzeRequest) -> AnalyzeResponse:
        article = request.article
        material = article.body_text or article.title
        sentences = split_sentences(material, self._settings.max_sentences) or [article.title]
        groundedness = "grounded" if article.body_text.strip() else "weak"

        return AnalyzeResponse(
            sentences=sentences,
            sections=[
                Section(
                    heading="핵심",
                    bullets=[
                        EvidenceBullet(
                            text=article.title,
                            evidence_sentence_ids=[1],
                            groundedness=groundedness,
                            confidence=1.0 if groundedness == "grounded" else 0.5,
                        )
                    ],
                )
            ],
            summary_ko=article.title,
            classification=Classification(
                intent="산업 동향 보도",
                sentiment="neutral",
                risk_level="low",
                relevance="reference",
                category="제품/공정",
            ),
            entities=Entities(companies=[], products=[], technologies=[]),
            meta=ResponseMeta(
                provider="mock",
                model="mock",
                prompt_version="analyze.mock.v1",
                input_tokens=0,
                output_tokens=0,
                cost_usd=0,
                credits=0,
                mock=True,
                truncated=False,
            ),
        )
