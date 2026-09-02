import asyncio
import json
import logging
from dataclasses import dataclass
from decimal import Decimal
from pathlib import Path
from urllib.parse import urlsplit

import httpx2
from pydantic_ai import Agent, NativeOutput, UnexpectedModelBehavior
from pydantic_ai.exceptions import ModelHTTPError
from pydantic_ai.models.openai import OpenAIChatModelSettings
from pydantic_ai.providers.openai import OpenAIProvider

from app.core.config import Settings
from app.core.errors import AgentError
from app.core.parser import parse_json_object
from app.llm.base import AnalyzeProvider, ProviderResponse, ProviderUsage
from app.llm.guarded_provider import run_guarded
from app.llm.pydantic_ai_mindlogic import (
    MindlogicUsageOpenAIChatModel,
    extract_mindlogic_cost_usd,
    extract_mindlogic_credits,
    is_mindlogic_truncated,
    mindlogic_model_profile,
    preserve_mindlogic_trailing_slash,
)
from app.llm.rate_limit_provider import run_with_request_policy
from app.llm.router import (
    get_analyze_provider,
    get_provider_coordinator,
    get_provider_guard,
)
from app.llm.structured_call import structured_call
from app.schemas.analyze import ResponseMeta
from app.schemas.explore import (
    CompareHistoryProposal,
    ConcludeProposal,
    ExploreProposal,
    ExploreRequest,
    ExploreResponse,
    Proposal,
    ReadFullTextProposal,
    SearchMoreProposal,
)

PROMPT_VERSION = "explore.ko.v1"
SYSTEM_INSTRUCTION = (
    Path(__file__).resolve().parents[1] / "prompts" / f"{PROMPT_VERSION}.md"
).read_text(encoding="utf-8").strip()

logger = logging.getLogger(__name__)


@dataclass(frozen=True, slots=True)
class _ExploreCallResult:
    proposal: Proposal
    provider: str
    model: str
    usage: ProviderUsage
    truncated: bool


class ExploreService:
    def __init__(
        self,
        settings: Settings,
        provider: AnalyzeProvider | None = None,
    ) -> None:
        self._settings = settings
        self._provider = provider

    def propose(self, request: ExploreRequest) -> ExploreResponse:
        if self._settings.mock:
            return _mock_response(request)
        if self._provider is not None or request.plan == "FREE":
            return self._structured_proposal(request)
        return self._pydantic_ai_proposal(request)

    def _structured_proposal(self, request: ExploreRequest) -> ExploreResponse:
        provider = self._provider or get_analyze_provider(self._settings, request.plan)
        result = structured_call(
            provider,
            system_instruction=SYSTEM_INSTRUCTION,
            prompt=_explore_prompt(request),
            response_schema=ExploreProposal.model_json_schema(by_alias=True),
            validate=lambda response: ExploreProposal.model_validate(
                parse_json_object(response.text)
            ).root,
            repair_attempts=self._settings.schema_repair_attempts,
            task_name="추가 조사 제안",
            input_tag="explore",
            schema_violation_message="Provider 조사 제안이 Agent 계약을 위반했습니다.",
            logger=logger,
        )
        return ExploreResponse(
            proposal=result.output,
            meta=_response_meta(result.response, result.usage),
        )

    def _pydantic_ai_proposal(self, request: ExploreRequest) -> ExploreResponse:
        guard = get_provider_guard(self._settings, request.plan)
        coordinator = get_provider_coordinator(self._settings, request.plan)
        result = run_with_request_policy(
            coordinator,
            lambda: run_guarded(
                guard,
                lambda: _run_mindlogic(self._settings, _explore_prompt(request)),
                credits=lambda value: value.usage.credits,
                usage_details=lambda value: {
                    "inputTokens": value.usage.input_tokens,
                    "outputTokens": value.usage.output_tokens,
                    "costUsd": float(value.usage.cost_usd),
                    "credits": float(value.usage.credits),
                },
            ),
        )
        return ExploreResponse(
            proposal=result.proposal,
            meta=ResponseMeta(
                provider="mindlogic-claude",
                model=result.model,
                prompt_version=PROMPT_VERSION,
                input_tokens=result.usage.input_tokens,
                output_tokens=result.usage.output_tokens,
                cost_usd=float(result.usage.cost_usd),
                credits=float(result.usage.credits),
                mock=False,
                truncated=result.truncated,
            ),
        )


def _run_mindlogic(settings: Settings, prompt: str) -> _ExploreCallResult:
    endpoint = urlsplit(settings.mindlogic_base_url)
    if endpoint.scheme.lower() != "https" or not endpoint.netloc:
        raise AgentError(
            status_code=503,
            code="PROVIDER_UNAVAILABLE",
            message="Mindlogic provider HTTPS 주소가 올바르지 않습니다.",
        )

    async def run() -> _ExploreCallResult:
        async with httpx2.AsyncClient(
            timeout=settings.provider_timeout_seconds,
            follow_redirects=False,
            event_hooks={"request": [preserve_mindlogic_trailing_slash]},
        ) as client:
            provider = OpenAIProvider(
                base_url=settings.mindlogic_base_url,
                api_key=settings.mindlogic_api_key,
                http_client=client,
            )
            model = MindlogicUsageOpenAIChatModel(
                settings.mindlogic_claude_model,
                provider=provider,
                profile=mindlogic_model_profile(),
            )
            agent = Agent(
                model,
                instructions=SYSTEM_INSTRUCTION,
                output_type=NativeOutput(ExploreProposal, strict=True),
                model_settings=OpenAIChatModelSettings(
                    max_tokens=settings.max_output_tokens,
                    temperature=0,
                ),
                retries=0,
            )
            result = await agent.run(prompt)
            response = result.response
            usage = ProviderUsage(
                input_tokens=result.usage.input_tokens,
                output_tokens=result.usage.output_tokens,
                cost_usd=extract_mindlogic_cost_usd(response),
                credits=extract_mindlogic_credits(
                    response,
                    default=Decimal(str(settings.mindlogic_credits_per_request)),
                ),
            )
            return _ExploreCallResult(
                proposal=result.output.root,
                provider="mindlogic-claude",
                model=response.model_name or settings.mindlogic_claude_model,
                usage=usage,
                truncated=is_mindlogic_truncated(response),
            )

    try:
        return asyncio.run(run())
    except ModelHTTPError as error:
        if error.status_code == 429:
            raise AgentError(
                status_code=429,
                code="PROVIDER_UNAVAILABLE",
                message="Mindlogic provider 호출 한도를 초과했습니다.",
                details={"rateLimited": True, "retryable": True},
            ) from error
        raise AgentError(
            status_code=503,
            code="PROVIDER_UNAVAILABLE",
            message="Mindlogic provider를 호출할 수 없습니다.",
        ) from error
    except UnexpectedModelBehavior as error:
        raise AgentError(
            status_code=502,
            code="SCHEMA_VIOLATION",
            message="Provider 조사 제안이 Agent 계약을 위반했습니다.",
        ) from error


def _response_meta(response: ProviderResponse, usage: ProviderUsage) -> ResponseMeta:
    return ResponseMeta(
        provider=response.provider,
        model=response.model,
        prompt_version=PROMPT_VERSION,
        input_tokens=usage.input_tokens,
        output_tokens=usage.output_tokens,
        cost_usd=float(usage.cost_usd),
        credits=float(usage.credits),
        mock=response.provider == "mock",
        truncated=response.truncated,
    )


def _explore_prompt(request: ExploreRequest) -> str:
    serialized = json.dumps(
        request.model_dump(by_alias=True, mode="json"),
        ensure_ascii=False,
    ).replace("<", "\\u003c").replace(">", "\\u003e")
    return (
        "다음 JSON을 현재 조사 상태로만 사용하고 다음 행동 하나를 제안하세요.\n\n"
        f"<explore-input>\n{serialized}\n</explore-input>"
    )


def _mock_response(request: ExploreRequest) -> ExploreResponse:
    if request.issue.missing_stakeholders and request.allowed_sources:
        stakeholder = request.issue.missing_stakeholders[0]
        proposal: Proposal = SearchMoreProposal(
            action="SEARCH_MORE",
            source_key=request.allowed_sources[0].key,
            query=f"{request.issue.title} {stakeholder}",
            reason=f"{stakeholder}의 입장이 없어 추가 보도가 필요한지 확인합니다.",
        )
    elif request.issue.metadata_only_article_ids:
        proposal = ReadFullTextProposal(
            action="READ_FULLTEXT",
            article_id=request.issue.metadata_only_article_ids[0],
            reason="제목과 요약만 있는 기사의 본문 근거를 확인합니다.",
        )
    elif request.issue.entities and not request.previous_steps:
        proposal = CompareHistoryProposal(
            action="COMPARE_HISTORY",
            entities=request.issue.entities[:3],
            days=30,
            reason="같은 엔티티의 최근 이슈와 비교해 반복되는 변화를 확인합니다.",
        )
    else:
        proposal = ConcludeProposal(
            action="CONCLUDE",
            reason="현재 근거로 결론을 작성할 수 있어 추가 조사를 마칩니다.",
        )
    return ExploreResponse(
        proposal=proposal,
        meta=ResponseMeta(
            provider="mock",
            model="mock",
            prompt_version=PROMPT_VERSION,
            input_tokens=0,
            output_tokens=0,
            cost_usd=0,
            credits=0,
            mock=True,
            truncated=False,
        ),
    )
