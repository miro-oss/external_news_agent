"""Bind a small set of corporate-finance events to selected source actors.

This supplements the number, company, polarity and event-state guards. It does
not establish whether an event completed: a supported IPO plan still needs all
existing factual checks. Only the caller's selected raw sentences are evidence.
"""

import re
import unicodedata
from collections.abc import Iterator, Sequence
from dataclasses import dataclass

from app.core.evidence import _COMPANY_ALIASES, _CURRENCY_AMOUNT, _PREFIX_CURRENCY_AMOUNT
from app.llm.report_insight_fact_graph import _actors

_PREFIX = "근거의 주체·사건 연결과 다릅니다: "
_EVENT = re.compile(
    r"(?P<ipo>(?<![A-Za-z0-9])IPO(?![A-Za-z0-9])|기업\s*공개|"
    r"(?<![가-힣])(?:신규\s*|중복\s*|재)?상장"
    r"(?!\s*(?:회사|기업|주식|종목)|사(?:들)?(?=$|[\s,.;]|(?:의|는|가|를|은|이|을)(?=\s))))|"
    r"(?P<funding>자금(?:을)?\s*조달|투자금(?:을)?\s*유치)|"
    r"(?P<equity>유상\s*증자)|(?P<debt>회사채(?:를)?\s*발행)",
    re.IGNORECASE,
)
_BREAK = re.compile(r"(?<!\d)[.!?](?!\d)|[。;；\n]")
_FUNDING_AFTER_AMOUNT = re.compile(
    r"\s*(?:\((?P<conversion>[^()\n]{1,60})\))?\s*(?:을|를)?\s*조달"
    r"(?=$|[^가-힣A-Za-z0-9]|하|할|했|되|된|됐|될|돼|함|한)"
)
_FINANCE_ANALYSIS = re.compile(
    r"\s*(?:여력|구조|조건|비용|환경|위험|수요|역량|전략|방안|업무)(?=$|[^가-힣]|"
    r"(?:을|를|은|는|이|가|의|에|과|와)(?=\s))"
)
_FINANCE_CHANGE = re.compile(
    r"직접(?:적)?(?:인)?\s*영향|구조(?:를|가|는|의)?\s*(?:변경|변화|재편)|"
    r"(?:바꿨|바뀌었|재편했|재편됐|변경했|변경됐)"
)
_NEGATIVE_EVENT = re.compile(
    r"(?:취소|철회|무산|보류|중단|불발|포기)"
    r"(?!(?:되|하|된\s*것이|한\s*것이)?지?\s*(?:않|아니))|"
    r"(?:추진|진행|실행|완료|성사|계획)(?:하|되|된|할|된\s*것이)?지\s*않|"
    r"계획(?:이|은)?\s*없"
)
_VENUE_OR_MODIFIER = re.compile(
    r"(?:\s*(?:이번|해당|새로운|신규|예정된|계획된|추진\s*중인|"
    r"올해|내년|금년|내후년|상반기|하반기|\d{4}년|\d{1,2}월|[1-4]분기|초|말|이르면|늦어도|"
    r"미국|한국|국내|해외|뉴욕|나스닥|코스피|코스닥|증시|증권시장|시장|에서의|"
    r"(?:취소|철회|무산|보류|중단|불발|포기)(?:된|한)))+$"
)
_BARE_OWNER = re.compile(
    r"(?<![가-힣A-Za-z0-9])(?P<name>[가-힣A-Za-z][가-힣A-Za-z0-9&.-]{1,35}?)"
    r"(?:은|는|이|가|의)?\s*$"
)
_JOINT = re.compile(r"\s*(?:와|과|및|·|,|and|&)\s*", re.IGNORECASE)
_JOINT_END = re.compile(r"(?:와|과|및|·|,|\band|&)\s*$", re.IGNORECASE)
_NON_OWNER_END = re.compile(r"(?:에도|에서|에게|으로|보다|통해|따라|위해|때문에|하여|하며|하고)$")
_NEW_SUBJECT_CLAUSE = re.compile(r"(?:고|며|지만|는데|한편)[,\s]*$|,\s*$")
_NON_OWNER = frozenset(
    {
        "기업",
        "회사",
        "업체",
        "당사",
        "그",
        "해당",
        "이번",
        "신규",
        "중복",
        "재",
        "주주",
        "투자자",
        "통한",
        "위한",
        "통해",
        "관련",
        "향후",
        "예정",
        "계획",
        "추진",
        "가능성",
        "필요성",
        "여부",
        "성공적인",
        "대규모",
        "추가",
        "자회사",
        "모회사",
        "검토",
        "자금",
        "조달",
        "투자금",
        "유치",
        "IPO",
        "기업공개",
        "상장",
        "이슈",
        "밸류에이션",
        "기업가치",
        "주주가치",
        "논의",
        "분석",
        "소식",
        "전망",
        "기대",
        "규모",
        "영향",
        "투입",
        "자본집행",
        "있어",
        "약",
    }
)


def _norm(value: str) -> str:
    return " ".join(unicodedata.normalize("NFKC", value).casefold().split())


_ALIASES = {
    _norm(alias): _norm(canonical)
    for canonical, aliases in _COMPANY_ALIASES.items()
    for alias in aliases
}
_NON_OWNER = frozenset(_norm(value) for value in _NON_OWNER)


@dataclass(frozen=True)
class _Event:
    kind: str
    actors: frozenset[str]
    negative: bool


def _non_owner(value: str) -> bool:
    return (
        value in _NON_OWNER
        or re.sub(r"(?:와|과|를|을|의|은|는|이|가)$", "", value) in _NON_OWNER
        or bool(_NON_OWNER_END.search(value))
    )


def _owners(prefix: str) -> frozenset[str]:
    """Use the nearest owner, not every company mentioned in the sentence."""
    actors, _ = _actors(prefix)
    candidates = [(a.span.start, a.span.end, a.value) for a in actors]
    trimmed = _VENUE_OR_MODIFIER.sub("", prefix.rstrip())
    bare = _BARE_OWNER.search(trimmed)
    if bare is not None:
        name = _norm(bare["name"])
        if not _non_owner(name):
            # Prefer the existing alias span when the bare pattern captured a
            # suffix of a multiword known name or its grammatical particle.
            span = bare.span("name")
            if not any(start <= span[0] and span[1] <= end for start, end, _ in candidates):
                candidates.append((*span, _ALIASES.get(name, name)))
    candidates = sorted({item for item in candidates if not _non_owner(item[2])})
    if not candidates:
        return frozenset()
    last = candidates[-1]
    names = {last[2]}
    # The shared actor lexer recognizes particles such as A와 B의, but a bare
    # A in A 및 B의 needs this immediately adjacent coordination check.
    cursor = last[0]
    while joint := _JOINT_END.search(prefix[:cursor]):
        preceding = _BARE_OWNER.search(prefix[: joint.start()].rstrip())
        if preceding is None:
            break
        name = _norm(preceding["name"])
        if _non_owner(name):
            break
        start, end = preceding.span("name")
        existing = next((item for item in candidates if item[0] <= start and end <= item[1]), None)
        names.add(existing[2] if existing is not None else _ALIASES.get(name, name))
        cursor = existing[0] if existing is not None else start
    for prior in reversed(candidates[:-1]):
        if not _JOINT.fullmatch(prefix[prior[1] : last[0]]):
            break
        names.add(prior[2])
        last = prior
    return frozenset(names)


def _funding_amount_spans(clause: str) -> Iterator[tuple[int, int, str]]:
    """Only a currency amount directly being raised establishes financing.

    An optional conversion must also be a complete currency amount; an amount
    somewhere in a procurement sentence cannot establish a funding event.
    """
    for pattern in (_CURRENCY_AMOUNT, _PREFIX_CURRENCY_AMOUNT):
        for amount in pattern.finditer(clause):
            after = _FUNDING_AFTER_AMOUNT.match(clause, amount.end())
            if after is None:
                continue
            conversion = after["conversion"]
            if conversion is not None and not any(
                currency.fullmatch(conversion.strip())
                for currency in (_CURRENCY_AMOUNT, _PREFIX_CURRENCY_AMOUNT)
            ):
                continue
            yield amount.start(), after.end(), "funding"


def _events(value: str) -> Iterator[_Event]:
    for clause in _BREAK.split(unicodedata.normalize("NFKC", value)):
        matches = sorted(
            {(match.start(), match.end(), match.lastgroup) for match in _EVENT.finditer(clause)}
            | set(_funding_amount_spans(clause))
        )
        for index, (start, event_end, kind) in enumerate(matches):
            end = matches[index + 1][0] if index + 1 < len(matches) else len(clause)
            tail = clause[event_end:end]
            # A later subject's canceled business is not this issuer's canceled
            # IPO. Limit polarity to the event's own coordinated clause.
            tail_actors, roles = _actors(tail)
            for actor in tail_actors:
                if roles.get((actor.span.start, actor.span.end)) in {"은", "는", "이", "가"} and (
                    _NEW_SUBJECT_CLAUSE.search(tail[: actor.span.start])
                ):
                    tail = tail[: actor.span.start]
                    break
            if (
                kind == "funding"
                and _FINANCE_ANALYSIS.match(clause, event_end)
                and not _FINANCE_CHANGE.search(tail)
            ):
                # Financing capacity/structure can be an analytical consequence
                # of valuation; it does not assert a financing transaction.
                continue
            negative = bool(_NEGATIVE_EVENT.search(tail)) or bool(
                re.search(r"(?:취소|철회|무산|중단|보류|불발|포기)(?:된|한)\s*$", clause[:start])
            )
            yield _Event(kind, _owners(clause[:start]), negative)


def corporate_event_mismatches(value: str, sources: Sequence[str]) -> list[str]:
    """Return only unsupported corporate-event/owner bindings, without edits."""
    supported = [event for source in sources for event in _events(source)]
    mismatches = []
    for event in _events(value):
        if any(
            event.kind == candidate.kind
            and event.negative == candidate.negative
            and (not event.actors or event.actors <= candidate.actors)
            for candidate in supported
        ):
            continue
        label = {
            "ipo": "IPO·기업공개·상장",
            "funding": "자금 조달",
            "equity": "유상증자",
            "debt": "회사채 발행",
        }[event.kind]
        owner = ", ".join(sorted(event.actors)) or "선택 원문"
        mismatches.append(_PREFIX + owner + " → " + label)
    return list(dict.fromkeys(mismatches))
