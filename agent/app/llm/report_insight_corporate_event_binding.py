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
from app.llm.report_insight_fact_graph import _NON_ACTORS, _actors

_PREFIX = "근거의 주체·사건 연결과 다릅니다: "
_EVENT = re.compile(
    r"(?P<ipo>(?<![A-Za-z0-9])IPO(?![A-Za-z0-9])|기업\s*공개|"
    r"(?<![가-힣])(?:신규\s*|중복\s*|재)?상장"
    r"(?!\s*(?:회사|기업|주식|종목)|사(?:들)?(?=$|[\s,.;]|(?:의|는|가|를|은|이|을)(?=\s))))|"
    r"(?P<funding>자금(?:을)?\s*조달|투자금(?:을)?\s*유치)|"
    r"(?P<share_issue>신주(?:를)?\s*발행|새\s*주식(?:을)?\s*발행)|"
    r"(?P<equity>유상\s*증자)|(?P<debt>회사채(?:를)?\s*발행)",
    re.IGNORECASE,
)
_BREAK = re.compile(r"(?<!\d)[.!?](?!\d)|[。;；\n]")
_FUNDING_AFTER_AMOUNT = re.compile(
    r"\s*(?:\((?P<conversion>[^()\n]{1,60})\))?\s*(?:을|를)?\s*조달"
    r"(?=$|[^가-힣A-Za-z0-9]|하|할|했|되|된|됐|될|돼|함|한)"
)
_FINANCE_ATTRIBUTE = r"(?:여력|구조|조건|비용|환경|위험|수요|역량|전략|방안|업무|경로)"
_ANALYTICAL_OUTCOMES = frozenset({"영향", "효과", "결과", "평가", "변화"})
_FINANCE_NOMINAL = re.compile(
    r"(?:(?:자금|조달|투자|공모|상장|발행|자본|재원|ipo|대표|주관사|평가)+)?"
    rf"(?:{_FINANCE_ATTRIBUTE}|{'|'.join(sorted(_ANALYTICAL_OUTCOMES))}|"
    r"규모|금액|계획|일정|전망|예상치|집행|실행|선정|선임|지정)"
    r"(?:여부|가능성)?"
)
_FINANCE_ANALYSIS = re.compile(
    rf"\s*(?:{_FINANCE_ATTRIBUTE}|관련\s*요인)(?=$|[^가-힣]|"
    r"(?:을|를|은|는|이|가|의|에|과|와)(?=\s))"
)
_FINANCE_CHANGE = re.compile(
    r"직접(?:적)?(?:인)?\s*영향|구조(?:를|가|는|의)?\s*(?:변경|변화|재편)|"
    r"(?:바꿨|바뀌었|재편했|재편됐|변경했|변경됐)|"
    rf"{_FINANCE_ATTRIBUTE}(?:을|를|은|는|이|가|의|에)?\s*"
    r"(?:개편|개선|확대|축소|증가|감소|상승|하락|확정|실행)"
    r"(?!\s*(?:여부|가능성)(?=$|[^가-힣]|을|를|은|는|이|가))"
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
    r"(?P<particle>은|는|이|가|의)?\s*$"
)
_OBLIQUE_PARTICLE = re.compile(r"(?:에서|에게|으로|부터|까지|보다|처럼|에|로|을|를)(?=\s|$)")
_BARE_OBLIQUE_END = re.compile(r"(?:으로|로|에)$")
_BARE_CONNECTIVE_END = re.compile(r"(?:라면|다면|으?므로)$")
_REPORTED_PREDICATE_END = re.compile(r"(?:다고|라고)\s+[가-힣]{2,}해\s*$")
_OWNER_DESCRIPTION = re.compile(r"(?:자회사|모회사|계열사|관계사|기업|회사|법인)\s*$")
_JOINT = re.compile(r"\s*(?:와|과|및|·|,|and|&)\s*", re.IGNORECASE)
_JOINT_END = re.compile(r"(?:와|과|및|·|,|\band|&)\s*$", re.IGNORECASE)
_NON_OWNER_END = re.compile(r"(?:에도|에서|에게|으로|보다|통해|따라|위해|때문에|하여|하며|하고)$")
_PREDICATE_OWNER = re.compile(
    r"(?:[가-힣]{2,}(?:된|되는|하는|했다|한다|된다)|"
    r"[가-힣]+(?:(?:았|었|했|됐)(?:고|으며)|(?:하|되)며))$"
)
_PREDICATE_STEM = re.compile(r"[가-힣]{2,}(?:되|하)$")
_SOURCE_CARRIERS = frozenset({"기사", "원문", "보도", "보고서", "자료"})
_NOMINALIZERS = frozenset({"사실", "내용", "점", "것", "바"})
_SOURCE_NOMINAL = re.compile(
    rf"(?:{'|'.join(sorted(_SOURCE_CARRIERS))})(?:{'|'.join(sorted(_NOMINALIZERS))})"
)
_VALUATION_NOUNS = frozenset({"할인", "프리미엄", "재평가"})
_DISCOURSE_MODIFIERS = frozenset({"다만", "또한", "특히", "반면", "한편", "따라서", "그러나"})
_SOURCE_REFERENCE_CLAUSE = re.compile(
    r"(?:^|,)\s*(?:(?:동일|같은|해당|그|이)\s+)?"
    rf"(?:{'|'.join(sorted(_SOURCE_CARRIERS))})에서\s+"
    r"(?:언급|보도|제시|거론|설명)(?:된|되는)\s+"
)
_ANALYSIS_NAME = r"[가-힣A-Za-z][가-힣A-Za-z0-9&.-]{1,35}"
_COMPLETED_SOURCE_CLAUSE = re.compile(
    r"(?:(?:원문\s*)?선택\s*근거에\s*따르면\s*)?"
    rf"{_ANALYSIS_NAME}의\s*(?:(?:밸류에이션|기업가치|주가)(?:이|가|은|는)\s*"
    rf"(?:{_ANALYSIS_NAME}(?:에)?\s*(?:대비|보다)\s*)?"
    r"(?:할인|저평가|고평가)(?:된|받는|받은)\s*(?:상태로\s*)?"
    r"(?:분석|평가)|(?:사업\s*)?(?:전망|실적|동향)(?:이|가)\s*"
    r"(?:분석|평가|소개|보도))(?:되며|됐으며|되었으며|되고|됐고|되었고)\s*$"
)
_NEW_SUBJECT_CLAUSE = re.compile(r"(?:고|며|지만|는데|한편)[,\s]*$|,\s*$")
_NON_OWNER = frozenset(
    {
        "기업",
        "회사",
        "업체",
        "업계",
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
        "인용해",
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
        "조달자금",
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
        "투입",
        "자본집행",
        "있어",
        "약",
        "특정",
        "주어",
    }
)


def _norm(value: str) -> str:
    return " ".join(unicodedata.normalize("NFKC", value).casefold().split())


_ALIASES = {
    _norm(alias): _norm(canonical)
    for canonical, aliases in _COMPANY_ALIASES.items()
    for alias in aliases
}
_NON_OWNER = frozenset(
    _norm(value)
    for value in _NON_OWNER
    | _NON_ACTORS
    | _SOURCE_CARRIERS
    | _NOMINALIZERS
    | _VALUATION_NOUNS
    | _ANALYTICAL_OUTCOMES
    | _DISCOURSE_MODIFIERS
)


@dataclass(frozen=True)
class _Event:
    kind: str
    actors: frozenset[str]
    negative: bool


def _non_owner(value: str, particle: str | None = None) -> bool:
    if value in _ALIASES:
        return False
    nominal_forms = {value, re.sub(r"(?:와|과|를|을|의|은|는|이|가)$", "", value)}
    if particle is not None:
        nominal_forms.add(value + particle)
    return (
        # Only whole combinations of financial nouns are excluded. An unknown
        # issuer containing one of these words (조달테크, 선정산업) remains a name.
        any(
            form in _NON_OWNER
            or _FINANCE_NOMINAL.fullmatch(form)
            or _SOURCE_NOMINAL.fullmatch(form)
            for form in nominal_forms
        )
        or bool(_NON_OWNER_END.search(value))
        or bool(_PREDICATE_OWNER.fullmatch(value))
        or bool(_JOINT.fullmatch(value))
        # The permissive bare-name fallback can parse 연결되는 as the name
        # 연결되 plus topic particle 는; retain the shared lexer's verb guard.
        or (particle == "는" and bool(_PREDICATE_STEM.fullmatch(value)))
    )


def _owners(prefix: str) -> frozenset[str]:
    """Use the nearest owner, not every company mentioned in the sentence."""
    # Only a completed, separate assessment/report ends inherited ownership.
    # A source modifier does not end a company's continuing plan/subject:
    # "가온의 계획은, 기사에서 언급된 IPO를 추진하는 것이다" still means 가온.
    if reference := _SOURCE_REFERENCE_CLAUSE.search(prefix):
        preceding, following = prefix[: reference.start()], prefix[reference.end() :]
        prefix = (
            following
            if _COMPLETED_SOURCE_CLAUSE.fullmatch(preceding.strip())
            # Preserve the modifier and its preceding subject. The separating
            # space lets the actor lexer read an unknown name before a comma.
            else preceding + " " + prefix[reference.start() :]
        )
    actors, roles = _actors(prefix)
    candidates = [
        (a.span.start, a.span.end, a.value)
        for a in actors
        if not _OBLIQUE_PARTICLE.match(prefix, a.span.end)
    ]
    trimmed = _VENUE_OR_MODIFIER.sub("", prefix.rstrip())
    bare = _BARE_OWNER.search(trimmed)
    if bare is not None:
        name = _norm(bare["name"])
        # A trailing venue or means ("나스닥에", "기업가치로") must not
        # replace an explicit issuer. Keep unmarked names at the start of a
        # clause and names with a subject/possessive particle, e.g. 파블로의.
        oblique = (
            candidates
            and bare["particle"] is None
            and name not in _ALIASES
            and _BARE_OBLIQUE_END.search(name)
            and not _OWNER_DESCRIPTION.search(trimmed[: bare.start("name")])
            and not _JOINT_END.search(trimmed[: bare.start("name")])
        )
        connective = (
            bare["particle"] is None
            and name not in _ALIASES
            and _BARE_CONNECTIVE_END.search(name)
            and not _OWNER_DESCRIPTION.search(trimmed[: bare.start("name")])
        )
        reporting_predicate = (
            bare["particle"] is None
            and name not in _ALIASES
            and _REPORTED_PREDICATE_END.search(trimmed)
        )
        if (
            not _non_owner(name, bare["particle"])
            and not oblique
            and not connective
            and not reporting_predicate
        ):
            # Prefer the existing alias span when the bare pattern captured a
            # suffix of a multiword known name or its grammatical particle.
            span = bare.span("name")
            if not any(start <= span[0] and span[1] <= end for start, end, _ in candidates):
                candidates.append((*span, _ALIASES.get(name, name)))
    event_spans = [match.span() for match in _EVENT.finditer(prefix)]
    candidates = sorted(
        {
            item
            for item in candidates
            if not _non_owner(item[2])
            # A prior event name, including a fragment such as 발행 in 신주
            # 발행, must not become the owner of the next financial event.
            and not any(start <= item[0] and item[1] <= end for start, end in event_spans)
        }
    )
    # The shared lexer offers the left noun of any A와 B phrase as a joint
    # actor. Require an actual right-hand owner before promoting an unknown
    # left noun: in "IPO 준비와 향후 자금조달", 준비 coordinates events.
    candidates = [
        item
        for item in candidates
        if item[2] in _ALIASES
        or roles.get(item[:2]) is not None
        or not _JOINT.match(prefix[item[1] :])
        or any(
            item[1] < other[0] and _JOINT.fullmatch(prefix[item[1] : other[0]])
            for other in candidates
        )
    ]
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
        if _non_owner(name, preceding["particle"]):
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

    An optional conversion must also be a complete currency amount, allowing
    the approximation prefix 약. An amount somewhere in a procurement sentence
    cannot establish a funding event.
    """
    for pattern in (_CURRENCY_AMOUNT, _PREFIX_CURRENCY_AMOUNT):
        for amount in pattern.finditer(clause):
            after = _FUNDING_AFTER_AMOUNT.match(clause, amount.end())
            if after is None:
                continue
            conversion = after["conversion"]
            if conversion is not None:
                conversion = re.sub(r"^약\s*", "", conversion.strip(), count=1)
                if not any(
                    currency.fullmatch(conversion)
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
                # Merely reviewing financing capacity/structure is analytical.
                # Concrete changes to them still require selected evidence.
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
            "share_issue": "신주 발행",
            "equity": "유상증자",
            "debt": "회사채 발행",
        }[event.kind]
        owner = ", ".join(sorted(event.actors)) or "선택 원문"
        mismatches.append(_PREFIX + owner + " → " + label)
    return list(dict.fromkeys(mismatches))
