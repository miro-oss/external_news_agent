import json
from decimal import Decimal

import pytest
from fastapi.testclient import TestClient

from app.core.config import Settings, get_settings
from app.core.errors import AgentError
from app.llm.base import ProviderResponse, ProviderUsage
from app.llm.self_critique_service import ArticleSelfCritiqueService
from app.main import create_app
from app.schemas.analyze import AnalyzeRequest


class FakeProvider:
    def __init__(self, output: dict[str, object], *, truncated: bool = False) -> None:
        self.output = output
        self.truncated = truncated
        self.prompts: list[str] = []

    def generate(
        self, *, system_instruction: str, prompt: str, response_schema: dict
    ) -> ProviderResponse:
        assert "이 요약에서 원문 문장으로 확인되지 않는 표현은 무엇인가?" in (system_instruction)
        assert response_schema["additionalProperties"] is False
        self.prompts.append(prompt)
        return ProviderResponse(
            text=json.dumps(self.output, ensure_ascii=False),
            provider="gemini",
            model="test-model",
            usage=ProviderUsage(
                input_tokens=20, output_tokens=10,
                cost_usd=Decimal("0.002"), credits=Decimal("0.1"),
            ),
            truncated=self.truncated,
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
                "sensitivity": {
                    "customerMove": {"score": 3, "evidenceSentenceIds": [1]},
                    "dealSignal": {"score": None, "evidenceSentenceIds": []},
                    "competitorThreat": {"score": 3, "evidenceSentenceIds": [1]},
                    "industryShift": {"score": 3, "evidenceSentenceIds": [1]},
                },
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

    response = ArticleSelfCritiqueService(Settings(AGENT_MOCK=False), provider).critique(target)

    assert len(provider.prompts) == 1
    assert response.target_claim_count == 1
    assert response.revised_claim_count == 1
    assert response.sections[0].bullets[0].text == "A사는 투자를 발표했다."
    assert response.summary_ko == target.previous_finding.summary_ko
    assert response.meta.prompt_version == "self-critique.ko.v2"


def critique_output(**changes: object) -> dict:
    return {
        "unsupportedExpressions": [],
        "summaryKo": "모델이 다시 작성한 투자 기사 요약입니다.",
        "revision": {
            "claimId": "0:0",
            "action": "KEEP",
            "text": "A사는 투자를 승인했다.",
            "evidenceSentenceIds": [1],
            "groundedness": "weak",
            "confidence": 0.6,
            "groundingReason": "표현 강도를 추가로 확인해야 합니다.",
            **changes,
        },
    }


@pytest.mark.parametrize("changes", [
    {"groundingReason": "provider가 임의로 바꾼 판정 이유입니다."},
    {"text": "B사는 100억 원 투자를 확정했다."},
    {"confidence": 0.95, "groundedness": "grounded"},
    {"evidenceSentenceIds": [999]},
    {
        "text": "B사는 100억 원 투자를 확정했다.",
        "evidenceSentenceIds": [999],
        "confidence": 0.95,
        "groundedness": "grounded",
        "groundingReason": "변경된 판정 이유입니다.",
    },
])
def test_keep_preserves_original_instead_of_provider_copies(changes: dict) -> None:
    provider = FakeProvider(critique_output(**changes))
    target = request(
        claim="A사는 투자를 승인했다.",
        evidence="A사는 투자를 발표했다.",
    )
    # A second, already rejected claim must also survive the response unchanged.
    original = target.previous_finding.sections[0].bullets[0]
    target.previous_finding.sections[0].bullets.append(original.model_copy(update={
        "text": "확인되지 않은 별도 주장입니다.",
        "groundedness": "ungrounded",
        "evidence_sentence_ids": [],
        "confidence": 0,
    }))
    before = target.previous_finding.model_dump()

    response = ArticleSelfCritiqueService(Settings(AGENT_MOCK=False), provider).critique(target)

    assert [section.model_dump() for section in response.sections] == before["sections"]
    assert response.summary_ko == before["summary_ko"]
    assert target.previous_finding.model_dump() == before
    assert response.target_claim_count == 1
    assert response.revised_claim_count == 0
    assert len(provider.prompts) == 1
    assert response.meta.input_tokens == 20
    assert response.meta.output_tokens == 10


@pytest.mark.parametrize("changes", [
    {"claimId": "0:1"},
    {"action": "UNKNOWN"},
    {"confidence": 1.1},
    {"evidenceSentenceIds": []},
    {"evidenceSentenceIds": [1, 1]},
    {"groundedness": "ungrounded", "evidenceSentenceIds": []},
    {"claimType": "FACT"},
    {"action": "REVISE", "evidenceSentenceIds": [2]},
    {"action": "REJECT", "groundedness": "ungrounded", "evidenceSentenceIds": []},
])
def test_rejects_invalid_contract_with_one_call_and_preserves_usage(changes: dict) -> None:
    provider = FakeProvider(critique_output(**changes), truncated=True)
    target = request(
        claim="A사는 투자를 승인했다.",
        evidence="A사는 투자를 발표했다.",
    )

    with pytest.raises(AgentError) as error:
        ArticleSelfCritiqueService(Settings(AGENT_MOCK=False), provider).critique(target)

    assert error.value.code == "SCHEMA_VIOLATION"
    assert error.value.status_code == 502
    assert error.value.details == {
        "usage": {"inputTokens": 20, "outputTokens": 10, "costUsd": 0.002, "credits": 0.1},
        "truncated": True,
    }
    assert len(provider.prompts) == 1


def test_reject_marks_only_selected_claim_unsupported() -> None:
    provider = FakeProvider(critique_output(
        action="REJECT", groundedness="ungrounded", evidenceSentenceIds=[], confidence=0,
    ))
    target = request(
        claim="A사는 투자를 승인했다.",
        evidence="A사는 투자를 발표했다.",
    )

    response = ArticleSelfCritiqueService(Settings(AGENT_MOCK=False), provider).critique(target)

    bullet = response.sections[0].bullets[0]
    assert bullet.groundedness == "ungrounded"
    assert bullet.evidence_sentence_ids == []
    assert bullet.confidence == 0
    assert bullet.claim_type == target.previous_finding.sections[0].bullets[0].claim_type
    assert response.revised_claim_count == 1
    assert response.summary_ko == target.previous_finding.summary_ko
    assert len(provider.prompts) == 1


def test_revise_still_downgrades_new_factual_mismatch() -> None:
    provider = FakeProvider(critique_output(
        action="REVISE", text="B사는 100억 원 투자를 확정했다.",
        confidence=0.95, groundedness="grounded",
    ))
    target = request(
        claim="A사는 투자를 승인했다.",
        evidence="A사는 투자를 발표했다.",
    )

    response = ArticleSelfCritiqueService(Settings(AGENT_MOCK=False), provider).critique(target)

    bullet = response.sections[0].bullets[0]
    assert bullet.groundedness == "ungrounded"
    assert bullet.evidence_sentence_ids == []
    assert bullet.confidence == 0
    assert response.revised_claim_count == 1


@pytest.mark.parametrize("invalid", [False, True])
def test_self_critique_http_contract_and_failure_usage(monkeypatch, invalid: bool) -> None:
    provider = FakeProvider(critique_output(
        claimId="9:9" if invalid else "0:0",
        groundingReason="provider가 다시 쓴 이유입니다.",
    ), truncated=True)
    monkeypatch.setattr(
        "app.llm.self_critique_service.get_analyze_provider", lambda *_: provider,
    )
    application = create_app()
    application.dependency_overrides[get_settings] = lambda: Settings(
        AGENT_MOCK=False, AGENT_SHARED_SECRET="local-self-critique-test-token",
    )
    target = request(
        claim="A사는 투자를 승인했다.",
        evidence="A사는 투자를 발표했다.",
    )

    with TestClient(application) as client:
        response = client.post(
            "/v1/analyze",
            headers={"X-Agent-Token": "local-self-critique-test-token"},
            json=target.model_dump(by_alias=True, mode="json"),
        )

    assert len(provider.prompts) == 1
    body = response.json()
    if invalid:
        assert response.status_code == 502
        assert body["error"] == {
            "code": "SCHEMA_VIOLATION",
            "message": "Provider 자기 검증 출력이 Agent 계약을 위반했습니다.",
            "details": {
                "usage": {
                    "inputTokens": 20, "outputTokens": 10, "costUsd": 0.002, "credits": 0.1,
                },
                "truncated": True,
            },
        }
    else:
        assert response.status_code == 200
        assert body["sections"] == target.previous_finding.model_dump(by_alias=True)["sections"]
        assert body["summaryKo"] == target.previous_finding.summary_ko
        assert body["revisedClaimCount"] == 0
        assert body["meta"]["promptVersion"] == "self-critique.ko.v2"
        assert body["meta"]["truncated"] is True


def test_skips_provider_when_decisive_rule_already_confirms_claim() -> None:
    provider = FakeProvider({})
    target = request(
        claim="A사는 투자 계획을 발표했다.",
        evidence="A사는 투자 계획을 발표했다.",
    )

    response = ArticleSelfCritiqueService(Settings(AGENT_MOCK=False), provider).critique(target)

    assert provider.prompts == []
    assert response.target_claim_count == 0
    assert response.revised_claim_count == 0
    assert response.meta.prompt_version == "self-critique.rules.v1"
