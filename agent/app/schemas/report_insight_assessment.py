"""Internal evidence-first assessments; public report insight shapes stay unchanged."""

from typing import Literal

from pydantic import ConfigDict, Field, StrictInt

from app.schemas.analyze import Audience
from app.schemas.common import AgentModel
from app.schemas.report_insight import ClaimId
from app.schemas.report_insight_source_quotes import AssessmentSourceQuotes

Relation = Literal["DIRECT", "CONDITIONAL", "BACKGROUND", "UNRELATED", "UNDETERMINED"]
ImpactScope = Literal[
    "CORE_CONSTRAINT", "PROJECT_CHANGE", "LIMITED_PREPARATION", "NO_CHANGE", "UNDETERMINED"
]
UrgencyState = Literal[
    "IMMEDIATE", "SCHEDULED_PREPARATION", "MONITOR", "NOT_URGENT", "UNDETERMINED"
]
Work = Literal[
    "PROCESS_QUALIFICATION",
    "PRODUCTION_SCHEDULE",
    "YIELD_CAPACITY",
    "CUSTOMER_REQUIREMENTS",
    "MATERIAL_SUPPLY",
    "PROCESS_VALIDATION",
    "DESIGN_IN",
    "ORDER_BOOKING",
    "DELIVERY_INSTALLATION",
    "MAINTENANCE_SERVICE",
    "GUIDANCE",
    "CAPEX_EXECUTION",
    "REVENUE_RECOGNITION",
    "PROFITABILITY",
    "SUPPLY_DEMAND_CONSTRAINT",
    "SYSTEM_PROCUREMENT",
    "COMPATIBILITY",
    "POWER_COOLING",
    "NETWORK",
    "DEPLOYMENT_OPERATIONS",
]


class ReportAssessmentSourceQuote(AgentModel):
    # Literal quotations must survive parsing unchanged, including source spacing.
    model_config = ConfigDict(str_strip_whitespace=False, strict=True)
    claim_id: ClaimId
    quote: str = Field(min_length=1, max_length=200)


class ReportFindingAssessmentDraft(AgentModel):
    model_config = ConfigDict(strict=True)
    finding_id: StrictInt = Field(gt=0)
    work: Work | None
    relation: Relation
    relation_basis: ReportAssessmentSourceQuote | None
    condition: str | None = Field(min_length=1, max_length=120)
    impact_scope: ImpactScope
    impact_basis: ReportAssessmentSourceQuote | None
    urgency_state: UrgencyState
    urgency_basis: ReportAssessmentSourceQuote | None
    reason: str = Field(min_length=1, max_length=180)
    # Internal-only provenance travels with authenticated repair snapshots.
    source_quotes: AssessmentSourceQuotes | None = Field(
        default=None, exclude_if=lambda value: value is None
    )


class ReportAssessmentDraft(AgentModel):
    model_config = ConfigDict(strict=True)
    assessments: dict[Audience, dict[str, ReportFindingAssessmentDraft]]


class ReportAssessmentSourceSpan(AgentModel):
    model_config = ConfigDict(str_strip_whitespace=False, strict=True)
    claim_id: ClaimId
    source_span_id: str = Field(pattern=r"^s[1-9][0-9]*_(?:0|[1-9][0-9]*)_(?:0|[1-9][0-9]*)$")


class ReportAssessmentConnection(AgentModel):
    model_config = ConfigDict(strict=True)
    relation: Relation
    work: Work | None
    condition: str | None = Field(min_length=1, max_length=120)
    basis: ReportAssessmentSourceSpan | None


class ReportAssessmentEffect(AgentModel):
    model_config = ConfigDict(strict=True)
    impact_scope: ImpactScope
    basis: ReportAssessmentSourceSpan | None


class ReportAssessmentTiming(AgentModel):
    model_config = ConfigDict(strict=True)
    urgency_state: UrgencyState
    basis: ReportAssessmentSourceSpan | None


class ReportAssessmentDecision(AgentModel):
    model_config = ConfigDict(strict=True)
    connection: ReportAssessmentConnection
    effect: ReportAssessmentEffect
    timing: ReportAssessmentTiming


class ReportFindingAssessmentWire(AgentModel):
    model_config = ConfigDict(strict=True)
    finding_id: StrictInt = Field(gt=0)
    decision: ReportAssessmentDecision
    reason: str = Field(min_length=1, max_length=180)
    # Missing metadata is accepted only by the legacy stored-draft reader.
    source_quotes: AssessmentSourceQuotes | None = None

    def flattened(self, source_spans: dict[str, dict[str, str]]) -> ReportFindingAssessmentDraft:
        def literal(basis: ReportAssessmentSourceSpan | None, field: str):
            if basis is None:
                return None
            quote = source_spans.get(basis.claim_id, {}).get(basis.source_span_id)
            if quote is None:
                raise ValueError(
                    f"{field}.sourceSpanId는 같은 finding/claim의 원문 선택지여야 합니다."
                )
            return ReportAssessmentSourceQuote(claim_id=basis.claim_id, quote=quote)

        return ReportFindingAssessmentDraft(
            finding_id=self.finding_id,
            work=self.decision.connection.work,
            relation=self.decision.connection.relation,
            relation_basis=literal(self.decision.connection.basis, "connection.basis"),
            condition=self.decision.connection.condition,
            impact_scope=self.decision.effect.impact_scope,
            impact_basis=literal(self.decision.effect.basis, "effect.basis"),
            urgency_state=self.decision.timing.urgency_state,
            urgency_basis=literal(self.decision.timing.basis, "timing.basis"),
            reason=self.reason,
            source_quotes=(
                self.source_quotes
                if self.source_quotes is not None
                and (
                    self.source_quotes.reason is not None
                    or self.source_quotes.condition is not None
                )
                else None
            ),
        )


class ReportAssessmentWireDraft(AgentModel):
    model_config = ConfigDict(strict=True)
    assessments: dict[Audience, dict[str, ReportFindingAssessmentWire]]


class ReportFindingAssessmentStructuredWire(ReportFindingAssessmentWire):
    source_quotes: AssessmentSourceQuotes


class ReportAssessmentStructuredWireDraft(ReportAssessmentWireDraft):
    assessments: dict[Audience, dict[str, ReportFindingAssessmentStructuredWire]]
