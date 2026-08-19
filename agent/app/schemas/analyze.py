from datetime import datetime
from typing import Annotated, Any, Literal

from pydantic import Field

from app.schemas.common import AgentModel

Plan = Literal["FREE", "PAID"]
Groundedness = Literal["grounded", "weak", "ungrounded"]


class ArticleInput(AgentModel):
    id: int = Field(gt=0)
    title: str = Field(min_length=1, max_length=1000)
    canonical_url: str = Field(min_length=1, max_length=2000)
    language: str | None = Field(default=None, max_length=10)
    published_at: datetime | None = None
    body_text: str = ""


class TopicInput(AgentModel):
    name: str = Field(min_length=1, max_length=200)
    query_text: str | None = Field(default=None, max_length=500)
    required_keywords: list[str] = Field(default_factory=list)
    optional_keywords: list[str] = Field(default_factory=list)
    excluded_keywords: list[str] = Field(default_factory=list)


class AnalyzeRequest(AgentModel):
    idempotency_key: str = Field(min_length=1, max_length=200)
    plan: Plan
    article: ArticleInput
    topic: TopicInput
    previous_finding: dict[str, Any] | None = None


class EvidenceBullet(AgentModel):
    text: str = Field(min_length=1)
    evidence_sentence_ids: list[int]
    groundedness: Groundedness
    confidence: float = Field(ge=0, le=1)


class Section(AgentModel):
    heading: str = Field(min_length=1)
    bullets: list[EvidenceBullet]


class Classification(AgentModel):
    intent: str = Field(min_length=1)
    sentiment: Literal["positive", "neutral", "negative"]
    risk_level: Literal["low", "medium", "high"]
    relevance: Literal["important", "watch", "reference"]
    category: Literal["제품/공정", "기업", "정책", "공급망"]


class Entities(AgentModel):
    companies: list[str]
    products: list[str]
    technologies: list[str]


class ResponseMeta(AgentModel):
    provider: Literal["gemini", "mindlogic-claude", "mock"]
    model: str = Field(min_length=1)
    prompt_version: str = Field(min_length=1, max_length=50)
    input_tokens: int = Field(ge=0)
    output_tokens: int = Field(ge=0)
    cost_usd: float = Field(ge=0)
    credits: float = Field(ge=0)
    mock: bool
    truncated: bool


class AnalyzeResponse(AgentModel):
    sentences: list[Annotated[str, Field(min_length=1)]] = Field(min_length=1)
    sections: list[Section]
    summary_ko: str = Field(min_length=1)
    classification: Classification
    entities: Entities
    meta: ResponseMeta
