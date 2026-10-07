"""Narrow checks for invented concrete work prerequisites and physical objects.

Literal citations do not establish semantic entailment. These checks catch a
few explicit source-absent procedure/object assertions; they neither prove role
relevance nor assign importance. Generic work interpretations, such as reviewing
procurement costs after a price move, are intentionally outside this detector.
"""

import re
import unicodedata
from collections.abc import Collection

from app.core.errors import OutputValidationError
from app.llm.report_validation_diagnostics import ReportValidationIssue
from app.schemas.report_insight import (
    ReportInsightOutput,
    ReportInsightReduceOutput,
    ReportInsightRequest,
)

_MODULE_NOUN_PATTERN = r"모듈(?!화|형|러|성)|\bmodules?\b"
_RULES = (
    (
        "approval_prerequisite",
        re.compile(r"고객.{0,10}승인|승인.{0,12}(?:전|요건|조건|전제|결과|절차|없이는)"),
        re.compile(r"승인|\bapprov(?:als?|es?|ed|ing)\b|\bauthoriz", re.I),
    ),
    (
        "inspection_prerequisite",
        re.compile(r"검수.{0,12}(?:승인|전|요건|조건|전제|결과|절차|완료|통과)"),
        re.compile(r"검수|\bacceptance\s+(?:inspection|test)\b", re.I),
    ),
    (
        "certification_prerequisite",
        re.compile(r"인증.{0,12}(?:승인|요건|전제|절차|완료|통과)"),
        re.compile(r"인증|\bcertif", re.I),
    ),
    (
        "cooling_procedure",
        re.compile(
            rf"냉각.{{0,12}}(?:검증|{_MODULE_NOUN_PATTERN}|호환성|승인)|"
            rf"(?:cooling|thermal).{{0,20}}(?:{_MODULE_NOUN_PATTERN}|test)",
            re.I,
        ),
        re.compile(r"냉각|\bcooling\b|\bthermal\b", re.I),
    ),
    (
        "compatibility_procedure",
        re.compile(
            r"호환성.{0,12}(?:검증|요건|승인|시험)|\bcompatibility.{0,20}(?:test|approval)", re.I
        ),
        re.compile(r"호환|\bcompatib", re.I),
    ),
    (
        "specification_procedure",
        re.compile(
            r"규격.{0,12}(?:승인|인증|검증|통과)|\bspecification.{0,20}(?:approval|test)", re.I
        ),
        re.compile(r"규격|\bspecification\b|\bspecs?\b", re.I),
    ),
)
# Recognize noun morphology, not the substring in modularity/modularization.
# Korean particles, plurals and noun compounds can attach without whitespace.
# Derivational 모듈화/모듈형/모듈러/모듈성 describe a form/process/quality and
# do not themselves assert a concrete module.
_MODULE_NOUN = re.compile(_MODULE_NOUN_PATTERN, re.I)
_MODULE_TARGET_ALIASES = {
    "memory": ("메모리", "램", "memory", "dram", "dimm"),
    "security": ("보안", "security", "secure"),
    "cooling": ("냉각", "cooling", "thermal"),
    "power": ("전력", "전원", "power"),
    "camera": ("카메라", "camera"),
    "optical": ("광학", "광", "optical"),
    "communication": ("통신", "communication", "communications"),
    "network": ("네트워크", "network", "networking"),
    "compute": ("연산", "컴퓨팅", "compute", "computing"),
    "sensor": ("센서", "sensor"),
    "software": ("소프트웨어", "software"),
}
_MODULE_TARGETS = {
    alias: target for target, aliases in _MODULE_TARGET_ALIASES.items() for alias in aliases
}
_MODULE_TARGET_SUFFIX = re.compile(
    r"(?<![가-힣A-Za-z0-9_])(?P<target>"
    + "|".join(re.escape(alias) for alias in sorted(_MODULE_TARGETS, key=len, reverse=True))
    + r")(?:용)?[\s-]*$",
    re.I,
)
_MODULE_TARGET_JOIN = re.compile(r"\s*(?:[·,/&]|및|과|와|\band\b|\bor\b)\s*$", re.I)
_MODULE_DASHES = str.maketrans({dash: "-" for dash in "‐‑–—"})
_TEAMS = re.compile(r"(?<![가-힣A-Za-z0-9])[가-힣A-Za-z][가-힣A-Za-z0-9_-]{0,19}팀")
_TEAM_RECOMMENDATION = re.compile(r"재검토|검토(?:해야|할|과제)|점검(?:해야|할)|고려")
_TEAM_ASSERTION = re.compile(r"대상|담당|주체|승인|전제|요건|절차|준비.{0,8}조정|조정.{0,8}준비")
# A negative existence statement exempts only its procedure noun phrase, not
# earlier assertions in the same clause ("... 준비하며 고객 승인 여부는 미확인").
_NO_REQUIREMENT = re.compile(
    r"(?P<object>(?:(?:고객|메모리|규격|냉각|호환성|검증|검수|인증|승인|모듈|"
    r"요건|절차|필요성|여부|존재|조건)(?:의)?[\s/·-]*){1,8})(?:은|는|이|가)\s*"
    r"(?:(?:있는지|필요한지)(?:는|도)?\s*)?(?:원문(?:에|에서|에는)?\s*)?"
    r"(?:미확인|불명|(?:확인|명시|제시)(?:되|되어)?지\s*않|알\s*수\s*없)"
)
# A coordinated "절차나 영향" object can explicitly describe source silence.
# Cover only its procedure noun phrase, not an unknown completion;
# a separate positive procedure elsewhere in the clause must still be checked.
_NO_PROCEDURE_DESCRIPTION = re.compile(
    r"(?P<object>(?:(?:고객|메모리|규격|냉각|호환성|검증|검수|인증|승인)"
    r"(?:의)?[\s/·-]*){1,6}(?:요건|절차|필요성|존재))"
    r"\s*(?:나|이나|와|과|및)\s*영향(?:을|를)\s*"
    r"(?:명시|제시)하지\s*않"
)
_DESCRIPTION_GATE = re.compile(r"선행|거쳐야|의무|강제|반드시")
_EXISTENCE_OBJECT = re.compile(r"요건|절차|필요성|여부|존재|규격|조건")
_OUTCOME_OBJECT = re.compile(r"결과|완료|통과|납품|설치")
_CLAUSE_JOIN = re.compile(r"[.!?。;；\n]|지만|으나|이고|이며|하고|그리고|\bbut\b", re.I)
_REQUIRED_GATE = re.compile(
    r"전제|필수|통과(?:해야|가)|없이는|(?:승인|검수|검증|인증)\s*전(?:에는|에|까지)?|"
    r"(?:요건|조건).{0,10}(?:유지|충족|삭제|있어야)"
)
_INTEGRATION_SOURCE = re.compile(
    r"통합|결합|연동|\bintegrat\w*|\b(?:optimized|designed)\s+to\s+use\b", re.I
)
_COMPATIBILITY_WORK = re.compile(
    r"호환성\s*(?:검증|시험)\s*업무(?:와|에)\s*"
    r"(?:직접(?:적(?:으로)?)?\s*)?(?:연결|관련|해당)"
)
# A coordinated list of work perspectives is not one compatibility test.
# Require source design-rule context and an explicit work interpretation; actual
# required/performed procedures in the same clause remain subject to the guard.
_COORDINATED_PROCESS_WORK = re.compile(r"호환성\s*[·,/]\s*공정\s*(?:검증|시험)\s*(?:관점|업무)")
_WORK_RELATION = re.compile(r"업무(?:와|에)\s*(?:직접(?:적(?:으로)?)?\s*)?(?:연결|관련|해당)")
_DESIGN_RULE_SOURCE = re.compile(r"설계\s*규칙|\bdesign\s+rules?\b", re.I)
_PROCEDURE_ACTION = re.compile(r"필요|요구|수행|진행|통과|실시|완료")
# In semiconductor prose qualification is a technical evaluation, not necessarily
# a regulator's certification. Bind it directly to an evaluated object/predicate;
# staff qualifications, qualified opinions and distant reliability clauses do
# not supply that relation. These surface forms establish only procedure family,
# never its authority, necessity, successful completion, or a new customer gate.
_TECHNICAL_OBJECT = (
    r"(?:semiconductor\s+(?:devices?|chips?)|chips?|semiconductors?|devices?|"
    r"products?|process(?:es)?|components?)"
)
_TECHNICAL_QUALIFICATION_SOURCE = re.compile(
    rf"\b{_TECHNICAL_OBJECT}\s+"
    r"(?:(?:are|is|were|was|be|been|being|have|has|had|can|will|must|should|to|"
    r"designed|tested|evaluated|and|then|also)\s+){1,8}qualif(?:ied|ying)\b|"
    rf"\b{_TECHNICAL_OBJECT}\s+(?:(?:design|reliability|environmental)\s+)?"
    r"qualification\s+(?:steps?|stages?|tests?|process(?:es)?|procedures?|programs?)\b|"
    rf"\bqualification\s+of\s+(?:(?:the|a|an|new|these|those)\s+)?"
    rf"{_TECHNICAL_OBJECT}\b"
    r"(?=[.,;:!?]|\s+(?:is|are|was|were|has|have|had|will|can|must|should|"
    r"includes?|involves?|requires?|uses?|for|in|under|with)\b)",
    re.I,
)
_TECHNICAL_CERTIFICATION = re.compile(r"(?:공정|칩|제품|소자|장치|부품|신뢰성)(?:의)?\s*인증")
_OTHER_CERTIFICATION_OR_OUTCOME = re.compile(
    r"고객|정부|당국|법정|법률|규제|공인|의무|강제|기관|제삼자|제3자|"
    r"\b(?:regulatory|government|customer|third[-\s]party|ISO|IEC|UL|CE|FCC)\b|"
    r"인증.{0,12}(?:승인|완료|통과|획득|취득|마쳤|마친|마침|끝냈|성공)",
    re.I,
)
_TEAM_TRANSLATIONS = {
    "운영팀": r"\b(?:operations?|operating)\s+team\b",
    "인증팀": r"\b(?:certification|qualification)\s+team\b",
    "설치팀": r"\binstallation\s+team\b",
    "재무팀": r"\b(?:finance|financial)\s+team\b",
    "구매팀": r"\b(?:purchasing|procurement)\s+team\b",
    "조달팀": r"\bprocurement\s+team\b",
    "인프라팀": r"\binfrastructure\s+team\b",
}


def _absence_ranges(clause: str) -> list[tuple[int, int]]:
    if _REQUIRED_GATE.search(clause):
        return []
    ranges = [
        (match.start(), match.end())
        for match in _NO_REQUIREMENT.finditer(clause)
        if _EXISTENCE_OBJECT.search(match["object"]) and not _OUTCOME_OBJECT.search(match["object"])
    ]
    if not _PROCEDURE_ACTION.search(clause) and not _DESCRIPTION_GATE.search(clause):
        ranges.extend(
            (match.start("object"), match.end("object"))
            for match in _NO_PROCEDURE_DESCRIPTION.finditer(clause)
        )
    return ranges


def _team_supported(team: str, source: str) -> bool:
    if team in source:
        return True
    translation = _TEAM_TRANSLATIONS.get(team)
    return bool(translation and re.search(translation, source, re.I))


def _generic_compatibility_work(kind: str, match, clause: str, source: str) -> bool:
    # Integration can be interpreted as compatibility work without inventing
    # an actual test or prerequisite. Keep this exemption local to the matched
    # work phrase; any required/performed procedure still needs literal support.
    return bool(
        kind == "compatibility_procedure"
        and (
            (
                _COMPATIBILITY_WORK.match(clause, match.start())
                and _INTEGRATION_SOURCE.search(source)
            )
            or (
                _COORDINATED_PROCESS_WORK.match(clause, match.start())
                and _WORK_RELATION.search(clause)
                and _DESIGN_RULE_SOURCE.search(source)
            )
        )
        and not _REQUIRED_GATE.search(clause)
        and not _PROCEDURE_ACTION.search(clause)
    )


def _technical_qualification_work(kind: str, match, clause: str, source: str) -> bool:
    return bool(
        kind == "certification_prerequisite"
        and _TECHNICAL_QUALIFICATION_SOURCE.search(source)
        and any(
            term.start() <= match.start() < term.end()
            for term in _TECHNICAL_CERTIFICATION.finditer(clause)
        )
        and not _REQUIRED_GATE.search(clause)
        and not _DESCRIPTION_GATE.search(clause)
        and not _OTHER_CERTIFICATION_OR_OUTCOME.search(clause)
    )


def _module_mentions(value: str):
    normalized = unicodedata.normalize("NFKC", value).casefold().translate(_MODULE_DASHES)
    for match in _MODULE_NOUN.finditer(normalized):
        prefix = normalized[: match.start()].rstrip()
        targets = set()
        while target := _MODULE_TARGET_SUFFIX.search(prefix):
            targets.add(_MODULE_TARGETS[target["target"]])
            prefix = prefix[: target.start()]
            join = _MODULE_TARGET_JOIN.search(prefix)
            if join is None:
                break
            prefix = prefix[: join.start()]
        yield match, frozenset(targets)


def _unsupported_module(clause: str, source_modules) -> bool:
    normalized = unicodedata.normalize("NFKC", clause).casefold().translate(_MODULE_DASHES)
    absent = _absence_ranges(normalized)
    supported_targets = {target for _, targets in source_modules for target in targets}
    for match, targets in _module_mentions(normalized):
        if any(start <= match.start() and match.end() <= end for start, end in absent):
            continue
        # A concrete noun needs source support. Known bilingual target names
        # must attach to the module itself: "security" elsewhere in a source
        # containing memory modules cannot establish a security module.
        if not source_modules or targets - supported_targets:
            return True
        # Unrecognized modifiers remain undecided by this narrow lexical guard.
        # Matching a noun/type does not prove its owner, use, existence polarity
        # or event stage; source/prose and semantic checks retain those duties.
    return False


def work_prose_problems(value: str, source: str) -> tuple[str, ...]:
    """Recognize explicit unsupported procedures; absence statements stay open."""
    problems = []
    source_modules = tuple(_module_mentions(source))
    for clause in _CLAUSE_JOIN.split(value):
        if not clause.strip():
            continue
        # Asking whether a requirement exists differs from treating it as an
        # established prerequisite with an unknown outcome.
        absent = _absence_ranges(clause)
        for kind, emitted, supported in _RULES:
            unsupported = any(
                not any(start <= match.start() and match.end() <= end for start, end in absent)
                and not _generic_compatibility_work(kind, match, clause, source)
                and not _technical_qualification_work(kind, match, clause, source)
                for match in emitted.finditer(clause)
            )
            if unsupported and not supported.search(source):
                problems.append(kind)
        if _unsupported_module(clause, source_modules):
            problems.append("physical_module")
        if _TEAM_ASSERTION.search(clause) and not _TEAM_RECOMMENDATION.search(clause):
            for match in _TEAMS.finditer(clause):
                if not _team_supported(match.group(), source):
                    problems.append("organization_prerequisite")
    return tuple(dict.fromkeys(problems))


def _cited_source(request: ReportInsightRequest, refs: Collection[str]) -> str:
    permitted = set(refs)
    pieces = []
    for finding in request.findings:
        sentences = {sentence.index: sentence.text for sentence in finding.sentences}
        for claim in finding.claims:
            if claim.id in permitted:
                pieces.append(claim.text)
                pieces.extend(sentences[index] for index in claim.evidence_sentence_ids)
    return "\n".join(pieces)


class ReportWorkValidationError(OutputValidationError):
    """One owned field/reference issue per work violation, including repeats."""

    def __init__(self, diagnostics):
        diagnostics = tuple(diagnostics)
        self.validation_issues = tuple(issue for issue, _ in diagnostics)
        self.repair_diagnostics = tuple(message for _, message in diagnostics)
        super().__init__(
            "구체 업무 전제·대상은 각 항목의 인용 claim와 연결 원문에서 지원되어야 합니다. "
            "원문에 없는 승인·검증 절차, 조직, 부품을 만들지 말고 알려진 사건의 판단 한계를 "
            "설명하거나 지원되지 않는 해석을 제외하세요.\n" + "\n".join(self.repair_diagnostics),
            error_kinds=tuple(issue.error_kind for issue in self.validation_issues),
        )


def validate_work_synthesis(
    output: ReportInsightReduceOutput | ReportInsightOutput,
    request: ReportInsightRequest,
    allowed: dict,
) -> None:
    """Use each item's own citations, including conditional/watch prose fields."""
    diagnostics = []
    for insight in output.insights:
        values = [("headline", insight.headline, allowed[insight.audience])]
        for name, items in (
            ("overview", insight.overview),
            ("implications", insight.implications),
            ("watchItems", insight.watch_items),
        ):
            for index, item in enumerate(items):
                for field, value in item.model_dump(
                    by_alias=True, exclude={"basis_claim_ids"}
                ).items():
                    values.append((f"{name}[{index}].{field}", value, item.basis_claim_ids))
        for path, value, refs in values:
            problems = work_prose_problems(value, _cited_source(request, refs))
            for problem in problems:
                diagnostics.append(
                    (
                        ReportValidationIssue(
                            insight.audience,
                            path,
                            f"report_work_{problem}_unsupported",
                            tuple(refs),
                        ),
                        f"{insight.audience}.{path}: {problem}",
                    )
                )
    if diagnostics:
        raise ReportWorkValidationError(diagnostics)
