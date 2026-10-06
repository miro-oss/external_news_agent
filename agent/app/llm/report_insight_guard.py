"""Conservative, report-only prose and temporal guards.

Article claims remain subject to the shared factual guard. Report limitations and
watch labels are interpretations, so their polarity is not an article assertion.
These rules reject detectable factual reversals; they are not a semantic judge.
"""

import calendar
import re
from datetime import date, timedelta

from app.core.evidence import _companies, factual_mismatches
from app.llm.report_insight_year_ranges import supported_year_range_context
from app.schemas.report_insight import ReportInsightRequest

_POLARITY_MESSAGE = "근거와 반대되는 부정 표현이 포함되어 있습니다."
_NUMERIC_CONTEXT_MESSAGE = "근거와 연결이 다른 숫자: "
_UNSUPPORTED_COMPANY_MESSAGE = "근거에서 확인되지 않는 기업명: "
_MICRON_UNIT = re.compile(
    r"(?<![A-Za-z가-힣])마이크론(?=\s*단위(?:의|로|에서|마다|를|는|가|에)?(?:$|[\s,.;。]))"
)
# An explicit ordered series pairs years with percentages; the following fact
# does not inherit the last year (e.g. a separately stated product yield).
_YEAR_PERCENT_SERIES = re.compile(
    r"(?P<reporter>(?:원문|기사|자료)(?:은|는)\s+)"
    r"(?P<first_year>20\d{2})\s*→\s*(?P<second_year>20\d{2})\s*년\s+"
    r"(?P<metric>[^\d\n;:,.%→]{1,40}?)\s*"
    r"(?P<first_percent>\d+(?:\.\d+)?)\s*%\s*→\s*"
    r"(?P<second_percent>\d+(?:\.\d+)?)\s*%(?![A-Za-z])"
)
_SIGNED_CONTRACT = re.compile(
    r"\bsigned\s+(?:(?:an?|the)\s+)?"
    r"(?:(?:new|expansive|strategic|definitive|binding|commercial|"
    r"supply|licensing|distribution|development|collaboration|[a-z0-9]+-year),?\s+){0,4}"
    r"(?:agreement|contract)\b(?!\s+(?:proposal|draft|plan|template|outline)\b)",
    re.IGNORECASE,
)
_NONFACTUAL_SIGNING = re.compile(
    r"\b(?:if|unless|whether|when|once|until|would|could|may|might|will|"
    r"plans?|planned|intends?|intended|expects?|expected|hopes?|hoped|"
    r"denied|denies|deny|disputed|false|untrue)\b",
    re.IGNORECASE,
)
_NEGATED_SIGNING = re.compile(r"(?:\bnot|\bnever|n't)(?:\s+[a-z]+){0,4}\s+$", re.IGNORECASE)
_MADE_CONTRACT = re.compile(r"계약(?:을)?\s*맺었다(?=$|[\s.!?;]|고)")
_UNMADE_CONTRACT = re.compile(r"계약(?:을)?\s*맺지\s*않(?:았다|는다)(?=$|[\s.!?;]|고)")
_SIGNED_LEASE = re.compile(
    r"\bsigned\s+(?:(?:an?|the)\s+)?(?:long-term\s+)?lease\b"
    r"(?!\s+(?:proposal|draft|plan|template|outline)\b)",
    re.IGNORECASE,
)
_CONTRACT_SENTENCE_BREAK = re.compile(r"(?<!\d)[.!?](?!\d)|[。！？;\n]")
_NONFACTUAL_CONTRACT_CONTEXT = re.compile(
    r"만약|가정|전제|예시|부인|부정|거짓|미체결|사실(?:이|은)?\s*아니|"
    r"\b(?:denied|denies|deny|disputed|false|untrue|hypothetical|suppose|assume|assuming)\b",
    re.IGNORECASE,
)
_UNASSERTED_CONTRACT_REPORT = re.compile(
    r"고\s*(?:(?:보도|공시|발표|확인)(?:되|하)지|알려지지)\s*않"
)
# An explicit report summary can coordinate two independent nominal facts.
# Do not split ordinary conjunctions: they may share an actor, date or action.
_PARALLEL_SUMMARY = re.compile(
    r"(?:원문|기사(?:\s*내용)?|보고서|자료)(?:은|는|이|가)\s+"
    r"(?P<left>.+(?:기대|전망|계획|도입|전환|변경))(?:과|와)\s+"
    r"(?P<right>[A-Za-z가-힣][A-Za-z0-9가-힣&.-]*의\s+"
    r".+(?:기대|전망|계획|도입|전환|변경))(?:을|를)\s+"
    r"각각\s+(?:언급|설명|제시)(?:한다|했다|하였다|하고\s*있다)[.!。]?"
)
_SHARED_PARALLEL_CONTEXT = re.compile(
    r"[\n\"'“”‘’;；]|(?<!\d)[.!?](?!\d)|"
    r"(?<!\d)(?:19|20)\d{2}(?:[-/]\d{1,2}){1,2}(?!\d)|"
    r"(?:\d\s*(?:년|월|일|분기)|오늘|내일|올해|내년|작년|지난해|상반기|하반기)|"
    r"같은|동일|해당|상기|전술|전자의|후자의|"
    r"(?:^|\s)(?:그|이|위|앞선)(?:\s|러한|런|$)|"
    r"에\s*따르면|(?:밝힌|말한|설명한|전한|발표한)|(?:측|관계자)의|"
    r"(?:이라는|라는|이라고|라고)"
)
# State nouns (미완료, 미제공) and conjugated predicates share the same polarity.
# A noun such as a watch topic '인증 완료 여부' is deliberately not a predicate.
_FACT_STATES = {
    "completion": (
        re.compile(
            r"(?:미완료|(?:완료|완공|준공)(?:가|이|는|을|를)?\s*(?:되지|하지)\s*않|(?:완료|완공|준공)(?:가|이)?\s*(?:안|미))"
        ),
        re.compile(
            r"(?:완료|완공|준공)(?:했|됐|되었|하였|한|된|됨|임|했다)|(?:완료|완공|준공)(?:를|의)?\s*발표"
        ),
    ),
    "contract": (
        re.compile(
            r"(?:계약|체결)(?:이|은|을|를)?\s*(?:없|취소|무산|되지\s*않|하지\s*않)|(?:체결|계약)(?:이|은)?\s*미(?:완료|체결)"
        ),
        re.compile(
            r"체결(?:했|됐|되었|하였|한|된|됨)|계약(?:이|은)?\s*(?:성립|확정)|계약(?:이|은)?\s*(?:별도\s*)?사실"
        ),
    ),
    "halt": (
        re.compile(r"(?:중단|취소|무산)(?:하|되)지\s*않|(?:중단|취소)(?:이|은|가)?\s*없"),
        re.compile(r"(?:중단|취소|무산)(?:했|됐|되었|하였|한|된|되어|돼|됨|임|되므로)"),
    ),
    "start": (
        re.compile(
            r"(?:착수|시작|개시|돌입|설치)(?:하|되)지\s*않|(?:착수|시작|설치)(?:가|는|이)?\s*미완료"
        ),
        re.compile(r"(?:착수|시작|개시|돌입|설치)(?:했|됐|되었|하였|한|된|됨)"),
    ),
    "production": (
        re.compile(
            r"(?:양산|가동|출하)(?:을|를)?\s*(?:하|되)지\s*않|"
            r"(?:양산|가동|출하)(?:이|은|가|을|를)?\s*"
            r"(?:(?:즉시|현재|일시적으로|잠정적으로)\s*)?중단"
        ),
        re.compile(r"(?:양산|가동|출하)(?:을|를)?\s*(?:했다|했|개시했다|돌입했다|중이다)"),
    ),
    "information": (
        re.compile(
            r"(?:정보|자료|금액|근거).{0,20}(?:미제공|미공개|부재|제공되지\s*않|공개되지\s*않)|(?:미제공|미공개)(?:은|는|이|된)?"
        ),
        re.compile(r"(?:정보|자료|금액|근거).{0,20}(?:제공|공개)(?:했|됐다|되었다|되었|한|된|됨)"),
    ),
}
# Only explicitly stated event predicates can inherit the shared modality ladder.
# "계약 금액 미제공", "인증 완료 여부" and "설치 확정 불가" do not assert
# a signed contract, completed certification, or a started installation.
_ASSERTION = re.compile(
    r"(?:완료|완공|준공|착수|시작|개시|돌입|체결|승인|확정|결정|설치|공급|발표)"
    r"(?:했|됐|되었|하였|한|된|함|됨|했다)|"
    r"(?:양산|가동|출하)(?:을|를)?\s*(?:했다|개시했다|돌입했다|중이다)"
)
_HYPOTHETICAL_SUFFIX = re.compile(r"(?:다)?(?:면|\s*(?:경우|때)|(?:고|다고)\s*(?:가정|전제))")
_NEGATIVE_HYPOTHETICAL_SUFFIX = re.compile(
    r"(?:으)?면|(?:될|할|되는|하는|된|한)\s*(?:경우|때)|\s*여부"
)
_DATE = re.compile(
    r"(?<!\d)(?P<year>20\d{2})(?:\s*년\s*|[-/])"
    r"(?P<month>\d{1,2})(?:\s*월(?:\s*(?P<day>\d{1,2})\s*일)?|"
    r"[-/](?P<iso_day>\d{1,2}))(?!\d)"
)
_YEAR = re.compile(r"(?<!\d)(20\d{2})\s*년(?!\s*\d{1,2}\s*월)")
_DEADLINE = re.compile(r"기한|마감|까지|만료|종료|deadline|\bdue\b", re.IGNORECASE)
_IMMINENT = re.compile(
    r"임박|다가오|다가올|다가온|가까워지|얼마\s*남지|곧\s*(?:도래|마감)|upcoming|imminent|approaching",
    re.IGNORECASE,
)
_CURRENT_ACTION = re.compile(
    r"(?:현재|지금|여전히|아직).{0,35}(?:중단|장애|차질|미해결|미완료|지연|미복구)|"
    r"(?:중단|장애|차질|지연)(?:된|이|은)?\s*(?:상태|중)|"
    r"(?:복구|해결)(?:하|되)지\s*않|즉시\s*(?:적용|중단|시행|전환|대응)|"
    r"(?:현재|지금).{0,30}(?:적용|시행|전환|대응)"
)


def _contract_alias_events(value: str):
    """Narrow observed past-tense synonyms, not proposed or quoted contracts."""
    for pattern, positive in (
        (_MADE_CONTRACT, True),
        (_UNMADE_CONTRACT, False),
        (_SIGNED_LEASE, True),
    ):
        for match in pattern.finditer(value):
            prefix = _CONTRACT_SENTENCE_BREAK.split(value[: match.start()])[-1]
            rest = value[match.end() :]
            ending = _CONTRACT_SENTENCE_BREAK.search(rest)
            suffix = rest[: ending.start()] if ending else rest
            if ending and ending[0] in {"?", "？"}:
                continue
            clause = prefix + match[0] + suffix
            # The new aliases intentionally leave reported denials and quoted
            # propositions to the existing conservative unknown-state path.
            if any(mark in clause for mark in "\"'“”‘’「」『』") or (
                _NONFACTUAL_CONTRACT_CONTEXT.search(clause)
                or _HYPOTHETICAL_SUFFIX.match(suffix)
                or _UNASSERTED_CONTRACT_REPORT.match(suffix)
            ):
                continue
            if pattern is _SIGNED_LEASE:
                if _NEGATED_SIGNING.search(prefix):
                    yield False, clause
                    continue
                if _NONFACTUAL_SIGNING.search(prefix):
                    continue
            yield positive, clause


def factual_states(value: str) -> dict[str, set[bool]]:
    """Known asserted states, True for positive and False for negative."""
    states = {}
    for name, (negative, positive) in _FACT_STATES.items():
        values = set()
        negative_spans = [
            match.span()
            for match in negative.finditer(value)
            if not _NEGATIVE_HYPOTHETICAL_SUFFIX.match(value[match.end() :])
        ]
        if negative_spans:
            values.add(False)
        for match in positive.finditer(value):
            if any(start <= match.start() < end for start, end in negative_spans):
                continue
            if _HYPOTHETICAL_SUFFIX.match(value[match.end() :]):
                continue
            values.add(True)
        if values:
            states[name] = values
    # The supplied English sentence may establish the same signed-contract
    # state as a Korean translation. Modal/conditional clauses are not execution.
    for match in _SIGNED_CONTRACT.finditer(value):
        prefix = re.split(r"[.!?;\n]|\bbut\b", value[: match.start()])[-1]
        if _NEGATED_SIGNING.search(prefix) or re.search(r"\b(?:no|neither|not)\b", match[0], re.I):
            states.setdefault("contract", set()).add(False)
        elif not _NONFACTUAL_SIGNING.search(prefix):
            states.setdefault("contract", set()).add(True)
    for positive, _ in _contract_alias_events(value):
        states.setdefault("contract", set()).add(positive)
    return states


def has_asserted_event(value: str) -> bool:
    return any(
        not _HYPOTHETICAL_SUFFIX.match(value[match.end() :]) for match in _ASSERTION.finditer(value)
    )


def _independent_parallel_numbers(value: str, source: str) -> bool:
    """Recheck explicit, unshared facts instead of joining their numeric owners.

    The neutral reporting subject and the second fact's explicit possessive
    actor rule out an elided shared subject. Dates, attribution, back references
    and nested sentences keep the original conservative check. All factual checks
    run on both complete noun phrases, so an actor/number misbinding survives.
    """
    match = _PARALLEL_SUMMARY.fullmatch(value.strip())
    if match is None:
        return False
    facts = (match["left"], match["right"])
    if any(_SHARED_PARALLEL_CONTEXT.search(fact) for fact in facts):
        return False
    return all(not factual_mismatches(fact, source) for fact in facts)


def _ordered_year_percentages(value: str, source: str) -> bool:
    """Expand one explicit series only after verifying both ordered source pairs.

    Rechecking the entire expanded prose preserves every remaining number,
    company, date and polarity check. An ordinary conjunction or reversed pair
    cannot use this exception merely because all its numbers appear somewhere.
    """
    matches = list(_YEAR_PERCENT_SERIES.finditer(value))
    if len(matches) != 1:
        return False
    match = matches[0]
    pairs = [
        (match["first_year"], match["first_percent"]),
        (match["second_year"], match["second_percent"]),
    ]
    # No intervening year or percentage may replace the stated owner/value.
    gap = r"(?:(?!20\d{2}\s*년|\d+(?:\.\d+)?\s*%)[^\n;.!?])*?"
    source_series = gap.join(
        rf"(?<!\d){year}\s*년{gap}(?<![\d.]){re.escape(percent)}\s*%" for year, percent in pairs
    )
    if re.search(source_series, source) is None:
        return False
    expanded = "; ".join(
        f"{match['reporter']}{year}년 {match['metric']} {percent}%" for year, percent in pairs
    )
    scoped = value[: match.start()] + expanded + "; " + value[match.end() :]
    return not factual_mismatches(scoped, source)


def report_prose_mismatches(
    value: str,
    source: str,
    mismatches: list[str],
    *,
    modality_reason: str | None,
    topic: bool = False,
) -> list[str]:
    """Scope report interpretations while retaining source-bound factual checks."""
    states = factual_states(value)
    source_states = factual_states(source)
    source_aliases = list(_contract_alias_events(source))
    # Interpretive prose without an asserted event may discuss missing relevance,
    # a status question, uncertainty, or a missing-information limitation. The
    # source's positive/negative event polarity does not constrain that analysis.
    has_factual_state = bool(states)
    remaining = list(mismatches)
    if _MICRON_UNIT.search(value) and any(
        item.startswith(_UNSUPPORTED_COMPANY_MESSAGE) for item in remaining
    ):
        # Recheck only entity diagnostics after disambiguating the literal
        # physical-unit phrase. Keep every original number/date/state error,
        # and keep Micron when another occurrence names the company itself.
        entity_checked = factual_mismatches(_MICRON_UNIT.sub("μm", value), source)
        remaining = [
            item for item in remaining if not item.startswith(_UNSUPPORTED_COMPANY_MESSAGE)
        ] + [item for item in entity_checked if item.startswith(_UNSUPPORTED_COMPANY_MESSAGE)]
    if any(item.startswith(_NUMERIC_CONTEXT_MESSAGE) for item in remaining) and (
        _independent_parallel_numbers(value, source)
        or _ordered_year_percentages(value, source)
        or supported_year_range_context(value, source)
    ):
        remaining = [item for item in remaining if not item.startswith(_NUMERIC_CONTEXT_MESSAGE)]
    if _POLARITY_MESSAGE in remaining:
        asserted_reversal = any(
            name in source_states and not values <= source_states[name]
            for name, values in states.items()
        )
        substantive_states = {
            name: values for name, values in states.items() if name != "information"
        }
        unknown_state = any(name not in source_states for name in substantive_states)
        if (
            (topic and not has_asserted_event(value))
            or not has_factual_state
            or (not asserted_reversal and not unknown_state)
        ):
            remaining.remove(_POLARITY_MESSAGE)
    # Do not treat the bare contract/approval nouns of analysis or a topic as an
    # affirmative event. Explicit assertion checks in the service still run.
    if modality_reason and (topic or not has_asserted_event(value)):
        remaining = [item for item in remaining if item != modality_reason]
    # Synonymous negative event descriptions must not gain an affirmative state
    # just because the generic article regex does not recognize the prefix 미-.
    if modality_reason and states and all(values == {False} for values in states.values()):
        remaining = [item for item in remaining if item != modality_reason]
    if any(
        name in source_states and not values <= source_states[name]
        for name, values in states.items()
    ):
        if _POLARITY_MESSAGE not in remaining:
            remaining.append(_POLARITY_MESSAGE)
    if any(name != "information" and name not in source_states for name in states):
        remaining.append("근거에서 확인되지 않는 완료·착수·계약·중단 사실입니다.")
    if source_aliases and True in states.get("contract", set()):
        # A newly recognized contract cannot borrow an organization that only
        # occurs in an unrelated source sentence. Reuse the factual guard's
        # company normalization, preserving its existing unsupported-actor rule.
        asserted_actors = set().union(
            *(
                _companies(clause)
                for clause in _CONTRACT_SENTENCE_BREAK.split(value)
                if True in factual_states(clause).get("contract", set())
            )
        )
        source_actors = set().union(
            *(
                _companies(clause)
                for clause in _CONTRACT_SENTENCE_BREAK.split(source)
                if True in factual_states(clause).get("contract", set())
            )
        )
        if asserted_actors - source_actors:
            remaining.append("근거의 계약 체결 주체와 다른 기업명입니다.")
    # Stored sources can use short anonymized organization names outside the
    # shared company dictionary. They remain factual entities, including when a
    # different finding is the only place that names them.
    actor = re.compile(
        r"(?<![A-Za-z0-9])([A-Z](?:[A-Za-z0-9]+)?(?:운영사|유통사|사))(?![A-Za-z0-9])"
    )
    if set(actor.findall(value)) - set(actor.findall(source)):
        remaining.append("근거에서 확인되지 않는 기업명입니다.")
    return remaining


def report_reference_date(request: ReportInsightRequest) -> date | None:
    if request.report.report_end_date is not None:
        return request.report.report_end_date
    if request.report.report_date is not None:
        return request.report.report_date
    return max(
        (finding.published_at for finding in request.findings if finding.published_at),
        default=None,
    )


def _date_intervals(value: str, published_at: date | None) -> list[tuple[date, date]]:
    intervals = []
    for match in _DATE.finditer(value):
        year, month = int(match["year"]), int(match["month"])
        raw_day = match["day"] or match["iso_day"]
        try:
            first = date(year, month, int(raw_day) if raw_day else 1)
            last = first if raw_day else date(year, month, calendar.monthrange(year, month)[1])
        except ValueError:
            continue
        intervals.append((first, last))
    for match in _YEAR.finditer(value):
        year = int(match[1])
        intervals.append((date(year, 1, 1), date(year, 12, 31)))
    if published_at:
        if "내일" in value:
            intervals.append((published_at + timedelta(days=1), published_at + timedelta(days=1)))
        for term, offset in (("올해", 0), ("내년", 1), ("지난해", -1), ("작년", -1)):
            if term in value:
                year = published_at.year + offset
                intervals.append((date(year, 1, 1), date(year, 12, 31)))
    return intervals


def validate_report_time(
    value: str,
    refs: list[str],
    request: ReportInsightRequest,
    *,
    urgency: int | None = None,
    conditional: bool = False,
) -> None:
    """Reject a past source deadline presented as an upcoming current deadline.

    A month/year is past only after its last day. A missing report date is not
    replaced with wall-clock time. Current explicit unresolved states may justify
    urgency even when the historical deadline itself has elapsed.
    """
    anchor = report_reference_date(request)
    if anchor is None or conditional:
        return
    claims = {
        claim.id: (finding, claim) for finding in request.findings for claim in finding.claims
    }
    deadline_intervals = []
    source_parts = []
    for ref in refs:
        finding, claim = claims[ref]
        source = "\n".join(
            [
                claim.text,
                *[
                    sentence.text
                    for sentence in finding.sentences
                    if sentence.index in claim.evidence_sentence_ids
                ],
            ]
        )
        source_parts.append(source)
        # Resolve relative dates against each original article, never the report.
        for clause in re.split(r"[。;；\n]|(?<!\d)[.!?](?!\d)", source):
            if _DEADLINE.search(clause):
                deadline_intervals.extend(_date_intervals(clause, finding.published_at))
    if not deadline_intervals:
        return
    past = [interval for interval in deadline_intervals if interval[1] < anchor]
    future = [interval for interval in deadline_intervals if interval[1] >= anchor]
    if not past:
        return
    if _IMMINENT.search(value):
        mentioned = _date_intervals(value, None)
        explicitly_past = any(last < anchor for _, last in mentioned)
        # If another referenced deadline is future, a date-free "임박" cannot
        # safely be attributed to the past deadline by a lexical rule.
        if explicitly_past or not future:
            raise ValueError(
                "이미 지난 근거 기한을 리포트 기준 시점의 임박한 마감으로 표현할 수 없습니다."
            )
    if urgency == 3 and not future and not _CURRENT_ACTION.search("\n".join(source_parts)):
        raise ValueError(
            "이미 지난 기한만으로 urgency=3을 판정할 수 없습니다. 현재 대응 근거가 필요합니다."
        )


_CONTRADICTION = re.compile(r"상충|충돌|엇갈리|엇갈린|불일치")


def has_blanket_insufficient_headline(value: str) -> bool:
    """A missing specific condition is compatible with otherwise related facts."""
    return bool(
        re.fullmatch(
            r"\s*(?:(?:이|해당)\s*관점(?:의|에서)?\s*)?관련\s*근거(?:가|는)?\s*"
            r"(?:부족(?:합니다|하다|함|해요)?|없(?:습니다|다|음|어요)?)[.!。…\s]*",
            value,
        )
    )


def validate_report_citations(
    value: str,
    refs: list[str],
    source: str,
    *,
    conditional: bool = False,
    topic: bool = False,
) -> None:
    """A claim that two sources conflict needs both sides, not a lone citation."""
    if conditional or (topic and not has_asserted_event(value)):
        return
    if _CONTRADICTION.search(value) and not _CONTRADICTION.search(source) and len(set(refs)) < 2:
        raise ValueError("원문에 명시되지 않은 상충 판정에는 양쪽 claim 근거가 필요합니다.")
