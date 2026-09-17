from typing import Annotated, Literal

from pydantic import Field, model_validator

from app.schemas.analyze import Plan, ResponseMeta
from app.schemas.common import AgentModel

MAX_RELEVANCE_ARTICLES = 10
MAX_RELEVANCE_INPUT_CHARS = 85_000
RelevanceStatus = Literal["RELEVANT", "IRRELEVANT", "UNCERTAIN"]
Keyword = Annotated[str, Field(min_length=1, max_length=100)]
EvidenceQuote = Annotated[str, Field(min_length=1, max_length=300)]


class RelevanceTopic(AgentModel):
    id: int = Field(gt=0)
    name: str = Field(min_length=1, max_length=200)
    query_text: str | None = Field(default=None, max_length=500)
    required_keywords: list[Keyword] = Field(default_factory=list, max_length=100)
    optional_keywords: list[Keyword] = Field(default_factory=list, max_length=100)
    excluded_keywords: list[Keyword] = Field(default_factory=list, max_length=100)


class RelevanceArticle(AgentModel):
    article_id: int = Field(gt=0)
    title: str = Field(min_length=1, max_length=1000)
    summary: str | None = Field(default=None, max_length=1000)
    body_text: str = Field(min_length=1, max_length=5000)


class TopicRelevanceRequest(AgentModel):
    idempotency_key: str = Field(min_length=1, max_length=200)
    plan: Plan
    topic: RelevanceTopic
    articles: list[RelevanceArticle] = Field(min_length=1, max_length=MAX_RELEVANCE_ARTICLES)

    def provider_input_json(self) -> str:
        """Bound exactly the compact, delimiter-safe JSON sent to the provider."""
        return self.model_dump_json(by_alias=True).replace("<", "\\u003c").replace(">", "\\u003e")

    @model_validator(mode="after")
    def validate_request(self) -> "TopicRelevanceRequest":
        article_ids = [article.article_id for article in self.articles]
        if len(article_ids) != len(set(article_ids)):
            raise ValueError("articles.articleId는 중복될 수 없습니다.")
        if len(self.provider_input_json()) > MAX_RELEVANCE_INPUT_CHARS:
            raise ValueError("topic relevance 입력은 85000자를 초과할 수 없습니다.")
        return self


class RelevanceDecision(AgentModel):
    article_id: int = Field(gt=0)
    status: RelevanceStatus
    reason: str = Field(min_length=1, max_length=500)
    evidence_quotes: list[EvidenceQuote] = Field(max_length=3)

    @model_validator(mode="after")
    def validate_evidence(self) -> "RelevanceDecision":
        if self.status != "UNCERTAIN" and not self.evidence_quotes:
            raise ValueError("RELEVANT/IRRELEVANT 판정에는 evidenceQuotes가 필요합니다.")
        return self


class TopicRelevanceOutput(AgentModel):
    decisions: list[RelevanceDecision] = Field(min_length=1, max_length=MAX_RELEVANCE_ARTICLES)

    @model_validator(mode="after")
    def validate_unique_article_ids(self) -> "TopicRelevanceOutput":
        article_ids = [decision.article_id for decision in self.decisions]
        if len(article_ids) != len(set(article_ids)):
            raise ValueError("decisions.articleId는 중복될 수 없습니다.")
        return self


class TopicRelevanceResponse(TopicRelevanceOutput):
    meta: ResponseMeta
