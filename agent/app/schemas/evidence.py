from typing import Annotated

from pydantic import Field, model_validator

from app.schemas.analyze import Groundedness, Plan, ResponseMeta
from app.schemas.common import AgentModel

NonEmptyString = Annotated[str, Field(min_length=1)]


class EvidenceSentence(AgentModel):
    id: int = Field(gt=0)
    text: str = Field(min_length=1)


class EvidenceVerifyRequest(AgentModel):
    idempotency_key: str = Field(min_length=1, max_length=200)
    plan: Plan = "FREE"
    claim: str = Field(min_length=1)
    sentences: list[EvidenceSentence] = Field(min_length=1)

    @model_validator(mode="after")
    def validate_unique_sentence_ids(self) -> "EvidenceVerifyRequest":
        ids = [sentence.id for sentence in self.sentences]
        if len(ids) != len(set(ids)):
            raise ValueError("sentence id는 요청 안에서 유일해야 합니다.")
        return self


class EvidenceOutput(AgentModel):
    status: Groundedness
    accepted_sentence_ids: list[Annotated[int, Field(gt=0)]]
    reason: NonEmptyString

    @model_validator(mode="after")
    def validate_status_and_evidence(self) -> "EvidenceOutput":
        if len(self.accepted_sentence_ids) != len(set(self.accepted_sentence_ids)):
            raise ValueError("acceptedSentenceIds는 중복될 수 없습니다.")
        if self.status == "ungrounded" and self.accepted_sentence_ids:
            raise ValueError("ungrounded 판정은 acceptedSentenceIds가 비어 있어야 합니다.")
        if self.status != "ungrounded" and not self.accepted_sentence_ids:
            raise ValueError("grounded/weak 판정은 acceptedSentenceIds가 필요합니다.")
        return self


class EvidenceVerifyResponse(EvidenceOutput):
    meta: ResponseMeta
