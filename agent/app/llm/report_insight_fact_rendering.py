"""Source-owned quotations and source-grounded perspective prose.

A private structured field may select one complete original sentence. It cannot
splice an actor, number or status into another sentence. The server quotes that
sentence and labels the separate prose as interpretation (or an assumption).
If that optional quotation exceeds the display limit, only the validated
interpretation is displayed; its source handle and original facts are retained.
The quotation proves source identity, not the truth of a publisher's claim.
Rendering checks source handles and layout; the existing MAP/REDUCE validators
check every interpretation against its cited evidence, including quoted prose.
"""

from __future__ import annotations

import re
from collections.abc import Collection
from dataclasses import dataclass
from typing import Literal

from app.llm.report_insight_fact_index import build_fact_index
from app.llm.report_repair_details import source_quote_error_kind
from app.schemas.report_insight import ReportInsightRequest

_KIND_LABELS = {
    "interpretation": "해석",
    "assumption": "미확인 가정",
    "falsifier": "반증 조건",
    "observation": "관찰 항목",
}
ProseKind = Literal["interpretation", "assumption", "falsifier", "observation"]
_MARKER = re.compile(r"\{\{fact:(source-[0-9a-f]{24})\}\}")
_MAX_SLOT_LENGTH = 500

SOURCE_QUOTE_INSTRUCTIONS = (
    "자연어 필드에는 관점 해석·확인할 업무·미확인 조건을 짧게 작성하세요. "
    "선택한 근거 원문에 있는 회사명·제품명·수치·시점은 업무 대상이나 조건을 "
    "구체화하는 데 사용할 수 있습니다. 숫자가 들어간 제품명도 같은 원칙입니다. "
    "수치·시점은 원문의 주체·대상과 연결을 유지하고, 계획·전망을 완료 사실로 "
    "바꾸거나 근거에 없는 회사·제품·수치·시점·사건을 추가하지 마세요. "
    "미확인 조건은 확인된 사실로 단정하지 마세요. 내부 범주 코드는 자연어에 쓰지 마세요. "
    "원문 자체를 인용하려면 factTextSlots의 slotId를 같은 객체의 sourceQuotes에서 "
    "해당 자연어 필드 이름에 지정하세요. MAP의 condition은 decision.connection.condition을 "
    "뜻합니다. 인용이 필요하지 않으면 선택자는 null로 작성하세요. "
    "reason/condition 등 자연어에는 표식이나 원문 식별자를 쓰지 마세요. "
    "인용 선택 여부와 무관하게 자연어는 같은 근거와 대조합니다. "
    "선택지의 findingId·sentenceIndex로 제공된 원문 문장을 찾으세요. "
    "서버가 전체 원문 문장을 그대로 인용하고 해석/가정과 구분합니다. "
    "인용문은 서버가 별도로 조립하며 자연어 문장에 끼워 넣지 않습니다. "
    "선택한 slot의 claimIds 중 하나가 현재 항목의 basisClaimIds 또는 선택한 basis에 "
    "포함되어야 합니다. 미판정 MAP은 같은 finding의 claim만 사용합니다. "
    "원문 인용까지 합쳐 최종 필드 길이를 넘으면 서버가 표시용 인용만 생략합니다. "
    "원문과 근거 연결은 유지됩니다. 해석 자체는 필드 길이 안에서 작성하세요. "
    "원문 선택지는 원문 인용이며, 추출되지 않은 문장이 검증된 사실이라는 뜻은 아닙니다."
)
# Import compatibility for callers while the output contract transitions.
FACT_TEMPLATE_INSTRUCTIONS = SOURCE_QUOTE_INSTRUCTIONS


@dataclass(frozen=True, slots=True)
class FactTextSlot:
    slot_id: str
    finding_id: int
    claim_ids: tuple[str, ...]
    source_text: str
    sentence_index: int
    source_sha256: str
    fact_ids: tuple[str, ...]
    claim_types: tuple[str, ...]
    attributed_to: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class FactTextCatalog:
    slots: tuple[FactTextSlot, ...]


@dataclass(frozen=True, slots=True)
class RenderedFactText:
    text: str
    interpretation: str
    slot: FactTextSlot | None
    kind: ProseKind
    fact_quote_omitted_for_length: bool = False


class FactTemplateError(ValueError):
    def __init__(self, rule: str):
        self.rule = rule
        # No provider prose, source text or arbitrary ID enters error logs.
        super().__init__(rule)


def build_fact_text_catalog(
    request: ReportInsightRequest, claim_ids: Collection[str] | None = None
) -> FactTextCatalog:
    index = build_fact_index(request, claim_ids)
    fact_ids: dict[str, list[str]] = {}
    for fact in index.facts:
        fact_ids.setdefault(fact.evidence_id, []).append(fact.fact_id)
    return FactTextCatalog(
        tuple(
            FactTextSlot(
                slot_id=row.evidence_id,
                finding_id=row.finding_id,
                claim_ids=tuple(origin.claim_id for origin in row.claims),
                source_text=row.text,
                sentence_index=row.sentence_index,
                source_sha256=row.source_sha256,
                fact_ids=tuple(fact_ids.get(row.evidence_id, ())),
                claim_types=tuple(dict.fromkeys(origin.claim_type for origin in row.claims)),
                attributed_to=tuple(
                    dict.fromkeys(
                        origin.attributed_to for origin in row.claims if origin.attributed_to
                    )
                ),
            )
            for row in index.evidence
            if len(row.text) <= _MAX_SLOT_LENGTH
        )
    )


def fact_text_slots_payload(
    request: ReportInsightRequest,
    *,
    finding_id: int | None = None,
    claim_ids: Collection[str] | None = None,
    include_source_text: bool = False,
) -> list[dict]:
    return [
        {
            "slotId": slot.slot_id,
            "claimIds": list(slot.claim_ids),
            "findingId": slot.finding_id,
            "sentenceIndex": slot.sentence_index,
            **({"sourceText": slot.source_text} if include_source_text else {}),
            "renderedQuoteLength": len(_quote_prefix(slot, "interpretation")),
            "factIds": list(slot.fact_ids),
            "claimTypes": list(slot.claim_types),
            "attributedTo": list(slot.attributed_to),
            "sourceSha256": slot.source_sha256,
            "sourceStart": 0,
            "sourceEnd": len(slot.source_text),
        }
        for slot in build_fact_text_catalog(request, claim_ids).slots
        if finding_id is None or slot.finding_id == finding_id
    ]


def _quote_prefix(slot: FactTextSlot, kind: ProseKind) -> str:
    return f"원문: 「{slot.source_text}」 {_KIND_LABELS[kind]}: "


def render_fact_template(
    value: str,
    catalog: FactTextCatalog,
    permitted_claim_ids: Collection[str],
    *,
    max_length: int,
    kind: ProseKind = "interpretation",
) -> RenderedFactText:
    """Read a legacy stored template; new provider output uses structured refs."""
    value = value.strip()
    marker = _MARKER.match(value)
    source_id = None
    interpretation = value
    if marker is not None:
        if marker.end() < len(value) and not value[marker.end()].isspace():
            raise FactTemplateError("report_fact_slot_position")
        source_id = marker[1]
        interpretation = value[marker.end() :].strip()
    return render_source_prose(
        interpretation, source_id, catalog, permitted_claim_ids, max_length=max_length, kind=kind
    )


def render_source_prose(
    value: str,
    source_id: str | None,
    catalog: FactTextCatalog,
    permitted_claim_ids: Collection[str],
    *,
    max_length: int,
    kind: ProseKind = "interpretation",
) -> RenderedFactText:
    """Authenticate a separate source selection, then assemble public text.

    This function never interprets provider prose as a template. Source identity
    and citation scope are checked before optional display-length omission. The
    caller must still ground every returned interpretation against its evidence.
    """
    interpretation = value.strip()
    slot = None
    if source_id is not None:
        slot = next((item for item in catalog.slots if item.slot_id == source_id), None)
        if slot is None:
            raise FactTemplateError("report_fact_slot_unknown")
        if not set(slot.claim_ids).intersection(permitted_claim_ids):
            raise FactTemplateError("report_fact_slot_scope")
    if "{{" in interpretation or "}}" in interpretation:
        raise FactTemplateError("report_fact_slot_position")
    if not interpretation:
        raise FactTemplateError("report_fact_interpretation_required")
    if len(interpretation) > max_length:
        raise FactTemplateError("report_fact_rendered_length")
    rendered = (_quote_prefix(slot, kind) if slot else "") + interpretation
    omitted = slot is not None and len(rendered) > max_length
    # Source selection has already passed identity/scope validation and the
    # remaining prose fits the field limit. Omit the whole optional
    # display quotation, never cut a source clause or rewrite its factual values.
    # Keep metadata for callers/audits; callers must still ground the prose.
    return RenderedFactText(
        interpretation if omitted else rendered, interpretation, slot, kind, omitted
    )


def split_rendered_prose(
    value: str,
    catalog: FactTextCatalog,
    permitted_claim_ids: Collection[str],
    *,
    kind: ProseKind = "interpretation",
) -> RenderedFactText:
    """Recover server-quoted source only by exact source/claim-bound matching.

    Callers must authenticate the whole native projection before using this to
    distinguish facts from interpretation. A provider-written label alone never
    grants permission to skip a fact check.
    """
    for slot in catalog.slots:
        if not set(slot.claim_ids).intersection(permitted_claim_ids):
            continue
        prefix = _quote_prefix(slot, kind)
        if value.startswith(prefix):
            return RenderedFactText(value, value[len(prefix) :], slot, kind)
    return RenderedFactText(value, value, None, kind)


def render_reduce_templates(
    value: dict,
    request: ReportInsightRequest,
    allowed: dict[str, Collection[str]],
) -> tuple[dict, tuple]:
    """Read legacy stored REDUCE templates with their original claim scopes."""
    return _render_reduce_prose(value, request, allowed, structured=False)


def render_reduce_source_quotes(
    value: dict,
    request: ReportInsightRequest,
    allowed: dict[str, Collection[str]],
) -> tuple[dict, tuple]:
    """Project private REDUCE selections into the unchanged public schema.

    The caller still performs its usual shape checks, captures repair context
    from the original provider bytes, and validates the entire rendered result.
    Failed units are reported without discarding their neighboring good units.
    """
    return _render_reduce_prose(value, request, allowed, structured=True)


def _render_reduce_prose(value, request, allowed, *, structured):
    from copy import deepcopy

    from app.llm.report_validation_diagnostics import ReportValidationIssue

    result = deepcopy(value)
    catalog = build_fact_text_catalog(request)
    issues = []
    fields = {
        "overview": (("text", 600, "interpretation"), ("assumption", 500, "assumption")),
        "implications": (
            ("text", 700, "interpretation"),
            ("mechanism", 500, "interpretation"),
            ("assumption", 500, "assumption"),
            ("falsifiedBy", 500, "falsifier"),
        ),
        "watchItems": (
            ("topic", 200, "observation"),
            ("indicator", 400, "observation"),
            ("trigger", 400, "observation"),
        ),
    }

    def render(record, name, refs, audience, path, limit, kind):
        # Shape errors belong to the caller's existing complete shape validator.
        if not isinstance(record.get(name), str):
            return
        try:
            if structured:
                selections = record.get("sourceQuotes")
                if not isinstance(selections, dict) or name not in selections:
                    raise FactTemplateError("report_fact_source_quotes_required")
                record[name] = render_source_prose(
                    record[name], selections[name], catalog, refs, max_length=limit, kind=kind
                ).text
            else:
                record[name] = render_fact_template(
                    record[name], catalog, refs, max_length=limit, kind=kind
                ).text
        except FactTemplateError as error:
            issues.append(
                ReportValidationIssue(
                    audience,
                    path,
                    source_quote_error_kind(error.rule),
                    tuple(dict.fromkeys(refs)),
                    error.rule,
                )
            )

    for insight in result.get("insights", []):
        if not isinstance(insight, dict):
            continue
        audience = insight.get("audience")
        if audience not in allowed:
            continue
        permitted = set(allowed[audience])
        render(insight, "headline", allowed[audience], audience, "headline", 200, "interpretation")
        if structured:
            insight.pop("sourceQuotes", None)
        for group, properties in fields.items():
            for index, unit in enumerate(insight.get(group, [])):
                if not isinstance(unit, dict):
                    continue
                refs = unit.get("basisClaimIds")
                if not isinstance(refs, list) or any(not isinstance(ref, str) for ref in refs):
                    continue
                authorized = tuple(dict.fromkeys(ref for ref in refs if ref in permitted))
                for name, limit, kind in properties:
                    render(
                        unit, name, authorized, audience, f"{group}[{index}].{name}", limit, kind
                    )
                if structured:
                    unit.pop("sourceQuotes", None)
    return result, tuple(issues)
