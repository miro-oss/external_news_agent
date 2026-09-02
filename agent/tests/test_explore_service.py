import json
from decimal import Decimal
from types import SimpleNamespace

import pytest
from pydantic import ValidationError

from app.core.config import Settings
from app.core.errors import AgentError
from app.llm import explore_service
from app.llm.base import ProviderResponse, ProviderUsage
from app.llm.explore_service import PROMPT_VERSION, ExploreService
from app.llm.router import close_analyze_providers
from app.schemas.explore import ExploreRequest, SearchMoreProposal


class FakeProvider:
    def __init__(self, payload: dict[str, object]) -> None:
        self.payload = payload
        self.prompts: list[str] = []

    def generate(self, *, system_instruction, prompt, response_schema):
        self.prompts.append(prompt)
        return ProviderResponse(
            text=json.dumps(self.payload, ensure_ascii=False),
            provider="gemini",
            model="gemini-test",
            usage=ProviderUsage(input_tokens=10, output_tokens=5),
        )


def request(*, step: int = 1) -> ExploreRequest:
    previous_steps = []
    if step == 2:
        previous_steps.append(
            {
                "step": 1,
                "action": "READ_FULLTEXT",
                "accepted": True,
                "summary": "본문을 확보했습니다.",
                "evidenceSentenceCount": 3,
            }
        )
    return ExploreRequest.model_validate(
        {
            "idempotencyKey": f"run:42:issue:77:investigate:{step}",
            "plan": "FREE",
            "target": {"type": "ISSUE", "id": 77},
            "step": step,
            "issue": {
                "title": "A사와 B사의 HBM 공급 협상",
                "summary": "A사 발표만 확인됐습니다.",
                "status": "DISPUTED",
                "importanceScore": 81.5,
                "sensitivityScore": 72.0,
                "entities": ["A사", "B사"],
                "missingStakeholders": ["B사"],
                "evidenceSentenceCount": 2,
                "metadataOnlyArticleIds": [1024],
            },
            "allowedSources": [
                {"key": "NAVER", "name": "네이버 뉴스", "kind": "SEARCH"}
            ],
            "previousSteps": previous_steps,
        }
    )


def test_structured_provider_returns_typed_search_proposal() -> None:
    provider = FakeProvider(
        {
            "action": "SEARCH_MORE",
            "sourceKey": "NAVER",
            "query": "B사 HBM 공급 협상 입장",
            "reason": "B사 입장이 빠져 있어 추가 확인이 필요합니다.",
        }
    )

    response = ExploreService(Settings(AGENT_MOCK=False), provider).propose(request())

    assert response.proposal.action == "SEARCH_MORE"
    assert response.proposal.source_key == "NAVER"
    assert response.meta.prompt_version == PROMPT_VERSION
    assert response.meta.input_tokens == 10
    assert len(provider.prompts) == 1
    assert "<explore-input>" in provider.prompts[0]


def test_mock_concludes_after_observation_without_repeatable_work() -> None:
    target = request(step=2).model_copy(
        update={
            "issue": request().issue.model_copy(
                update={
                    "missing_stakeholders": [],
                    "metadata_only_article_ids": [],
                    "entities": [],
                }
            )
        }
    )

    response = ExploreService(Settings(AGENT_MOCK=True)).propose(target)

    assert response.proposal.action == "CONCLUDE"
    assert response.meta.mock is True


def test_paid_path_uses_pydantic_ai_adapter_and_preserves_usage(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    paid_request = request().model_copy(update={"plan": "PAID"})
    result = SimpleNamespace(
        proposal=SearchMoreProposal(
            action="SEARCH_MORE",
            source_key="NAVER",
            query="B사 HBM 입장",
            reason="빠진 입장을 확인합니다.",
        ),
        model="claude-test",
        usage=ProviderUsage(
            input_tokens=21,
            output_tokens=8,
            cost_usd=Decimal("0.03"),
            credits=Decimal("1.25"),
        ),
        truncated=False,
    )
    monkeypatch.setattr(explore_service, "_run_mindlogic", lambda settings, prompt: result)
    close_analyze_providers()

    response = ExploreService(Settings(AGENT_MOCK=False)).propose(paid_request)

    assert response.proposal.action == "SEARCH_MORE"
    assert response.meta.provider == "mindlogic-claude"
    assert response.meta.model == "claude-test"
    assert response.meta.credits == 1.25
    assert response.meta.cost_usd == 0.03
    close_analyze_providers()


def test_rejects_oversized_explore_collections_and_aggregate_input() -> None:
    payload = request().model_dump(by_alias=True, mode="json")
    payload["issue"]["entities"] = [f"기업-{index}" for index in range(51)]

    with pytest.raises(ValidationError):
        ExploreRequest.model_validate(payload)

    payload["issue"]["entities"] = ["가" * 200 for _ in range(50)]
    payload["issue"]["missingStakeholders"] = ["나" * 200 for _ in range(50)]

    with pytest.raises(ValidationError, match="20000자"):
        ExploreRequest.model_validate(payload)


def test_mindlogic_provider_requires_https() -> None:
    settings = Settings(
        AGENT_MOCK=False,
        MINDLOGIC_BASE_URL="http://mindlogic.invalid/v1/gateway",
    )

    with pytest.raises(AgentError) as error:
        explore_service._run_mindlogic(settings, "prompt")

    assert "HTTPS" in error.value.message
