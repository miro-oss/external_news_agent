from typing import Annotated

from pydantic import Field, model_validator

from app.schemas.analyze import ClaimType, Groundedness, Plan, ResponseMeta
from app.schemas.common import AgentModel

NonEmptyString = Annotated[str, Field(min_length=1)]


class EvidenceSentence(AgentModel):
    id: int = Field(gt=0)
    text: str = Field(min_length=1)


class EvidenceClaim(AgentModel):
    claim_id: str = Field(min_length=1, max_length=200)
    claim: str = Field(min_length=1)
    claim_type: ClaimType
    attributed_to: str | None = Field(default=None, min_length=1, max_length=200)
    sentences: list[EvidenceSentence] = Field(min_length=1)

    @model_validator(mode="after")
    def validate_contract(self) -> "EvidenceClaim":
        ids = [sentence.id for sentence in self.sentences]
        if len(ids) != len(set(ids)):
            raise ValueError("sentence id는 요청 안에서 유일해야 합니다.")
        if self.claim_type == "OPINION":
            if self.attributed_to is None:
                raise ValueError("OPINION은 attributedTo가 필요합니다.")
        elif self.attributed_to is not None:
            raise ValueError("OPINION이 아니면 attributedTo는 null이어야 합니다.")
        return self


class EvidenceVerifyRequest(AgentModel):
    idempotency_key: str = Field(min_length=1, max_length=200)
    plan: Plan = "FREE"
    claims: list[EvidenceClaim] = Field(min_length=1, max_length=50)

    @model_validator(mode="after")
    def validate_unique_claim_ids(self) -> "EvidenceVerifyRequest":
        claim_ids = [claim.claim_id for claim in self.claims]
        if len(claim_ids) != len(set(claim_ids)):
            raise ValueError("claimId는 요청 안에서 유일해야 합니다.")
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


class EvidenceResult(EvidenceOutput):
    claim_id: str = Field(min_length=1, max_length=200)


class EvidenceBatchOutput(AgentModel):
    results: list[EvidenceResult] = Field(min_length=1, max_length=50)


class EvidenceVerifyResponse(AgentModel):
    results: list[EvidenceResult] = Field(min_length=1, max_length=50)
    meta: ResponseMeta
