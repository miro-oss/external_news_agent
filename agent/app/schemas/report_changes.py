import json
from typing import Annotated, Any, Literal

from pydantic import Field, model_validator

from app.schemas.analyze import Plan
from app.schemas.common import AgentModel
from app.schemas.report import ReportResponseMeta

PROMPT_VERSION = "report-changes.ko.v1"
MAX_REQUEST_CHARS = 100_000
ClaimId = Annotated[str, Field(min_length=1, max_length=200)]
ClaimText = Annotated[str, Field(min_length=1, max_length=600)]
ChangeType = Literal["UPDATED", "REFUTATION", "UNCHANGED", "UNDETERMINED"]


class ReportChangeClaim(AgentModel):
    id: ClaimId
    text: ClaimText
    evidence: list[ClaimText] = Field(min_length=1, max_length=3)


class ReportChangeCandidate(AgentModel):
    id: ClaimId
    relation: Literal["SAME_ISSUE", "MERGED", "REFUTES"]
    previous: list[ReportChangeClaim] = Field(min_length=1, max_length=6)
    current: list[ReportChangeClaim] = Field(min_length=1, max_length=6)

    @model_validator(mode="after")
    def unique_claim_ids(self) -> "ReportChangeCandidate":
        for claims in (self.previous, self.current):
            if len({claim.id for claim in claims}) != len(claims):
                raise ValueError("claim id는 각 비교 방향 안에서 유일해야 합니다.")
        return self


class ReportChangesRequest(AgentModel):
    idempotency_key: str = Field(min_length=1, max_length=200)
    plan: Plan
    report_id: int = Field(gt=0)
    base_report_id: int = Field(gt=0)
    candidates: list[ReportChangeCandidate] = Field(max_length=50)

    @model_validator(mode="before")
    @classmethod
    def bounded_request(cls, value: Any) -> Any:
        if isinstance(value, dict):
            serialized = json.dumps(value, ensure_ascii=False, default=str, separators=(",", ":"))
            if len(serialized) > MAX_REQUEST_CHARS:
                raise ValueError("비교 요청 전체는 100000자 이하여야 합니다.")
        return value

    @model_validator(mode="after")
    def unique_candidates(self) -> "ReportChangesRequest":
        if self.report_id == self.base_report_id:
            raise ValueError("이전 보고서와 현재 보고서는 서로 달라야 합니다.")
        if len({candidate.id for candidate in self.candidates}) != len(self.candidates):
            raise ValueError("candidate id는 요청 안에서 유일해야 합니다.")
        return self


class ReportChangeAssessment(AgentModel):
    candidate_id: ClaimId
    type: ChangeType
    summary: str = Field(min_length=1, max_length=1600)
    previous_claim_ids: list[ClaimId] = Field(max_length=6)
    current_claim_ids: list[ClaimId] = Field(max_length=6)


class ReportChangesOutput(AgentModel):
    items: list[ReportChangeAssessment] = Field(max_length=50)


class ReportChangesMeta(ReportResponseMeta):
    prompt_version: Literal["report-changes.ko.v1"] = PROMPT_VERSION


class ReportChangesResponse(ReportChangesOutput):
    meta: ReportChangesMeta
