"""Report-wide interpretations reference stored claims; they never generate facts."""

from datetime import date
from typing import Annotated, Literal

from pydantic import ConfigDict, Field, StrictInt, model_validator

from app.schemas.analyze import Audience, ClaimType, Plan
from app.schemas.common import AgentModel
from app.schemas.report import MAX_REPORT_FINDINGS, ReportResponseMeta

ClaimId = Annotated[str, Field(min_length=1, max_length=50)]
Score = Annotated[StrictInt, Field(ge=0, le=3)] | None
CLAIMLESS_ASSESSMENT_REASON = "검증을 통과한 claim 근거가 없어 중요도 판단을 보류합니다."


class ReportInsightTarget(AgentModel):
    id: int = Field(gt=0)
    title: str = Field(min_length=1, max_length=1000)
    report_scope: Literal["RUN", "DAILY", "WEEKLY"]
    report_date: date | None = None
    report_end_date: date | None = None

    @model_validator(mode="after")
    def validate_range(self) -> "ReportInsightTarget":
        if (
            self.report_date is not None
            and self.report_end_date is not None
            and self.report_end_date < self.report_date
        ):
            raise ValueError("reportEndDate는 reportDate보다 빠를 수 없습니다.")
        return self


class ReportInsightSentence(AgentModel):
    index: int = Field(ge=0)
    text: str = Field(min_length=1)


class ReportInsightClaim(AgentModel):
    # Keep stored claim wording exactly as supplied, including original spacing.
    model_config = ConfigDict(str_strip_whitespace=False)
    id: ClaimId
    text: str = Field(min_length=1)
    claim_type: ClaimType
    attributed_to: str | None = Field(default=None, min_length=1, max_length=200)
    evidence_sentence_ids: list[Annotated[int, Field(ge=0)]] = Field(min_length=1)

    @model_validator(mode="after")
    def validate_claim(self) -> "ReportInsightClaim":
        if not self.text.strip():
            raise ValueError("claim text는 공백만 사용할 수 없습니다.")
        if len(self.evidence_sentence_ids) != len(set(self.evidence_sentence_ids)):
            raise ValueError("evidenceSentenceIds는 중복될 수 없습니다.")
        if self.claim_type == "OPINION" and not self.attributed_to:
            raise ValueError("OPINION은 attributedTo가 필요합니다.")
        if self.claim_type != "OPINION" and self.attributed_to is not None:
            raise ValueError("FACT/FORECAST의 attributedTo는 null이어야 합니다.")
        return self


class ReportInsightFinding(AgentModel):
    id: int = Field(gt=0)
    article_id: int = Field(gt=0)
    article_title: str = Field(min_length=1, max_length=1000)
    canonical_url: str = Field(min_length=1, max_length=2000)
    published_at: date | None = None
    topic_name: str = Field(min_length=1, max_length=500)
    claims: list[ReportInsightClaim] = Field(min_length=1)
    sentences: list[ReportInsightSentence] = Field(min_length=1)

    @model_validator(mode="after")
    def validate_claim_sources(self) -> "ReportInsightFinding":
        indices = [sentence.index for sentence in self.sentences]
        if len(indices) != len(set(indices)):
            raise ValueError("sentence index는 finding 안에서 유일해야 합니다.")
        claim_ids = [claim.id for claim in self.claims]
        if len(claim_ids) != len(set(claim_ids)):
            raise ValueError("claim id는 finding 안에서 유일해야 합니다.")
        for claim in self.claims:
            prefix, separator, point_index = claim.id.partition(":")
            if (
                prefix != str(self.id)
                or separator != ":"
                or not point_index.isascii()
                or not point_index.isdecimal()
                or str(int(point_index)) != point_index
            ):
                raise ValueError("claim id는 findingId:0-based-pointIndex 형식이어야 합니다.")
            if not set(claim.evidence_sentence_ids) <= set(indices):
                raise ValueError("claim evidenceSentenceIds는 같은 finding에 존재해야 합니다.")
        return self


class ReportInsightRequest(AgentModel):
    idempotency_key: str = Field(min_length=1, max_length=200)
    plan: Plan
    audiences: list[Audience] = Field(min_length=1, max_length=4)
    report: ReportInsightTarget
    findings: list[ReportInsightFinding] = Field(min_length=1, max_length=MAX_REPORT_FINDINGS)

    @model_validator(mode="after")
    def validate_unique_values(self) -> "ReportInsightRequest":
        if len(self.audiences) != len(set(self.audiences)):
            raise ValueError("audiences는 중복될 수 없습니다.")
        ids = [finding.id for finding in self.findings]
        if len(ids) != len(set(ids)):
            raise ValueError("finding id는 요청 안에서 유일해야 합니다.")
        return self


class ReportImportanceAxes(AgentModel):
    directness: Score
    impact: Score
    urgency: Score
    # No baseline is provided by this contract. There is no novelty measurement.
    novelty: None


class ReportInsightAssessment(AgentModel):
    finding_id: int = Field(gt=0)
    reason: str = Field(min_length=1, max_length=500)
    basis_claim_ids: list[ClaimId]
    axes: ReportImportanceAxes

    @model_validator(mode="after")
    def validate_assessment_basis(self) -> "ReportInsightAssessment":
        if len(self.basis_claim_ids) != len(set(self.basis_claim_ids)):
            raise ValueError("basisClaimIds는 중복될 수 없습니다.")
        if (
            any(
                score is not None
                for score in (self.axes.directness, self.axes.impact, self.axes.urgency)
            )
            and not self.basis_claim_ids
        ):
            raise ValueError("판정 가능한 중요도 축에는 basisClaimIds가 필요합니다.")
        return self


class ReportInsightOverview(AgentModel):
    text: str = Field(min_length=1, max_length=600)
    basis_claim_ids: list[ClaimId] = Field(min_length=1)
    assumption: str = Field(min_length=1, max_length=500)


class ReportInsightImplication(AgentModel):
    text: str = Field(min_length=1, max_length=700)
    mechanism: str = Field(min_length=1, max_length=500)
    basis_claim_ids: list[ClaimId] = Field(min_length=1)
    assumption: str = Field(min_length=1, max_length=500)
    falsified_by: str = Field(min_length=1, max_length=500)


class ReportInsightWatchItem(AgentModel):
    topic: str = Field(min_length=1, max_length=200)
    indicator: str = Field(min_length=1, max_length=400)
    trigger: str = Field(min_length=1, max_length=400)
    basis_claim_ids: list[ClaimId] = Field(min_length=1)


class ReportAudienceInsight(AgentModel):
    audience: Audience
    headline: str = Field(min_length=1, max_length=200)
    overview: list[ReportInsightOverview] = Field(max_length=3)
    assessments: list[ReportInsightAssessment] = Field(min_length=1, max_length=MAX_REPORT_FINDINGS)
    implications: list[ReportInsightImplication] = Field(max_length=5)
    watch_items: list[ReportInsightWatchItem] = Field(max_length=5)


class ReportInsightOutput(AgentModel):
    insights: list[ReportAudienceInsight] = Field(min_length=1, max_length=4)


class ReportInsightMapAudience(AgentModel):
    audience: Audience
    assessments: list[ReportInsightAssessment] = Field(min_length=1, max_length=MAX_REPORT_FINDINGS)


class ReportInsightMapOutput(AgentModel):
    insights: list[ReportInsightMapAudience] = Field(min_length=1, max_length=4)


class ReportInsightReduceAudience(AgentModel):
    audience: Audience
    headline: str = Field(min_length=1, max_length=200)
    overview: list[ReportInsightOverview] = Field(max_length=3)
    implications: list[ReportInsightImplication] = Field(max_length=5)
    watch_items: list[ReportInsightWatchItem] = Field(max_length=5)


class ReportInsightReduceOutput(AgentModel):
    insights: list[ReportInsightReduceAudience] = Field(min_length=1, max_length=4)


class ReportInsightResponse(ReportInsightOutput):
    meta: ReportResponseMeta
