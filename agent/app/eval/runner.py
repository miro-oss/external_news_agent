import json
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Literal

from pydantic import ValidationError

from app.core.config import Settings
from app.core.errors import AgentError
from app.eval.dataset import GoldenCase, GoldenDataset
from app.eval.scorer import (
    MetricCounts,
    korean_summary_pass,
    unsupported_report_claim_count,
)
from app.llm.analyze_service import PROMPT_VERSION as ANALYZE_PROMPT_VERSION
from app.llm.analyze_service import ArticleAnalyzeService
from app.llm.base import ProviderResponse, ProviderUsage
from app.llm.report_service import PROMPT_VERSION as REPORT_PROMPT_VERSION
from app.llm.report_service import ReportWriterService
from app.schemas.analyze import AnalyzeRequest, AnalyzeResponse, Plan
from app.schemas.report import ReportFindingInput, ReportRequest, ReportResponse

EvalProfile = Literal["replay", "live"]


@dataclass(frozen=True, slots=True)
class EvalError:
    check: str
    code: str

    def to_dict(self) -> dict[str, str]:
        return {"check": self.check, "code": self.code}


@dataclass(frozen=True, slots=True)
class EvalResult:
    dataset_version: str
    baseline_prompt_version: str
    analyze_prompt_version: str
    report_prompt_version: str
    profile: EvalProfile
    plan: Plan
    metrics: dict[str, int | float]
    errors: tuple[EvalError, ...]

    def to_dict(self) -> dict[str, object]:
        return {
            "datasetVersion": self.dataset_version,
            "baselinePromptVersion": self.baseline_prompt_version,
            "analyzePromptVersion": self.analyze_prompt_version,
            "reportPromptVersion": self.report_prompt_version,
            "profile": self.profile,
            "plan": self.plan,
            "metrics": self.metrics,
            "errors": [error.to_dict() for error in self.errors],
        }


class ReplayProvider:
    def __init__(self, payload: dict[str, object]) -> None:
        self._text = json.dumps(payload, ensure_ascii=False)

    def generate(
        self,
        *,
        system_instruction: str,
        prompt: str,
        response_schema: dict[str, object],
    ) -> ProviderResponse:
        del system_instruction, prompt, response_schema
        return ProviderResponse(
            text=self._text,
            provider="gemini",
            model="golden-replay",
            usage=ProviderUsage(),
        )


def run_evaluation(
    dataset: GoldenDataset,
    *,
    profile: EvalProfile = "replay",
    plan: Plan = "FREE",
    settings: Settings | None = None,
) -> EvalResult:
    execution_settings = (settings or Settings()).model_copy(update={"mock": False})
    responses: list[tuple[GoldenCase, AnalyzeResponse]] = []
    errors: list[EvalError] = []
    schema_passes = 0

    for case in dataset.cases:
        provider = (
            ReplayProvider(case.replay.model_dump(by_alias=True, mode="json"))
            if profile == "replay"
            else None
        )
        try:
            response = ArticleAnalyzeService(execution_settings, provider).analyze(
                _analyze_request(case, plan)
            )
        except (AgentError, ValidationError, ValueError) as error:
            errors.append(_eval_error(case.case_id, error))
            continue
        responses.append((case, response))
        schema_passes += 1

    report_request = _report_request(dataset, responses, plan)
    report_response: ReportResponse | None = None
    try:
        report_provider = (
            ReplayProvider(_replay_report_output(report_request)) if profile == "replay" else None
        )
        report_response = ReportWriterService(execution_settings, report_provider).write(
            report_request
        )
        schema_passes += 1
    except (AgentError, ValidationError, ValueError) as error:
        errors.append(_eval_error("report", error))

    bullets = [
        bullet
        for _, response in responses
        for section in response.sections
        for bullet in section.bullets
    ]
    report_claim_count = 0
    unsupported_count = 0
    if report_response is not None:
        report_claim_count, unsupported_count = unsupported_report_claim_count(
            report_response,
            report_request,
            grounded_overlap=execution_settings.evidence_grounded_overlap,
            weak_overlap=execution_settings.evidence_weak_overlap,
        )

    counts = MetricCounts(
        case_count=len(dataset.cases),
        schema_checks=len(dataset.cases) + 1,
        schema_passes=schema_passes,
        bullet_count=len(bullets),
        grounded_bullet_count=sum(bullet.groundedness == "grounded" for bullet in bullets),
        korean_summary_passes=sum(
            korean_summary_pass(response.summary_ko) for _, response in responses
        ),
        report_claim_count=report_claim_count,
        unsupported_report_claim_count=unsupported_count,
    )
    return EvalResult(
        dataset_version=dataset.version,
        baseline_prompt_version=dataset.baseline_prompt_version,
        analyze_prompt_version=ANALYZE_PROMPT_VERSION,
        report_prompt_version=REPORT_PROMPT_VERSION,
        profile=profile,
        plan=plan,
        metrics=counts.to_dict(),
        errors=tuple(errors),
    )


def _analyze_request(case: GoldenCase, plan: Plan) -> AnalyzeRequest:
    return AnalyzeRequest(
        idempotency_key=f"golden:{case.case_id}",
        plan=plan,
        article=case.article,
        topic=case.topic,
    )


def _report_request(
    dataset: GoldenDataset,
    responses: list[tuple[GoldenCase, AnalyzeResponse]],
    plan: Plan,
) -> ReportRequest:
    timestamp = datetime(2026, 8, 25, tzinfo=UTC)
    findings = []
    topics = []
    for case, response in responses:
        topics.append(case.topic.name)
        findings.append(
            {
                "id": case.article.id,
                "articleId": case.article.id,
                "articleTitle": case.article.title,
                "canonicalUrl": case.article.canonical_url,
                "sourceName": "Golden eval",
                "changeType": "NEW",
                "summaryKo": response.summary_ko,
                "keyPoints": [
                    bullet.text
                    for section in response.sections
                    for bullet in section.bullets
                    if bullet.groundedness != "ungrounded"
                ],
                "intent": response.classification.intent,
                "sentiment": response.classification.sentiment,
                "riskLevel": response.classification.risk_level,
                "relevance": response.classification.relevance,
                "category": response.classification.category,
                "fetchStatus": "FULLTEXT",
            }
        )
    return ReportRequest.model_validate(
        {
            "idempotencyKey": f"golden:{dataset.version}:report",
            "plan": plan,
            "run": {
                "id": 1,
                "startedAt": timestamp.isoformat(),
                "finishedAt": timestamp.isoformat(),
                "topics": list(dict.fromkeys(topics)),
            },
            "findings": findings,
            "events": [],
            "sourceStats": {
                "collected": len(dataset.cases),
                "blocked": 0,
                "failed": len(dataset.cases) - len(responses),
            },
            "sourceNotes": [f"Golden eval {dataset.version}: 분석 성공 {len(responses)}건."],
        }
    )


def _replay_report_output(request: ReportRequest) -> dict[str, object]:
    important = [
        {
            "title": finding.article_title,
            "summaryKo": finding.summary_ko,
            "significance": _first_key_point(finding),
            "sourceFindingIds": [finding.id],
        }
        for finding in request.findings
        if finding.relevance == "important"
    ]
    important_ids = {finding_id for event in important for finding_id in event["sourceFindingIds"]}
    watch = [
        {
            "topic": finding.article_title,
            "reason": _first_key_point(finding),
            "sourceFindingIds": [finding.id],
        }
        for finding in request.findings
        if finding.id not in important_ids
    ]
    return {
        "title": "Golden eval 반도체 뉴스 보고서",
        "executiveSummary": [finding.summary_ko for finding in request.findings[:3]]
        or ["분석에 성공한 finding이 없습니다."],
        "importantEvents": important,
        "watchItems": watch,
        "sourceNotes": request.source_notes,
    }


def _first_key_point(finding: ReportFindingInput) -> str:
    return finding.key_points[0] if finding.key_points else finding.summary_ko


def _eval_error(check: str, error: Exception) -> EvalError:
    code = error.code if isinstance(error, AgentError) else type(error).__name__
    return EvalError(check=check, code=code)
