from datetime import date, timedelta
from typing import Annotated

from pydantic import Field, model_validator

from app.schemas.analyze import Plan
from app.schemas.common import AgentModel
from app.schemas.report import NonEmptyString

PositiveId = Annotated[int, Field(gt=0)]


class DailyEvent(AgentModel):
    title: NonEmptyString
    summary_ko: NonEmptyString
    significance: str | None
    source_finding_ids: list[PositiveId] = Field(min_length=1)


class DailyWatchItem(AgentModel):
    topic: NonEmptyString
    reason: NonEmptyString
    source_finding_ids: list[PositiveId] = Field(min_length=1)


class DailyContent(AgentModel):
    executive_summary: list[NonEmptyString]
    important_events: list[DailyEvent]
    watch_items: list[DailyWatchItem]
    source_notes: list[NonEmptyString]


class DailyReportSnapshot(AgentModel):
    report_id: PositiveId
    report_date: date
    title: NonEmptyString
    structured_content: DailyContent | None
    reflected_finding_ids: list[PositiveId]
    issue_ids_by_finding: dict[PositiveId, PositiveId] = Field(default_factory=dict)

    @model_validator(mode="after")
    def validate_references(self) -> "DailyReportSnapshot":
        allowed = set(self.reflected_finding_ids)
        if not self.issue_ids_by_finding.keys() <= allowed:
            raise ValueError("이슈 식별자의 근거는 해당 일일 보고서에 존재해야 합니다.")
        if self.structured_content is not None:
            for item in [
                *self.structured_content.important_events,
                *self.structured_content.watch_items,
            ]:
                if not set(item.source_finding_ids) <= allowed:
                    raise ValueError(
                        "일일 보고서 근거는 저장된 reflectedFindingIds에 존재해야 합니다."
                    )
        return self


class WeeklyReportRequest(AgentModel):
    idempotency_key: str = Field(min_length=1, max_length=200)
    plan: Plan
    report_id: PositiveId
    report_date: date
    report_end_date: date
    sources: list[DailyReportSnapshot] = Field(max_length=7)
    missing_report_dates: list[date] = Field(max_length=7)
    source_notes: list[NonEmptyString] = Field(min_length=1)

    @model_validator(mode="after")
    def validate_week(self) -> "WeeklyReportRequest":
        if self.report_date.weekday() != 0 or self.report_end_date != self.report_date + timedelta(
            6
        ):
            raise ValueError("주간 보고서는 월요일부터 일요일까지 7일이어야 합니다.")
        dates = [source.report_date for source in self.sources]
        ids = [source.report_id for source in self.sources]
        expected = {self.report_date + timedelta(offset) for offset in range(7)}
        missing = set(self.missing_report_dates)
        if (
            len(dates) != len(set(dates))
            or len(ids) != len(set(ids))
            or len(missing) != len(self.missing_report_dates)
            or set(dates) & missing
            or set(dates) | missing != expected
        ):
            raise ValueError("일일 보고서와 누락 날짜는 해당 주의 7일을 중복 없이 포함해야 합니다.")
        self.sources.sort(key=lambda source: source.report_date)
        return self
