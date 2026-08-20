from datetime import datetime
from typing import Annotated, Literal
from urllib.parse import urlsplit

from pydantic import Field, ValidationInfo, field_validator, model_validator

from app.schemas.analyze import Plan
from app.schemas.common import AgentModel

NonEmptyString = Annotated[str, Field(min_length=1)]
MAX_REPORT_FINDINGS = 50


class ReportRunInput(AgentModel):
    id: int = Field(gt=0)
    started_at: datetime
    finished_at: datetime
    topics: list[NonEmptyString]

    @model_validator(mode="after")
    def validate_time_range(self) -> "ReportRunInput":
        if self.finished_at < self.started_at:
            raise ValueError("finishedAt은 startedAt보다 빠를 수 없습니다.")
        return self


class ReportFindingInput(AgentModel):
    id: int = Field(gt=0)
    article_id: int = Field(gt=0)
    article_title: str = Field(min_length=1, max_length=1000)
    canonical_url: str = Field(min_length=1, max_length=2000)
    source_name: str | None = Field(default=None, max_length=200)
    change_type: Literal["NEW", "UPDATED"]
    summary_ko: str = Field(min_length=1)
    key_points: list[NonEmptyString]
    intent: str | None = Field(default=None, max_length=200)
    sentiment: Literal["positive", "neutral", "negative"]
    risk_level: Literal["low", "medium", "high"]
    relevance: Literal["important", "watch", "reference"]
    category: Literal["제품/공정", "기업", "정책", "공급망"]
    fetch_status: Literal[
        "METADATA_ONLY",
        "FULLTEXT",
        "FULLTEXT_BLOCKED",
        "ROBOTS_DISALLOWED",
        "FETCH_FAILED",
    ]

    @field_validator("canonical_url")
    @classmethod
    def validate_canonical_url(cls, value: str) -> str:
        if any(character.isspace() or character in "<>" for character in value):
            raise ValueError("canonicalUrl에는 공백이나 꺾쇠괄호를 사용할 수 없습니다.")
        parsed = urlsplit(value)
        if parsed.scheme.lower() not in {"http", "https"} or not parsed.hostname:
            raise ValueError("canonicalUrl은 hostname이 있는 HTTP(S) URL이어야 합니다.")
        return value


class ReportEventInput(AgentModel):
    id: str = Field(min_length=1, max_length=200)
    title: str = Field(min_length=1, max_length=500)
    summary_ko: str = Field(min_length=1)
    finding_ids: list[Annotated[int, Field(gt=0)]] = Field(min_length=1)


class SourceStats(AgentModel):
    collected: int = Field(ge=0)
    blocked: int = Field(ge=0)
    failed: int = Field(ge=0)
    paywalled: int = Field(default=0, ge=0)
    stub_excluded: int = Field(default=0, ge=0)

    @model_validator(mode="after")
    def validate_blocked_breakdown(self) -> "SourceStats":
        if self.paywalled > self.blocked:
            raise ValueError("paywalled는 blocked보다 클 수 없습니다.")
        return self


class ReportRequest(AgentModel):
    idempotency_key: str = Field(min_length=1, max_length=200)
    plan: Plan
    run: ReportRunInput
    findings: list[ReportFindingInput] = Field(max_length=MAX_REPORT_FINDINGS)
    events: list[ReportEventInput] = Field(default_factory=list)
    source_stats: SourceStats
    source_notes: list[NonEmptyString] = Field(min_length=1)
    perspective: Literal["TECHNOLOGY", "COMPANY", "POLICY", "SUPPLY_CHAIN"] = (
        "TECHNOLOGY"
    )

    @model_validator(mode="after")
    def validate_event_finding_ids(self) -> "ReportRequest":
        finding_id_list = [finding.id for finding in self.findings]
        finding_ids = set(finding_id_list)
        if len(finding_id_list) != len(finding_ids):
            raise ValueError("finding id는 요청 안에서 유일해야 합니다.")
        if any(
            finding_id not in finding_ids
            for event in self.events
            for finding_id in event.finding_ids
        ):
            raise ValueError("event findingIds는 요청 findings에 존재해야 합니다.")
        return self


class ImportantEvent(AgentModel):
    title: str = Field(min_length=1, max_length=500)
    summary_ko: str = Field(min_length=1)
    significance: str = Field(min_length=1)
    source_finding_ids: list[Annotated[int, Field(gt=0)]] = Field(min_length=1)


class WatchItem(AgentModel):
    topic: str = Field(min_length=1, max_length=500)
    reason: str = Field(min_length=1)
    source_finding_ids: list[Annotated[int, Field(gt=0)]] = Field(min_length=1)


class ReportOutput(AgentModel):
    title: str = Field(min_length=1, max_length=500)
    executive_summary: list[NonEmptyString] = Field(min_length=1)
    important_events: list[ImportantEvent]
    watch_items: list[WatchItem]
    source_notes: list[NonEmptyString]

    @model_validator(mode="after")
    def validate_source_finding_ids(self, info: ValidationInfo) -> "ReportOutput":
        allowed = (info.context or {}).get("allowed_finding_ids")
        if allowed is None:
            return self
        if any(
            finding_id not in allowed
            for item in [*self.important_events, *self.watch_items]
            for finding_id in item.source_finding_ids
        ):
            raise ValueError("sourceFindingIds는 요청 findings에 존재해야 합니다.")
        return self


class ReportResponseMeta(AgentModel):
    provider: Literal["gemini", "mindlogic-claude", "mock"]
    model: str = Field(min_length=1)
    prompt_version: str = Field(min_length=1, max_length=50)
    input_tokens: int = Field(ge=0)
    output_tokens: int = Field(ge=0)
    cost_usd: float = Field(ge=0)
    credits: float = Field(ge=0)
    mock: bool
    truncated: bool = False


class ReportResponse(AgentModel):
    title: str = Field(min_length=1, max_length=500)
    executive_summary: list[NonEmptyString] = Field(min_length=1)
    important_events: list[ImportantEvent]
    watch_items: list[WatchItem]
    source_notes: list[NonEmptyString]
    markdown_body: str = Field(min_length=1)
    meta: ReportResponseMeta
