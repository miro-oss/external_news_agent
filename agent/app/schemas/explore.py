from typing import Annotated, Literal

from pydantic import Field, RootModel, model_validator

from app.schemas.analyze import Plan, ResponseMeta
from app.schemas.common import AgentModel

MAX_EXPLORE_INPUT_CHARS = 20_000


class ExploreTarget(AgentModel):
    type: Literal["ISSUE"]
    id: int = Field(gt=0)


class ExploreIssue(AgentModel):
    title: str = Field(min_length=1, max_length=1000)
    summary: str | None = Field(default=None, max_length=4000)
    status: str = Field(min_length=1, max_length=30)
    importance_score: float | None = Field(default=None, ge=0, le=100)
    sensitivity_score: float | None = Field(default=None, ge=0, le=100)
    entities: list[Annotated[str, Field(min_length=1, max_length=200)]] = Field(
        max_length=50
    )
    missing_stakeholders: list[
        Annotated[str, Field(min_length=1, max_length=200)]
    ] = Field(max_length=50)
    evidence_sentence_count: int = Field(ge=0)
    metadata_only_article_ids: list[Annotated[int, Field(gt=0)]] = Field(
        max_length=500
    )


class AllowedSource(AgentModel):
    key: str = Field(min_length=1, max_length=100)
    name: str = Field(min_length=1, max_length=200)
    kind: Literal["SEARCH", "FEED"]


class ExploreObservation(AgentModel):
    step: int = Field(ge=1, le=3)
    action: str = Field(min_length=1, max_length=30)
    accepted: bool
    summary: str = Field(min_length=1, max_length=2000)
    evidence_sentence_count: int = Field(ge=0)


class ExploreRequest(AgentModel):
    idempotency_key: str = Field(min_length=1, max_length=200)
    plan: Plan
    target: ExploreTarget
    step: int = Field(ge=1, le=3)
    issue: ExploreIssue
    allowed_sources: list[AllowedSource] = Field(max_length=100)
    previous_steps: list[ExploreObservation] = Field(max_length=2)

    @model_validator(mode="after")
    def validate_unique_inputs(self) -> "ExploreRequest":
        source_keys = [source.key for source in self.allowed_sources]
        if len(source_keys) != len(set(source_keys)):
            raise ValueError("allowedSources key는 중복될 수 없습니다.")
        if [item.step for item in self.previous_steps] != list(range(1, self.step)):
            raise ValueError("previousSteps는 현재 step 전까지 순서대로 포함해야 합니다.")
        if len(self.model_dump_json(by_alias=True)) > MAX_EXPLORE_INPUT_CHARS:
            raise ValueError("explore 입력은 20000자를 초과할 수 없습니다.")
        return self


class SearchMoreProposal(AgentModel):
    action: Literal["SEARCH_MORE"]
    source_key: str = Field(min_length=1, max_length=100)
    query: str = Field(min_length=1, max_length=500)
    reason: str = Field(min_length=1, max_length=1000)


class ReadFullTextProposal(AgentModel):
    action: Literal["READ_FULLTEXT"]
    article_id: int = Field(gt=0)
    reason: str = Field(min_length=1, max_length=1000)


class CompareHistoryProposal(AgentModel):
    action: Literal["COMPARE_HISTORY"]
    entities: list[Annotated[str, Field(min_length=1, max_length=200)]] = Field(
        min_length=1,
        max_length=10,
    )
    days: int = Field(ge=1, le=365)
    reason: str = Field(min_length=1, max_length=1000)


class ConcludeProposal(AgentModel):
    action: Literal["CONCLUDE"]
    reason: str = Field(min_length=1, max_length=1000)


Proposal = Annotated[
    SearchMoreProposal | ReadFullTextProposal | CompareHistoryProposal | ConcludeProposal,
    Field(discriminator="action"),
]


class ExploreProposal(RootModel[Proposal]):
    pass


class ExploreResponse(AgentModel):
    proposal: Proposal
    meta: ResponseMeta
