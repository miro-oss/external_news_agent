"""Stateless internal contracts; ownership and policy activation belong to the backend."""

from typing import Annotated, Literal

from pydantic import Field, model_validator

from app.schemas.analyze import Plan, ResponseMeta
from app.schemas.common import AgentModel

Keyword = Annotated[str, Field(min_length=1, max_length=100)]
FeedbackCategory = Literal[
    "PREFERENCE", "TOPIC_MISMATCH", "SUMMARY_ERROR", "WRONG_CLUSTER", "OTHER"
]
FeedbackVerdict = Literal["PREFERENCE", "CONFIRMED_ERROR", "NOT_CONFIRMED", "INSUFFICIENT_EVIDENCE"]


class FeedbackTopic(AgentModel):
    id: int = Field(gt=0)
    name: str = Field(min_length=1, max_length=200)
    keywords: list[Keyword] = Field(default_factory=list, max_length=100)
    negative_keywords: list[Keyword] = Field(default_factory=list, max_length=100)


class FeedbackArticle(AgentModel):
    id: int = Field(gt=0)
    title: str = Field(min_length=1, max_length=1000)
    content: str = Field(default="", max_length=10_000)
    url: str = Field(default="", max_length=2000)


class FeedbackIssue(AgentModel):
    id: int = Field(gt=0)
    title: str = Field(min_length=1, max_length=1000)
    summary: str = Field(default="", max_length=5000)


class UserFeedback(AgentModel):
    category: FeedbackCategory
    comment: str = Field(min_length=1, max_length=2000)
    allow_personalization: bool = False


class DeliveryPolicy(AgentModel):
    id: int = Field(gt=0)
    instruction: str = Field(min_length=1, max_length=500)


class FeedbackEvidence(AgentModel):
    article_id: int = Field(gt=0)
    quote: str = Field(min_length=1, max_length=300)


class ProposedPolicy(AgentModel):
    instruction: str = Field(min_length=1, max_length=500)
    reason: str = Field(min_length=1, max_length=500)


class _ArticleRequest(AgentModel):
    idempotency_key: str = Field(min_length=1, max_length=200)
    plan: Plan
    topic: FeedbackTopic
    articles: list[FeedbackArticle] = Field(min_length=1, max_length=10)

    @model_validator(mode="after")
    def validate_articles(self) -> "_ArticleRequest":
        if len({article.id for article in self.articles}) != len(self.articles):
            raise ValueError("articles.id는 중복될 수 없습니다.")
        if len(self.model_dump_json(by_alias=True)) > 150_000:
            raise ValueError("피드백 입력은 150000자를 초과할 수 없습니다.")
        return self


class FeedbackReviewRequest(_ArticleRequest):
    issue: FeedbackIssue
    feedback: UserFeedback
    active_policies: list[DeliveryPolicy] = Field(default_factory=list, max_length=20)

    @model_validator(mode="after")
    def validate_policies(self) -> "FeedbackReviewRequest":
        _unique_policies(self.active_policies)
        return self


class FeedbackReviewOutput(AgentModel):
    verdict: FeedbackVerdict
    diagnosis: str = Field(min_length=1, max_length=2000)
    evidence: list[FeedbackEvidence] = Field(max_length=10)
    proposed_policy: ProposedPolicy | None = None


class FeedbackReviewResponse(FeedbackReviewOutput):
    meta: ResponseMeta


class FeedbackEvaluateRequest(_ArticleRequest):
    policies: list[DeliveryPolicy] = Field(max_length=20)

    @model_validator(mode="after")
    def validate_policies(self) -> "FeedbackEvaluateRequest":
        _unique_policies(self.policies)
        return self


class DeliveryDecision(AgentModel):
    article_id: int = Field(gt=0)
    status: Literal["KEEP", "SUPPRESS", "UNCERTAIN"]
    policy_ids: list[Annotated[int, Field(gt=0)]] = Field(max_length=20)
    evidence: list[FeedbackEvidence] = Field(max_length=3)
    reason: str = Field(min_length=1, max_length=500)


class FeedbackEvaluateOutput(AgentModel):
    decisions: list[DeliveryDecision] = Field(min_length=1, max_length=10)


class FeedbackEvaluateResponse(FeedbackEvaluateOutput):
    meta: ResponseMeta


def _unique_policies(policies: list[DeliveryPolicy]) -> None:
    if len({policy.id for policy in policies}) != len(policies):
        raise ValueError("정책 ID는 중복될 수 없습니다.")
