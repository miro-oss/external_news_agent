from datetime import datetime
from typing import Annotated, Any, Literal

from pydantic import Field, model_validator

from app.schemas.common import AgentModel

Plan = Literal["FREE", "PAID"]
Groundedness = Literal["grounded", "weak", "ungrounded"]
Audience = Literal[
    "CHIP_MAKER",
    "EQUIPMENT_MAKER",
    "MARKET_INVESTOR",
    "IT_INFRA",
]
AudienceRelevance = Literal["none", "low", "medium", "high"]
NonEmptyString = Annotated[str, Field(min_length=1)]
AUDIENCES = frozenset(
    {"CHIP_MAKER", "EQUIPMENT_MAKER", "MARKET_INVESTOR", "IT_INFRA"}
)


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
    evidence_sentence_ids: list[Annotated[int, Field(ge=1)]] = Field(min_length=1)
    groundedness: Groundedness
    confidence: float = Field(ge=0, le=1)


class Section(AgentModel):
    heading: str = Field(min_length=1)
    bullets: list[EvidenceBullet] = Field(min_length=1)


class Classification(AgentModel):
    intent: str = Field(min_length=1)
    sentiment: Literal["positive", "neutral", "negative"]
    risk_level: Literal["low", "medium", "high"]
    relevance: Literal["important", "watch", "reference"]
    category: Literal["제품/공정", "기업", "정책", "공급망"]


class Entities(AgentModel):
    companies: list[NonEmptyString]
    products: list[NonEmptyString]
    technologies: list[NonEmptyString]


class PerspectiveTag(AgentModel):
    audience: Audience
    relevance: AudienceRelevance
    hook: str | None = Field(default=None, min_length=1)
    evidence_sentence_ids: list[Annotated[int, Field(ge=1)]]

    @model_validator(mode="after")
    def validate_evidence_contract(self) -> "PerspectiveTag":
        if self.relevance == "none":
            if self.hook is not None or self.evidence_sentence_ids:
                raise ValueError("relevance가 none이면 hook과 evidenceSentenceIds가 없어야 합니다.")
        elif self.hook is None or not self.evidence_sentence_ids:
            raise ValueError(
                "relevance가 none이 아니면 hook과 evidenceSentenceIds가 필요합니다."
            )
        if len(self.evidence_sentence_ids) != len(set(self.evidence_sentence_ids)):
            raise ValueError("perspective tag의 evidenceSentenceIds는 중복될 수 없습니다.")
        return self


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


class AnalyzeOutput(AgentModel):
    sections: list[Section] = Field(min_length=1)
    summary_ko: str = Field(min_length=1)
    classification: Classification
    entities: Entities
    perspective_tags: list[PerspectiveTag]

    @model_validator(mode="after")
    def validate_perspective_tags(self) -> "AnalyzeOutput":
        _validate_perspective_tag_set(self.perspective_tags)
        return self


class AnalyzeResponse(AgentModel):
    sentences: list[Annotated[str, Field(min_length=1)]] = Field(min_length=1)
    sections: list[Section] = Field(min_length=1)
    summary_ko: str = Field(min_length=1)
    classification: Classification
    entities: Entities
    perspective_tags: list[PerspectiveTag]
    meta: ResponseMeta

    @model_validator(mode="after")
    def validate_evidence_sentence_ids(self) -> "AnalyzeResponse":
        sentence_count = len(self.sentences)
        if any(
            not bullet.evidence_sentence_ids
            or any(sentence_id > sentence_count for sentence_id in bullet.evidence_sentence_ids)
            for section in self.sections
            for bullet in section.bullets
        ):
            raise ValueError(
                "모든 bullet은 sentences 범위 안의 evidenceSentenceIds를 가져야 합니다."
            )
        _validate_perspective_tag_set(self.perspective_tags)
        if any(
            sentence_id > sentence_count
            for tag in self.perspective_tags
            for sentence_id in tag.evidence_sentence_ids
        ):
            raise ValueError(
                "perspective tag의 evidenceSentenceIds는 sentences 범위 안이어야 합니다."
            )
        return self


def _validate_perspective_tag_set(tags: list[PerspectiveTag]) -> None:
    audiences = [tag.audience for tag in tags]
    if len(tags) != len(AUDIENCES) or set(audiences) != AUDIENCES:
        raise ValueError("perspectiveTags는 4개 audience를 정확히 한 번씩 포함해야 합니다.")
    if len(audiences) != len(set(audiences)):
        raise ValueError("perspectiveTags의 audience는 중복될 수 없습니다.")
    if sum(tag.relevance == "high" for tag in tags) > 2:
        raise ValueError("high relevance 관점은 최대 2개입니다.")
