import re
import unicodedata
from collections.abc import Sequence
from dataclasses import dataclass

from app.core.evidence import (
    RuleAssessment,
    assess_with_rules,
    has_forecast_qualifier,
    modality_overreach,
)
from app.schemas.evidence import EvidenceSentence
from app.schemas.report import ReportFindingInput

_WORD = re.compile(r"[A-Za-z0-9가-힣]+")
_CLAUSE_SEPARATOR = re.compile(
    r"(?:[.!?。！？;；\n]+|,\s+|\s+(?:및|그리고)\s+|"
    r"(?:하고|했고|이며|이고|였고|됐고),?\s+)"
)


@dataclass(frozen=True, slots=True)
class ReportPolicyViolation:
    reason: str
    fallback: str


def assess_finding_claim(
    claim: str,
    findings: Sequence[ReportFindingInput],
    *,
    grounded_overlap: float,
    weak_overlap: float,
) -> RuleAssessment:
    evidence_texts = [
        text
        for finding in findings
        for text in [
            finding.summary_ko,
            *(
                point.text
                for point in finding.key_points
                if point.groundedness != "ungrounded"
            ),
        ]
    ]
    return assess_with_rules(
        claim,
        [EvidenceSentence(id=index, text=text) for index, text in enumerate(evidence_texts, 1)],
        grounded_overlap=grounded_overlap,
        weak_overlap=weak_overlap,
    )


def report_claim_policy_violation(
    claim: str,
    findings: Sequence[ReportFindingInput],
) -> ReportPolicyViolation | None:
    matched_points = [
        point
        for finding in findings
        for point in finding.key_points
        if _related_claim(claim, point.text)
    ]
    if not matched_points:
        return None

    violations: list[ReportPolicyViolation] = []
    for point in matched_points:
        if point.claim_type == "FORECAST" and not has_forecast_qualifier(claim):
            violations.append(
                ReportPolicyViolation(
                    "전망 주장을 발생한 사실처럼 표현했습니다.", point.text
                )
            )
            continue
        if point.claim_type == "OPINION" and (
            point.attributed_to is None
            or point.attributed_to.casefold() not in claim.casefold()
        ):
            violations.append(
                ReportPolicyViolation(
                    "견해의 발화 주체가 리포트 문장에서 빠졌습니다.",
                    _attributed_opinion(point.attributed_to, point.text),
                )
            )
            continue
        if point.groundedness == "weak":
            modality = modality_overreach(claim, point.text)
            if modality is not None:
                violations.append(ReportPolicyViolation(modality.reason, point.text))
                continue
        return None
    return violations[0] if violations else None


def assess_independent_finding_claim(
    claim: str,
    findings: Sequence[ReportFindingInput],
    *,
    grounded_overlap: float,
    weak_overlap: float,
) -> RuleAssessment:
    clauses = [value.strip() for value in _CLAUSE_SEPARATOR.split(claim) if value.strip()]
    statuses: list[str] = []
    reasons: list[str] = []
    for clause in clauses:
        assessments = [
            assess_finding_claim(
                clause,
                [finding],
                grounded_overlap=grounded_overlap,
                weak_overlap=weak_overlap,
            )
            for finding in findings
        ]
        best = next(
            (
                assessment
                for status in ("grounded", "weak", "ungrounded")
                for assessment in assessments
                if assessment.status == status
            ),
            RuleAssessment("ungrounded", [], "연결된 finding이 없습니다."),
        )
        statuses.append(best.status)
        reasons.append(best.reason)
    if not statuses or "ungrounded" in statuses:
        return RuleAssessment("ungrounded", [], "; ".join(reasons))
    status = "weak" if "weak" in statuses else "grounded"
    return RuleAssessment(status, [], "; ".join(reasons))


def _related_claim(left: str, right: str) -> bool:
    left_tokens = _tokens(left)
    right_tokens = _tokens(right)
    if not left_tokens or not right_tokens:
        return False
    return len(left_tokens & right_tokens) / min(len(left_tokens), len(right_tokens)) >= 0.4


def _attributed_opinion(attributed_to: str | None, text: str) -> str:
    if attributed_to is None or attributed_to.casefold() in text.casefold():
        return text
    return f"{attributed_to}{_topic_particle(attributed_to)} {text}"


def _topic_particle(value: str) -> str:
    last = value.rstrip()[-1]
    if "가" <= last <= "힣":
        return "은" if (ord(last) - ord("가")) % 28 else "는"
    return "은"


def _tokens(value: str) -> set[str]:
    normalized = unicodedata.normalize("NFKC", value).casefold()
    return {match.group() for match in _WORD.finditer(normalized) if len(match.group()) >= 2}
