"""Source-owned factual clauses and free-form, fact-free perspective prose.

A template may select one complete original sentence at its beginning. It cannot
splice an actor, number or status into another sentence. The server quotes that
sentence and labels the remaining text as interpretation (or an assumption).
If that optional quotation exceeds the display limit, only the validated
interpretation is displayed; its source handle and original facts are retained.
The quotation proves source identity, not the truth of a publisher's claim.
"""

from __future__ import annotations

import re
from collections.abc import Collection
from dataclasses import dataclass
from typing import Literal

from app.core.evidence import _companies
from app.llm.report_insight_fact_graph import analyze_sentence
from app.llm.report_insight_fact_index import build_fact_index
from app.schemas.report_insight import ReportInsightRequest

_KIND_LABELS = {
    "interpretation": "해석",
    "assumption": "미확인 가정",
    "falsifier": "반증 조건",
    "observation": "관찰 항목",
}
ProseKind = Literal["interpretation", "assumption", "falsifier", "observation"]
_MARKER = re.compile(r"\{\{fact:(source-[0-9a-f]{24})\}\}")
# Numbers, including product generations, dates and ranges, are source-owned.
_NUMBER = re.compile(r"\d|[零一二三四五六七八九十百千萬億兆]")
_WRITTEN_QUANTITY = re.compile(
    r"(?<![가-힣])(?:한|두|세|네|다섯|여섯|일곱|여덟|아홉|열|백|천|만|억|조)\s*"
    r"(?:개|명|대|곳|배|퍼센트|억원|만원|년|개월)(?=$|[\s,.]|[은는이가을를로에의])"
)
# An action noun is ordinary work vocabulary; an asserted occurrence is not.
_ASSERTED_EVENT = re.compile(
    r"(?:체결|완공|준공|양산|공급|출하|수주|가동|인수|투자|착공|증설|설치|개발|발표|공개|유출|매각)"
    r"(?:을|를)?\s*(?:(?:완료|확정|시작|개시)\s*)?"
    r"(?:했|됐|되었|하였|되|하)(?:다|고|으며|음|기|다는|다고|던|으므로|으니)?(?![가-힣])|"
    r"(?:계약|투자|공급|양산|가동|출하|수주|완공|준공|인수|매각)\s*(?:이|가|은|는)?\s*"
    r"(?:확정|완료|성사|시작|중단)(?:이다|됐다|되었다|됐고|됐으며)(?![가-힣])|"
    r"\b(?:has|have|had)\s+(?:signed|completed|acquired|invested|shipped|started)|"
    r"\b(?:signed|completed|acquired|invested|shipped|announced)\b",
    re.I,
)
_PLANNED_ASSERTION = re.compile(
    r"(?:할|될|을)\s*(?:계획|예정|목표)(?:이다|이었다|이라고|로)(?![가-힣])|"
    r"(?:예정|계획)(?:돼|되어|됐|되었)\s*(?:있다|다|고|으며)|"
    r"(?:계획|추진)(?:했|하였)(?:다|고|으며|음)(?![가-힣])"
)
_EVENT_NOUN = (
    r"(?:계약|체결|완공|준공|양산|공급|출하|수주|가동|인수|투자|착공|증설|설치|"
    r"개발|발표|공개|유출|매각|건설)"
)
_CONTINUING_EVENT = re.compile(
    _EVENT_NOUN
    + r"(?:을|를|이|가|은|는)?\s*"
    + r"(?:(?:추진|진행|체결|실행|시행|확대|완료|확정|개시|시작|중단|재개)\s*)?"
    + r"(?:중(?:이다|입니다|이며|이고)|"
    + r"(?:돼|되어|되고|하고)\s*있(?:다|습니다|으며|고)|"
    + r"(?:된다|합니다|한다|한다는|한다고))"
    + r"(?![가-힣])|"
    + _EVENT_NOUN
    + r"(?:을|를|이|가|은|는)?\s*(?:추진|진행|계획|검토)(?:한다|합니다|중이다)(?![가-힣])"
)
_WRITTEN_TIME = re.compile(
    r"상반기|하반기|지난달|다음\s*분기|다음\s*주|지난주|내주|내달|금주|금월|익월|월말|분기말"
)
_PAST_ASSERTION = re.compile(
    r"(?:늘었|줄었|증가했|감소했|상승했|하락했|기록했|달성했|돌파했|확대됐|축소됐)"
    r"(?!다면|으면|는지|을지|더라도|더라면|다는\s*(?:가정|전제))"
)
_LATIN_NAME = re.compile(r"(?<![A-Za-z])[A-Z][a-z]+(?:[A-Z][A-Za-z]*)*\b|\b[A-Z]{2,}\b")
_TECHNICAL_ROLE = re.compile(
    r"\s*(?:공정|생산라인|구조|방식|기술|규격|표준|인터페이스|접합|패키징|메모리|"
    r"반도체|칩|기판|배선|호환성|프로토콜|아키텍처)(?:\b|(?=[가-힣]))"
)
_GENERIC_LATIN = frozenset(
    {
        "AI",
        "IT",
        "HBM",
        "DRAM",
        "SRAM",
        "CAPEX",
        "OPEX",
        "GPU",
        "CPU",
        "ASIC",
        "CXL",
        "PCB",
        "FOM",
        "DTCO",
        "EUV",
        "DUV",
        "ESG",
        # A financial instrument category, not a company or a specific fund.
        # Named funds, tickers, amounts and asserted events remain source-owned.
        "ETF",
        "R&D",
        "API",
        "If",
        "When",
        "The",
        "This",
        "That",
        "Check",
        "Monitor",
        "Review",
        "Assess",
        "Production",
        "Supply",
    }
)
_MAX_SLOT_LENGTH = 500

FACT_TEMPLATE_INSTRUCTIONS = (
    "모든 자연어 필드에는 관점 해석·확인할 업무·미확인 조건만 직접 작성하세요. "
    "회사명·수치(제품 세대 포함)·날짜·계획/완료 사건을 다시 서술해야 할 때는 "
    "factTextSlots의 slotId를 {{fact:slotId}} 형태로 필드 맨 앞에 한 개만 넣고 "
    "그 뒤에 회사·수치·날짜·발생 단정 없는 해석을 쓰세요. "
    "표식 자체가 원문 사실을 완전히 대신합니다. 표식 뒤에 회사명·증권사명·내년 같은 "
    "시점이나 원문 전망을 다시 설명하면 실패합니다. 한국어 업무명만 쓰고 ORDER_BOOKING "
    "같은 내부 범주 코드는 자연어에 쓰지 마세요. 예: '{{fact:slotId}} 실제 발주로 연결되는지 "
    "확인한 뒤 장비 인도 일정의 조정 여부를 판단한다.' 또는 표식 없이 '실제 발주 연결 "
    "여부에 따라 장비 수주 전망을 조건부로 검토한다.'처럼 짧게 작성하세요. "
    "선택지의 findingId·sentenceIndex로 제공된 원문 문장을 찾으세요. "
    "서버가 전체 원문 문장을 그대로 인용하고 해석/가정과 구분합니다. "
    "표식은 문장 중간, 부정문, 다른 사실과의 결합에 넣을 수 없습니다. "
    "선택한 slot의 claimIds 중 하나가 현재 항목의 basisClaimIds 또는 선택한 basis에 "
    "포함되어야 합니다. 미판정 MAP은 같은 finding의 claim만 사용합니다. "
    "원문 인용까지 합쳐 최종 필드 길이를 넘으면 서버가 표시용 인용만 생략하고 검증된 "
    "해석·가정을 표시합니다. 원문과 근거 연결은 유지됩니다. 해석 자체는 필드 길이 안에서 "
    "짧게 작성하고, 표시용 인용 생략을 대비해 사실을 다시 서술하지 마세요. "
    "원문 선택지는 원문 인용이며, 추출되지 않은 문장이 검증된 사실이라는 뜻은 아닙니다."
)


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


def _source_technical_acronym(token: str, sources: tuple[str, ...]) -> bool:
    """Recognize source-scoped technical usage, not every capitalized identifier.

    Known company aliases still take precedence. Unknown names are not granted
    factual authority: only uppercase tokens used as modifiers of a technical
    category in cited original text may recur in an interpretation.
    """
    if not token.isupper() or _companies(token.casefold()):
        return False
    pattern = re.compile(r"(?<![A-Za-z0-9])" + re.escape(token) + r"(?![A-Za-z0-9])")
    named_organization = re.compile(
        r"(?:기업|회사|업체|제조사|공급사|주식회사)\s*[\"'“‘]?"
        + re.escape(token)
        + r"(?![A-Za-z0-9])|(?<![A-Za-z0-9])"
        + re.escape(token)
        + r"\s+(?:Inc|Corp|Corporation|Ltd|Holdings)\b",
        re.I,
    )
    if any(named_organization.search(source) for source in sources):
        return False
    return any(
        _TECHNICAL_ROLE.match(source, match.end())
        for source in sources
        for match in pattern.finditer(source)
    )


def _freehand_hard_fact(value: str, *, sources: tuple[str, ...] = ()) -> bool:
    if _NUMBER.search(value) or _WRITTEN_QUANTITY.search(value) or _WRITTEN_TIME.search(value):
        return True
    if (
        _ASSERTED_EVENT.search(value)
        or _PAST_ASSERTION.search(value)
        or _PLANNED_ASSERTION.search(value)
        or _CONTINUING_EVENT.search(value)
    ):
        return True
    if any(
        match.group() not in _GENERIC_LATIN
        and not _source_technical_acronym(match.group(), sources)
        for match in _LATIN_NAME.finditer(value)
    ):
        return True
    analysis = analyze_sentence(value)
    return bool(_companies(value.casefold())) or any(
        mention.kind in {"quantity", "time"} for mention in analysis.mentions
    )


def render_fact_template(
    value: str,
    catalog: FactTextCatalog,
    permitted_claim_ids: Collection[str],
    *,
    max_length: int,
    kind: ProseKind = "interpretation",
) -> RenderedFactText:
    """Render exactly one optional, prefix-only factual quotation.

    Validation runs on the template and interpretation before layout. A valid unknown
    claim ID, a source from a different finding and a fact from another audience
    cannot grant access merely because its handle is present elsewhere.
    """
    value = value.strip()
    marker = _MARKER.match(value)
    slot = None
    interpretation = value
    if marker is not None:
        if marker.end() < len(value) and not value[marker.end()].isspace():
            raise FactTemplateError("report_fact_slot_position")
        slot = next((item for item in catalog.slots if item.slot_id == marker[1]), None)
        if slot is None:
            raise FactTemplateError("report_fact_slot_unknown")
        if not set(slot.claim_ids).intersection(permitted_claim_ids):
            raise FactTemplateError("report_fact_slot_scope")
        interpretation = value[marker.end() :].strip()
    if "{{" in interpretation or "}}" in interpretation:
        raise FactTemplateError("report_fact_slot_position")
    if not interpretation:
        raise FactTemplateError("report_fact_interpretation_required")
    sources = tuple(
        slot.source_text
        for slot in catalog.slots
        if set(slot.claim_ids).intersection(permitted_claim_ids)
    )
    if _freehand_hard_fact(interpretation, sources=sources):
        raise FactTemplateError("report_fact_template_required")
    if len(interpretation) > max_length:
        raise FactTemplateError("report_fact_rendered_length")
    rendered = (_quote_prefix(slot, kind) if slot else "") + interpretation
    omitted = slot is not None and len(rendered) > max_length
    # Source selection has already passed identity/scope validation and the
    # remaining prose has passed the hard-fact gate. Omit the whole optional
    # display quotation, never cut a source clause or rewrite its factual values.
    # Keep the slot and explicit internal metadata for callers/audits.
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
    """Render every human-visible REDUCE field with its own authorized citations.

    The caller still performs its usual shape checks, captures repair context
    from the original provider bytes, and validates the entire rendered result.
    Failed units are reported without discarding their neighboring good units.
    """
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
            record[name] = render_fact_template(
                record[name], catalog, refs, max_length=limit, kind=kind
            ).text
        except FactTemplateError as error:
            issues.append(
                ReportValidationIssue(
                    audience, path, "report_fact_mismatch", tuple(dict.fromkeys(refs)), error.rule
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
    return result, tuple(issues)
