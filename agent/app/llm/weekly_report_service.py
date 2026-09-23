"""Synthesize frozen daily reports, keeping their dated claims and source identities."""

import logging
import re
import unicodedata
from dataclasses import dataclass
from datetime import date
from pathlib import Path

from app.core.config import Settings
from app.core.parser import parse_json_object
from app.llm.base import AnalyzeProvider, ProviderResponse
from app.llm.prompt_data import prompt_json
from app.llm.request_contract import _integer_choices
from app.llm.router import get_analyze_provider
from app.llm.structured_call import structured_call
from app.schemas.report import (
    ImportantEvent,
    ReportOutput,
    ReportResponse,
    ReportResponseMeta,
    WatchItem,
)
from app.schemas.weekly_report import WeeklyReportRequest

PROMPT_VERSION = "weekly-report.ko.v1"
SYSTEM_INSTRUCTION = (
    (Path(__file__).resolve().parents[1] / "prompts" / f"{PROMPT_VERSION}.md")
    .read_text(encoding="utf-8")
    .strip()
)
logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class SavedClaim:
    date: date
    title: str
    text: str
    ids: tuple[int, ...]
    watch: bool = False
    issue_ids: tuple[int, ...] = ()


class WeeklyReportWriterService:
    def __init__(self, settings: Settings, provider: AnalyzeProvider | None = None) -> None:
        self._settings = settings
        self._provider = provider

    def write(self, request: WeeklyReportRequest) -> ReportResponse:
        claims = _claims(request)
        if self._settings.mock or not claims:
            output = _deterministic(claims)
            meta = ReportResponseMeta(
                provider="mock",
                model="deterministic-weekly-report",
                prompt_version=PROMPT_VERSION,
                input_tokens=0,
                output_tokens=0,
                cost_usd=0,
                credits=0,
                mock=True,
            )
        else:
            settings = self._settings.model_copy(
                update={
                    "max_output_tokens": self._settings.report_max_output_tokens,
                    "provider_timeout_seconds": self._settings.report_provider_timeout_seconds,
                }
            )
            provider = self._provider or get_analyze_provider(settings, request.plan)
            schema = ReportOutput.model_json_schema(by_alias=True)
            ids = sorted({id_ for claim in claims for id_ in claim.ids})
            for name in ("ImportantEvent", "WatchItem"):
                schema["$defs"][name]["properties"]["sourceFindingIds"]["items"] = _integer_choices(
                    ids
                )
            schema["properties"]["importantEvents"]["maxItems"] = 5
            result = structured_call(
                provider,
                system_instruction=SYSTEM_INSTRUCTION,
                prompt=(
                    "아래 일일 통합 스냅샷만 사용하세요.\n<weekly-report-input>\n"
                    + prompt_json(
                        request.model_dump(
                            by_alias=True,
                            mode="json",
                            exclude={
                                "idempotency_key",
                                "plan",
                            },
                        )
                    )
                    + "\n</weekly-report-input>"
                ),
                response_schema=schema,
                validate=lambda response: _validated_output(response, ids),
                repair_attempts=settings.schema_repair_attempts,
                task_name="주간 통합 보고서",
                input_tag="weekly-report",
                schema_violation_message="주간 보고서 출력 계약 위반입니다.",
                failure_prompt_version=PROMPT_VERSION,
                logger=logger,
            )
            output = result.output
            meta = ReportResponseMeta(
                provider=result.response.provider,
                model=result.response.model,
                prompt_version=PROMPT_VERSION,
                input_tokens=result.usage.input_tokens,
                output_tokens=result.usage.output_tokens,
                cost_usd=float(result.usage.cost_usd),
                credits=float(result.usage.credits),
                mock=False,
                truncated=result.response.truncated,
            )
        output = _verified(output, claims)
        title = f"{request.report_date} ~ {request.report_end_date} 주간 통합 뉴스 보고서"
        notes = list(dict.fromkeys(request.source_notes))
        return ReportResponse(
            title=title,
            executive_summary=output.executive_summary,
            important_events=output.important_events,
            watch_items=output.watch_items,
            source_notes=notes,
            markdown_body=_markdown(title, output, notes),
            meta=meta,
        )


def _claims(request: WeeklyReportRequest) -> list[SavedClaim]:
    claims = []
    for source in request.sources:
        if source.structured_content is None:
            continue
        for event in source.structured_content.important_events:
            claims.append(
                SavedClaim(
                    source.report_date,
                    event.title,
                    event.summary_ko,
                    tuple(dict.fromkeys(event.source_finding_ids)),
                    issue_ids=tuple(
                        dict.fromkeys(
                            source.issue_ids_by_finding[id_]
                            for id_ in event.source_finding_ids
                            if id_ in source.issue_ids_by_finding
                        )
                    ),
                )
            )
        for item in source.structured_content.watch_items:
            claims.append(
                SavedClaim(
                    source.report_date,
                    item.topic,
                    item.reason,
                    tuple(dict.fromkeys(item.source_finding_ids)),
                    True,
                    tuple(
                        dict.fromkeys(
                            source.issue_ids_by_finding[id_]
                            for id_ in item.source_finding_ids
                            if id_ in source.issue_ids_by_finding
                        )
                    ),
                )
            )
    return claims


def _validated_output(response: ProviderResponse, ids: list[int]) -> ReportOutput:
    output = ReportOutput.model_validate(
        parse_json_object(response.text),
        context={"allowed_finding_ids": set(ids)},
    )
    if len(output.important_events) > 5:
        raise ValueError("주요 이슈는 최대 5개입니다.")
    return output


def _deterministic(claims: list[SavedClaim]) -> ReportOutput:
    events = [claim for claim in claims if not claim.watch]
    return ReportOutput(
        title="주간 통합 보고서",
        executive_summary=[_summary(claim.text, 100, claim.title) for claim in events[:3]]
        or ["해당 주에 저장된 일일 보고서의 확인 가능한 근거가 없습니다."],
        important_events=[
            ImportantEvent(
                title=claim.title[:500],
                summary_ko=_summary(claim.text, 150),
                significance=claim.text,
                source_finding_ids=list(claim.ids),
            )
            for claim in events
        ],
        watch_items=[
            WatchItem(
                topic=claim.title[:500], reason=claim.text, source_finding_ids=list(claim.ids)
            )
            for claim in claims
            if claim.watch
        ],
        source_notes=[],
    )


def _normalized(text: str) -> str:
    return " ".join(unicodedata.normalize("NFKC", text).casefold().split())


def _summary(text: str, limit: int, fallback: str | None = None) -> str:
    # Never cut away an attribution, negation or forecast qualifier to fit a display limit.
    if len(text) <= limit:
        return text
    if fallback and len(fallback) <= limit:
        return fallback
    return "날짜별 일일 보고서의 확인 내용은 아래 주간 흐름을 참고하세요."


def _supported(text: str, claims: list[SavedClaim], *, title: bool = False) -> bool:
    # Daily assertions have already passed grounding. Keep their wording: token overlap
    # cannot prove who supplied whom, or preserve attribution and forecast qualifiers.
    return any(
        _normalized(text) == _normalized(evidence)
        for claim in claims
        for evidence in ([claim.title, claim.text] if title else [claim.text])
    )


def _same_issue(left: SavedClaim, right: SavedClaim) -> bool:
    if left.issue_ids and right.issue_ids:
        return set(left.issue_ids) == set(right.issue_ids)
    if set(left.ids) & set(right.ids):
        return True
    # Matching generic wording cannot override two different saved issue identities.
    if left.issue_ids or right.issue_ids:
        return False
    return _normalized(left.title) == _normalized(right.title) and _normalized(
        left.text
    ) == _normalized(right.text)


def _claim_groups(claims: list[SavedClaim]) -> list[list[SavedClaim]]:
    groups: list[list[SavedClaim]] = []
    for claim in claims:
        # Prefer immutable identity links. Ambiguous legacy wording must never bridge
        # two independently known issues, even via a third claim without issue IDs.
        connected = [
            group
            for group in groups
            if not (
                claim.issue_ids
                and (known := {id_ for old in group for id_ in old.issue_ids})
                and set(claim.issue_ids) != known
            )
            if any(
                set(claim.ids) & set(old.ids) or set(claim.issue_ids) & set(old.issue_ids)
                for old in group
            )
        ]
        if not connected:
            connected = [
                group
                for group in groups
                if any(_same_issue(claim, old) for old in group)
                and not (
                    claim.issue_ids
                    and {id_ for old in group for id_ in old.issue_ids}
                    and not set(claim.issue_ids) & {id_ for old in group for id_ in old.issue_ids}
                )
            ]
            if len(connected) > 1:
                connected = []
        # Also guard unidentified evidence shared by multiple known histories.
        identities = {
            frozenset(id_ for old in group for id_ in old.issue_ids)
            for group in connected
        } - {frozenset()}
        if len(identities) > 1:
            connected = []
        if not connected:
            groups.append([claim])
            continue
        merged = [claim]
        for group in connected:
            groups.remove(group)
            merged.extend(group)
        merged.sort(key=lambda item: item.date)
        groups.append(list(dict.fromkeys(merged)))
    return sorted(groups, key=lambda group: group[0].date)


def _select_group(ids: list[int], groups: list[list[SavedClaim]]) -> list[SavedClaim] | None:
    selected = set(ids)
    return next(
        (
            group
            for group in groups
            if selected <= {id_ for claim in group for id_ in claim.ids}
            and any(set(claim.ids) <= selected for claim in group)
        ),
        None,
    )


def _verified(output: ReportOutput, claims: list[SavedClaim]) -> ReportOutput:
    available = _claim_groups([claim for claim in claims if not claim.watch])
    events: list[ImportantEvent] = []
    groups: list[list[SavedClaim]] = []
    for event in output.important_events:
        supporting = _select_group(event.source_finding_ids, available)
        if supporting is None or supporting in groups:
            continue
        groups.append(supporting)
        events.append(event)
    if not events and available:
        return _verified(_deterministic(claims), claims)
    verified_events = []
    for event, supporting in list(zip(events, groups, strict=True))[:5]:
        supporting.sort(key=lambda claim: claim.date)
        # The original daily wording/date remains visible even when a generated paraphrase fails.
        summary = (
            event.summary_ko if _supported(event.summary_ko, supporting) else supporting[-1].text
        )
        title = (
            event.title if _supported(event.title, supporting, title=True) else supporting[-1].title
        )
        chronology = list(
            dict.fromkeys(f"{claim.date} 일일 보고서: {claim.text}" for claim in supporting)
        )
        verified_events.append(
            ImportantEvent(
                title=title[:500],
                summary_ko=_summary(summary, 150),
                significance="\n".join(chronology),
                source_finding_ids=list(
                    dict.fromkeys(id_ for claim in supporting for id_ in claim.ids)
                ),
            )
        )
    watch_items = []
    seen = set()
    watch_groups = _claim_groups([claim for claim in claims if claim.watch])
    for item in output.watch_items:
        supporting = _select_group(item.source_finding_ids, watch_groups)
        if not supporting:
            continue
        reason = item.reason if _supported(item.reason, supporting) else supporting[-1].text
        identity = tuple(sorted({id_ for claim in supporting for id_ in claim.ids}))
        if identity in seen:
            continue
        seen.add(identity)
        watch_items.append(
            WatchItem(
                topic=item.topic
                if _supported(item.topic, supporting, title=True)
                else supporting[-1].title,
                reason=reason,
                source_finding_ids=list(
                    dict.fromkeys(id_ for claim in supporting for id_ in claim.ids)
                ),
            )
        )
    selected = [claim for group in groups[:5] for claim in group]
    summaries = list(
        dict.fromkeys(
            summary for summary in output.executive_summary if _supported(summary, selected)
        )
    )[:3]
    if not summaries:
        summaries = list(
            dict.fromkeys(_summary(event.summary_ko, 100, event.title) for event in verified_events)
        )[:3]
    return output.model_copy(
        update={
            "executive_summary": summaries
            or ["해당 주에 저장된 일일 보고서의 확인 가능한 근거가 없습니다."],
            "important_events": verified_events,
            "watch_items": watch_items,
        }
    )


def _text(value: str) -> str:
    return re.sub(r"([\\`*_{}\[\]<>()#+!|])", r"\\\1", " ".join(value.split()))


def _markdown(title: str, output: ReportOutput, notes: list[str]) -> str:
    lines = [f"# {_text(title)}", "", "## 이번 주 핵심", ""]
    lines.extend(f"- {_text(summary)}" for summary in output.executive_summary)
    lines += ["", "## 주요 이슈와 주간 흐름", ""]
    for event in output.important_events:
        lines += [f"### {_text(event.title)}", "", _text(event.summary_ko), ""]
        lines.extend(f"- {_text(step)}" for step in event.significance.splitlines())
        lines.append("")
    lines += ["## 후속 관찰", ""]
    lines.extend(f"- **{_text(item.topic)}**: {_text(item.reason)}" for item in output.watch_items)
    lines += ["", "## 자료 범위", ""]
    lines.extend(f"- {_text(note)}" for note in notes)
    return "\n".join(lines).strip() + "\n"
