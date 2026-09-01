import json
import math
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Literal

from pydantic import ValidationError

from app.core.config import Settings
from app.core.errors import AgentError
from app.core.evidence import assess_with_decisive_rules
from app.eval.checkpoint import LiveCheckpointStore
from app.eval.dataset import (
    GoldenCase,
    GoldenClaimDataset,
    GoldenDataset,
    GoldenReportFixture,
    load_claim_dataset,
    load_report_fixture,
)
from app.eval.live_provider import (
    LiveProviderPolicy,
    LiveRequestCoordinator,
    PacedRetryProvider,
    default_live_policy,
)
from app.eval.scorer import (
    ClaimControlCounts,
    MetricCounts,
    korean_summary_pass,
    score_claim_controls,
    score_report_claims,
)
from app.llm.analyze_service import PROMPT_VERSION as ANALYZE_PROMPT_VERSION
from app.llm.analyze_service import ArticleAnalyzeService
from app.llm.base import AnalyzeProvider, ProviderResponse, ProviderUsage
from app.llm.report_service import PROMPT_VERSION as REPORT_PROMPT_VERSION
from app.llm.report_service import ReportWriterService
from app.llm.router import get_analyze_provider
from app.schemas.analyze import AUDIENCES, AnalyzeRequest, AnalyzeResponse, Plan, Sensitivity
from app.schemas.evidence import EvidenceSentence
from app.schemas.report import ReportRequest, ReportResponse

EvalProfile = Literal["replay", "live"]
_DEFAULT_CLAIM_DATASET = Path(__file__).resolve().parent / "golden" / "claims.ko.v1.json"
_DEFAULT_REPORT_FIXTURE = Path(__file__).resolve().parent / "golden" / "report.ko.v1.4.json"


def _classification_sensitivity(sensitivity: Sensitivity) -> dict[str, object]:
    named_axes = (
        ("customerMove", sensitivity.customer_move, 0.35),
        ("dealSignal", sensitivity.deal_signal, 0.30),
        ("competitorThreat", sensitivity.competitor_threat, 0.20),
        ("industryShift", sensitivity.industry_shift, 0.15),
    )
    available = [(axis.score, weight) for _, axis, weight in named_axes if axis.score is not None]
    weighted = sum(score * weight for score, weight in available)
    score = round(weighted * 100 / (sum(weight for _, weight in available) * 3), 2)
    level = "high" if score >= 70 else "medium" if score >= 40 else "low"
    return {
        "score": score,
        "level": level,
        "axes": {name: axis.model_dump(by_alias=True, mode="json") for name, axis, _ in named_axes},
    }


@dataclass(frozen=True, slots=True)
class EvalError:
    check: str
    code: str
    details: object | None = None

    def to_dict(self) -> dict[str, object]:
        payload: dict[str, object] = {"check": self.check, "code": self.code}
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
    live_policy: LiveProviderPolicy | None = None

    def to_dict(self) -> dict[str, object]:
        payload: dict[str, object] = {
            "providerModel": self.provider_model,
            "evidenceGroundedOverlap": self.evidence_grounded_overlap,
            "evidenceWeakOverlap": self.evidence_weak_overlap,
            "maxSentences": self.max_sentences,
            "schemaRepairAttempts": self.schema_repair_attempts,
            "maxOutputTokens": self.max_output_tokens,
            "reportMaxOutputTokens": self.report_max_output_tokens,
        }
        if self.live_policy is not None:
            payload["livePolicy"] = self.live_policy.to_dict()
        return payload


@dataclass(frozen=True, slots=True)
class EvalResult:
    dataset_version: str
    baseline_prompt_version: str
    claim_labels_version: str
    analyze_prompt_version: str
    report_prompt_version: str
    profile: EvalProfile
    plan: Plan
    config: EvalConfig
    complete: bool
    metrics: dict[str, int | float]
    claim_control_diagnostics: dict[str, list[str]]
    errors: tuple[EvalError, ...]

    def to_dict(self) -> dict[str, object]:
        return {
            "datasetVersion": self.dataset_version,
            "baselinePromptVersion": self.baseline_prompt_version,
            "claimLabelsVersion": self.claim_labels_version,
            "analyzePromptVersion": self.analyze_prompt_version,
            "reportPromptVersion": self.report_prompt_version,
            "profile": self.profile,
            "plan": self.plan,
            "config": self.config.to_dict(),
            "complete": self.complete,
            "metrics": self.metrics,
            "claimControlDiagnostics": self.claim_control_diagnostics,
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
    claim_dataset: GoldenClaimDataset | None = None,
    report_fixture: GoldenReportFixture | None = None,
    live_policy: LiveProviderPolicy | None = None,
    checkpoint_path: Path | None = None,
    resume: bool = False,
) -> EvalResult:
    if profile != "live" and (checkpoint_path is not None or resume):
        raise ValueError("checkpoint와 resume은 live profile에서만 사용할 수 있습니다.")
    if resume and checkpoint_path is None:
        raise ValueError("--resume에는 --checkpoint 경로가 필요합니다.")

    source_settings = settings or Settings()
    execution_settings = source_settings.model_copy(update={"mock": False})
    selected_live_policy = live_policy or default_live_policy(plan) if profile == "live" else None
    selected_claim_dataset = claim_dataset or load_claim_dataset(_DEFAULT_CLAIM_DATASET)
    config = _eval_config(source_settings, profile, plan, selected_live_policy)
    fixture = _replay_fixture(dataset, profile, report_fixture)
    checkpoint = (
        LiveCheckpointStore(
            checkpoint_path,
            dataset=dataset,
            analyze_prompt_version=ANALYZE_PROMPT_VERSION,
            report_prompt_version=REPORT_PROMPT_VERSION,
            plan=plan,
            config=config.to_dict(),
            resume=resume,
        )
        if checkpoint_path is not None
        else None
    )
    analysis_provider: AnalyzeProvider | None = None
    live_report_provider: AnalyzeProvider | None = None
    if profile == "live":
        assert selected_live_policy is not None
        analysis_provider, live_report_provider = _live_providers(
            execution_settings,
            plan,
            selected_live_policy,
        )
    responses: list[tuple[GoldenCase, AnalyzeResponse]] = []
    errors: list[EvalError] = []
    schema_passes = 0

    for case in dataset.cases:
        cached = checkpoint.analysis(case.case_id) if checkpoint is not None else None
        if cached is not None:
            responses.append((case, cached))
            schema_passes += 1
            continue

        provider = ReplayProvider(case.replay) if profile == "replay" else analysis_provider
        assert provider is not None
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
            if profile == "live" and _should_abort_live(error):
                break
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
        if checkpoint is not None:
            checkpoint.record_analysis(case.case_id, response)

    report_request = _report_request(dataset, responses, plan)
    report_response: ReportResponse | None = None
    if profile == "live" and len(responses) != len(dataset.cases):
        errors.append(
            EvalError(
                check="report",
                code="INCOMPLETE_ANALYSIS",
                details={
                    "completedCaseCount": len(responses),
                    "requiredCaseCount": len(dataset.cases),
                },
            )
        )
    elif not report_request.findings:
        errors.append(EvalError(check="report", code="NO_REPORT_INPUT"))
    elif checkpoint is not None and checkpoint.checkpoint.report is not None:
        report_response = checkpoint.checkpoint.report
        schema_passes += 1
    else:
        try:
            report_provider = (
                ReplayProvider(fixture.output) if fixture is not None else live_report_provider
            )
            report_response = ReportWriterService(execution_settings, report_provider).write(
                report_request
            )
            schema_passes += 1
            if checkpoint is not None:
                checkpoint.record_report(report_response)
        except (AgentError, ValidationError, ValueError) as error:
            errors.append(_eval_error("report", error))

    bullets = [
        bullet
        for _, response in responses
        for section in response.sections
        for bullet in section.bullets
    ]
    summary_lengths = sorted(len(response.summary_ko) for _, response in responses)
    high_sensitivity_responses = [
        response
        for _, response in responses
        if _classification_sensitivity(response.classification.sensitivity)["level"] == "high"
    ]
    evidence_verification_count, evidence_rule_decision_count = _estimated_evidence_routes(
        responses,
        grounded_overlap=execution_settings.evidence_grounded_overlap,
    )
    claim_control_counts = ClaimControlCounts.from_scores(
        score_claim_controls(
            selected_claim_dataset.controls,
            grounded_overlap=execution_settings.evidence_grounded_overlap,
        )
    )
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
        summary_length_p50=_nearest_rank(summary_lengths, 0.50),
        summary_length_p95=_nearest_rank(summary_lengths, 0.95),
        summary_length_max=max(summary_lengths, default=0),
        high_sensitivity_count=len(high_sensitivity_responses),
        high_sensitivity_evidence_count=sum(
            all(
                axis.score is None or bool(axis.evidence_sentence_ids)
                for axis in (
                    response.classification.sensitivity.customer_move,
                    response.classification.sensitivity.deal_signal,
                    response.classification.sensitivity.competitor_threat,
                    response.classification.sensitivity.industry_shift,
                )
            )
            for response in high_sensitivity_responses
        ),
        # replay에서는 fixture/라벨 일관성 가드이며, provider 품질은 live에서만 측정한다.
        perspective_tag_checks=len(responses) * len(AUDIENCES),
        perspective_tag_correct_count=sum(
            _perspective_tag_correct_count(case, response) for case, response in responses
        ),
        report_claim_count=len(claim_statuses),
        report_grounded_claim_count=sum(score.status == "grounded" for score in claim_statuses),
        report_weak_claim_count=sum(score.status == "weak" for score in claim_statuses),
        unsupported_report_claim_count=sum(
            score.status == "ungrounded" for score in claim_statuses
        ),
        evidence_verification_count=evidence_verification_count,
        evidence_rule_decision_count=evidence_rule_decision_count,
        claim_control_counts=claim_control_counts,
    )
    return EvalResult(
        dataset_version=dataset.version,
        baseline_prompt_version=dataset.baseline_prompt_version,
        claim_labels_version=selected_claim_dataset.version,
        analyze_prompt_version=ANALYZE_PROMPT_VERSION,
        report_prompt_version=REPORT_PROMPT_VERSION,
        profile=profile,
        plan=plan,
        config=config,
        complete=(
            report_response is not None
            and (profile == "replay" or schema_passes == len(dataset.cases) + 1)
        ),
        metrics=counts.to_dict(),
        claim_control_diagnostics=claim_control_counts.to_diagnostics(),
        errors=tuple(errors),
    )


def _nearest_rank(values: list[int], percentile: float) -> int:
    if not values:
        return 0
    return values[max(0, math.ceil(len(values) * percentile) - 1)]


def _perspective_tag_correct_count(
    case: GoldenCase,
    response: AnalyzeResponse,
) -> int:
    expected = set(case.expected_audiences)
    return sum(
        (tag.audience in expected) == (tag.relevance in {"medium", "high"})
        for tag in response.perspective_tags
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


def _estimated_evidence_routes(
    responses: list[tuple[GoldenCase, AnalyzeResponse]],
    *,
    grounded_overlap: float,
) -> tuple[int, int]:
    verification_count = 0
    rule_decision_count = 0
    for _, response in responses:
        for section in response.sections:
            for bullet in section.bullets:
                if bullet.groundedness == "ungrounded":
                    continue
                verification_count += 1
                evidence = [
                    EvidenceSentence(
                        id=sentence_id,
                        text=response.sentences[sentence_id - 1],
                    )
                    for sentence_id in bullet.evidence_sentence_ids
                ]
                decision = assess_with_decisive_rules(
                    bullet.text,
                    evidence,
                    grounded_overlap=grounded_overlap,
                )
                if decision is not None:
                    rule_decision_count += 1
    return verification_count, rule_decision_count


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
                    {
                        "text": bullet.text,
                        "evidence": [
                            sentence_id - 1 for sentence_id in bullet.evidence_sentence_ids
                        ],
                        "groundedness": bullet.groundedness,
                        "groundingReason": None,
                        "claimType": bullet.claim_type,
                        "attributedTo": bullet.attributed_to,
                    }
                    for section in response.sections
                    for bullet in section.bullets
                    if bullet.groundedness != "ungrounded"
                ],
                "intent": response.classification.intent,
                "sentiment": response.classification.sentiment,
                "sensitivity": _classification_sensitivity(response.classification.sensitivity),
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


def _eval_config(
    settings: Settings,
    profile: EvalProfile,
    plan: Plan,
    live_policy: LiveProviderPolicy | None,
) -> EvalConfig:
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
        live_policy=live_policy,
    )


def _eval_error(check: str, error: Exception) -> EvalError:
    code = error.code if isinstance(error, AgentError) else type(error).__name__
    details = error.details if isinstance(error, AgentError) else None
    return EvalError(check=check, code=code, details=details)


def _should_abort_live(error: Exception) -> bool:
    if not isinstance(error, AgentError) or not isinstance(error.details, dict):
        return False
    return any(
        (
            error.details.get("rateLimited") is True,
            error.details.get("retryable") is False,
            error.details.get("circuitOpen") is True,
        )
    )


def _live_providers(
    settings: Settings,
    plan: Plan,
    policy: LiveProviderPolicy,
) -> tuple[AnalyzeProvider, AnalyzeProvider]:
    coordinator = LiveRequestCoordinator(policy)
    report_settings = settings.model_copy(
        update={
            "max_output_tokens": settings.report_max_output_tokens,
            "provider_timeout_seconds": settings.report_provider_timeout_seconds,
        }
    )
    return (
        PacedRetryProvider(
            get_analyze_provider(settings, plan, apply_request_policy=False),
            coordinator,
        ),
        PacedRetryProvider(
            get_analyze_provider(report_settings, plan, apply_request_policy=False),
            coordinator,
        ),
    )
