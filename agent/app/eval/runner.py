import json
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Literal

from pydantic import ValidationError

from app.core.config import Settings
from app.core.errors import AgentError
from app.eval.dataset import (
    GoldenCase,
    GoldenDataset,
    GoldenReportFixture,
    load_report_fixture,
)
from app.eval.scorer import MetricCounts, korean_summary_pass, score_report_claims
from app.llm.analyze_service import PROMPT_VERSION as ANALYZE_PROMPT_VERSION
from app.llm.analyze_service import ArticleAnalyzeService
from app.llm.base import ProviderResponse, ProviderUsage
from app.llm.report_service import PROMPT_VERSION as REPORT_PROMPT_VERSION
from app.llm.report_service import ReportWriterService
from app.schemas.analyze import AnalyzeRequest, AnalyzeResponse, Plan
from app.schemas.report import ReportRequest, ReportResponse

EvalProfile = Literal["replay", "live"]
_DEFAULT_REPORT_FIXTURE = Path(__file__).resolve().parent / "golden" / "report.ko.v1.json"


@dataclass(frozen=True, slots=True)
class EvalError:
    check: str
    code: str
    details: str | None = None

    def to_dict(self) -> dict[str, str]:
        payload = {"check": self.check, "code": self.code}
        if self.details is not None:
            payload["details"] = self.details
        return payload


@dataclass(frozen=True, slots=True)
class EvalConfig:
    provider_model: str
    evidence_grounded_overlap: float
    evidence_weak_overlap: float
    max_sentences: int
    schema_repair_attempts: int
    max_output_tokens: int
    report_max_output_tokens: int

    def to_dict(self) -> dict[str, str | int | float]:
        return {
            "providerModel": self.provider_model,
            "evidenceGroundedOverlap": self.evidence_grounded_overlap,
            "evidenceWeakOverlap": self.evidence_weak_overlap,
            "maxSentences": self.max_sentences,
            "schemaRepairAttempts": self.schema_repair_attempts,
            "maxOutputTokens": self.max_output_tokens,
            "reportMaxOutputTokens": self.report_max_output_tokens,
        }


@dataclass(frozen=True, slots=True)
class EvalResult:
    dataset_version: str
    baseline_prompt_version: str
    analyze_prompt_version: str
    report_prompt_version: str
    profile: EvalProfile
    plan: Plan
    config: EvalConfig
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
            "config": self.config.to_dict(),
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
            provider="mock",
            model="golden-replay",
            usage=ProviderUsage(),
        )


def run_evaluation(
    dataset: GoldenDataset,
    *,
    profile: EvalProfile = "replay",
    plan: Plan = "FREE",
    settings: Settings | None = None,
    report_fixture: GoldenReportFixture | None = None,
) -> EvalResult:
    source_settings = settings or Settings()
    execution_settings = source_settings.model_copy(update={"mock": False})
    fixture = _replay_fixture(dataset, profile, report_fixture)
    responses: list[tuple[GoldenCase, AnalyzeResponse]] = []
    errors: list[EvalError] = []
    schema_passes = 0

    for case in dataset.cases:
        provider = ReplayProvider(case.replay) if profile == "replay" else None
        try:
            response = ArticleAnalyzeService(execution_settings, provider).analyze(
                _analyze_request(case, plan)
            )
        except (AgentError, ValidationError, ValueError) as error:
            _record_analysis_outcome(
                case,
                profile=profile,
                observed_failures={"schema"},
                errors=errors,
                runtime_error=error,
            )
            continue

        observed_failures = set()
        if any(
            bullet.groundedness != "grounded"
            for section in response.sections
            for bullet in section.bullets
        ):
            observed_failures.add("grounding")
        if not korean_summary_pass(response.summary_ko):
            observed_failures.add("korean-summary")
        _record_analysis_outcome(
            case,
            profile=profile,
            observed_failures=observed_failures,
            errors=errors,
        )
        responses.append((case, response))
        schema_passes += 1

    report_request = _report_request(dataset, responses, plan)
    report_response: ReportResponse | None = None
    if not report_request.findings:
        errors.append(EvalError(check="report", code="NO_REPORT_INPUT"))
    else:
        try:
            report_provider = ReplayProvider(fixture.output) if fixture is not None else None
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
    claim_statuses = []
    if report_response is not None:
        claim_statuses = score_report_claims(
            report_response,
            report_request,
            grounded_overlap=execution_settings.evidence_grounded_overlap,
            weak_overlap=execution_settings.evidence_weak_overlap,
        )
        if fixture is not None:
            actual = {score.key: score.status for score in claim_statuses}
            if actual != fixture.expected_claim_statuses:
                errors.append(
                    EvalError(
                        check="report",
                        code="EXPECTED_OUTCOME_MISMATCH",
                        details=_expectation_difference(fixture.expected_claim_statuses, actual),
                    )
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
        report_claim_count=len(claim_statuses),
        report_grounded_claim_count=sum(score.status == "grounded" for score in claim_statuses),
        report_weak_claim_count=sum(score.status == "weak" for score in claim_statuses),
        unsupported_report_claim_count=sum(
            score.status == "ungrounded" for score in claim_statuses
        ),
    )
    return EvalResult(
        dataset_version=dataset.version,
        baseline_prompt_version=dataset.baseline_prompt_version,
        analyze_prompt_version=ANALYZE_PROMPT_VERSION,
        report_prompt_version=REPORT_PROMPT_VERSION,
        profile=profile,
        plan=plan,
        config=_eval_config(source_settings, profile, plan),
        metrics=counts.to_dict(),
        errors=tuple(errors),
    )


def _replay_fixture(
    dataset: GoldenDataset,
    profile: EvalProfile,
    fixture: GoldenReportFixture | None,
) -> GoldenReportFixture | None:
    if profile != "replay":
        return None
    selected = fixture or load_report_fixture(_DEFAULT_REPORT_FIXTURE)
    if selected.dataset_version != dataset.version:
        raise ValueError("report replay fixture의 datasetVersion이 일치하지 않습니다.")
    if selected.prompt_version != REPORT_PROMPT_VERSION:
        raise ValueError("report replay fixture의 promptVersion이 일치하지 않습니다.")
    return selected


def _record_analysis_outcome(
    case: GoldenCase,
    *,
    profile: EvalProfile,
    observed_failures: set[str],
    errors: list[EvalError],
    runtime_error: Exception | None = None,
) -> None:
    if profile == "replay":
        expected = set(case.expected_failures)
        if observed_failures != expected:
            errors.append(
                EvalError(
                    check=case.case_id,
                    code="EXPECTED_OUTCOME_MISMATCH",
                    details=_expectation_difference(expected, observed_failures),
                )
            )
            return
        if runtime_error is None or "schema" in expected:
            return
    if runtime_error is not None:
        errors.append(_eval_error(case.case_id, runtime_error))


def _expectation_difference(expected: object, actual: object) -> str:
    return f"expected={expected!r}, actual={actual!r}"


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


def _eval_config(settings: Settings, profile: EvalProfile, plan: Plan) -> EvalConfig:
    if profile == "replay":
        provider_model = "golden-replay"
    elif plan == "FREE":
        provider_model = settings.gemini_model
    else:
        provider_model = settings.mindlogic_claude_model
    return EvalConfig(
        provider_model=provider_model,
        evidence_grounded_overlap=settings.evidence_grounded_overlap,
        evidence_weak_overlap=settings.evidence_weak_overlap,
        max_sentences=settings.max_sentences,
        schema_repair_attempts=settings.schema_repair_attempts,
        max_output_tokens=settings.max_output_tokens,
        report_max_output_tokens=settings.report_max_output_tokens,
    )


def _eval_error(check: str, error: Exception) -> EvalError:
    code = error.code if isinstance(error, AgentError) else type(error).__name__
    return EvalError(check=check, code=code)
