"""Narrow enum/reason contradictions, without judging or changing the source.

Only explicit present-tense declarations about the assessed work or the whole
impact axis are checked. Quoted, hypothetical, negated and narrower statements
are deliberately left to semantic review. This is not a category classifier.
"""

import re

from app.schemas.report_insight_assessment import ReportFindingAssessmentDraft

_QUOTED = re.compile(
    r'```[\s\S]*?```|`[^`]*`|"(?:\\.|[^"\\])*"|'
    r"'(?:\\.|[^'\\])*'|“[^”]*”|‘[^’]*’|「[^」]*」|『[^』]*』"
)
_QUOTE_MARKS = frozenset("\"'`“”‘’「」『』")
_CLAUSE_BREAK = re.compile(
    r"(?<=[.!?。！？;；,，\n])|\s+(?=(?:다만|그러나|하지만)\s)|"
    r"(?<=며)\s+|(?<=으나)\s+|(?<=하나)\s+"
)
_PREFIX = (
    r"(?:(?:현재(?:로서는)?|다만|그러나|하지만|원문만으로(?:는)?|"
    r"제공된\s*근거만으로(?:는)?)\s+)*"
)
_WORK = r"(?:(?:이|해당)\s*)?(?:관점(?:의|에서의)?\s*)?업무"
_ABSENT = r"없(?:다|습니다|음|어요|으며|으나|고)?"
# These are literal names of the already selected work enum, not article or
# industry keywords. A negated different work never determines this relation.
# Compound enums use the whole work name, not one possibly unrelated component.
_WORK_OBJECTS = {
    "PROCESS_QUALIFICATION": r"공정\s*(?:인증|검증)",
    "PRODUCTION_SCHEDULE": r"생산\s*일정",
    "YIELD_CAPACITY": r"수율\s*(?:[·/]|및|과)\s*생산\s*능력",
    "CUSTOMER_REQUIREMENTS": r"고객\s*요구사항",
    "MATERIAL_SUPPLY": r"소재\s*공급",
    "PROCESS_VALIDATION": r"공정\s*검증",
    "DESIGN_IN": r"설계\s*적용",
    "ORDER_BOOKING": r"수주",
    "DELIVERY_INSTALLATION": r"납품\s*(?:[·/]|및|과)\s*설치",
    "MAINTENANCE_SERVICE": r"유지보수(?:\s*서비스)?",
    "GUIDANCE": r"실적\s*전망",
    "CAPEX_EXECUTION": r"설비\s*투자\s*집행",
    "REVENUE_RECOGNITION": r"매출\s*인식",
    "PROFITABILITY": r"수익성",
    "SUPPLY_DEMAND_CONSTRAINT": r"수급\s*제약",
    "SYSTEM_PROCUREMENT": r"시스템\s*조달",
    "COMPATIBILITY": r"호환성",
    "POWER_COOLING": r"전력\s*(?:[·/]|및|과)\s*냉각",
    "NETWORK": r"네트워크",
    "DEPLOYMENT_OPERATIONS": r"배포\s*(?:[·/]|및|와)\s*운영",
}


def _nonrelation_pattern(subject: str) -> re.Pattern[str]:
    linked = subject + r"(?:와는?|과는?|에는?)\s*(?:직접(?:적|적인|적으로)?\s*)?"
    return re.compile(
        _PREFIX
        + r"(?:"
        + linked
        + r"(?:관련(?:성)?|연관(?:성)?|관계|연결)(?:이|가|은|는)?\s*"
        + _ABSENT
        + r"|"
        + subject
        + r"\s*(?:직접(?:적|적인)?\s*)?(?:관련성|연관성|관계|연결)"
        + r"(?:이|가|은|는)?\s*"
        + _ABSENT
        + r"|"
        + linked
        + r"(?:관련|연결)되지\s*않(?:는다|습니다|음|으며|으나)"
        + r"|"
        + linked
        + r"(?:관련된|관련한|연관된)\s*(?:내용|사건|사안|정보)(?:이|은)?\s*아니(?:다|며)"
        + r")"
    )


_NO_DIRECT_RELATION = _nonrelation_pattern(_WORK)
_WORK_NONRELATION = {work: _nonrelation_pattern(text) for work, text in _WORK_OBJECTS.items()}
_IMPACT_SUBJECT = (
    r"(?:(?:이|해당)\s*)?(?:관점(?:의|에서의)?\s*)?"
    r"(?:업무(?:상|의|에\s*미치는)?\s*)?"
    r"영향(?:의?\s*범위)?(?:\s*자체)?(?:이|가|은|는|을|를)?\s*"
)
_UNKNOWN_IMPACT = re.compile(
    _PREFIX
    + _IMPACT_SUBJECT
    + r"(?:"
    + r"불명확(?:하다|합니다|함|해요)?|불명(?:이다|입니다|임)?|"
    + r"미확인(?:이다|입니다|임)?|"
    + r"(?:판단|확인)(?:이)?\s*불가(?:하다|합니다|함|능하다|능합니다)?|"
    + r"(?:판단할|확인할|알)\s*수\s*없(?:다|습니다|음)|"
    + r"명확하지\s*않(?:다|습니다|음)|"
    + r"불명확(?:하여|하므로)\s+.+|"
    + r"미확인이므로\s+.+"
    + r")"
)


def _unquoted_declarations(reason: str):
    # Mask rather than delete quoted text, so its surrounding words cannot join
    # into a new assertion. An unmatched quote makes its clause too ambiguous.
    unquoted = _QUOTED.sub(lambda match: " " * len(match.group()), reason)
    for part in _CLAUSE_BREAK.split(unquoted):
        if "?" in part or "？" in part or any(char in _QUOTE_MARKS for char in part):
            continue
        clause = re.sub(r"\s+", " ", part).strip(" \t\r\n.;,。；，!！")
        if clause:
            yield clause


def assessment_coherence_errors(item: ReportFindingAssessmentDraft) -> list[str]:
    """Report explicit self-contradictions; never mutate a category or its proof.

    A smaller unknown (such as cost, magnitude, timing, or the reader company's
    outcome) is compatible with a known impact scope. Only the reason's whole
    impact declaration can conflict here. A non-direct connection is also valid
    for CONDITIONAL/BACKGROUND; this function does not promote it to DIRECT.
    """
    clauses = tuple(_unquoted_declarations(item.reason))
    errors = []
    work_pattern = _WORK_NONRELATION.get(item.work)
    if item.relation == "DIRECT" and any(
        _NO_DIRECT_RELATION.fullmatch(c) or (work_pattern and work_pattern.fullmatch(c))
        for c in clauses
    ):
        errors.append(
            "connection.relation: DIRECT와 reason의 명시적 직접 업무 관계 부정이 모순됩니다. "
            "해당 축과 설명을 근거에 맞게 함께 재검토하세요."
        )
    if item.impact_scope != "UNDETERMINED" and any(_UNKNOWN_IMPACT.fullmatch(c) for c in clauses):
        errors.append(
            "effect.impactScope: 판정 가능한 영향 범주와 reason의 전체 영향 범위 미확인이 "
            "모순됩니다. 미확인을 변경 없음으로 바꾸지 말고 해당 축과 설명을 함께 재검토하세요."
        )
    return errors
