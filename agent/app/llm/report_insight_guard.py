"""Conservative, report-only prose and temporal guards.

Article claims remain subject to the shared factual guard. Report limitations and
watch labels are interpretations, so their polarity is not an article assertion.
These rules reject detectable factual reversals; they are not a semantic judge.
"""

import calendar
import re
from datetime import date, timedelta

from app.core.evidence import _COMPANY_ALIASES, _companies, factual_mismatches
from app.llm.report_insight_amount_context import supported_labeled_amount_context
from app.llm.report_insight_year_ranges import supported_year_range_context
from app.schemas.report_insight import ReportInsightRequest

_POLARITY_MESSAGE = "근거와 반대되는 부정 표현이 포함되어 있습니다."
_NUMERIC_CONTEXT_MESSAGE = "근거와 연결이 다른 숫자: "
_UNSUPPORTED_COMPANY_MESSAGE = "근거에서 확인되지 않는 기업명: "
_UNSUPPORTED_IDENTIFIER_MESSAGE = "근거에서 확인되지 않는 식별자: "
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
    r"(?:agreements?|contracts?)\b(?!\s+(?:proposals?|drafts?|plans?|templates?|outlines?)\b)",
    re.IGNORECASE,
)
_NONFACTUAL_SIGNING = re.compile(
    r"\b(?:if|unless|whether|when|once|until|would|could|may|might|will|"
    r"plans?|planned|intends?|intended|expects?|expected|hopes?|hoped|"
    r"denied|denies|deny|disputed|false|untrue)\b",
    re.IGNORECASE,
)
_SIGNING_NEGATION = re.compile(r"\b(?:not|never)\b|n't\b", re.IGNORECASE)
_NEGATED_SIGNING = re.compile(
    r"(?:\bnot|\bnever|n't)\s+"
    r"(?:(?:yet|ever|already|previously|formally|officially|actually|"
    r"fully|finally|have|has|had|been)\s+)*$",
    re.IGNORECASE,
)
_CLOSED_SIGNING_ADJUNCT = re.compile(
    r"^\s*(?:after|before|following|despite|although|even\s+though)\b[^,]*,\s*",
    re.IGNORECASE,
)
_DO_NEGATED_COORDINATE = re.compile(
    r"\b(?:did\s+not|didn't)\s+(?P<verb>[a-z]+)\b"
    r"(?P<object>[^,;.!?]*)\band\s+(?:[a-z]+ly\s+)*$",
    re.IGNORECASE,
)
# Only known non-clausal verb/object constructions may establish a separate
# finite coordinate. An unknown verb may embed a clause without 'that'
# (e.g. 'did not reveal they reviewed and signed'); it must stay unasserted.
_NONCLAUSAL_DO_VERB = re.compile(
    r"^(?:change|alter|adjust|raise|lower|increase|decrease|reduce|"
    r"remove|replace|renew|cancel|amend|update)$",
    re.IGNORECASE,
)
_EMBEDDED_SIGNING_CLAUSE = re.compile(
    r"\b(?:that|whether|if|who|whom|whose|which|when|where|why|how|to|"
    r"i|we|you|he|she|they|it|have|has|had|is|are|was|were|do|does|did|[a-z]+ed)\b",
    re.IGNORECASE,
)
_MADE_CONTRACT = re.compile(r"계약(?:을)?\s*맺었다(?=$|[\s.!?;]|고)")
_UNMADE_CONTRACT = re.compile(r"계약(?:을)?\s*맺지\s*않(?:았다|는다)(?=$|[\s.!?;]|고)")
_SIGNED_LEASE = re.compile(
    r"\bsigned\s+(?:(?:an?|the)\s+)?(?:long-term\s+)?leases?\b"
    r"(?!\s+(?:proposals?|drafts?|plans?|templates?|outlines?)\b)",
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
            r"(?:계약|체결)(?:이|은|을|를)?\s*(?:없|취소|무산|되지\s*않|하지\s*않)|"
            r"(?:체결|계약)(?:이|은)?\s*미(?:완료|체결)|계약\s*사실(?:이|은)?\s*없"
            r"(?=(?:다|습니다|었다|었습니다|음|고|으며|다고\s*밝혔다)(?=$|[\s,.!?;。]))"
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
_HYPOTHETICAL_SUFFIX = re.compile(
    r"(?:다)?면|(?:을|는|은)?\s*(?:경우|때)|"
    r"(?:고|다고|다는)\s*(?:가정|전제)|"
    r"\s*상태(?:라면|일\s*(?:경우|때))|"
    r"는지(?:를)?\s*(?:확인|점검|검토)|\s+시(?=$|[\s,.!?;。]|에는|에|엔)"
)
# The contract pattern ends at the nominal 확정/성립, before its verb ending.
# Inspect only the immediately attached conditional ending; a condition elsewhere
# in the sentence cannot hide an independently asserted contract.
_NOMINAL_CONTRACT_HYPOTHETICAL_SUFFIX = re.compile(
    r"(?:되|하)면(?![가-힣])|(?:될|할|되는|하는)\s*(?:경우|때)"
)
# "계약 사실" alone is an asserted-state cue, but its immediately attached
# reporting predicate can explicitly leave execution unknown. Match only this
# noun's information absence, never a different event elsewhere in the sentence.
# Closed endings exclude quoted/double-negated absence ("않았다는 뜻은 아니다").
_CONTRACT_INFORMATION_ABSENCE_SUFFIX = re.compile(
    r"(?:이|은|을)?\s*"
    r"(?:(?:원문|기사|자료|보고서)(?:에는|에서|에)\s*)?"
    r"(?:(?:별도로|별도|직접|명확히|아직|구체적으로)\s*){0,2}"
    r"(?:명시|제시|언급|확인)(?:되지|되어\s*있지|하지)\s*"
    r"않(?:았습니다|았으며|았지만|았으나|았는데|았고|았다|습니다|는다|아서|아|으며|으나|는데|고|지만|음)"
    r"(?=$|[\s,.!?;。])"
)
_NEGATIVE_HYPOTHETICAL_SUFFIX = re.compile(
    r"(?:으)?면|(?:될|할|되는|하는|된|한)\s*(?:경우|때)|\s*여부|"
    r"(?:(?:되|하|된|한|됐|했|되었|하였|았|었))?(?:" + _HYPOTHETICAL_SUFFIX.pattern + r")"
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
# These two observed recovery expressions are aliases of a production state,
# not new forbidden words. Compare them only with an explicitly continuing halt
# of the same operation and facility in a cited source clause.
_PRODUCTION_RECOVERY = re.compile(
    r"(?P<resolved>양산|가동|출하)\s*중단(?:은|는|이|가)?\s*(?:해소|해결)(?:됐다|되었다)|"
    r"정상\s*(?P<normal>양산|가동|출하)\s*상태(?:다|이다)"
)
_CONTINUING_HALT = re.compile(
    r"중단(?:은|는|이|가)?\s*(?:상태(?:가|는)?\s*)?"
    r"(?:(?:현재|지금|아직|여전히)\s*)?(?:계속|지속)(?:된다|되고\s*있다)"
)
_STATE_CLAUSE_BREAK = re.compile(
    r",|，|(?:했으며|됐으며|되었으며|했고|됐고|되었고|하며|이며|하지만|반면)\s+"
)
_STATE_QUOTATION = re.compile(
    r'"[^"\n]*"|\'[^\'\n]*\'|“[^”\n]*”|‘[^’\n]*’|「[^」\n]*」|『[^』\n]*』'
)
_FACILITY = re.compile(
    r"(?P<label>(?<![A-Za-z0-9가-힣])(?:제?\d+|[A-Z][A-Za-z0-9-]*)\s*)?"
    r"(?P<kind>생산\s*라인|라인|공장|공정|설비)"
)
_UNASSERTED_STATE_SUFFIX = re.compile(
    r"는\s*(?:것|뜻|의미|사실)(?:은|이)?\s*(?:아니|없)|"
    r"(?:라고|고)\s*(?:가정|전제|부인|부정)|"
    r"(?:라는|는)\s*(?:주장|사실)(?:을|은)?\s*(?:부인|부정)|"
    r"고\s*(?:단정|확정|확인)(?:할\s*수\s*없|하기\s*어렵)"
)
_STATE_ASSUMPTION_PREFIX = re.compile(r"^\s*(?:만약(?:에)?\s+|(?:가정|전제|예시)\s*[:：])")
_LATIN_IDENTIFIER = re.compile(r"(?<![A-Za-z0-9_])[A-Za-z][A-Za-z0-9]*(?![A-Za-z0-9_])")
# Capitalization alone is not evidence of an organization: Yield, Foundry and
# technical acronyms can all describe ordinary work. Unknown names require an
# explicit organization/fund naming construction, rather than a vocabulary list.
_NAMED_ENTITY_PREFIX = re.compile(
    r"(?:^|[\s(])(?:기업|회사|주식회사|업체|제조사|공급사|기관)\s+[\"'“‘]?$"
)
_NAMED_ENTITY_SUFFIX = re.compile(
    r"\s*,?\s+(?:Inc|Corp|Corporation|Ltd|LLC|PLC)\.?(?=$|[\s가-힣,.])|"
    r"사(?=$|[은는이가의\s,.])|\s+ETF(?=$|[\s가-힣,.])",
    re.I,
)


def _signing_polarity(prefix: str) -> bool | None:
    """Resolve only explicit local syntax; ambiguous scope stays unasserted.

    A closed temporal/concessive adjunct does not condition the main event.
    Conversely, an initial if/unless/whether clause still governs its event.
    This is a bounded surface check, not a proof of arbitrary English entailment.
    """
    scope = prefix.replace("’", "'")
    while adjunct := _CLOSED_SIGNING_ADJUNCT.match(scope):
        scope = scope[adjunct.end() :]
    contrast = re.split(r"\bbut\b", scope, flags=re.IGNORECASE)
    if len(contrast) > 1 and not _NONFACTUAL_SIGNING.search("but".join(contrast[:-1])):
        scope = contrast[-1]
    if _NONFACTUAL_SIGNING.search(scope):
        return None
    if _NEGATED_SIGNING.search(scope):
        return False
    if _SIGNING_NEGATION.search(scope):
        coordinate = _DO_NEGATED_COORDINATE.search(scope)
        if (
            coordinate
            and _NONCLAUSAL_DO_VERB.fullmatch(coordinate["verb"])
            and not (
                not coordinate["object"].strip()
                or _EMBEDDED_SIGNING_CLAUSE.search(coordinate["object"])
                or _SIGNING_NEGATION.search(scope[: coordinate.start()] + coordinate["object"])
            )
        ):
            # 'did not change ... and signed' contains two finite predicates:
            # do-support requires 'sign', so it cannot negate past-tense 'signed'.
            # In contrast, 'have not reviewed and signed' shares an auxiliary;
            # that form intentionally remains unasserted below.
            return True
        return None
    return True


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
                signing_polarity = _signing_polarity(prefix)
                if signing_polarity is None:
                    continue
                yield signing_polarity, clause
                continue
            yield positive, clause


def _unreported_contract_fact(match, value: str) -> bool:
    return match[0].endswith("사실") and bool(
        _CONTRACT_INFORMATION_ABSENCE_SUFFIX.match(value[match.end() :])
    )


def _conditional_state_label(match, value: str) -> bool:
    """Only an entire nominal observation label, never a finite assertion.

    The field may ask to watch for '공급 계약 취소' or '인증 미완료'. Any
    following predicate or earlier recognized state keeps ordinary validation.
    """
    if not (match[0].endswith("취소") or match[0] == "미완료"):
        return False
    if value[match.end() :].strip(" .。"):
        return False
    prefix = value[: match.start()]
    return not (
        _CONTRACT_SENTENCE_BREAK.search(prefix)
        or _ASSERTION.search(prefix)
        or _PRODUCTION_RECOVERY.search(prefix)
        or any(pattern.search(prefix) for patterns in _FACT_STATES.values() for pattern in patterns)
    )


def factual_states(value: str, *, conditional: bool = False) -> dict[str, set[bool]]:
    """Known asserted states, True for positive and False for negative."""
    states = {}
    for name, (negative, positive) in _FACT_STATES.items():
        values = set()
        negative_spans = [
            match.span()
            for match in negative.finditer(value)
            if not _NEGATIVE_HYPOTHETICAL_SUFFIX.match(value[match.end() :])
            and not (conditional and _conditional_state_label(match, value))
        ]
        if negative_spans:
            values.add(False)
        for match in positive.finditer(value):
            if any(start <= match.start() < end for start, end in negative_spans):
                continue
            if name == "contract" and _unreported_contract_fact(match, value):
                continue
            suffix = value[match.end() :]
            if _HYPOTHETICAL_SUFFIX.match(suffix) or (
                name == "contract"
                and match[0].endswith(("확정", "성립"))
                and _NOMINAL_CONTRACT_HYPOTHETICAL_SUFFIX.match(suffix)
            ):
                continue
            values.add(True)
        if values:
            states[name] = values
    # The supplied English sentence may establish the same signed-contract
    # state as a Korean translation. Modal/conditional clauses are not execution.
    for match in _SIGNED_CONTRACT.finditer(value):
        prefix = _CONTRACT_SENTENCE_BREAK.split(value[: match.start()])[-1]
        signing_polarity = _signing_polarity(prefix)
        if signing_polarity is not None:
            states.setdefault("contract", set()).add(signing_polarity)
    for positive, _ in _contract_alias_events(value):
        states.setdefault("contract", set()).add(positive)
    return states


def has_asserted_event(value: str) -> bool:
    return any(
        not _HYPOTHETICAL_SUFFIX.match(value[match.end() :]) for match in _ASSERTION.finditer(value)
    )


def _state_clauses(value: str):
    start = 0
    quotations = [match.span() for match in _STATE_QUOTATION.finditer(value)]
    boundaries = sorted(
        [*_CONTRACT_SENTENCE_BREAK.finditer(value), *_STATE_CLAUSE_BREAK.finditer(value)],
        key=lambda match: match.start(),
    )
    for boundary in boundaries:
        # Retain a quotation's attached denial/assumption even if its sentence
        # contains punctuation: '"정상 상태다."는 주장을 부인했다'.
        if any(left < boundary.start() < right for left, right in quotations):
            continue
        yield value[start : boundary.end()]
        start = boundary.end()
    yield value[start:]


def _asserted_state_alias(match, clause: str) -> bool:
    # Quotation marks do not change the state: publishers commonly quote a
    # factual assertion directly, and an unrelated quoted word cannot erase it.
    # Inspect the predicate's attached reporting/conditional suffix instead.
    suffix = clause[match.end() :]
    suffix = re.sub(r"^[.。](?=[\"'”’」』])", "", suffix).lstrip("\"'”’」』 ")
    return not (
        _STATE_ASSUMPTION_PREFIX.match(clause[: match.start()])
        or _HYPOTHETICAL_SUFFIX.match(suffix)
        or _UNASSERTED_STATE_SUFFIX.match(suffix)
        or _UNASSERTED_CONTRACT_REPORT.match(suffix)
        or suffix.startswith(("?", "？"))
    )


def _facility_refs(clause: str) -> set[tuple[str, str]]:
    return {
        ((match["label"] or "").strip(), match["kind"].replace("생산", "").replace(" ", ""))
        for match in _FACILITY.finditer(clause)
    }


def _known_company_spans(value: str) -> list[tuple[int, int]]:
    """Leave complete bilingual aliases to the shared company guard.

    Match the whole alias before examining individual words, so 'Samsung
    Electronics' is not reclassified as two unknown entities. Alphanumeric
    boundaries deliberately exclude 'Samsung Electronics2'.
    """
    return [
        match.span()
        for aliases in _COMPANY_ALIASES.values()
        for alias in aliases
        if re.search(r"[A-Za-z]", alias)
        for match in re.finditer(
            r"(?<![A-Za-z0-9_])" + re.escape(alias).replace(r"\ ", r"\s+") + r"(?![A-Za-z0-9_])",
            value,
            re.I,
        )
    ]


def _source_identifier_mismatches(value: str, source: str) -> list[str]:
    known_spans = _known_company_spans(value)
    errors = []
    for match in _LATIN_IDENTIFIER.finditer(value):
        if any(start <= match.start() and match.end() <= end for start, end in known_spans):
            continue
        identifier = match[0]
        numbered = any(char.isdigit() for char in identifier)
        named_entity = _NAMED_ENTITY_PREFIX.search(value[: match.start()]) or (
            _NAMED_ENTITY_SUFFIX.match(value[match.end() :])
        )
        if not numbered and not named_entity:
            continue
        if not re.search(
            rf"(?<![A-Za-z0-9_]){re.escape(identifier)}(?![A-Za-z0-9_])", source, re.I
        ):
            prefix = _UNSUPPORTED_IDENTIFIER_MESSAGE if numbered else _UNSUPPORTED_COMPANY_MESSAGE
            errors.append(prefix + identifier)
    return errors


def _production_reversal(value: str, source: str) -> bool:
    """Recognize bounded recovery aliases without merging unrelated states.

    The ordinary state map deliberately discards actors and targets. Do not add
    recovery aliases there: that would combine a halted line with another line's
    normal operation. Ambiguous subjects, time changes and hypothetical clauses
    stay outside this narrow deterministic check.
    """
    source_clauses = list(_state_clauses(source))
    source_facilities = set().union(*(_facility_refs(clause) for clause in source_clauses))
    for clause in _state_clauses(value):
        for recovery in _PRODUCTION_RECOVERY.finditer(clause):
            if not _asserted_state_alias(recovery, clause):
                continue
            operation = recovery["resolved"] or recovery["normal"]
            actors, facilities = _companies(clause), _facility_refs(clause)
            # An omitted facility cannot pick one of several named source lines.
            if len(source_facilities) > 1 and not any(label for label, _ in facilities):
                continue
            # A source may itself describe a later recovery. A matching source
            # assertion defeats this narrow reversal proof; chronology belongs
            # to the richer fact relation checks, not this synonym guard.
            if any(
                (prior["resolved"] or prior["normal"]) == operation
                and _asserted_state_alias(prior, source_clause)
                and (not actors or actors <= _companies(source_clause))
                and (not facilities or facilities <= _facility_refs(source_clause))
                for source_clause in source_clauses
                for prior in _PRODUCTION_RECOVERY.finditer(source_clause)
            ):
                continue
            for source_clause in source_clauses:
                halt = _FACT_STATES["production"][0].search(source_clause)
                continuing = _CONTINUING_HALT.search(source_clause)
                if (
                    halt is None
                    or not halt[0].startswith(operation)
                    or continuing is None
                    or not _asserted_state_alias(continuing, source_clause)
                ):
                    continue
                source_actors, source_targets = (
                    _companies(source_clause),
                    _facility_refs(source_clause),
                )
                if actors and source_actors and not actors <= source_actors:
                    continue
                if (
                    facilities
                    and source_targets
                    and any(
                        not any(
                            kind == source_kind and (not label or label == source_label)
                            for source_label, source_kind in source_targets
                        )
                        for label, kind in facilities
                    )
                ):
                    continue
                if re.search(r"작년|지난해|당시|과거", source_clause):
                    continue
                return True
    return False


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
    fact_source: str | None = None,
    conditional: bool = False,
) -> list[str]:
    """Scope report interpretations while retaining source-bound factual checks."""
    states = factual_states(value, conditional=conditional)
    source_states = factual_states(source)
    source_aliases = list(_contract_alias_events(source))
    # Interpretive prose without an asserted event may discuss missing relevance,
    # a status question, uncertainty, or a missing-information limitation. The
    # source's positive/negative event polarity does not constrain that analysis.
    has_factual_state = bool(states)
    remaining = list(mismatches)
    # Ground product generations and explicitly named unknown entities in raw
    # source text. General English work vocabulary is not itself a company.
    actor_source = source if fact_source is None else fact_source
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
    remaining.extend(_source_identifier_mismatches(value, actor_source))
    if any(item.startswith(_NUMERIC_CONTEXT_MESSAGE) for item in remaining) and (
        _independent_parallel_numbers(value, source)
        or _ordered_year_percentages(value, source)
        or supported_year_range_context(value, source)
    ):
        remaining = [item for item in remaining if not item.startswith(_NUMERIC_CONTEXT_MESSAGE)]
    elif any(item.startswith(_NUMERIC_CONTEXT_MESSAGE) for item in remaining):
        proven = supported_labeled_amount_context(value, actor_source)
        if proven is not None:
            remaining = [item for item in remaining if item != _NUMERIC_CONTEXT_MESSAGE + proven]
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
    if _production_reversal(value, actor_source):
        remaining.append("근거에서 확인되지 않는 완료·착수·계약·중단 사실입니다.")
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
