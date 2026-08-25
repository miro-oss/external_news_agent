import re
from dataclasses import dataclass

from app.core.evidence import assess_with_rules
from app.schemas.evidence import EvidenceSentence
from app.schemas.report import ReportRequest, ReportResponse

_HANGUL = re.compile(r"[가-힣]")
_LATIN = re.compile(r"[A-Za-z]")


@dataclass(frozen=True, slots=True)
class MetricCounts:
    case_count: int
    schema_checks: int
    schema_passes: int
    bullet_count: int
    grounded_bullet_count: int
    korean_summary_passes: int
    report_claim_count: int
    unsupported_report_claim_count: int

    def to_dict(self) -> dict[str, int | float]:
        return {
            "caseCount": self.case_count,
            "schemaChecks": self.schema_checks,
            "schemaPasses": self.schema_passes,
            "schemaPassRate": _rate(self.schema_passes, self.schema_checks),
            "bulletCount": self.bullet_count,
            "groundedBulletCount": self.grounded_bullet_count,
            "groundedRate": _rate(self.grounded_bullet_count, self.bullet_count),
            "koreanSummaryPasses": self.korean_summary_passes,
            "koreanSummaryPassRate": _rate(self.korean_summary_passes, self.case_count),
            "reportClaimCount": self.report_claim_count,
            "unsupportedReportClaimCount": self.unsupported_report_claim_count,
        }


def korean_summary_pass(summary: str) -> bool:
    hangul_count = len(_HANGUL.findall(summary))
    latin_count = len(_LATIN.findall(summary))
    linguistic_count = hangul_count + latin_count
    return hangul_count >= 5 and linguistic_count > 0 and hangul_count / linguistic_count >= 0.5


def unsupported_report_claim_count(
    response: ReportResponse,
    request: ReportRequest,
    *,
    grounded_overlap: float,
    weak_overlap: float,
) -> tuple[int, int]:
    evidence_by_finding = {
        finding.id: [finding.summary_ko, *finding.key_points] for finding in request.findings
    }
    all_finding_ids = list(evidence_by_finding)
    claims: list[tuple[str, list[int]]] = [
        (summary, all_finding_ids) for summary in response.executive_summary
    ]
    for event in response.important_events:
        claims.extend(
            [
                (event.summary_ko, event.source_finding_ids),
                (event.significance, event.source_finding_ids),
            ]
        )
    claims.extend((item.reason, item.source_finding_ids) for item in response.watch_items)

    unsupported = 0
    for claim, finding_ids in claims:
        evidence_texts = [
            text for finding_id in finding_ids for text in evidence_by_finding.get(finding_id, [])
        ]
        sentences = [
            EvidenceSentence(id=index, text=text) for index, text in enumerate(evidence_texts, 1)
        ]
        if not sentences:
            unsupported += 1
            continue
        assessment = assess_with_rules(
            claim,
            sentences,
            grounded_overlap=grounded_overlap,
            weak_overlap=weak_overlap,
        )
        if assessment.status == "ungrounded":
            unsupported += 1
    return len(claims), unsupported


def compare_metrics(
    current: dict[str, int | float],
    baseline: dict[str, int | float],
) -> dict[str, object]:
    higher_is_better = (
        "schemaPassRate",
        "groundedRate",
        "koreanSummaryPassRate",
    )
    lower_is_better = ("unsupportedReportClaimCount",)
    deltas = {
        metric: round(float(current[metric]) - float(baseline[metric]), 6)
        for metric in (*higher_is_better, *lower_is_better)
    }
    regressions = [
        metric for metric in higher_is_better if float(current[metric]) < float(baseline[metric])
    ]
    regressions.extend(
        metric for metric in lower_is_better if float(current[metric]) > float(baseline[metric])
    )
    return {"deltas": deltas, "regressions": regressions}


def _rate(numerator: int, denominator: int) -> float:
    return round(numerator / denominator, 6) if denominator else 0.0
