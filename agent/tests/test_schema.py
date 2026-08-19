import pytest
from pydantic import ValidationError

from app.schemas.analyze import AnalyzeResponse


def response(evidence_sentence_ids: list[int]) -> dict[str, object]:
    return {
        "sentences": ["근거 문장."],
        "sections": [
            {
                "heading": "핵심",
                "bullets": [
                    {
                        "text": "핵심 주장",
                        "evidenceSentenceIds": evidence_sentence_ids,
                        "groundedness": "grounded",
                        "confidence": 1,
                    }
                ],
            }
        ],
        "summaryKo": "요약",
        "classification": {
            "intent": "산업 동향 보도",
            "sentiment": "neutral",
            "riskLevel": "low",
            "relevance": "reference",
            "category": "제품/공정",
        },
        "entities": {"companies": [], "products": [], "technologies": []},
        "meta": {
            "provider": "mock",
            "model": "mock",
            "promptVersion": "analyze.mock.v1",
            "inputTokens": 0,
            "outputTokens": 0,
            "costUsd": 0,
            "credits": 0,
            "mock": True,
            "truncated": False,
        },
    }


@pytest.mark.parametrize("evidence_ids", [[0], [2]])
def test_rejects_evidence_ids_outside_one_based_sentence_range(
    evidence_ids: list[int],
) -> None:
    with pytest.raises(ValidationError):
        AnalyzeResponse.model_validate(response(evidence_ids))
