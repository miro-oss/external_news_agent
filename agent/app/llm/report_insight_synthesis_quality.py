"""Conservative synthesis checks against each cited claim's own evidence.

Parsed event frames are hints, not a semantic entailment model. Only explicit
owners, realization predicates and narrowly defined contradictions cause a
rejection. Unknown aliases, unnamed actors and genuinely conditional outcomes
stay undecided rather than acquiring an invented factual relation.
"""

import re
import unicodedata
from collections.abc import Collection
from dataclasses import dataclass

from app.core.errors import OutputValidationError
from app.llm.report_validation_diagnostics import ReportValidationIssue
from app.schemas.report_insight import (
    ReportAudienceInsight,
    ReportInsightReduceAudience,
    ReportInsightRequest,
)

_EVENTS = re.compile(
    r"(?P<investment>투자|\bcapex\b|\binvest(?:ment|ments|ed|ing|s)?\b)|"
    r"(?P<capacity>증설|\bcapacity\s+expansion\b)|"
    r"(?P<orders>수주|\border\s+bookings?\b|\bnew\s+orders?\b)|"
    r"(?P<production>양산|\bmass\s+production\b)",
    re.IGNORECASE,
)
_KOREAN_OWNER = re.compile(
    r"(?<![가-힣A-Za-z0-9])(?P<actor>[가-힣A-Za-z][가-힣A-Za-z0-9&·.-]+?)"
    r"(?:\([^()]{1,80}\))?\s*(?P<link>에\s*의해(?:서)?|에\s*의한|의|은|는|이|가)"
)
_ENGLISH_OWNER = re.compile(
    r"(?<![A-Za-z0-9])(?P<actor>[A-Z][A-Za-z0-9&.-]*"
    r"(?:\s+[A-Z][A-Za-z0-9&.-]*){0,4})(?:\s*\([^()]{1,80}\))?['’]s?\s+"
)
_ALIAS = re.compile(
    r"(?P<name>[A-Z][A-Za-z0-9&.-]*(?:\s+[A-Z][A-Za-z0-9&.-]*){0,4}|"
    r"[가-힣][가-힣A-Za-z0-9&·.-]+)\s*\((?P<alias>[^()]{2,80})\)"
)
_CLAUSE_BREAK = re.compile(r"[。;；\n]|(?<!\d)[.!?](?!\d)")
_FUTURE = re.compile(
    r"계획|예정|전망|예상|목표|방침|가정|기대|가능성|(?:할|될|하는|되는)\s*경우|"
    r"\b(?:plans?|planned|expects?|expected|forecast|forecasted|intends?|may|might|could|would|will)\b",
    re.IGNORECASE,
)
_CONDITIONAL = re.compile(
    r"(?:다면|라면|으면|되면|하면|이면|늘면|줄면)(?=\s|[,.!?]|$)|\bif\b|\bassuming\b|"
    r"(?:할|될|하는|되는|한|된|없는|있는)\s*(?:경우|때)",
    re.IGNORECASE,
)
_REALIZED = re.compile(
    r"집행(?:했|됐|되었|중)|진행\s*중|착수(?:했|한)|"
    r"(?:확대|증가|감소)(?:했|됐|되었|했다|하였다)|늘어나|늘어났|길어졌|"
    r"\b(?:underway|invested|increased|expanded|started|began)\b",
    re.IGNORECASE,
)
_COMPLETED = re.compile(r"완료(?:했|됐|되었|한|된)|마쳤|\bcompleted\b", re.IGNORECASE)
_CURRENT_OBSERVATION = re.compile(
    r"(?:현재|이미|실제|최근).{0,30}(?:관측|확인|집행|진행|이뤄|이루어)|"
    r"\b(?:currently|already).{0,35}(?:observed|underway|invested)\b",
    re.IGNORECASE,
)
_BENEFICIARY = re.compile(r"\s*(?:확대\s*)?(?:수혜|혜택)|\s+benefit", re.IGNORECASE)
_LIMITATION = re.compile(r"여부|미확인|불명|판단\s*보류|확인.{0,8}필요|검토.{0,8}필요|알\s*수\s*없")
_GENERIC_MECHANISM = frozenset(
    {
        "근거",
        "사건",
        "결합",
        "변화",
        "영향",
        "업무판단",
        "판단",
        "해석",
        "결과",
        "메커니즘",
        "조건",
        "예시",
        "내용",
        "source",
        "event",
        "effect",
        "decision",
        "tbd",
        "todo",
    }
)
_MECHANISM_BREAK = re.compile(r"→|⇒|➜|->|=>|>")
_CONFIRMED = re.compile(
    r"확인(?:됨|됐다|되었다|되었|된\s*사실)|관측(?:됨|됐다|되었다)|"
    r"\b(?:confirmed|established|verified)\b",
    re.IGNORECASE,
)
_POWER_TARGET = re.compile(r"전력\s*(?:공급|수급)|power\s+supply", re.IGNORECASE)
_HALTED_AUCTION = re.compile(
    r"경매.{0,35}중단|중단.{0,35}경매|auction.{0,40}(?:halt|suspend)", re.IGNORECASE
)
_HALT_DENIAL = re.compile(
    r"중단(?:하|되)지\s*않|중단(?:은|이)?\s*없|(?:not|never).{0,12}halt", re.IGNORECASE
)
_POWER_EFFECT = re.compile(
    r"전력\s*(?:공급|수급).{0,45}(?:영향|차질|불안|부족|악화|제약|안정성)|"
    r"(?:영향|차질|불안|부족|악화).{0,35}전력\s*(?:공급|수급)|"
    r"power\s+supply.{0,45}(?:affect|impact|risk|shortage|constraint)",
    re.IGNORECASE,
)
_POSITIVE_SUPPLY_HYPOTHESIS = re.compile(
    r"(?:전력\s*공급|공급량|대체\s*전력|추가\s*공급).{0,25}(?:늘|증가|확대|증대|촉진)|"
    r"(?:촉진|늘|증가|확대|증대).{0,25}(?:전력\s*공급|공급량|대체\s*전력|추가\s*공급)|"
    r"(?:increase|expand|stimulate).{0,25}(?:additional\s+)?power\s+supply",
    re.IGNORECASE,
)
_NO_ADDITIONAL_SUPPLY = re.compile(
    r"(?:추가\s*전력\s*공급|추가\s*공급|공급량\s*증대).{0,40}(?:없|미확보|부재)|"
    r"(?:no|absence\s+of).{0,25}(?:additional\s+power\s+supply|extra\s+capacity)",
    re.IGNORECASE,
)
_WITHDRAWN_ABSENCE = re.compile(
    r"없(?:다는|던).{0,25}(?:철회|반박|부정)|없지\s*않|없는\s*것이\s*아니"
)
_EXPANSION_EVENT = re.compile(
    r"증설|생산\s*(?:능력|영역|량)?(?:을|를|이|가)?\s*"
    r"(?:확대|확장|향상|증가|넓|늘)|\bcapacity\s+expansion\b",
    re.I,
)
_FACTORY_SITE = re.compile(r"(?P<site>[가-힣A-Za-z][가-힣A-Za-z0-9.-]+)\s*공장")
_GENERIC_FACTORY_SITE = frozenset({"반도체", "생산", "신규", "기존", "해당", "새로운"})
_EXPLICIT_EXPANSION_OWNER = re.compile(_KOREAN_OWNER.pattern + r"(?=\s|$)")
_CANCELLATION = re.compile(r"철회|취소|연기|중단|지연")
_CANCELLATION_DENIAL = re.compile(
    r"(?:철회|취소|연기|중단|지연)(?:하|되)지\s*않|"
    r"(?:철회|취소|연기|중단|지연)(?:나|와|과|\s|하는|되는|한|된|사건|일|상황|적|사례|이|가|은|는|도)*"
    r"(?:없|발생(?:하|되)지\s*않)"
)
# Only a denial that ends the condition is decisive. Additional substantive
# text may describe a real setback even when the expansion itself continues.
_DENIAL_END = re.compile(r"(?:았|었)?(?:다|음|습니다|(?:는|은|을|던)\s*(?:경우|때))?\s*$")
_EXPANSION_TO_DENIAL = re.compile(
    r"\s*(?:계획|사업|일정)?(?:이|가|을|를|은|는|에|의)?\s*"
    r"(?:(?:철회|취소|연기|중단|지연)(?:하|되)?(?:거나|나|와|과)\s*)*"
)
_REVERSED_DENIAL = re.compile(
    r"(?:않|없).{0,15}(?:것|주장|판단|설명|발표|보도)(?:이|은|는|가)?\s*"
    r"(?:아니|아닌|틀리|틀린|철회|반박|부정)|"
    r"(?:없지|않지)\s*않|않은\s*것은?\s*아니"
)
_EXPANSION_BENEFIT = re.compile(
    r"(?:생산\s*능력|생산량|공급\s*능력|공급량).{0,15}(?:향상|증가|확대|늘)"
)
_EXPANSION_RESULT_FAILURE = re.compile(
    r"(?:생산\s*능력|생산량|공급\s*능력|공급량).{0,15}"
    r"(?:향상|증가|확대|늘어나|늘)(?:하|되)?지\s*(?:않|못)|"
    r"(?:생산\s*능력|생산량|공급\s*능력|공급량).{0,15}(?:감소|하락)"
)
_INFORMATION_GAP = re.compile(
    r"(?:업무|연결|조건|범위|시급성|영향).{0,50}"
    r"(?:미확인|불명|(?:명시|제시|확인)(?:되|되어|되어\s*있|되었)?지\s*않)|"
    r"(?:원문|근거|정보|자료).{0,20}(?:없|부족)|"
    r"(?:missing|unknown|unclear|unconfirmed).{0,35}(?:evidence|connection|scope|timing)",
    re.IGNORECASE,
)
_MISSING_EVIDENCE = re.compile(
    r"(?:근거|증거|정보|자료).{0,18}(?:없|부재|미확인|부족)|"
    r"(?:연결|업무|관련).{0,30}(?:제시|명시).{0,15}(?:사건|정보).{0,8}(?:없|부재)|"
    r"(?:no|missing|absence\s+of|lack\s+of).{0,20}(?:evidence|information)",
    re.IGNORECASE,
)
_METADATA_ONLY_START = re.compile(
    r"^(?:원문(?:에|에서|상)?(?:는|은)?\s*)?(?:구체적(?:인)?\s*)?"
    r"(?:관점(?:의)?\s*)?업무\s*연결\s*(?:조건|경로)|"
    r"^(?:원문|근거|정보|자료)(?:가|는|이|은)?\s*(?:미확인|없|부족)"
)
_MEASUREMENT_CONTEXT = re.compile(r"시험|검증\s*시험|측정|테스트|\b(?:test|experiment)\b", re.I)
_OBSERVED_RESULT = re.compile(
    r"결과.{0,20}(?:확인|관측|측정|보고)|\b(?:observed|measured|reported)\s+result\b",
    re.IGNORECASE,
)
# A planned production date is still a factual relation: citing a process in one
# article and a different factory's schedule in another does not join the two.
_PRODUCTION_CONTEXT = re.compile(
    r"양산|생산|공장|가동|\b(?:production|manufacturing|factory|plant|fab)\b", re.I
)
_PROCESS_TARGET = re.compile(r"(?<![A-Za-z0-9])\d+(?:\.\d+)?\s*(?:nm|나노미터)(?![A-Za-z])", re.I)
_PRODUCTION_TIME = re.compile(
    r"(?:내년|올해|금년|20\d{2}\s*년)\s*"
    r"(?:(?:상|하)반기|[1-4]\s*분기|\d{1,2}\s*월)?|"
    r"\b(?:20\d{2}|(?:first|second)\s+half\s+of\s+(?:next\s+year|20\d{2}))\b",
    re.I,
)
_INDEPENDENT_EVENT_BREAK = re.compile(
    r",|，|(?:했으며|됐으며|되었으며|했고|됐고|되었고|하며|이며|하지만|반면)\s+"
)
_UNASSERTED_SCHEDULE = re.compile(
    r"(?:일정|시점|시기|계획).{0,45}"
    r"(?:미정|미확정|(?:확정|확인)(?:하|되)지\s*않|정해지지\s*않)|"
    r"(?:생산|양산|가동).{0,30}(?:시작|개시|돌입)(?:하|되)지\s*않|"
    r"\b(?:schedule|timing|date).{0,40}(?:unconfirmed|not\s+(?:confirmed|set|determined))\b",
    re.I,
)


@dataclass(frozen=True)
class EvidenceText:
    claim_id: str
    claim_type: str
    attributed_to: str | None
    sentence_id: int | None
    text: str
    finding_id: int


@dataclass(frozen=True)
class EventFrame:
    actor: str | None
    family: str
    stage: int  # 0 conditional/planned, 1 mentioned, 2 observed, 3 completed
    actor_role: str | None = None


def _normalize(value: str) -> str:
    return " ".join(unicodedata.normalize("NFKC", value).casefold().split())


def _texts(request: ReportInsightRequest, claim_ids: Collection[str]) -> list[EvidenceText]:
    rows = []
    for finding in request.findings:
        sentences = {sentence.index: sentence.text for sentence in finding.sentences}
        for claim in finding.claims:
            if claim.id not in claim_ids:
                continue
            rows.append(
                EvidenceText(
                    claim.id, claim.claim_type, claim.attributed_to, None, claim.text, finding.id
                )
            )
            rows.extend(
                EvidenceText(
                    claim.id,
                    claim.claim_type,
                    claim.attributed_to,
                    index,
                    sentences[index],
                    finding.id,
                )
                for index in claim.evidence_sentence_ids
            )
    return rows


def _aliases(rows: list[EvidenceText]) -> dict[str, str]:
    result = {}
    for row in rows:
        for match in _ALIAS.finditer(row.text):
            name, alias = _normalize(match["name"]), _normalize(match["alias"])
            if not re.fullmatch(r"[가-힣A-Za-z0-9&. -]+", match["alias"]) or alias.isdecimal():
                continue
            canonical = result.get(name, result.get(alias, name))
            result[name] = canonical
            result[alias] = canonical
    return result


def _canonical(actor: str, aliases: dict[str, str]) -> str:
    normalized = _normalize(actor)
    return aliases.get(normalized, normalized)


def _known_actor(actor: str, rows: list[EvidenceText], aliases: dict[str, str]) -> bool:
    name = _normalize(actor)
    if name in aliases:
        return True
    # An undeclared acronym/group shorthand is ambiguous, not a different firm.
    boundary = r"(?=$|[^가-힣A-Za-z0-9]|(?:의|은|는|이|가|에|과|와|도|을|를)(?:\s|$))"
    pattern = re.compile(r"(?<![가-힣A-Za-z0-9])" + re.escape(name) + boundary)
    return any(pattern.search(_normalize(row.text)) for row in rows)


def _actor(clause: str, event: re.Match) -> tuple[str | None, str | None]:
    prefix = clause[: event.start()]
    candidates = []
    for pattern in (_KOREAN_OWNER, _ENGLISH_OWNER):
        for match in pattern.finditer(prefix):
            if _EVENTS.fullmatch(match["actor"]) or match["actor"] in {
                "확대",
                "증가",
                "계획",
                "전망",
                "중단",
                "경매",
                "결정",
            }:
                continue
            distance = event.start() - match.end()
            if distance > 100:
                continue
            gap = clause[match.end() : event.start()]
            # A prior investment is not the grammatical owner of a later order
            # expansion. Only directly coordinated nouns inherit their subject.
            link = match.groupdict().get("link", "의")
            if (
                match["actor"] == "진행"
                and link in {"은", "는", "이", "가"}
                and re.search(r"(?:투자|증설|수주|양산)\s+$", prefix[: match.start()])
            ):
                # In '투자 진행은 … 증설', 진행 names the earlier action's
                # progress, not a company that owns the later expansion.
                continue
            coordinated = re.fullmatch(
                r"\s*(?:투자|증설|수주|양산)(?:\s*확대)?\s*(?:와|과|및|·)\s*", gap
            )
            funding_own_expansion = (
                event.lastgroup == "investment"
                and link in {"은", "는", "이", "가"}
                and re.search(r"증설에\s*$", gap)
                and not re.search(r"이끌|수혜|영향|따라|촉진", gap)
            )
            if _EVENTS.search(gap) and not (coordinated or funding_own_expansion):
                continue
            priority = 2 if link == "의" or "의해" in link or "의한" in link else 1
            role = "agent" if "의해" in link or "의한" in link else "owner"
            candidates.append((priority, match.end(), match["actor"], role))
    selected = max(candidates, default=(0, 0, None, None))
    return selected[2], selected[3]


def _frames(value: str, *, claim_type: str = "FACT") -> list[EventFrame]:
    frames = []
    for clause in _CLAUSE_BREAK.split(value):
        matches = list(_EVENTS.finditer(clause))
        for index, event in enumerate(matches):
            if _BENEFICIARY.match(clause[event.end() :]):
                continue
            end = matches[index + 1].start() if index + 1 < len(matches) else len(clause)
            local = clause[event.start() : end]
            if _CONDITIONAL.search(clause) or _FUTURE.search(local) or claim_type != "FACT":
                stage = 0
            elif _COMPLETED.search(local):
                stage = 3
            elif _REALIZED.search(local) or _CURRENT_OBSERVATION.search(clause):
                stage = 2
            elif _FUTURE.search(clause):
                stage = 0
            else:
                stage = 1
            actor, role = _actor(clause, event)
            frames.append(EventFrame(actor, event.lastgroup, stage, role))
    return frames


def synthesis_evidence_frames(
    request: ReportInsightRequest, allowed_claim_ids: Collection[str]
) -> list[dict]:
    """Optional generation hints; retain original claim type and attribution.

    These frames never replace the original claim/sentence or introduce actors
    for unnamed sentences. Consumers should label them as parsed hints.
    """
    hints = []
    labels = ("planned_or_conditional", "mentioned", "observed", "completed")
    for row in _texts(request, allowed_claim_ids):
        frames = _frames(row.text, claim_type=row.claim_type)
        if frames:
            hints.append(
                {
                    "claimId": row.claim_id,
                    "claimType": row.claim_type,
                    "attributedTo": row.attributed_to,
                    "evidenceSentenceId": row.sentence_id,
                    "parsedEvents": [
                        {
                            "subject": frame.actor,
                            "subjectRole": frame.actor_role,
                            "event": frame.family,
                            "stage": labels[frame.stage],
                        }
                        for frame in frames
                    ],
                }
            )
    return hints


def _event_problems(
    value: str, rows: list[EvidenceText], global_rows: list[EvidenceText]
) -> list[tuple[str, str]]:
    aliases = _aliases(rows)
    sources = [frame for row in rows for frame in _frames(row.text, claim_type=row.claim_type)]
    problems = []
    for generated in _frames(value):
        if generated.stage == 0:
            continue
        if generated.stage == 1 and _LIMITATION.search(value):
            continue
        matching = [frame for frame in sources if frame.family == generated.family]
        if not matching:
            if (
                generated.actor is not None
                and _known_actor(generated.actor, global_rows, _aliases(global_rows))
                and not _known_actor(generated.actor, rows, aliases)
            ):
                problems.append(
                    (
                        "report_synthesis_reference_gap",
                        f"{generated.family} 사건의 주체 {generated.actor}는 해당 인용 근거에 "
                        "없습니다. 다른 claim의 사건을 basisClaimIds에 연결하지 않고 "
                        "섞을 수 없습니다.",
                    )
                )
            continue  # Other factual guards handle unsupported event families.
        if generated.actor is not None:
            if not _known_actor(generated.actor, rows, aliases):
                continue
            comparable = [frame for frame in matching if frame.actor_role == generated.actor_role]
            own = [
                frame
                for frame in comparable
                if frame.actor is not None
                and _canonical(frame.actor, aliases) == _canonical(generated.actor, aliases)
            ]
            if comparable and not own and all(frame.actor is not None for frame in comparable):
                problems.append(
                    (
                        "report_synthesis_subject_mismatch",
                        f"{generated.family} 사건의 명시 주체가 인용 근거와 다릅니다: "
                        f"{generated.actor}. "
                        "동일 문장에 등장한 회사라도 투자자·공급자·수혜자를 바꿀 수 없습니다.",
                    )
                )
            if own:
                matching = own + [frame for frame in matching if frame.actor is None]
        if generated.stage >= 2 and max(frame.stage for frame in matching) == 0:
            problems.append(
                (
                    "report_synthesis_stage_overreach",
                    f"인용 근거의 {generated.family} 사건은 계획·전망 단계입니다. "
                    "현재 관측·집행·진행된 사건으로 서술할 수 없습니다.",
                )
            )
    return problems


def _possessive_actors(value: str, aliases: dict[str, str]) -> set[str]:
    """Explicit names only; a bare factory or an unnamed sentence is not an owner."""
    return {
        _canonical(match["actor"], aliases)
        for pattern in (_KOREAN_OWNER, _ENGLISH_OWNER)
        for match in pattern.finditer(value)
        if match.groupdict().get("link", "의") == "의"
    }


def _binding_terms(pattern: re.Pattern, value: str) -> set[str]:
    return {_normalize(match[0]).replace(" ", "") for match in pattern.finditer(value)}


def _mentions_owner(value: str, owner: str, aliases: dict[str, str]) -> bool:
    names = {owner, *(name for name, canonical in aliases.items() if canonical == owner)}
    boundary = r"(?=$|[^가-힣A-Za-z0-9]|(?:의|은|는|이|가|에|과|와|도|을|를)(?:\s|$))"
    return any(
        re.search(r"(?<![가-힣A-Za-z0-9])" + re.escape(name) + boundary, _normalize(value))
        for name in names
    )


def _production_binding_problems(
    value: str, rows: list[EvidenceText], global_rows: list[EvidenceText]
) -> list[tuple[str, str]]:
    """Detect explicit cross-article schedule transfers, not general entailment.

    Source identities may use the other eligible claims of the *same* finding
    to resolve an unnamed factory. They cannot supply an uncited date. Unknown
    owners/aliases and corroborating articles about the same actor stay open.
    """
    source_ids = {row.finding_id for row in rows}
    if len(source_ids) < 2:
        return []
    aliases = _aliases(global_rows)
    context = {
        finding_id: "\n".join(row.text for row in global_rows if row.finding_id == finding_id)
        for finding_id in source_ids
    }
    owners = {finding_id: _possessive_actors(text, aliases) for finding_id, text in context.items()}
    known_owners = set().union(*owners.values())
    targets = {
        finding_id: _binding_terms(_PROCESS_TARGET, text) for finding_id, text in context.items()
    }
    cited = {
        finding_id: "\n".join(row.text for row in rows if row.finding_id == finding_id)
        for finding_id in source_ids
    }
    schedules = {
        finding_id: _binding_terms(_PRODUCTION_TIME, text)
        for finding_id, text in cited.items()
        if _PRODUCTION_CONTEXT.search(text)
    }
    problems = []
    for sentence in _CLAUSE_BREAK.split(value):
        for clause in _INDEPENDENT_EVENT_BREAK.split(sentence):
            if (
                not _PRODUCTION_CONTEXT.search(clause)
                or _CONDITIONAL.search(clause)
                or _LIMITATION.search(clause)
                or _UNASSERTED_SCHEDULE.search(clause)
            ):
                continue
            generated_owners = _possessive_actors(clause, aliases)
            # An undeclared group/acronym may refer to a source actor. Lexical
            # inequality is insufficient to reject it as a different company.
            if not generated_owners or generated_owners - known_owners:
                continue
            generated_targets = _binding_terms(_PROCESS_TARGET, clause)
            generated_times = _binding_terms(_PRODUCTION_TIME, clause)
            for time in generated_times:
                providers = {key for key, times in schedules.items() if time in times}
                if not providers or any(not owners[key] for key in providers):
                    continue
                # Separate named events can share a sentence/list. Only bind a
                # date when every named owner points away from its source.
                if any(
                    _mentions_owner(clause, owner, aliases)
                    for key in providers
                    for owner in owners[key]
                ):
                    continue
                anchors = {
                    key
                    for key in source_ids
                    if _PRODUCTION_CONTEXT.search(cited[key])
                    and (
                        generated_targets & _binding_terms(_PROCESS_TARGET, cited[key])
                        or generated_owners & owners[key]
                    )
                }
                if not anchors or anchors & providers or any(not owners[key] for key in anchors):
                    continue
                # Distinct articles can corroborate a shared company's project;
                # absent an explicit contradiction, preserve that possibility.
                if any(owners[left] & owners[right] for left in anchors for right in providers):
                    continue
                if any(generated_targets & targets[key] for key in providers):
                    continue
                problems.append(
                    (
                        "report_synthesis_source_binding",
                        "생산 사건의 명시 주체·공정과 일정이 서로 다른 기사의 근거에 "
                        "연결됩니다. 다른 공장·프로젝트의 일정을 하나의 사건으로 "
                        "합치지 말고, 각각의 주체·대상·시점과 인용을 분리해야 합니다.",
                    )
                )
                break
    return problems


def _placeholder(value: str) -> bool:
    segments = [_normalize(segment).replace(" ", "") for segment in _MECHANISM_BREAK.split(value)]
    return bool(segments) and all(
        segment.strip(".:()[]") in _GENERIC_MECHANISM for segment in segments
    )


def _information_gap_only(value: str) -> bool:
    # Reject chains made entirely of missing-information statements. A real
    # source event followed by an uncertain effect is still a valid conditional
    # mechanism; unknown scope alone does not erase that event.
    segments = [part.strip() for part in _MECHANISM_BREAK.split(value) if part.strip()]
    return (
        len(segments) >= 2
        and bool(_METADATA_ONLY_START.search(segments[0]))
        and all(_INFORMATION_GAP.search(part) for part in segments)
    )


def _auction_halted(rows: list[EvidenceText]) -> bool:
    return any(
        _HALTED_AUCTION.search(row.text) and not _HALT_DENIAL.search(row.text) for row in rows
    )


def _site_mentioned(value: str, site: str) -> bool:
    return bool(
        re.search(
            r"(?<![가-힣A-Za-z0-9])"
            + re.escape(site)
            + r"(?=$|[^가-힣A-Za-z0-9]|공장|(?:에서|에|의|은|는|이|가)(?:\s|$))",
            _normalize(value),
        )
    )


def _expansion_actor(value: str, event: re.Match, *, at_start: bool = False) -> str | None:
    # An internal 이/가 is part of a company name, not a grammatical particle.
    # Keep this conservative parsing local instead of changing other event guards.
    owners = [
        (2 if match["link"] == "의" else 1, match.end(), match["actor"])
        for match in _EXPLICIT_EXPANSION_OWNER.finditer(value[: event.start()])
        if event.start() - match.end() <= 100
        and not _EVENTS.search(value[match.end() : event.start()])
        and (not at_start or not value[: match.start()].strip())
    ]
    return max(owners, default=(0, 0, None))[2]


def _expansion_falsifier_reversed(
    proposition: str, falsifier: str, rows: list[EvidenceText]
) -> bool:
    """Recognize a denied cancellation of the cited actor's same factory expansion.

    A negation alone is not a direction error: failed capacity gains can refute
    expansion benefits, and cancellation itself may be the hypothesis. Require
    an explicit shared owner and named facility; ambiguous subjects/projects
    remain undecided by this narrow guard.
    """
    if (
        not _EXPANSION_BENEFIT.search(proposition)
        or _CANCELLATION.search(proposition)
        or _EXPANSION_RESULT_FAILURE.search(proposition)
        or _REVERSED_DENIAL.search(falsifier)
        or _EXPANSION_RESULT_FAILURE.search(falsifier)
        or len([part for part in _CLAUSE_BREAK.split(falsifier) if part.strip()]) != 1
    ):
        return False
    aliases = _aliases(rows)
    for clause in _CLAUSE_BREAK.split(proposition):
        for event in _EXPANSION_EVENT.finditer(clause):
            actor = _expansion_actor(clause, event)
            if actor is None:
                continue
            sites = {
                _normalize(match["site"])
                for match in _FACTORY_SITE.finditer(clause[: event.end()])
                if _normalize(match["site"]) not in _GENERIC_FACTORY_SITE
            }
            for site in sites:
                if not any(
                    _mentions_owner(source, _canonical(actor, aliases), aliases)
                    and _site_mentioned(source, site)
                    and _EXPANSION_EVENT.search(source)
                    and not _CANCELLATION.search(source)
                    for row in rows
                    for source in _CLAUSE_BREAK.split(row.text)
                ):
                    continue
                for condition in _CLAUSE_BREAK.split(falsifier):
                    if not _site_mentioned(condition, site):
                        continue
                    for target in _EXPANSION_EVENT.finditer(condition):
                        target_actor = _expansion_actor(condition, target, at_start=True)
                        if target_actor is not None and _canonical(
                            target_actor, aliases
                        ) == _canonical(actor, aliases):
                            for denial in _CANCELLATION_DENIAL.finditer(condition, target.end()):
                                if _EXPANSION_TO_DENIAL.fullmatch(
                                    condition[target.end() : denial.start()]
                                ) and _DENIAL_END.fullmatch(condition[denial.end() :]):
                                    return True
    return False


def _assumption_unconfirmed(value: str, rows: list[EvidenceText]) -> bool:
    if not (
        _auction_halted(rows)
        and _POWER_EFFECT.search(value)
        and _CONFIRMED.search(value)
        and not _CONDITIONAL.search(value)
    ):
        return False
    return not any(
        _HALTED_AUCTION.search(clause)
        and not _HALT_DENIAL.search(clause)
        and _POWER_EFFECT.search(clause)
        and not _FUTURE.search(clause)
        and row.claim_type == "FACT"
        for row in rows
        for clause in _CLAUSE_BREAK.split(row.text)
    )


class ReportSynthesisQualityValidationError(OutputValidationError):
    """One server-owned field/reference diagnostic per quality violation."""

    def __init__(self, diagnostics):
        self.validation_issues = tuple(issue for issue, _ in diagnostics)
        self.repair_diagnostics = tuple(message for _, message in diagnostics)
        super().__init__(
            "\n".join(self.repair_diagnostics),
            error_kinds=tuple(issue.error_kind for issue in self.validation_issues),
        )


def validate_synthesis_quality(
    insight: ReportAudienceInsight | ReportInsightReduceAudience,
    request: ReportInsightRequest,
    allowed_claim_ids: Collection[str],
) -> None:
    """Raise typed repair diagnostics for explicit synthesis quality violations."""
    violations = []

    def record(kind, path, refs, message):
        violations.append(
            (ReportValidationIssue(insight.audience, path, kind, tuple(refs)), f"{path}: {message}")
        )

    global_rows = _texts(
        request, [claim.id for finding in request.findings for claim in finding.claims]
    )
    for path, value, refs in [
        ("headline", insight.headline, allowed_claim_ids),
        *[
            (f"overview[{index}].text", item.text, item.basis_claim_ids)
            for index, item in enumerate(insight.overview)
        ],
        *[
            (f"implications[{index}].{field}", getattr(item, field), item.basis_claim_ids)
            for index, item in enumerate(insight.implications)
            for field in ("text", "mechanism")
        ],
    ]:
        rows = _texts(request, refs)
        for kind, message in [
            *_event_problems(value, rows, global_rows),
            *_production_binding_problems(value, rows, global_rows),
        ]:
            record(kind, path, refs, message)
    for path, item in [
        *[(f"overview[{index}].assumption", item) for index, item in enumerate(insight.overview)],
        *[
            (f"implications[{index}].assumption", item)
            for index, item in enumerate(insight.implications)
        ],
    ]:
        if _assumption_unconfirmed(item.assumption, _texts(request, item.basis_claim_ids)):
            record(
                "report_assumption_unconfirmed",
                path,
                item.basis_claim_ids,
                "경매 중단 사실과 실제 전력 공급 영향의 확인은 다릅니다. "
                "확인되지 않은 영향은 조건으로 명시해야 합니다.",
            )
    for index, item in enumerate(insight.implications):
        if _placeholder(item.mechanism):
            record(
                "report_synthesis_placeholder",
                f"implications[{index}].mechanism",
                item.basis_claim_ids,
                "일반 자리표시자 대신 근거 사건과 "
                "해당 관점의 구체적 업무 판단을 잇는 경로를 작성해야 합니다.",
            )
        if _information_gap_only(item.mechanism):
            record(
                "report_synthesis_information_gap",
                f"implications[{index}].mechanism",
                item.basis_claim_ids,
                "정보 부족만 잇는 문장은 인과 경로가 "
                "아닙니다. 인용한 실제 사건과 관점 업무 사이의 조건을 설명하거나 "
                "연결 경로를 만들 수 없는 implication을 제외해야 합니다.",
            )
        if (
            _MISSING_EVIDENCE.search(item.falsified_by)
            and not _WITHDRAWN_ABSENCE.search(item.falsified_by)
            and not (
                _MEASUREMENT_CONTEXT.search(item.falsified_by)
                and _OBSERVED_RESULT.search(item.falsified_by)
            )
        ):
            record(
                "report_falsification_missing_observation",
                f"implications[{index}].falsifiedBy",
                item.basis_claim_ids,
                "근거·정보 부족은 반증 관측이 "
                "아닙니다. 같은 대상의 계약 철회·검증 실패·대체 공급 확보처럼 "
                "해석을 바꾸는 관측을 쓰거나 해당 implication을 제외해야 합니다.",
            )
        rows = _texts(request, item.basis_claim_ids)
        source_halt = _auction_halted(rows)
        proposition = item.text + "\n" + item.mechanism
        if _expansion_falsifier_reversed(proposition, item.falsified_by, rows):
            record(
                "report_falsification_direction",
                f"implications[{index}].falsifiedBy",
                item.basis_claim_ids,
                "동일 공장 증설 계획의 철회·연기가 "
                "발생하지 않았다는 관측은 증설 효과를 반증하지 않습니다. 실제 계획 "
                "철회·연기 또는 예상 생산 능력 향상 실패처럼 해석을 약화시키는 "
                "관측 조건을 작성해야 합니다.",
            )
        if (
            source_halt
            and _POWER_EFFECT.search(proposition)
            and _NO_ADDITIONAL_SUPPLY.search(item.falsified_by)
            and not _WITHDRAWN_ABSENCE.search(item.falsified_by)
            and not _POSITIVE_SUPPLY_HYPOTHESIS.search(proposition)
        ):
            record(
                "report_falsification_direction",
                f"implications[{index}].falsifiedBy",
                item.basis_claim_ids,
                "추가 공급의 부재는 전력 부족·차질 "
                "위험을 반증하지 않습니다. 대체 공급 확보처럼 위험을 약화시키는 "
                "관측 가능한 조건을 작성해야 합니다.",
            )
    if violations:
        raise ReportSynthesisQualityValidationError(violations)
