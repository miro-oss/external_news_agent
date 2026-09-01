import json

from app.core.config import Settings
from app.llm.base import ProviderResponse, ProviderUsage
from app.llm.self_critique_service import ArticleSelfCritiqueService
from app.schemas.analyze import AnalyzeRequest


class FakeProvider:
    def __init__(self, output: dict[str, object]) -> None:
        self.output = output
        self.prompts: list[str] = []

    def generate(
        self, *, system_instruction: str, prompt: str, response_schema: dict
    ) -> ProviderResponse:
        assert "이 요약에서 원문 문장으로 확인되지 않는 표현은 무엇인가?" in (
            system_instruction
        )
        assert response_schema["additionalProperties"] is False
        self.prompts.append(prompt)
        return ProviderResponse(
            text=json.dumps(self.output, ensure_ascii=False),
            provider="gemini",
            model="test-model",
            usage=ProviderUsage(input_tokens=20, output_tokens=10),
        )


def request(*, claim: str, evidence: str) -> AnalyzeRequest:
    return AnalyzeRequest.model_validate(
        {
            "idempotencyKey": "run:42:issue:88:self-critique",
            "plan": "FREE",
            "article": {
                "id": 10,
                "title": "투자 계획",
                "canonicalUrl": "https://example.com/10",
                "language": "ko",
                "bodyText": evidence,
            },
            "topic": {
                "name": "반도체",
                "queryText": "투자",
                "requiredKeywords": [],
                "optionalKeywords": [],
                "excludedKeywords": [],
            },
            "previousFinding": {
                "summaryKo": "회사의 투자 결정을 다룬 기사입니다.",
                "riskLevel": "high",
                "sections": [
                    {
                        "heading": "핵심",
                        "bullets": [
                            {
                                "text": claim,
                                "evidenceSentenceIds": [1],
                                "groundedness": "weak",
                                "confidence": 0.6,
                                "groundingReason": "표현 강도를 추가로 확인해야 합니다.",
                                "claimType": "FACT",
                                "attributedTo": None,
                            }
                        ],
                    }
                ],
                "crossSource": {
                    "consensus": [],
                    "soleSource": [],
                    "conflicts": [],
                    "missingStakeholders": [],
                },
            },
            "selfCritique": True,
        }
    )


def test_revises_one_weak_modality_claim_with_one_provider_call() -> None:
    provider = FakeProvider(
        {
            "unsupportedExpressions": ["투자를 승인했다"],
            "summaryKo": "회사가 투자를 발표한 기사입니다.",
            "revision": {
                "claimId": "0:0",
                "action": "REVISE",
                "text": "A사는 투자를 발표했다.",
                "evidenceSentenceIds": [1],
                "groundedness": "grounded",
                "confidence": 0.9,
                "groundingReason": "원문 문장에서 투자 계획 발표가 확인됩니다.",
            },
        }
    )
    target = request(
        claim="A사는 투자를 승인했다.",
        evidence="A사는 투자를 발표했다.",
    )

    response = ArticleSelfCritiqueService(
        Settings(AGENT_MOCK=False), provider
    ).critique(target)

    assert len(provider.prompts) == 1
    assert response.target_claim_count == 1
    assert response.revised_claim_count == 1
    assert response.sections[0].bullets[0].text == "A사는 투자를 발표했다."
    assert response.meta.prompt_version == "self-critique.ko.v1"


def test_skips_provider_when_decisive_rule_already_confirms_claim() -> None:
    provider = FakeProvider({})
    target = request(
        claim="A사는 투자 계획을 발표했다.",
        evidence="A사는 투자 계획을 발표했다.",
    )

    response = ArticleSelfCritiqueService(
        Settings(AGENT_MOCK=False), provider
    ).critique(target)

    assert provider.prompts == []
    assert response.target_claim_count == 0
    assert response.revised_claim_count == 0
    assert response.meta.prompt_version == "self-critique.rules.v1"
