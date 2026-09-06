"""OpenAI wire format; claim IDs belong to the application, not the model."""

from typing import Annotated, Literal

from pydantic import Field

from app.schemas.analyze import Audience
from app.schemas.common import AgentModel
from app.schemas.insight import InsightOutput


class OpenAIInsightFactDraft(AgentModel):
    claim_type: Literal["FACT"]
    text: str = Field(min_length=1, max_length=1000)
    finding_id: int = Field(gt=0)
    evidence_sentence_ids: list[Annotated[int, Field(gt=0)]] = Field(min_length=1)


class OpenAIInsightImplicationDraft(AgentModel):
    claim_type: Literal["IMPLICATION"]
    text: str = Field(min_length=1, max_length=1000)
    assumption: str = Field(min_length=1, max_length=1000)
    falsified_by: str = Field(min_length=1, max_length=1000)


class OpenAIInsightFactGroup(AgentModel):
    facts: list[OpenAIInsightFactDraft] = Field(
        min_length=1,
        description="Every fact in this group supports each of its implications.",
    )
    implications: list[OpenAIInsightImplicationDraft]


class OpenAIAudienceInsightDraft(AgentModel):
    audience: Audience
    headline: str = Field(min_length=1, max_length=500)
    fact_groups: list[OpenAIInsightFactGroup]
    watch_next: list[Annotated[str, Field(min_length=1, max_length=500)]]
    confidence: float = Field(ge=0, le=1)


class OpenAIInsightDraft(AgentModel):
    insights: list[OpenAIAudienceInsightDraft] = Field(min_length=1, max_length=4)

    def to_output(self) -> InsightOutput:
        # Assign before grounding so filtering cannot change a claim's references.
        insights = []
        for insight in self.insights:
            payload = insight.model_dump(exclude={"fact_groups"})
            facts = []
            implications = []
            fact_ids: dict[tuple[int, str, tuple[int, ...]], str] = {}
            for group in insight.fact_groups:
                basis_ids = []
                for fact in group.facts:
                    key = (fact.finding_id, fact.text, tuple(sorted(fact.evidence_sentence_ids)))
                    fact_id = fact_ids.get(key)
                    if fact_id is None:
                        fact_id = f"f{len(facts) + 1}"
                        fact_ids[key] = fact_id
                        facts.append({**fact.model_dump(), "id": fact_id})
                    if fact_id not in basis_ids:
                        basis_ids.append(fact_id)
                for implication in group.implications:
                    implications.append({
                        **implication.model_dump(),
                        "id": f"i{len(implications) + 1}",
                        "basis_fact_ids": list(basis_ids),
                    })
            payload["facts"] = facts
            payload["implications"] = implications
            insights.append(payload)
        # Keep all public contract validators, including evidence and reference uniqueness.
        return InsightOutput.model_validate({"insights": insights})
