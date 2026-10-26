"""Bounded historical error cases selected and persisted by Spring, never gold labels."""

from typing import Literal

from pydantic import Field, model_validator

from app.schemas.common import AgentModel

FeedbackLearningCategory = Literal["TOPIC_MISMATCH", "SUMMARY_ERROR", "WRONG_CLUSTER", "OTHER"]


class FeedbackLearningEvidence(AgentModel):
    article_id: int = Field(gt=0)
    quote: str = Field(min_length=1, max_length=300)


class FeedbackLearningExample(AgentModel):
    feedback_id: int = Field(gt=0)
    topic_id: int = Field(gt=0)
    category: FeedbackLearningCategory
    event_title: str = Field(max_length=1000)
    event_summary: str = Field(max_length=2000)
    diagnosis: str = Field(min_length=1, max_length=2000)
    evidence: list[FeedbackLearningEvidence] = Field(min_length=1, max_length=10)


class FeedbackLearningRequest(AgentModel):
    feedback_examples: list[FeedbackLearningExample] = Field(default_factory=list, max_length=5)

    @model_validator(mode="after")
    def validate_feedback_examples(self) -> "FeedbackLearningRequest":
        keys = [(example.feedback_id, example.topic_id) for example in self.feedback_examples]
        if len(keys) != len(set(keys)):
            raise ValueError("feedbackExamples의 feedbackId/topicId는 중복될 수 없습니다.")
        return self

    def validate_feedback_scope(
        self, topic_ids: set[int], *, categories: set[str] | None = None
    ) -> None:
        if any(example.topic_id not in topic_ids for example in self.feedback_examples):
            raise ValueError("feedbackExamples는 현재 입력의 주제에만 적용할 수 있습니다.")
        if categories is not None and any(
            example.category not in categories for example in self.feedback_examples
        ):
            raise ValueError("feedbackExamples의 유형은 현재 판단 단계에 맞아야 합니다.")
