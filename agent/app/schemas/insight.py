from typing import Annotated, Literal

from pydantic import Field, model_validator

from app.schemas.analyze import Audience, Groundedness, Plan, ResponseMeta, TopicInput
from app.schemas.common import AgentModel

MAX_INSIGHT_FINDINGS = 10


class InsightTarget(AgentModel):
    type: Literal["ISSUE"]
    id: int = Field(gt=0)


class InsightSentence(AgentModel):
    id: int = Field(gt=0)
    text: str = Field(min_length=1)


class InsightFinding(AgentModel):
    id: int = Field(gt=0)
    article_title: str = Field(min_length=1, max_length=1000)
    canonical_url: str = Field(min_length=1, max_length=2000)
    summary_ko: str = Field(min_length=1, max_length=2000)
    sentences: list[InsightSentence] = Field(min_length=1)

    @model_validator(mode="after")
    def validate_sentence_ids(self) -> "InsightFinding":
        ids = [sentence.id for sentence in self.sentences]
        if len(ids) != len(set(ids)):
            raise ValueError("finding 안의 sentence id는 중복될 수 없습니다.")
        return self


class InsightRequest(AgentModel):
    idempotency_key: str = Field(min_length=1, max_length=200)
    plan: Plan
    audiences: list[Audience] = Field(min_length=1, max_length=4)
    target: InsightTarget
    topic: TopicInput
    findings: list[InsightFinding] = Field(min_length=1, max_length=MAX_INSIGHT_FINDINGS)

    @model_validator(mode="after")
    def validate_unique_values(self) -> "InsightRequest":
        if len(self.audiences) != len(set(self.audiences)):
            raise ValueError("audiences는 중복될 수 없습니다.")
        finding_ids = [finding.id for finding in self.findings]
        if len(finding_ids) != len(set(finding_ids)):
            raise ValueError("findings의 id는 중복될 수 없습니다.")
        return self


class InsightFactOutput(AgentModel):
    claim_type: Literal["FACT"]
    id: str = Field(min_length=1, max_length=30)
    text: str = Field(min_length=1, max_length=1000)
    finding_id: int = Field(gt=0)
    evidence_sentence_ids: list[Annotated[int, Field(gt=0)]] = Field(min_length=1)

    @model_validator(mode="after")
    def validate_unique_evidence(self) -> "InsightFactOutput":
        if len(self.evidence_sentence_ids) != len(set(self.evidence_sentence_ids)):
            raise ValueError("FACT evidenceSentenceIds는 중복될 수 없습니다.")
        return self


class InsightImplication(AgentModel):
    claim_type: Literal["IMPLICATION"]
    id: str = Field(min_length=1, max_length=30)
    text: str = Field(min_length=1, max_length=1000)
    basis_fact_ids: list[Annotated[str, Field(min_length=1, max_length=30)]] = Field(
        min_length=1
    )
    assumption: str = Field(min_length=1, max_length=1000)
    falsified_by: str = Field(min_length=1, max_length=1000)

    @model_validator(mode="after")
    def validate_unique_basis(self) -> "InsightImplication":
        if len(self.basis_fact_ids) != len(set(self.basis_fact_ids)):
            raise ValueError("IMPLICATION basisFactIds는 중복될 수 없습니다.")
        return self


class AudienceInsightOutput(AgentModel):
    audience: Audience
    headline: str = Field(min_length=1, max_length=500)
    facts: list[InsightFactOutput]
    implications: list[InsightImplication]
    watch_next: list[Annotated[str, Field(min_length=1, max_length=500)]]
    confidence: float = Field(ge=0, le=1)

    @model_validator(mode="after")
    def validate_claim_references(self) -> "AudienceInsightOutput":
        fact_ids = [fact.id for fact in self.facts]
        implication_ids = [implication.id for implication in self.implications]
        if len(fact_ids) != len(set(fact_ids)):
            raise ValueError("FACT id는 관점 안에서 중복될 수 없습니다.")
        if len(implication_ids) != len(set(implication_ids)):
            raise ValueError("IMPLICATION id는 관점 안에서 중복될 수 없습니다.")
        known_fact_ids = set(fact_ids)
        if any(
            not set(implication.basis_fact_ids) <= known_fact_ids
            for implication in self.implications
        ):
            raise ValueError("basisFactIds는 같은 관점의 FACT만 참조해야 합니다.")
        if not self.facts and self.implications:
            raise ValueError("FACT가 없으면 IMPLICATION도 비어 있어야 합니다.")
        return self


class InsightOutput(AgentModel):
    insights: list[AudienceInsightOutput] = Field(min_length=1, max_length=4)


class InsightFact(InsightFactOutput):
    groundedness: Groundedness
    grounding_reason: str = Field(min_length=1, max_length=1000)


class AudienceInsight(AgentModel):
    audience: Audience
    headline: str = Field(min_length=1, max_length=500)
    facts: list[InsightFact]
    implications: list[InsightImplication]
    watch_next: list[Annotated[str, Field(min_length=1, max_length=500)]]
    confidence: float = Field(ge=0, le=1)


class InsightResponse(AgentModel):
    insights: list[AudienceInsight] = Field(min_length=1, max_length=4)
    meta: ResponseMeta
