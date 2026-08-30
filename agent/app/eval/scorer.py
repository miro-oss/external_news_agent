import re
from dataclasses import dataclass

from app.core.evidence import assess_with_rules
from app.core.report_grounding import assess_finding_claim
from app.eval.dataset import ClaimValidity, GoldenClaimControl
from app.schemas.analyze import Groundedness
from app.schemas.report import ReportFindingInput, ReportRequest, ReportResponse

_HANGUL = re.compile(r"[가-힣]")
_LATIN = re.compile(r"[A-Za-z]")
_EXECUTIVE_CLAUSE_SEPARATOR = re.compile(
    r"(?:[.!?。！？;；\n]+|,\s+|\s+(?:및|그리고)\s+|(?:하고|했고|이며|이고|였고|됐고),?\s+)"
)
_HIGHER_IS_BETTER = (
    "caseCount",
    "schemaChecks",
    "schemaPasses",
    "schemaPassRate",
    "bulletCount",
    "groundedBulletCount",
    "groundedRate",
    "koreanSummaryPasses",
    "koreanSummaryPassRate",
    "highSensitivityEvidenceRate",
    "perspectiveTagAccuracy",
    "reportClaimCount",
    "reportGroundedClaimCount",
    "evidenceRuleDecisionCount",
    "evidenceProviderCallReductionRate",
    "invalidClaimCount",
    "positiveControlCount",
)
_LOWER_IS_BETTER = (
    "summaryLengthP95",
    "reportWeakClaimCount",
    "unsupportedReportClaimCount",
    "evidenceProviderCallCount",
    "falsePassRate",
    "falseRejectCount",
)
_METADATA_KEYS = (
    "datasetVersion",
    "baselinePromptVersion",
    "claimLabelsVersion",
    "profile",
    "plan",
)
_PROMPT_KEYS = ("analyzePromptVersion", "reportPromptVersion")


class ComparisonError(ValueError):
    """Raised when two evaluation results are not safely comparable."""


@dataclass(frozen=True, slots=True)
class ReportClaimScore:
    key: str
    status: Groundedness


@dataclass(frozen=True, slots=True)
class ClaimControlScore:
    claim_id: str
    validity: ClaimValidity
    status: Groundedness


@dataclass(frozen=True, slots=True)
class ClaimControlCounts:
    invalid_claim_count: int
    false_pass_count: int
    positive_control_count: int
    false_reject_count: int

    @classmethod
    def from_scores(cls, scores: list[ClaimControlScore]) -> "ClaimControlCounts":
        invalid = [score for score in scores if score.validity == "invalid"]
        positive = [score for score in scores if score.validity == "valid"]
        return cls(
            invalid_claim_count=len(invalid),
            false_pass_count=sum(score.status == "grounded" for score in invalid),
            positive_control_count=len(positive),
            false_reject_count=sum(score.status != "grounded" for score in positive),
        )

    def to_dict(self) -> dict[str, int | float]:
        return {
            "invalidClaimCount": self.invalid_claim_count,
            "falsePassCount": self.false_pass_count,
            "falsePassRate": _rate(self.false_pass_count, self.invalid_claim_count),
            "positiveControlCount": self.positive_control_count,
            "falseRejectCount": self.false_reject_count,
        }


@dataclass(frozen=True, slots=True)
class MetricCounts:
    case_count: int
    schema_checks: int
    schema_passes: int
    bullet_count: int
    grounded_bullet_count: int
    korean_summary_passes: int
    summary_length_p50: int
    summary_length_p95: int
    summary_length_max: int
    high_sensitivity_count: int
    high_sensitivity_evidence_count: int
    perspective_tag_checks: int
    perspective_tag_correct_count: int
    report_claim_count: int
    report_grounded_claim_count: int
    report_weak_claim_count: int
    unsupported_report_claim_count: int
    evidence_verification_count: int
    evidence_rule_decision_count: int
    claim_control_counts: ClaimControlCounts

    def to_dict(self) -> dict[str, int | float]:
        payload: dict[str, int | float] = {
            "caseCount": self.case_count,
            "schemaChecks": self.schema_checks,
            "schemaPasses": self.schema_passes,
            "schemaPassRate": _rate(self.schema_passes, self.schema_checks),
            "bulletCount": self.bullet_count,
            "groundedBulletCount": self.grounded_bullet_count,
            "groundedRate": _rate(self.grounded_bullet_count, self.bullet_count),
            "koreanSummaryPasses": self.korean_summary_passes,
            "koreanSummaryPassRate": _rate(self.korean_summary_passes, self.case_count),
            "summaryLengthP50": self.summary_length_p50,
            "summaryLengthP95": self.summary_length_p95,
            "summaryLengthMax": self.summary_length_max,
            "highSensitivityCount": self.high_sensitivity_count,
            "highSensitivityEvidenceCount": self.high_sensitivity_evidence_count,
            "highSensitivityEvidenceRate": _rate(
                self.high_sensitivity_evidence_count,
                self.high_sensitivity_count,
            ),
            "perspectiveTagChecks": self.perspective_tag_checks,
            "perspectiveTagCorrectCount": self.perspective_tag_correct_count,
            "perspectiveTagAccuracy": _rate(
                self.perspective_tag_correct_count,
                self.perspective_tag_checks,
            ),
            "reportClaimCount": self.report_claim_count,
            "reportGroundedClaimCount": self.report_grounded_claim_count,
            "reportWeakClaimCount": self.report_weak_claim_count,
            "unsupportedReportClaimCount": self.unsupported_report_claim_count,
            "evidenceVerificationCount": self.evidence_verification_count,
            "evidenceRuleDecisionCount": self.evidence_rule_decision_count,
            "evidenceProviderCallCount": (
                self.evidence_verification_count - self.evidence_rule_decision_count
            ),
            "evidenceProviderCallReductionRate": _rate(
                self.evidence_rule_decision_count,
                self.evidence_verification_count,
            ),
        }
        payload.update(self.claim_control_counts.to_dict())
        return payload


def korean_summary_pass(summary: str) -> bool:
    hangul_count = len(_HANGUL.findall(summary))
    linguistic_count = hangul_count + len(_LATIN.findall(summary))
    return hangul_count >= 5 and hangul_count / linguistic_count >= 0.5


def score_claim_controls(
    controls: list[GoldenClaimControl],
    *,
    grounded_overlap: float,
    weak_overlap: float,
) -> list[ClaimControlScore]:
    scores = []
    for control in controls:
        for label in control.labels:
            assessment = assess_with_rules(
                label.claim,
                control.evidence,
                grounded_overlap=grounded_overlap,
                weak_overlap=weak_overlap,
            )
            scores.append(
                ClaimControlScore(
                    claim_id=label.claim_id,
                    validity=label.validity,
                    status=assessment.status,
                )
            )
    return scores


def score_report_claims(
    response: ReportResponse,
    request: ReportRequest,
    *,
    grounded_overlap: float,
    weak_overlap: float,
) -> list[ReportClaimScore]:
    finding_by_id = {finding.id: finding for finding in request.findings}
    scores = [
        ReportClaimScore(
            key=f"executiveSummary:{index}",
            status=_executive_summary_status(
                summary,
                request.findings,
                grounded_overlap=grounded_overlap,
                weak_overlap=weak_overlap,
            ),
        )
        for index, summary in enumerate(response.executive_summary)
    ]
    for index, event in enumerate(response.important_events):
        findings = _referenced_findings(event.source_finding_ids, finding_by_id)
        scores.extend(
            [
                ReportClaimScore(
                    key=f"importantEvents:{index}:summaryKo",
                    status=_combined_finding_status(
                        event.summary_ko,
                        findings,
                        grounded_overlap=grounded_overlap,
                        weak_overlap=weak_overlap,
                    ),
                ),
                ReportClaimScore(
                    key=f"importantEvents:{index}:significance",
                    status=_combined_finding_status(
                        event.significance,
                        findings,
                        grounded_overlap=grounded_overlap,
                        weak_overlap=weak_overlap,
                    ),
                ),
            ]
        )
    for index, item in enumerate(response.watch_items):
        scores.append(
            ReportClaimScore(
                key=f"watchItems:{index}:reason",
                status=_combined_finding_status(
                    item.reason,
                    _referenced_findings(item.source_finding_ids, finding_by_id),
                    grounded_overlap=grounded_overlap,
                    weak_overlap=weak_overlap,
                ),
            )
        )
    return scores


def compare_results(
    current: dict[str, object],
    baseline: dict[str, object],
    *,
    allow_prompt_version_change: bool = False,
) -> dict[str, object]:
    _require_complete_result(current, "current result")
    _require_complete_result(baseline, "baseline")
    for key in _METADATA_KEYS:
        _require_key(current, key, "current result")
        _require_key(baseline, key, "baseline")
        if current[key] != baseline[key]:
            raise ComparisonError(
                f"metadata mismatch for {key}: current={current[key]!r}, baseline={baseline[key]!r}"
            )
    for key in _PROMPT_KEYS:
        _require_key(current, key, "current result")
        _require_key(baseline, key, "baseline")
        if not allow_prompt_version_change and current[key] != baseline[key]:
            raise ComparisonError(
                f"prompt version mismatch for {key}: "
                f"current={current[key]!r}, baseline={baseline[key]!r}"
            )

    _require_key(current, "config", "current result")
    _require_key(baseline, "config", "baseline")
    if current["config"] != baseline["config"]:
        raise ComparisonError("runtime config differs from the baseline")

    current_metrics = _metrics(current, "current result")
    baseline_metrics = _metrics(baseline, "baseline")
    return compare_metrics(current_metrics, baseline_metrics)


def compare_metrics(
    current: dict[str, object],
    baseline: dict[str, object],
) -> dict[str, object]:
    required = (*_HIGHER_IS_BETTER, *_LOWER_IS_BETTER)
    missing_current = [metric for metric in required if metric not in current]
    missing_baseline = [metric for metric in required if metric not in baseline]
    if missing_current or missing_baseline:
        parts = []
        if missing_current:
            parts.append("current metrics missing: " + ", ".join(missing_current))
        if missing_baseline:
            parts.append("baseline metrics missing: " + ", ".join(missing_baseline))
        raise ComparisonError("; ".join(parts))

    deltas = {
        metric: round(
            _number(current, metric, "current metrics")
            - _number(baseline, metric, "baseline metrics"),
            6,
        )
        for metric in required
    }
    regressions = [
        metric
        for metric in _HIGHER_IS_BETTER
        if _number(current, metric, "current metrics")
        < _number(baseline, metric, "baseline metrics")
    ]
    regressions.extend(
        metric
        for metric in _LOWER_IS_BETTER
        if _number(current, metric, "current metrics")
        > _number(baseline, metric, "baseline metrics")
    )
    return {"deltas": deltas, "regressions": regressions}


def _best_single_finding_status(
    claim: str,
    findings: list[ReportFindingInput],
    *,
    grounded_overlap: float,
    weak_overlap: float,
) -> Groundedness:
    statuses = [
        _combined_finding_status(
            claim,
            [finding],
            grounded_overlap=grounded_overlap,
            weak_overlap=weak_overlap,
        )
        for finding in findings
    ]
    for status in ("grounded", "weak", "ungrounded"):
        if status in statuses:
            return status
    return "ungrounded"


def _executive_summary_status(
    claim: str,
    findings: list[ReportFindingInput],
    *,
    grounded_overlap: float,
    weak_overlap: float,
) -> Groundedness:
    clauses = [
        clause.strip()
        for clause in _EXECUTIVE_CLAUSE_SEPARATOR.split(claim)
        if clause.strip()
    ]
    statuses = [
        _best_single_finding_status(
            clause,
            findings,
            grounded_overlap=grounded_overlap,
            weak_overlap=weak_overlap,
        )
        for clause in clauses
    ]
    if not statuses or "ungrounded" in statuses:
        return "ungrounded"
    return "weak" if "weak" in statuses else "grounded"


def _combined_finding_status(
    claim: str,
    findings: list[ReportFindingInput],
    *,
    grounded_overlap: float,
    weak_overlap: float,
) -> Groundedness:
    assessment = assess_finding_claim(
        claim,
        findings,
        grounded_overlap=grounded_overlap,
        weak_overlap=weak_overlap,
    )
    if assessment.status == "grounded":
        return "grounded"
    if assessment.status == "weak":
        return "weak"
    return "ungrounded"


def _referenced_findings(
    finding_ids: list[int],
    finding_by_id: dict[int, ReportFindingInput],
) -> list[ReportFindingInput]:
    return [finding_by_id[finding_id] for finding_id in finding_ids]


def _metrics(payload: dict[str, object], source: str) -> dict[str, object]:
    _require_key(payload, "metrics", source)
    metrics = payload["metrics"]
    if not isinstance(metrics, dict):
        raise ComparisonError(f"{source} metrics must be an object")
    return metrics


def _require_complete_result(payload: dict[str, object], source: str) -> None:
    _require_key(payload, "complete", source)
    if payload["complete"] is not True:
        raise ComparisonError(f"{source} is incomplete and cannot be used for comparison")
    _require_key(payload, "errors", source)
    errors = payload["errors"]
    if not isinstance(errors, list):
        raise ComparisonError(f"{source} errors must be an array")
    if errors:
        raise ComparisonError(f"{source} contains evaluation errors")


def _number(payload: dict[str, object], key: str, source: str) -> float:
    value = payload[key]
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ComparisonError(f"{source} {key} must be numeric")
    return float(value)


def _require_key(payload: dict[str, object], key: str, source: str) -> None:
    if key not in payload:
        raise ComparisonError(f"{source} missing required key: {key}")


def _rate(numerator: int, denominator: int) -> float:
    return round(numerator / denominator, 6) if denominator else 0.0
