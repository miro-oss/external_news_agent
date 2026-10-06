"""Conservative, report-only prose and temporal guards.

Article claims remain subject to the shared factual guard. Report limitations and
watch labels are interpretations, so their polarity is not an article assertion.
These rules reject detectable factual reversals; they are not a semantic judge.
"""

import calendar
import re
from datetime import date, timedelta

from app.core.evidence import factual_mismatches
from app.schemas.report_insight import ReportInsightRequest

_POLARITY_MESSAGE = "근거와 반대되는 부정 표현이 포함되어 있습니다."
_NUMERIC_CONTEXT_MESSAGE = "근거와 연결이 다른 숫자: "
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
    # Interpretive prose without an asserted event may discuss missing relevance,
    # a status question, uncertainty, or a missing-information limitation. The
    # source's positive/negative event polarity does not constrain that analysis.
    has_factual_state = bool(states)
    remaining = list(mismatches)
    if any(item.startswith(_NUMERIC_CONTEXT_MESSAGE) for item in remaining) and (
        _independent_parallel_numbers(value, source)
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
