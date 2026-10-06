"""Narrow checks for invented concrete work prerequisites and physical objects.

Literal citations do not establish semantic entailment. These checks catch a
few explicit source-absent procedure/object assertions; they neither prove role
relevance nor assign importance. Generic work interpretations, such as reviewing
procurement costs after a price move, are intentionally outside this detector.
"""

import re
from collections.abc import Collection

from app.core.errors import OutputValidationError
from app.schemas.report_insight import (
    ReportInsightOutput,
    ReportInsightReduceOutput,
    ReportInsightRequest,
)

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
            r"냉각.{0,12}(?:검증|모듈|호환성|승인)|(?:cooling|thermal).{0,20}(?:module|test)", re.I
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
    (
        "physical_module",
        re.compile(r"모듈|\bmodule\b", re.I),
        re.compile(r"모듈|\bmodule\b", re.I),
    ),
)
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
_PROCEDURE_ACTION = re.compile(r"필요|요구|수행|진행|통과|실시|완료")
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
    return [
        (match.start(), match.end())
        for match in _NO_REQUIREMENT.finditer(clause)
        if _EXISTENCE_OBJECT.search(match["object"]) and not _OUTCOME_OBJECT.search(match["object"])
    ]


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
        and _COMPATIBILITY_WORK.match(clause, match.start())
        and _INTEGRATION_SOURCE.search(source)
        and not _REQUIRED_GATE.search(clause)
        and not _PROCEDURE_ACTION.search(clause)
    )


def work_prose_problems(value: str, source: str) -> tuple[str, ...]:
    """Recognize explicit unsupported procedures; absence statements stay open."""
    problems = []
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
                for match in emitted.finditer(clause)
            )
            if unsupported and not supported.search(source):
                problems.append(kind)
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


def validate_work_synthesis(
    output: ReportInsightReduceOutput | ReportInsightOutput,
    request: ReportInsightRequest,
    allowed: dict,
) -> None:
    """Use each item's own citations, including conditional/watch prose fields."""
    errors, kinds = [], []
    for insight in output.insights:
        values = [("headline", insight.headline, allowed[insight.audience])]
        for name, items in (
            ("overview", insight.overview),
            ("implications", insight.implications),
            ("watchItems", insight.watch_items),
        ):
            for index, item in enumerate(items):
                for field, value in item.model_dump(exclude={"basis_claim_ids"}).items():
                    values.append((f"{name}[{index}].{field}", value, item.basis_claim_ids))
        for path, value, refs in values:
            problems = work_prose_problems(value, _cited_source(request, refs))
            if problems:
                kinds.extend(problems)
                errors.append(f"{insight.audience}.{path}: {', '.join(problems)}")
    if errors:
        raise OutputValidationError(
            "구체 업무 전제·대상은 각 항목의 인용 claim와 연결 원문에서 지원되어야 합니다. "
            "원문에 없는 승인·검증 절차, 조직, 부품을 만들지 말고 알려진 사건의 판단 한계를 "
            "설명하거나 지원되지 않는 해석을 제외하세요.\n" + "\n".join(errors),
            error_kinds=tuple(f"report_work_{kind}_unsupported" for kind in dict.fromkeys(kinds)),
        )
