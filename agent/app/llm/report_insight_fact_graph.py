"""Literal, sentence-local argument bindings for report evidence.

This is an extraction graph, not an entailment model. Mentions are found before
relations are bound, and uncertain relations remain useful *hints*, never proof.
Every argument refers to the original string; normalization only changes values.
No entity, subject or date is inherited from a different sentence.
"""

import hashlib
import re
import unicodedata
from dataclasses import dataclass, replace
from decimal import Decimal, InvalidOperation
from functools import lru_cache

from app.core.evidence import _COMPANY_ALIASES


@dataclass(frozen=True, slots=True)
class Span:
    start: int
    end: int
    text: str

    def matches(self, source: str) -> bool:
        return (
            0 <= self.start < self.end <= len(source) and source[self.start : self.end] == self.text
        )


@dataclass(frozen=True, slots=True)
class Mention:
    kind: str
    value: str
    span: Span
    unit: str | None = None
    qualifier: str = "exact"
    upper: str | None = None
    role: str = "level"


@dataclass(frozen=True, slots=True)
class Relation:
    subjects: tuple[Mention, ...]
    predicate: str
    target: Mention | None
    quantity: Mention | None
    time: Mention | None
    state: str
    span: Span
    bindings: tuple[Mention, ...]
    rule: str
    uncertainty: tuple[str, ...] = ()
    subject_mode: str = "single"
    attributed_to: Mention | None = None


@dataclass(frozen=True, slots=True)
class Unresolved:
    span: Span
    reason: str


@dataclass(frozen=True, slots=True)
class Analysis:
    source_sha256: str
    mentions: tuple[Mention, ...]
    relations: tuple[Relation, ...]
    unresolved: tuple[Unresolved, ...]


def _norm(value: str) -> str:
    return " ".join(unicodedata.normalize("NFKC", value).casefold().split())


def _span(source: str, start: int, end: int) -> Span:
    return Span(start, end, source[start:end])


def _decimal(value: Decimal) -> str:
    return format(value.normalize(), "f")


_METRICS = {
    "생산량": "production_volume",
    "생산 능력": "production_capacity",
    "생산능력": "production_capacity",
    "공급량": "supply_volume",
    "판매량": "sales_volume",
    "영업이익": "operating_profit",
    "순이익": "net_profit",
    "매출액": "revenue",
    "연매출": "revenue",
    "매출": "revenue",
    "수주잔고": "order_backlog",
    "수주 잔고": "order_backlog",
    "공급 가격": "supply_price",
    "공급가격": "supply_price",
    "공급가": "supply_price",
    "판매단가": "selling_price",
    "판매 단가": "selling_price",
    "방어율": "defense_rate",
    "냉각 용량": "cooling_capacity",
    "냉각용량": "cooling_capacity",
    "대응용량": "cooling_capacity",
    "대응 용량": "cooling_capacity",
    "투자액": "investment_amount",
    "투자금": "investment_amount",
    "사업비": "project_cost",
    "총사업비": "project_cost",
    "매출 비중": "revenue_share",
    "매출비중": "revenue_share",
    "점유율": "market_share",
    "수익률": "return_rate",
    "가동률": "utilization_rate",
    "수율": "yield_rate",
    "소비전력": "power_consumption",
    "전력 용량": "power_capacity",
    "전력용량": "power_capacity",
    "production volume": "production_volume",
    "production output": "production_volume",
    "production capacity": "production_capacity",
    "sales volume": "sales_volume",
    "supply volume": "supply_volume",
    "operating profit": "operating_profit",
    "net income": "net_profit",
    "annual revenue": "revenue",
    "revenue": "revenue",
    "order backlog": "order_backlog",
    "supply price": "supply_price",
    "selling price": "selling_price",
    "cooling capacity": "cooling_capacity",
    "market share": "market_share",
    "revenue share": "revenue_share",
    "investment amount": "investment_amount",
    "project cost": "project_cost",
}
_METRIC = re.compile("|".join(re.escape(s) for s in sorted(_METRICS, key=len, reverse=True)), re.I)
_COMPOSED_METRIC = re.compile(r"매출(?:에서|의)\s+[^.!?。;\n]{0,35}?비중")
_EVENTS = {
    "장기공급계약": "contract",
    "장기 공급 계약": "contract",
    "공급계약": "contract",
    "공급 계약": "contract",
    "계약": "contract",
    "협약": "contract",
    "건설": "construction",
    "완공": "construction",
    "준공": "construction",
    "양산": "production",
    "생산": "production",
    "증설": "capacity_expansion",
    "투자": "investment",
    "공급": "supply",
    "인수": "acquisition",
    "출하": "shipment",
    "설치": "installation",
    "공개": "disclosure",
    "개발": "development",
    "유출": "data_leak",
    "구축": "construction",
    "조성": "construction",
    "수주": "orders",
    "가동": "operation",
    "contract": "contract",
    "agreement": "contract",
    "construction": "construction",
    "build": "construction",
    "built": "construction",
    "complete": "construction",
    "completed": "construction",
    "production": "production",
    "invest": "investment",
    "invested": "investment",
    "investment": "investment",
    "supply": "supply",
    "supplied": "supply",
    "acquire": "acquisition",
    "acquired": "acquisition",
}
_EVENT = re.compile(
    "|".join(
        (r"\b" + re.escape(s) + r"\b") if s.isascii() else re.escape(s)
        for s in sorted(_EVENTS, key=len, reverse=True)
    ),
    re.I,
)
_TIME = re.compile(
    r"(?<![A-Za-z0-9])(?:19|20)\d{2}(?!\d)(?:\s*년)?"
    r"(?:\s*\d{1,2}\s*월(?:\s*\d{1,2}\s*일)?)?"
    r"(?:\s*(?:[1-4]\s*분기|상반기|하반기))?|"
    r"(?:내년|올해|금년|지난해|작년|내년도|연말|내달|다음\s*달|이달)"
    r"(?:\s*(?:[1-4]\s*분기|상반기|하반기|\d{1,2}\s*월))?|"
    r"(?<!\d)[1-4]\s*분기|\b(?:next|last|this)\s+year\b",
    re.I,
)
_RELATIVE = {
    "내년": "next_year",
    "내년도": "next_year",
    "올해": "this_year",
    "금년": "this_year",
    "지난해": "last_year",
    "작년": "last_year",
    "연말": "year_end",
    "내달": "next_month",
    "다음 달": "next_month",
    "이달": "this_month",
    "next year": "next_year",
    "last year": "last_year",
    "this year": "this_year",
}
_NUMBER = r"[+−-]?(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d+)?"
_SCALE = {
    "조": 10**12,
    "억": 10**8,
    "만": 10**4,
    "천": 10**3,
    "trillion": 10**12,
    "billion": 10**9,
    "million": 10**6,
    "thousand": 10**3,
}
_SCALE_PATTERN = "|".join(_SCALE)
_AMOUNT = rf"{_NUMBER}(?:\s*(?:{_SCALE_PATTERN})(?:\s*{_NUMBER})?)*"
_UNITS = {
    "원": ("krw", 1),
    "달러": ("usd", 1),
    "유로": ("eur", 1),
    "usd": ("usd", 1),
    "eur": ("eur", 1),
    "krw": ("krw", 1),
    "$": ("usd", 1),
    "€": ("eur", 1),
    "₩": ("krw", 1),
    "개": ("count", 1),
    "대": ("machines", 1),
    "건": ("cases", 1),
    "명": ("people", 1),
    "units": ("count", 1),
    "unit": ("count", 1),
    "pieces": ("count", 1),
    "톤": ("tonne", 1),
    "tonnes": ("tonne", 1),
    "%": ("percent", 1),
    "퍼센트": ("percent", 1),
    "percent": ("percent", 1),
    "배": ("multiple", 1),
    "times": ("multiple", 1),
    "gw": ("watt", 10**9),
    "기가와트": ("watt", 10**9),
    "mw": ("watt", 10**6),
    "메가와트": ("watt", 10**6),
    "kw": ("watt", 10**3),
    "킬로와트": ("watt", 10**3),
    "w": ("watt", 1),
    "nm": ("nm", 1),
    "나노미터": ("nm", 1),
    "년간": ("year", 1),
    "years": ("year", 1),
    "개월": ("month", 1),
}
_UNIT_PATTERN = "|".join(re.escape(s) for s in sorted(_UNITS, key=len, reverse=True))
_QUANTITY = re.compile(
    rf"(?<![A-Za-z0-9+−\-])(?P<prefix>약\s*|대략\s*|최소\s*|적어도\s*|최대\s*|"
    rf"about\s+|approximately\s+|at\s+least\s+|at\s+most\s+|"
    rf"more\s+than\s+|less\s+than\s+|over\s+|under\s+|[<>]\s*)?"
    rf"(?P<currency>[$€₩]|(?:USD|EUR|KRW)\s+)?"
    rf"(?P<lower>{_AMOUNT})(?:\s*(?:~|∼|–|—|\bto\b)\s*(?P<upper>{_AMOUNT}))?\s*"
    rf"(?P<unit>{_UNIT_PATTERN})?(?P<suffix>\s*(?:이상|이하|초과|미만|안팎|가량|수준|"
    r"대(?=$|[\s,.]|(?:이|를|로|의|에|가|는|은))))?",
    re.I,
)
_AMOUNT_COMPONENT = re.compile(rf"({_NUMBER})\s*({_SCALE_PATTERN})?", re.I)
_SUBJECT = re.compile(
    r"(?<![가-힣A-Za-z0-9])(?P<name>[가-힣A-Za-z][가-힣A-Za-z0-9&·.-]{1,35}?)"
    r"(?:\([^()]{1,80}\))?(?P<particle>은|는|이|가|의)(?=\s)",
)
_JOINT_SUBJECT = re.compile(
    r"(?<![가-힣A-Za-z0-9])(?P<name>[가-힣A-Za-z][가-힣A-Za-z0-9&·.-]{1,35}?)"
    r"(?:와|과)(?=\s+[가-힣A-Za-z])"
)
_ENGLISH_OWNER = re.compile(
    r"(?<![A-Za-z0-9])(?P<name>[A-Z][A-Za-z0-9&.-]*"
    r"(?:\s+[A-Z][A-Za-z0-9&.-]*){0,4})(?:['’]s|['’])(?=\s)",
)
_ENGLISH_SUBJECT = re.compile(
    r"(?:^|[,;]\s*)(?P<name>[A-Z][A-Za-z0-9&.-]*(?:\s+[A-Z][A-Za-z0-9&.-]*){0,4})"
    r"\s+(?=plans?\b|will\b|has\b|have\b|did\b|signed\b|completed\b|invested\b|"
    r"expects?\b|reported\b|supplied\b|built\b|acquired\b)",
)
_NON_ACTORS = frozenset(_METRICS) | frozenset(
    {
        "계획",
        "예정",
        "전망",
        "예상",
        "가능성",
        "가격",
        "상황",
        "규모",
        "비중",
        "확대",
        "성과",
        "투자",
        "증설",
        "생산",
        "양산",
        "물량",
        "결과",
        "현재",
        "이는",
        "이것",
        "경우",
        "것",
        "정보",
        "조건",
        "이후",
        "때문",
        "이상",
        "이하",
        "근거",
        "원문",
        "제품",
        "부분",
        "내용",
        "기반",
        "산업",
        "분야",
        "단계",
        "평가",
        "조성",
        "방안",
        "공개",
        "유출",
        "설비",
        "장비",
        "반도체",
        "소재",
        "고부",
        "공장",
        "시설",
        "수요",
    }
)
_GENERIC = frozenset(
    {
        "기업",
        "회사",
        "업체",
        "당사",
        "그",
        "그 회사",
        "해당기업",
        "고객사",
        "제조사",
        "개발사",
        "그들은",
        "this",
        "it",
        "they",
    }
)
_REPORTING = re.compile(
    r"전망|예상|추산|평가|분석|관측|봤다|내다봤|추정|밝혔|설명했|보도했|보고했|말했다|"
    r"전했다|강조했다|공시했다|"
    r"\b(?:said|reported|expects?|estimates?|forecasts?|believes?)\b",
    re.I,
)
_SPEAKER = re.compile(r"증권|연구원|애널리스트|교수|관계자|연구소|연구진|analyst|research", re.I)
_SENTENCE_END = re.compile(r"(?<!\d)[.!?](?!\d)|[。;；\n]")
_COORDINATION = re.compile(r"(?:했으며|됐으며|되었으며|했고|됐고|되었고|하지만|반면)\s+")
_JOINT = re.compile(r"\s*(?:와|과|및|·|,|and|&)\s*", re.I)
_OBJECT = re.compile(
    r"(?<![가-힣A-Za-z0-9])(?P<object>(?:[가-힣A-Za-z0-9&·.-]+\s+){0,2}"
    r"[가-힣A-Za-z0-9.-]*?(?:공장|팹|센터|칠러|장비|반도체|전력|사업|클러스터|계약|협약|제품|시설))"
    r"(?:\([^()]{1,80}\))?(?:을|를|에|의|이|가|도)(?=\s)",
)
_EN_OBJECT = re.compile(
    r"\b(?:a\s+|the\s+|its\s+)?(?P<object>(?:semiconductor\s+)?"
    r"(?:factory|plant|fab|contract|agreement|power|equipment))\b",
    re.I,
)


_NOMINAL_OBJECT = re.compile(
    r"(?P<object>(?:[가-힣A-Za-z0-9&·.-]+\s+){0,2}"
    r"[가-힣A-Za-z0-9.-]*?(?:공장|팹|시설|클러스터|센터))\s*$"
)


def _clauses(source: str):
    start = 0
    ends = sorted(
        {m.end() for pattern in (_SENTENCE_END, _COORDINATION) for m in pattern.finditer(source)}
        | {len(source)}
    )
    for end in ends:
        left, right = start, end
        while left < right and source[left].isspace():
            left += 1
        while right > left and (source[right - 1].isspace() or source[right - 1] in ".!?。;；"):
            right -= 1
        if left < right:
            yield _span(source, left, right)
        start = end


def _time_value(raw: str) -> str:
    value = _norm(raw)
    for literal in sorted(_RELATIVE, key=len, reverse=True):
        if value.startswith(literal):
            suffix = value[len(literal) :].strip()
            return _RELATIVE[literal] + (":" + suffix.replace(" ", "") if suffix else "")
    year = re.match(r"((?:19|20)\d{2})", value)
    if year:
        rest = value[year.end() :]
        month = re.search(r"(\d{1,2})\s*월", rest)
        day = re.search(r"(\d{1,2})\s*일", rest)
        quarter = re.search(r"([1-4])\s*분기", rest)
        result = year[1]
        if month:
            result += f"-{int(month[1]):02d}"
        if day:
            result += f"-{int(day[1]):02d}"
        if quarter:
            result += "-Q" + quarter[1]
        if "상반기" in rest or "하반기" in rest:
            result += "-H1" if "상반기" in rest else "-H2"
        return result
    return value.replace(" ", "")


def _amount(value: str) -> Decimal | None:
    sign = -1 if value.startswith(("-", "−")) else 1
    value = value.lstrip("+-−")
    total = Decimal(0)
    cursor = 0
    for match in _AMOUNT_COMPONENT.finditer(value):
        if value[cursor : match.start()].strip():
            return None
        try:
            if match[1].startswith(("-", "+", "−")):
                return None
            total += Decimal(match[1].replace(",", "")) * _SCALE.get(_norm(match[2] or ""), 1)
        except InvalidOperation:
            return None
        cursor = match.end()
    return total * sign if cursor == len(value.rstrip()) else None


def _quantities(source: str, times: list[Mention]) -> list[Mention]:
    result = []
    for match in _QUANTITY.finditer(source):
        unit = _norm(match["unit"] or match["currency"] or "")
        if not unit or any(t.span.start <= match.start() < t.span.end for t in times):
            continue
        amount = _amount(match["lower"])
        upper = _amount(match["upper"]) if match["upper"] else None
        if amount is None or (match["upper"] and upper is None):
            continue
        canonical, scale = _UNITS[unit]
        suffix, prefix = _norm(match["suffix"] or ""), _norm(match["prefix"] or "")
        qualifier = (
            "range"
            if upper is not None
            else "min"
            if suffix == "이상" or prefix in {"at least", "최소", "적어도"}
            else "max"
            if suffix == "이하" or prefix in {"at most", "최대"}
            else "gt"
            if suffix == "초과" or prefix in {"more than", "over", ">"}
            else "lt"
            if suffix == "미만" or prefix in {"less than", "under", "<"}
            else "approx"
            if prefix or suffix
            else "exact"
        )
        role = (
            "multiple"
            if canonical == "multiple"
            else "process_node"
            if canonical == "nm"
            else "duration"
            if canonical in {"year", "month"}
            else "level"
        )
        result.append(
            Mention(
                "quantity",
                _decimal(amount * scale),
                _span(source, match.start(), match.end()),
                canonical,
                qualifier,
                _decimal(upper * scale) if upper is not None else None,
                role,
            )
        )
    return result


def _actors(source: str) -> tuple[list[Mention], dict[tuple[int, int], str]]:
    actors, roles = {}, {}
    aliases = {
        _norm(alias): _norm(canonical)
        for canonical, values in _COMPANY_ALIASES.items()
        for alias in values
    }
    for alias, canonical in aliases.items():
        for match in re.finditer(re.escape(alias), source, re.I):
            if match.start() and source[match.start() - 1].isalnum():
                continue
            following = source[match.end() :]
            if (
                following
                and following[0].isalnum()
                and not re.match(r"(?:은|는|이|가|의|에|와|과|도|을|를)(?=\s|$|[,.])", following)
            ):
                continue
            span = _span(source, match.start(), match.end())
            actors[(span.start, span.end)] = Mention("actor", canonical, span)
    for pattern in (_SUBJECT, _JOINT_SUBJECT, _ENGLISH_OWNER, _ENGLISH_SUBJECT):
        for match in pattern.finditer(source):
            name = match["name"]
            if _norm(name) in _NON_ACTORS or _METRIC.fullmatch(name) or _EVENT.fullmatch(name):
                continue
            if name not in aliases and re.search(
                r"(?:에서|에게|으로|부터|까지|보다|처럼|에|되|하|있|없|넘어가|만드|이르)$", name
            ):
                continue
            span = _span(source, match.start("name"), match.end("name"))
            key = (span.start, span.end)
            # A grammatical phrase containing a known multiword name must not
            # create a second, shortened company identity.
            enclosing = next((k for k in actors if k[0] <= key[0] and key[1] <= k[1]), None)
            if enclosing is not None:
                key = enclosing
            else:
                actors[key] = Mention("actor", aliases.get(_norm(name), _norm(name)), span)
            if pattern is not _JOINT_SUBJECT:
                roles[key] = match.groupdict().get("particle") or (
                    "possessive" if pattern is _ENGLISH_OWNER else "subject"
                )
    return sorted(actors.values(), key=lambda m: m.span.start), roles


def _in(items: list[Mention], span: Span) -> list[Mention]:
    return [m for m in items if span.start <= m.span.start and m.span.end <= span.end]


def _state(text: str) -> tuple[str, tuple[str, ...]]:
    if re.search(r"(?:다면|라면|으면|경우|가정)|\b(?:if|assuming)\b", text, re.I):
        return "conditional", ()
    if re.search(
        r"(?:않|못|없|아니|취소|중단|무산)|\b(?:not|never|cancelled|canceled)\b", text, re.I
    ):
        return "negated", ()
    if re.search(
        r"전망|예상|추산|목표|정조준|관측|것으로\s*봤|내다봤|추정|"
        r"\b(?:expects?|forecast|estimated?|projects?)\b",
        text,
        re.I,
    ):
        return "forecast", ()
    if re.search(
        r"계획|예정|추진|방안|구상|방침|\b(?:plans?|will|intends?|scheduled)\b", text, re.I
    ):
        return "planned", ()
    if re.search(r"가능성|\b(?:may|might|could|possibly)\b", text, re.I):
        return "conditional", ()
    if re.search(
        r"체결했|맺었|완공했|준공했|완료했|마쳤|인수했|유출됐|"
        r"\b(?:signed|completed|built|acquired)\b",
        text,
        re.I,
    ):
        return "completed", ()
    if re.search(
        r"진행\s*중|구축하고\s*있|조성되고\s*있|공급하고\s*있|생산하고\s*있|"
        r"\b(?:underway|ongoing)\b",
        text,
        re.I,
    ):
        return "ongoing", ()
    if re.search(r"보도|알려졌|전해졌|언급|\b(?:reported|said)\b", text, re.I):
        return "reported", ()
    return "asserted", ()


def _owners(source, clause, actors, roles, target):
    candidates = []
    for actor in actors:
        role = roles.get((actor.span.start, actor.span.end))
        if actor.span.end > target.span.start:
            continue
        if role in {"은", "는", "이", "가", "subject", "의", "possessive"}:
            candidates.append(actor)
        elif role is None and re.fullmatch(
            r"\s+(?:(?:D램|낸드|HBM\d?|AI|데이터센터|냉각|솔루션|연결|반도체|메모리|부문)\s+)*",
            source[actor.span.end : target.span.start],
            re.I,
        ):
            # Adjacent company-product-metric noun phrases express ownership.
            # Arbitrary intervening prose does not satisfy this finite grammar.
            candidates.append(actor)
    attribution = re.search(r"(?P<name>[가-힣A-Za-z][가-힣A-Za-z0-9&·.-]+)에\s*따르면", clause.text)
    speaker = (
        Mention(
            "attribution",
            _norm(attribution["name"]),
            _span(
                source,
                clause.start + attribution.start("name"),
                clause.start + attribution.end("name"),
            ),
        )
        if attribution
        else None
    )
    if (
        candidates
        and _REPORTING.search(clause.text)
        and (
            _SPEAKER.search(candidates[0].span.text)
            or (
                re.search(r'["“”]', clause.text)
                and re.search(r"말했다|전했다|강조했다", clause.text)
            )
            or (
                len(candidates) > 1
                and roles.get((candidates[-1].span.start, candidates[-1].span.end))
                in {"의", "possessive"}
            )
        )
    ):
        speaker, candidates = candidates[0], candidates[1:]
    # An explicit possessive adjacent to the argument owns it even when an
    # attribution speaker appears earlier: A증권은 B사의 매출을 전망했다.
    possessives = [
        a
        for a in candidates
        if roles.get((a.span.start, a.span.end)) in {"의", "possessive"}
        and not source[a.span.end : target.span.start].strip(" 의's’\t")
    ]
    if len(possessives) == 1:
        candidates = possessives
    if candidates:
        last = candidates[-1]
        preceding = [a for a in actors if a.span.end <= last.span.start]
        for actor in reversed(preceding):
            first = candidates[0]
            gap = source[actor.span.end : first.span.start]
            if _JOINT.fullmatch(gap):
                if actor not in candidates:
                    candidates.insert(0, actor)
            else:
                break
    uncertainties = []
    if not candidates:
        uncertainties.append("missing_subject")
    if any(actor.value in _GENERIC for actor in candidates):
        uncertainties.append("unresolved_coreference")
    if len(candidates) > 1 and not all(
        _JOINT.fullmatch(source[a.span.end : b.span.start])
        for a, b in zip(candidates, candidates[1:], strict=False)
    ):
        uncertainties.append("multiple_subject_scopes")
    return tuple(candidates), speaker, uncertainties


_STATE_BINDINGS = {
    "conditional": r"다면|라면|으면|경우|가정|가능성|\b(?:if|assuming|may|might|could)\b",
    "negated": r"않|못|없|아니|취소|중단|무산|\b(?:not|never|cancelled|canceled)\b",
    "forecast": r"전망|예상|추산|목표|정조준|관측|것으로\s*봤|내다봤|추정|"
    r"\b(?:expects?|forecast|estimated?|projects?)\b",
    "planned": r"계획|예정|추진|방안|구상|방침|\b(?:plans?|will|intends?|scheduled)\b",
    "completed": r"체결했|맺었|완공했|준공했|완료했|마쳤|인수했|유출됐|"
    r"\b(?:signed|completed|built|acquired)\b",
    "ongoing": r"진행\s*중|구축하고\s*있|조성되고\s*있|공급하고\s*있|생산하고\s*있|"
    r"\b(?:underway|ongoing)\b",
    "reported": r"보도|알려졌|전해졌|언급|\b(?:reported|said)\b",
    "asserted": r"이었다|였다|이다|기록했|집계됐|\b(?:is|are|was|were)\b",
}


def _state_binding(source: str, scope: Span, state: str) -> Mention | None:
    match = re.search(_STATE_BINDINGS[state], scope.text, re.I)
    return (
        Mention(
            "state", state, _span(source, scope.start + match.start(), scope.start + match.end())
        )
        if match
        else None
    )


def _scope_uncertainty(clause: Span) -> list[str]:
    quotes = re.findall(r'["“”]', clause.text)
    if len(quotes) >= 4 or (quotes and _REPORTING.search(clause.text)):
        return ["quoted_proposition_scope"]
    return []


def _time_for(source, quantity, times, quantities):
    if not times:
        return None, []
    if len(times) == 1:
        return times[0], []
    # Timeline lists bind an explicitly adjacent year to its own amount. A date
    # elsewhere in the sentence is never chosen merely because it is closest.
    adjacent = [
        t
        for t in times
        if t.span.end <= quantity.span.start
        and re.fullmatch(
            r"\s*(?:에|에는|은|는|의|,|:)?\s*", source[t.span.end : quantity.span.start]
        )
    ]
    if len(adjacent) == 1 and len(quantities) >= len(times):
        return adjacent[0], []
    return None, ["ambiguous_time"]


def _metric_target(source, metric, owners, times):
    """Keep product/site qualifiers as part of the metric's identity.

    The lexer knowing 'revenue' does not prove that HBM revenue and DDR revenue
    are the same property. Unknown nominal scopes explicitly prevent matching.
    """
    identity = metric.value
    if metric.value == "revenue_share" and "차지" in metric.span.text:
        numerator = re.fullmatch(
            r"매출(?:에서|의)\s+(.+?)(?:이|가)\s+차지하는\s+비중", metric.span.text
        )
        if numerator is None:
            return metric, ["unresolved_share_numerator"]
        identity += ":numerator=" + _norm(numerator[1])
    if not owners:
        return replace(metric, value=identity), []
    start = owners[-1].span.end
    gap = source[start : metric.span.start]
    particle = re.match(r"(?:['’]s|['’]|의|은|는|이|가)?\s*", gap)
    start += particle.end()
    # A causal clause can precede the property, but a date cannot erase a
    # product qualifier before it (HBM transitioning in 2030 is still HBM).
    delimiters = list(
        start + m.end()
        for m in re.finditer(
            r"(?:힘입어|따라|대해|대해서|관해서|때문에)\s+", source[start : metric.span.start]
        )
    )
    if delimiters:
        start = max(delimiters)
    for time in times:
        if (
            start <= time.span.start < metric.span.start
            and not source[start : time.span.start].strip()
        ):
            start = time.span.end
    while start < metric.span.start and source[start].isspace():
        start += 1
    qualifier = source[start : metric.span.start].strip()
    if not qualifier:
        return replace(metric, value=identity), []
    words = qualifier.split()
    if (
        len(words) > 5
        or any(
            re.search(r"(?:에서|으로|에게|하는|되는|넘어가는|이며|했다|하고|했다는)$", w)
            for w in words
        )
        or re.search(r"[,;\"“”]|(?:을|를)\s", qualifier)
    ):
        return metric, ["unresolved_target_qualification"]
    if not re.fullmatch(r"[가-힣A-Za-z0-9&·(). /-]+", qualifier):
        return metric, ["unresolved_target_qualification"]
    target = Mention(
        "metric", identity + ":" + _norm(qualifier), _span(source, start, metric.span.end)
    )
    return target, []


def _numeric_relations(source, clause, actors, roles, metrics, quantities, times):
    result = []
    for index, metric in enumerate(metrics):
        next_metric = metrics[index + 1].span.start if index + 1 < len(metrics) else clause.end
        local = [q for q in quantities if metric.span.end <= q.span.start < next_metric]
        # Nominal amounts preceding a metric (20개 생산량) lack a complete
        # predication and remain mentions rather than invented relations.
        if not local:
            continue
        owners, speaker, owner_uncertainty = _owners(source, clause, actors, roles, metric)
        target, target_uncertainty = _metric_target(source, metric, owners, times)
        state_scope = _span(source, metric.span.start, next_metric)
        state, state_uncertainty = _state(state_scope.text)
        if _state(clause.text)[0] == "conditional":
            state, state_scope = "conditional", clause
        local_times = [
            t
            for t in times
            if (index == 0 or t.span.start >= metric.span.start) and t.span.end <= next_metric
        ]
        respective = bool(re.search(r"각각|\brespectively\b", clause.text, re.I))
        for q_index, quantity in enumerate(local):
            uncertainty = [
                *owner_uncertainty,
                *state_uncertainty,
                *target_uncertainty,
                *_scope_uncertainty(clause),
            ]
            selected = owners
            mode = "joint" if len(owners) > 1 else "single"
            if respective:
                if len(owners) == len(local) and len(owners) > 1:
                    selected, mode = (owners[q_index],), "respective"
                elif len(owners) > 1:
                    uncertainty.append("respective_arity_mismatch")
            elif len(owners) > 1 and len(local) > 1:
                uncertainty.append("ambiguous_respective_binding")
            time, time_uncertainty = _time_for(source, quantity, local_times, local)
            uncertainty.extend(time_uncertainty)
            if len(local) > 1 and not respective and not local_times:
                uncertainty.append("multiple_quantities_without_roles")
            role = quantity.role
            tail = source[quantity.span.end : min(next_metric, quantity.span.end + 22)]
            if quantity.unit == "percent":
                role = (
                    "share"
                    if metric.value.endswith(("share", "rate"))
                    else (
                        "change_increase"
                        if re.search(r"증가|상승|뛰었|올랐|오를|increase", tail, re.I)
                        else "change_decrease"
                        if re.search(r"감소|하락|decrease", tail, re.I)
                        else "level"
                    )
                )
            quantity = replace(quantity, role=role)
            # A baseline marked as realized must not inherit a later target's
            # forecast operator. Otherwise a single timeline forecast retains
            # its own projection status for all explicitly connected amounts.
            following_time = next(
                (t for t in local_times if t.span.start > quantity.span.end), None
            )
            quantity_scope = _span(
                source,
                quantity.span.end,
                following_time.span.start if following_time else next_metric,
            )
            own_state, _ = _state(quantity_scope.text)
            state_mention = _state_binding(source, state_scope, state)
            selected_state = state
            if _state_binding(source, quantity_scope, own_state) is not None and (
                own_state != "asserted" or state == "asserted"
            ):
                selected_state = own_state
                state_mention = _state_binding(source, quantity_scope, own_state)
            if len(local_times) > 1 and re.search(r"실적|실제|기록했|집계됐|이었다|였으며", tail):
                selected_state = "asserted"
                state_mention = _state_binding(source, quantity_scope, "asserted")
            baseline = re.search(
                r"(?:전년(?:\s*동기)?|작년|지난해|현재|전\s*분기)(?:\s*대비|보다|의)?", clause.text
            )
            baseline_mention = None
            if baseline and (role.startswith("change_") or role == "multiple"):
                baseline_value = "current" if baseline[0].startswith("현재") else "previous_year"
                if "분기" in baseline[0]:
                    baseline_value = "previous_quarter"
                if "동기" in baseline[0]:
                    baseline_value += "_same_period"
                quantity = replace(quantity, role=role + ":" + baseline_value)
                baseline_mention = Mention(
                    "baseline",
                    baseline_value,
                    _span(source, clause.start + baseline.start(), clause.start + baseline.end()),
                )
            elif "대비" in clause.text and (role.startswith("change_") or role == "multiple"):
                uncertainty.append("unresolved_comparison_baseline")
            bindings = (
                *selected,
                target,
                quantity,
                *((time,) if time else ()),
                *((speaker,) if speaker else ()),
                *((state_mention,) if state_mention else ()),
                *((baseline_mention,) if baseline_mention else ()),
            )
            result.append(
                Relation(
                    selected,
                    metric.value,
                    target,
                    quantity,
                    time,
                    selected_state,
                    clause,
                    bindings,
                    "explicit_metric_arguments",
                    tuple(dict.fromkeys(uncertainty)),
                    mode,
                    speaker,
                )
            )
    return result


def _event_relations(source, clause, actors, roles, events, quantities, times, metrics):
    result = []
    # A noun within a named metric is not a second independent event.
    events = [
        e for e in events if not any(m.span.start <= e.span.start < m.span.end for m in metrics)
    ]
    # 설계·개발 계약: development is the contract's nominal purpose, not a
    # separate completed development. Require only noun coordinators in between.
    events = [
        event
        for event in events
        if not any(
            later.value == "contract"
            and event.span.end <= later.span.start
            and re.fullmatch(r"[\s·/]*(?:공급\s*)?", source[event.span.end : later.span.start])
            for later in events
            if later is not event
        )
    ]
    for index, event in enumerate(events):
        start = events[index - 1].span.end if index else clause.start
        end = events[index + 1].span.start if index + 1 < len(events) else clause.end
        local_text = source[start:end]
        owners, speaker, uncertainty = _owners(source, clause, actors, roles, event)
        state, state_uncertainty = _state(local_text)
        uncertainty.extend(state_uncertainty)
        uncertainty.extend(_scope_uncertainty(clause))
        # Event nouns need a predicate; mentions such as 투자 심리 or 공급 여부
        # must not become accomplished investments or actual supplies.
        tail = source[event.span.end : end]
        if re.match(r"\s*(?:심리|sentiment\b|여부|수혜|benefit\b)", tail, re.I):
            continue
        if not re.search(
            r"(?:했|했다|한다|하고|되고|됐다|되었다|중|예정|계획|추진|목표|될|한다|맺|체결|"
            r"완공|준공|마쳤|발표|예상|전망|증가|감소|으로|였다|이다)|"
            r"\b(?:plans?|will|signed|completed|built|invested|underway|"
            r"supplied|acquired|expects?)\b",
            local_text,
            re.I,
        ):
            uncertainty.append("event_mention_only")
        objects = []
        for pattern in (_OBJECT, _EN_OBJECT):
            for match in pattern.finditer(source, start, end):
                left, right = match.start("object"), match.end("object")
                # Keep the noun phrase after grammatical subjects, dates and
                # partner phrases, rather than swallowing those into its name.
                for token in re.finditer(r"\S+\s+", source[left:right]):
                    if re.search(r"(?:은|는|이|가|와|과|에서|년에|년)$", token.group().strip()):
                        left = match.start("object") + token.end()
                for actor in actors:
                    if left <= actor.span.start < right and source[
                        actor.span.end : right
                    ].startswith("의 "):
                        left = actor.span.end + 2
                span = _span(source, left, right)
                if any(span.start <= a.span.start < span.end for a in actors):
                    continue
                value = re.sub(r"^(?:이후\s+)?(?:이|그|해당)\s+", "", _norm(span.text))
                if value != _norm(span.text):
                    uncertainty.append("unresolved_coreference")
                objects.append(Mention("object", value, span))
        if not objects:
            nominal = _NOMINAL_OBJECT.search(source, start, event.span.start)
            if nominal:
                span = _span(source, nominal.start("object"), nominal.end("object"))
                objects.append(Mention("object", _norm(span.text), span))
        # Two target noun phrases require a parser rather than nearest-noun
        # selection; expose the ambiguity explicitly.
        target = objects[0] if len(objects) == 1 else None
        if len(objects) > 1:
            uncertainty.append("ambiguous_event_target")
        local_quantities = [
            q
            for q in quantities
            if start <= q.span.start and q.span.end <= end and q.role != "process_node"
        ]
        quantity = local_quantities[0] if len(local_quantities) == 1 else None
        if len(local_quantities) > 1:
            uncertainty.append("multiple_event_quantities")
        local_times = [t for t in times if start <= t.span.start and t.span.end <= end]
        time = local_times[0] if len(local_times) == 1 else None
        if len(local_times) > 1:
            uncertainty.append("ambiguous_time")
        if len(events) > 1:
            uncertainty.append("multiple_event_scope")
        if target is None:
            uncertainty.append("missing_event_target")
        bindings = (
            *owners,
            event,
            *((target,) if target else ()),
            *((quantity,) if quantity else ()),
            *((time,) if time else ()),
            *((speaker,) if speaker else ()),
            *((s,) if (s := _state_binding(source, _span(source, start, end), state)) else ()),
        )
        result.append(
            Relation(
                owners,
                event.value,
                target,
                quantity,
                time,
                state,
                clause,
                bindings,
                "explicit_event_arguments",
                tuple(dict.fromkeys(uncertainty)),
                "joint" if len(owners) > 1 else "single",
                speaker,
            )
        )
    return result


@lru_cache(maxsize=256)
def analyze_sentence(source: str) -> Analysis:
    """Build immutable source-local facts and retain uncertain/unparsed scopes."""
    actors, roles = _actors(source)
    times = [
        Mention("time", _time_value(m.group()), _span(source, m.start(), m.end()))
        for m in _TIME.finditer(source)
    ]
    quantities = _quantities(source, times)
    composed = [
        Mention("metric", "revenue_share", _span(source, m.start(), m.end()))
        for m in _COMPOSED_METRIC.finditer(source)
    ]
    metrics = [
        Mention("metric", _METRICS[_norm(m.group())], _span(source, m.start(), m.end()))
        for m in _METRIC.finditer(source)
        if not any(c.span.start <= m.start() < c.span.end for c in composed)
    ]
    metrics = sorted([*metrics, *composed], key=lambda m: m.span.start)
    events = [
        Mention("event", _EVENTS[_norm(m.group())], _span(source, m.start(), m.end()))
        for m in _EVENT.finditer(source)
    ]
    mentions = tuple(
        sorted(
            [*actors, *times, *quantities, *metrics, *events],
            key=lambda m: (m.span.start, m.span.end, m.kind),
        )
    )
    relations, unresolved = [], []
    for clause in _clauses(source):
        a, q, t, m, e = (
            _in(items, clause) for items in (actors, quantities, times, metrics, events)
        )
        local = _numeric_relations(source, clause, a, roles, m, q, t)
        local.extend(_event_relations(source, clause, a, roles, e, q, t, m))
        if not local:
            unresolved.append(Unresolved(clause, "no_explicit_relation"))
        elif any(relation.uncertainty for relation in local):
            unresolved.append(Unresolved(clause, "ambiguous_relation_scope"))
        relations.extend(local)
    return Analysis(
        hashlib.sha256(source.encode("utf-8")).hexdigest(),
        mentions,
        tuple(relations),
        tuple(unresolved),
    )
