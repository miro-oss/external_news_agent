"""Private source selections; public report fields remain plain strings."""

from typing import Annotated

from pydantic import ConfigDict, Field

from app.schemas.common import AgentModel
from app.schemas.report_insight import (
    ReportInsightImplication,
    ReportInsightOverview,
    ReportInsightReduceAudience,
    ReportInsightWatchItem,
)

SOURCE_QUOTE_ID_PATTERN = r"^source-[0-9a-f]{24}$"
SourceQuoteId = Annotated[str, Field(pattern=SOURCE_QUOTE_ID_PATTERN)] | None


class AssessmentSourceQuotes(AgentModel):
    model_config = ConfigDict(strict=True)
    reason: SourceQuoteId
    condition: SourceQuoteId


class HeadlineSourceQuotes(AgentModel):
    headline: SourceQuoteId


class OverviewSourceQuotes(AgentModel):
    text: SourceQuoteId
    assumption: SourceQuoteId


class ImplicationSourceQuotes(AgentModel):
    text: SourceQuoteId
    mechanism: SourceQuoteId
    assumption: SourceQuoteId
    falsified_by: SourceQuoteId


class WatchSourceQuotes(AgentModel):
    topic: SourceQuoteId
    indicator: SourceQuoteId
    trigger: SourceQuoteId


class ReportInsightStructuredOverview(ReportInsightOverview):
    source_quotes: OverviewSourceQuotes


class ReportInsightStructuredImplication(ReportInsightImplication):
    source_quotes: ImplicationSourceQuotes


class ReportInsightStructuredWatchItem(ReportInsightWatchItem):
    source_quotes: WatchSourceQuotes


class ReportInsightStructuredReduceAudience(ReportInsightReduceAudience):
    source_quotes: HeadlineSourceQuotes
    overview: list[ReportInsightStructuredOverview] = Field(max_length=3)
    implications: list[ReportInsightStructuredImplication] = Field(max_length=5)
    watch_items: list[ReportInsightStructuredWatchItem] = Field(max_length=5)


class ReportInsightStructuredReduceOutput(AgentModel):
    insights: list[ReportInsightStructuredReduceAudience] = Field(min_length=1, max_length=4)


def source_quotes_schema(fields: tuple[str, ...], *, enabled: bool = True) -> dict:
    """A closed, required selection object without repeating source-ID enums."""
    return {
        "type": "object",
        "properties": {
            field: (
                {
                    "anyOf": [
                        {"type": "string", "pattern": SOURCE_QUOTE_ID_PATTERN},
                        {"type": "null"},
                    ]
                }
                if enabled
                else {"type": "null"}
            )
            for field in fields
        },
        "required": list(fields),
        "additionalProperties": False,
    }
