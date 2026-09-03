from datetime import datetime
from typing import Annotated, Literal

from pydantic import Field, model_validator

from app.schemas.analyze import Plan, ResponseMeta
from app.schemas.common import AgentModel

MAX_STRATEGY_ARTICLES = 20
MAX_STRATEGY_INPUT_CHARS = 20_000


class KeywordStrategyTarget(AgentModel):
    type: Literal["TOPIC"]
    id: int = Field(gt=0)


class KeywordStrategyTopic(AgentModel):
    name: str = Field(min_length=1, max_length=200)
    query_text: str | None = Field(default=None, max_length=500)
    required_keywords: list[Annotated[str, Field(min_length=1, max_length=100)]] = Field(
        default_factory=list, max_length=100
    )
    optional_keywords: list[Annotated[str, Field(min_length=1, max_length=100)]] = Field(
        default_factory=list, max_length=100
    )
    excluded_keywords: list[Annotated[str, Field(min_length=1, max_length=100)]] = Field(
        default_factory=list, max_length=100
    )


class KeywordStrategyRun(AgentModel):
    id: int = Field(gt=0)
    trigger_type: Literal["SCHEDULED", "MANUAL"]
    scanned_count: int | None = Field(default=None, ge=0)
    new_count: int | None = Field(default=None, ge=0)
    updated_count: int | None = Field(default=None, ge=0)


class KeywordStat(AgentModel):
    bucket: Literal["REQUIRED", "OPTIONAL", "EXCLUDED"]
    keyword: str = Field(min_length=1, max_length=100)
    article_match_count: int = Field(ge=0)


class ArticleObservation(AgentModel):
    article_id: int = Field(gt=0)
    title: str = Field(min_length=1, max_length=1000)
    summary: str | None = Field(default=None, max_length=2000)
    publisher: str | None = Field(default=None, max_length=500)
    change_type: Literal["NEW", "UPDATED", "UNCHANGED"]
    published_at: datetime | None = None
    topic_fit: float = Field(ge=0, le=1)


class KeywordProposal(AgentModel):
    bucket: Literal["REQUIRED", "OPTIONAL", "EXCLUDED"]
    action: Literal["ADD", "REMOVE"]
    keyword: str = Field(min_length=1, max_length=100)
    reason: str = Field(min_length=1, max_length=500)


class KeywordStrategyOutput(AgentModel):
    summary: str = Field(min_length=1, max_length=1000)
    proposals: list[KeywordProposal] = Field(max_length=12)

    @model_validator(mode="after")
    def validate_unique_keywords(self) -> "KeywordStrategyOutput":
        seen: set[tuple[str, str]] = set()
        for proposal in self.proposals:
            key = (proposal.bucket, proposal.keyword.casefold())
            if key in seen:
                raise ValueError("같은 bucket의 keyword를 중복 제안할 수 없습니다.")
            seen.add(key)
        return self


class KeywordStrategyRequest(AgentModel):
    idempotency_key: str = Field(min_length=1, max_length=200)
    plan: Plan
    target: KeywordStrategyTarget
    topic: KeywordStrategyTopic
    run: KeywordStrategyRun
    current_keyword_stats: list[KeywordStat] = Field(max_length=300)
    articles: list[ArticleObservation] = Field(max_length=MAX_STRATEGY_ARTICLES)

    @model_validator(mode="after")
    def validate_request(self) -> "KeywordStrategyRequest":
        stat_keys = [(item.bucket, item.keyword.casefold()) for item in self.current_keyword_stats]
        if len(stat_keys) != len(set(stat_keys)):
            raise ValueError("currentKeywordStats는 같은 bucket/keyword를 중복 포함할 수 없습니다.")
        article_ids = [article.article_id for article in self.articles]
        if len(article_ids) != len(set(article_ids)):
            raise ValueError("articles.articleId는 중복될 수 없습니다.")
        if len(self.model_dump_json(by_alias=True)) > MAX_STRATEGY_INPUT_CHARS:
            raise ValueError("keyword strategy 입력은 20000자를 초과할 수 없습니다.")
        return self


class KeywordStrategyResponse(AgentModel):
    summary: str = Field(min_length=1, max_length=1000)
    proposals: list[KeywordProposal] = Field(max_length=12)
    meta: ResponseMeta
