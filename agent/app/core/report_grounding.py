from collections.abc import Sequence

from app.core.evidence import RuleAssessment, assess_with_rules
from app.schemas.evidence import EvidenceSentence
from app.schemas.report import ReportFindingInput


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
        for text in [finding.summary_ko, *(point.text for point in finding.key_points)]
    ]
    return assess_with_rules(
        claim,
        [EvidenceSentence(id=index, text=text) for index, text in enumerate(evidence_texts, 1)],
        grounded_overlap=grounded_overlap,
        weak_overlap=weak_overlap,
    )
