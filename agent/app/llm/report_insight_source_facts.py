"""Source-bound facts for a small, explicit grammar, never a general entailment judge.

Every extracted slot retains its literal source offsets. Unrecognized or ambiguous
clauses remain unresolved; their absence from ``facts`` is not evidence of falsity.
Only matching, unambiguous event/metric identities can establish a contradiction.
"""

import hashlib
import re
import unicodedata
from collections.abc import Mapping
from dataclasses import dataclass
from decimal import Decimal
from functools import lru_cache

_CLAUSE_END = re.compile(r"(?<!\d)[.!?](?!\d)|[。;；\n]")
_NUMBER = r"[+-]?(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d+)?"
_QUANTITY = re.compile(
    rf"(?P<number>{_NUMBER})\s*(?P<scale>천|만|억|thousand|million|billion)?\s*"
    r"(?P<unit>개|대|건|명|톤|%|퍼센트|달러|유로|원|units?|pieces?|tons?|tonnes?|"
    r"percent|USD|EUR|KRW)(?![A-Za-z])",
    re.I,
)
_METRICS = {
    "생산량": "production_volume",
    "production volume": "production_volume",
    "production volumes": "production_volume",
    "production output": "production_volume",
    "판매량": "sales_volume",
    "sales volume": "sales_volume",
    "공급량": "supply_volume",
    "supply volume": "supply_volume",
    "매출": "revenue",
    "revenue": "revenue",
}
_METRIC_PATTERN = "|".join(re.escape(item) for item in sorted(_METRICS, key=len, reverse=True))
_KOREAN_METRIC = re.compile(
    r"(?P<owners>.+?)(?:의\s*|\s+)(?P<metric>생산량|판매량|공급량|매출)"
    r"(?:은|는|이|가)?\s*(?P<respectively>각각\s*)?(?P<values>.+?)"
    r"(?:이다|이었다|였다|입니다|다)?",
    re.I,
)
_ENGLISH_METRIC = re.compile(
    rf"(?P<owners>.+?)(?:['’]s|['’])\s+(?P<metric>{_METRIC_PATTERN})\s+"
    r"(?:is|are|was|were)\s+(?P<values>.+?)(?P<respectively>,?\s+respectively)?",
    re.I,
)
_YEAR_METRIC = re.compile(
    rf"(?:In\s+)?(?P<owners>(?:19|20)\d{{2}}(?:\s+and\s+(?:19|20)\d{{2}})?)"
    rf",?\s+(?P<metric>{_METRIC_PATTERN})\s+(?:is|are|was|were)\s+"
    r"(?P<values>.+?)(?P<respectively>,?\s+respectively)?",
    re.I,
)
_OWNER_SEPARATOR = re.compile(r"(?:과|와)\s+|\s+및\s+|,\s*|\s+and\s+", re.I)
_QUANTITY_SEPARATOR = re.compile(r"\s*(?:과|와|및|(?<!\d),|and)\s*", re.I)
_SCALES = {
    None: 1,
    "천": 1000,
    "만": 10000,
    "억": 100000000,
    "thousand": 1000,
    "million": 1000000,
    "billion": 1000000000,
}
_UNITS = {
    "개": "count",
    "unit": "count",
    "units": "count",
    "piece": "count",
    "pieces": "count",
    "대": "machines",
    "건": "cases",
    "명": "people",
    "톤": "tonne",
    "ton": "unspecified_ton",
    "tons": "unspecified_ton",
    "tonne": "tonne",
    "tonnes": "tonne",
    "%": "percent",
    "퍼센트": "percent",
    "percent": "percent",
    "달러": "usd",
    "usd": "usd",
    "유로": "eur",
    "eur": "eur",
    "원": "krw",
    "krw": "krw",
}
_ACTOR_ALIASES = {
    "samsung electronics": "삼성전자",
    "삼성전자": "삼성전자",
    "sk hynix": "sk하이닉스",
    "sk하이닉스": "sk하이닉스",
}
_YEAR = re.compile(r"((?:19|20)\d{2})(?:\s*년)?")
_KO_CONSTRUCTION = re.compile(
    r"(?P<actor>.+?)(?:은|는|이|가)\s+(?P<object>[^,;\n]*?공장)(?:을|를)\s*"
    r"(?P<action>건설할\s*계획이다|건설을\s*계획한다|건설할\s*예정이다|"
    r"완공할\s*계획이다|완공할\s*예정이다|완공했다|완공하였다|준공했다|"
    r"완공하지\s*않았다|준공하지\s*않았다)"
)
_EN_CONSTRUCTION = re.compile(
    r"(?P<actor>.+?)\s+(?P<action>plans?\s+to\s+build|will\s+build|"
    r"completed|has\s+completed|did\s+not\s+complete)\s+"
    r"(?:(?:a|the|its)\s+)?(?P<object>[^,;\n]*?factory)",
    re.I,
)
_OBJECT_ALIASES = {
    "공장": "factory",
    "factory": "factory",
    "반도체공장": "semiconductor_factory",
    "반도체 공장": "semiconductor_factory",
    "semiconductor factory": "semiconductor_factory",
    "별도 공장": "별도 공장",
    "다른 공장": "다른 공장",
    "another factory": "another factory",
    "other factory": "other factory",
}


@dataclass(frozen=True, slots=True)
class SourceSpan:
    start: int
    end: int
    text: str

    def matches(self, source: str) -> bool:
        return (
            0 <= self.start <= self.end <= len(source)
            and source[self.start : self.end] == self.text
        )


@dataclass(frozen=True, slots=True)
class FactBinding:
    field: str
    span: SourceSpan


@dataclass(frozen=True, slots=True)
class SourceFact:
    source_sha256: str
    subject: str | None
    predicate: str
    object: str
    quantity: str | None
    unit: str | None
    time: str | None
    modality: str
    span: SourceSpan
    bindings: tuple[FactBinding, ...]
    rule: str

    def matches(self, source: str) -> bool:
        return (
            self.source_sha256 == _identity(source)
            and self.span.matches(source)
            and all(binding.span.matches(source) for binding in self.bindings)
        )


@dataclass(frozen=True, slots=True)
class UnresolvedSource:
    span: SourceSpan
    reason: str


@dataclass(frozen=True, slots=True)
class SourceFacts:
    source_sha256: str
    facts: tuple[SourceFact, ...]
    unresolved: tuple[UnresolvedSource, ...]


def _identity(source: str) -> str:
    return hashlib.sha256(source.encode("utf-8")).hexdigest()


def _normalize(value: str) -> str:
    return " ".join(unicodedata.normalize("NFKC", value).casefold().split())


def _actor(value: str) -> str | None:
    # Without an entity inventory, an arbitrary token is not a proven company.
    # In particular, time/forecast qualifiers must never become part of its name.
    return _ACTOR_ALIASES.get(_normalize(value))


def _span(source: str, start: int, end: int) -> SourceSpan:
    return SourceSpan(start, end, source[start:end])


def _clauses(source: str):
    start = 0
    for end in [*(_match.start() for _match in _CLAUSE_END.finditer(source)), len(source)]:
        left = start + len(source[start:end]) - len(source[start:end].lstrip())
        right = end - (len(source[start:end]) - len(source[start:end].rstrip()))
        if left < right:
            yield _span(source, left, right)
        start = end + 1


def _parts(value: str, separator: re.Pattern, offset: int, source: str):
    start = 0
    parts = []
    for match in [*separator.finditer(value), None]:
        end = match.start() if match else len(value)
        left = start + len(value[start:end]) - len(value[start:end].lstrip())
        right = end - (len(value[start:end]) - len(value[start:end].rstrip()))
        if left >= right:
            return []
        parts.append(_span(source, offset + left, offset + right))
        start = match.end() if match else end
    return parts


def _quantity_facts(source: str, clause: SourceSpan, identity: str):
    match = next(
        (
            m
            for pattern in (_KOREAN_METRIC, _ENGLISH_METRIC, _YEAR_METRIC)
            if (m := pattern.fullmatch(clause.text))
        ),
        None,
    )
    if match is None:
        return None
    owners = _parts(match["owners"], _OWNER_SEPARATOR, clause.start + match.start("owners"), source)
    values = _parts(
        match["values"], _QUANTITY_SEPARATOR, clause.start + match.start("values"), source
    )
    if (
        not owners
        or len(owners) != len(values)
        or len(owners) > 8
        or (len(owners) > 1 and not match["respectively"])
    ):
        return ()
    result = []
    for owner, value in zip(owners, values, strict=True):
        quantity = _QUANTITY.fullmatch(value.text)
        year = _YEAR.fullmatch(owner.text)
        subject = None if year else _actor(owner.text)
        if quantity is None or (year is None and subject is None):
            return ()
        number = (
            Decimal(quantity["number"].replace(",", ""))
            * _SCALES[quantity["scale"].casefold() if quantity["scale"] else None]
        )
        bindings = (
            FactBinding("time" if year else "subject", owner),
            FactBinding(
                "object",
                _span(
                    source, clause.start + match.start("metric"), clause.start + match.end("metric")
                ),
            ),
            FactBinding("quantity", value),
            FactBinding(
                "unit",
                _span(
                    source, value.start + quantity.start("unit"), value.start + quantity.end("unit")
                ),
            ),
        )
        result.append(
            SourceFact(
                identity,
                subject,
                "quantity",
                _METRICS[_normalize(match["metric"])],
                format(number.normalize(), "f"),
                _UNITS[quantity["unit"].casefold()],
                year[1] if year else None,
                "asserted",
                clause,
                bindings,
                "explicit_respective_quantity" if len(owners) > 1 else "explicit_quantity",
            )
        )
    return tuple(result)


def _construction_fact(source: str, clause: SourceSpan, identity: str):
    match = _KO_CONSTRUCTION.fullmatch(clause.text) or _EN_CONSTRUCTION.fullmatch(clause.text)
    if match is None:
        return None
    subject = _actor(match["actor"])
    target = _OBJECT_ALIASES.get(_normalize(match["object"]))
    if subject is None or target is None:
        return ()
    action = _normalize(match["action"])
    modality = (
        "planned"
        if re.search(r"계획|예정|\bplans?\b|\bwill\b", action)
        else ("negated" if re.search(r"않|\bnot\b", action) else "completed")
    )
    bindings = tuple(
        FactBinding(
            field, _span(source, clause.start + match.start(group), clause.start + match.end(group))
        )
        for field, group in (("subject", "actor"), ("object", "object"), ("modality", "action"))
    )
    return (
        SourceFact(
            identity,
            subject,
            "construction",
            target,
            None,
            None,
            None,
            modality,
            clause,
            bindings,
            "explicit_construction_state",
        ),
    )


@lru_cache(maxsize=256)
def build_source_facts(source: str) -> SourceFacts:
    """Extract only complete explicit clauses; retain every unresolved clause."""
    identity = _identity(source)
    facts, unresolved = [], []
    for clause in _clauses(source):
        extracted = _quantity_facts(source, clause, identity)
        if extracted is None:
            extracted = _construction_fact(source, clause, identity)
        if extracted:
            facts.extend(extracted)
        else:
            unresolved.append(
                UnresolvedSource(
                    clause, "ambiguous_binding" if extracted == () else "unparsed_clause"
                )
            )
    return SourceFacts(identity, tuple(facts), tuple(unresolved))


def source_fact_mismatches(value: str, source: str) -> list[str]:
    """Reject explicit conflicts only; an empty list does not establish support."""
    evidence = build_source_facts(source)
    generated = build_source_facts(value)
    # Unparsed context can revise, attribute, or qualify an extracted assertion.
    # Keyword guesses cannot establish that the omitted context is irrelevant.
    if evidence.unresolved or generated.unresolved:
        return []
    errors = []
    for fact in generated.facts:
        candidates = [
            other
            for other in evidence.facts
            if (other.subject, other.predicate, other.object, other.time, other.unit)
            == (fact.subject, fact.predicate, fact.object, fact.time, fact.unit)
        ]
        if fact.predicate == "quantity":
            amounts = {other.quantity for other in candidates}
            if len(amounts) == 1 and fact.quantity not in amounts:
                errors.append("근거와 연결이 다른 숫자: 동일 주체·대상·시점의 수치 충돌")
        elif fact.modality == "completed":
            states = {other.modality for other in candidates}
            if states and states <= {"planned", "negated"}:
                errors.append(
                    "근거의 주체·사건 연결과 다릅니다: 같은 주체·대상의 계획·미완료를 완료로 변경"
                )
    return list(dict.fromkeys(errors))


def source_fact_payload(source: str, *, max_facts: int = 24) -> dict:
    """Bounded hints with literal anchors; unresolved text is not negative evidence."""
    if max_facts < 0:
        raise ValueError("max_facts must be nonnegative")
    result = build_source_facts(source)

    def span_payload(span):
        return {"start": span.start, "end": span.end}

    return {
        "sourceSha256": result.source_sha256,
        "hintOnly": True,
        "experimental": True,
        "extractionScope": "explicit_closed_grammar",
        "interpretation": "partial_explicit_extraction; unresolved does not mean unsupported",
        "facts": [
            {
                "subject": fact.subject,
                "predicate": fact.predicate,
                "object": fact.object,
                "quantity": fact.quantity,
                "unit": fact.unit,
                "time": fact.time,
                "modality": fact.modality,
                "rule": fact.rule,
                "span": span_payload(fact.span),
                "bindings": [
                    {"field": item.field, **span_payload(item.span)} for item in fact.bindings
                ],
            }
            for fact in result.facts[:max_facts]
        ],
        "unresolved": [
            {"span": span_payload(item.span), "reason": item.reason}
            for item in result.unresolved[:8]
        ],
        "truncated": len(result.facts) > max_facts or len(result.unresolved) > 8,
    }


def source_fact_hints(sources: Mapping[str, str]) -> dict[str, dict]:
    """Attach bounded hints only when a source has at least one extracted fact."""
    return {
        source_id: payload
        for source_id, source in sources.items()
        if (payload := source_fact_payload(source))["facts"]
    }
