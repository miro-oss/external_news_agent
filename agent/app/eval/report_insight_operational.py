"""Bounded, standalone evaluation of the current production insight service.

Run in a standalone process: observers patch SDK methods and clear provider caches.
This is an Agent service evaluation, not a BE persistence/HTTP acceptance test.
No dotenv is loaded. ``prepare``/``check`` are offline. ``run --execute-live`` asks
for a key with getpass; only ``--use-injected-key`` reads OPENAI_API_KEY from the
already injected process environment. Keep all exports untracked.
The legacy nano comparison remains available in report_insight_measure.
"""

from __future__ import annotations

import argparse
import getpass
import json
import math
import os
import threading
import time
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from copy import deepcopy
from decimal import Decimal
from pathlib import Path
from typing import Literal
from unittest.mock import patch

from pydantic import BaseModel, ConfigDict, Field, ValidationError, create_model

from app.core.config import Settings
from app.core.errors import AgentError
from app.eval.report_insight_measure import generation_metrics
from app.eval.report_insight_review import QUALITY_RUBRIC
from app.eval.report_insight_run import (
    AGENT_ROOT,
    EvaluationStopped,
    atomic_save,
    canonical,
    dependencies,
    digest,
    evaluation_lock,
    file_digest,
    require,
    stamp,
)
from app.llm import openai_provider, structured_call
from app.llm.report_insight_pipeline import ReportInsightPipelineProvider
from app.llm.report_insight_service import PROMPT_VERSION, RUBRIC_VERSION, ReportInsightService
from app.llm.router import close_analyze_providers
from app.schemas.report_insight import ReportInsightRequest

AUDIENCES = ("CHIP_MAKER", "EQUIPMENT_MAKER", "IT_INFRA", "MARKET_INVESTOR")
SETTING_NAMES = (
    "report_max_output_tokens",
    "report_provider_timeout_seconds",
    "report_insight_timeout_seconds",
    "provider_retry_attempts",
    "openai_request_interval_seconds",
    "rate_limit_retry_attempts",
    "rate_limit_backoff_seconds",
    "rate_limit_max_backoff_seconds",
    "rate_limit_max_wait_seconds",
    "provider_concurrency",
    "provider_acquire_timeout_seconds",
    "circuit_failure_threshold",
    "circuit_cooldown_seconds",
    "hard_cap_credits_per_request",
    "schema_repair_attempts",
    "evidence_grounded_overlap",
    "evidence_weak_overlap",
    "evidence_max_claim_chars",
    "evidence_max_sentences",
    "evidence_max_total_chars",
)


def _policy_fields():
    fields = {}
    for name in SETTING_NAMES:
        field = deepcopy(Settings.model_fields[name])
        field.validation_alias = None
        fields[name] = (field.annotation, field)
    return fields


RuntimeSettings = create_model(
    "RuntimeSettings", __config__=ConfigDict(extra="forbid", frozen=True), **_policy_fields()
)


class OperationalPolicy(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)
    model: str = Field(min_length=1)
    # Explicit prices, not a claim that repository defaults are current billing prices.
    prices_usd_per_million: tuple[Decimal, Decimal, Decimal]
    settings: RuntimeSettings = Field(default_factory=RuntimeSettings)
    client_concurrency: int = Field(default=2, ge=1, le=4)
    repeats: int = Field(default=1, ge=1)
    max_calls: int = Field(ge=1)
    max_estimated_usd: Decimal = Field(gt=0)
    previous_calls: int = Field(default=0, ge=0)
    previous_estimated_usd: Decimal = Field(default=Decimal(0), ge=0)
    protocol_margin_tokens: int = Field(default=16384, ge=16384)

    def runtime_settings(self, api_key: str = "") -> Settings:
        require(
            all(price.is_finite() and price > 0 for price in self.prices_usd_per_million),
            "INVALID_PRICES",
        )
        require(self.max_estimated_usd.is_finite(), "INVALID_BUDGET")
        require(
            self.previous_estimated_usd.is_finite()
            and self.previous_estimated_usd < self.max_estimated_usd
            and self.previous_calls < self.max_calls,
            "BUDGET_ALREADY_EXHAUSTED",
        )
        values = self.settings.model_dump()
        result = Settings.model_construct(
            **values,
            mock=False,
            openai_api_key=api_key,
            openai_model=self.model,
            openai_input_cost_per_million=self.prices_usd_per_million[0],
            openai_cached_input_cost_per_million=self.prices_usd_per_million[1],
            openai_output_cost_per_million=self.prices_usd_per_million[2],
        )
        # Cross-field checks without BaseSettings environment discovery.
        return Settings.validate_evidence_thresholds(result)


class ReportCase(BaseModel):
    model_config = ConfigDict(extra="forbid")
    case_id: str = Field(min_length=1, max_length=100)
    split: Literal["development", "holdout"]
    review_policy: Literal["production", "disabled"] = "production"
    request: ReportInsightRequest


def runtime_hashes() -> dict[str, str]:
    # Include current stage instructions/guards plus this runner, not legacy v3 alone.
    paths = {AGENT_ROOT / "pyproject.toml", AGENT_ROOT / "uv.lock"}
    for folder, suffix in (("app", "*.py"), ("app/prompts", "*.md")):
        paths.update((AGENT_ROOT / folder).rglob(suffix))
    return {str(path.relative_to(AGENT_ROOT)): file_digest(path) for path in sorted(paths)}


def prepare(cases: list[dict], policy: dict, output_dir: Path) -> dict:
    parsed = [ReportCase.model_validate(case) for case in cases]
    settings = OperationalPolicy.model_validate(policy)
    settings.runtime_settings()
    require(bool(parsed), "EMPTY_CASES")
    require(len({case.case_id for case in parsed}) == len(parsed), "DUPLICATE_CASE")
    for case in parsed:
        require(case.request.plan == "FREE", "ONLY_OPENAI_FREE_PLAN_SUPPORTED")
    manifest = {
        "schemaVersion": 1,
        "kind": "current-report-insight-service",
        "createdAt": stamp(),
        "promptVersion": PROMPT_VERSION,
        "rubricVersion": RUBRIC_VERSION,
        "runtimeHashes": runtime_hashes(),
        "dependencies": dependencies(),
        "policy": settings.model_dump(mode="json"),
        "effectiveReportDeadlineSeconds": min(
            settings.settings.report_insight_timeout_seconds, 180
        ),
        "cases": [case.model_dump(mode="json", by_alias=True) for case in parsed],
        "qualityRubric": QUALITY_RUBRIC,
        "scope": "Agent service with production admission/retry/validation; no BE or HTTP",
        "credentials": "explicit run argument; CLI process key only by opt-in; no dotenv loading",
        "budgetScope": "explicit cumulative baseline plus this run; never grants authorization",
        "costKind": "conservative-token-price-estimate-not-invoice",
    }
    jobs = [
        {
            "jobId": f"{index}:{repeat}:{audience}",
            "caseId": case.case_id,
            "caseIndex": index,
            "repeat": repeat,
            "audience": audience,
            "variant": "staged",
            "reviewPolicy": case.review_policy,
            "status": "pending",
            "attemptIds": [],
        }
        for index, case in enumerate(parsed)
        for repeat in range(settings.repeats)
        for audience in AUDIENCES
        if audience in case.request.audiences
    ]
    manifest["jobs"] = [
        {key: value for key, value in job.items() if key not in {"status", "attemptIds"}}
        for job in jobs
    ]
    state = {
        "schemaVersion": 1,
        "manifestHash": digest(manifest),
        "results": jobs,
        "attempts": [],
        "stages": [],
        "reviewAdmissions": [],
        "repairs": [],
        "admissionDenials": [],
    }
    with evaluation_lock(output_dir):
        require(not (output_dir / "manifest.json").exists(), "OUTPUT_ALREADY_PREPARED")
        require(not (output_dir / "result.json").exists(), "OUTPUT_ALREADY_USED")
        atomic_save(output_dir / "manifest.json", manifest)
        atomic_save(output_dir / "result.json", state)
    return manifest


def check(output_dir: Path) -> tuple[dict, dict, OperationalPolicy]:
    manifest = json.loads((output_dir / "manifest.json").read_text())
    state = json.loads((output_dir / "result.json").read_text())
    require(manifest.get("kind") == "current-report-insight-service", "WRONG_EVALUATION_KIND")
    require(state["manifestHash"] == digest(manifest), "MANIFEST_CHANGED")
    require(
        [
            {key: job.get(key) for key in expected}
            for job, expected in zip(state["results"], manifest["jobs"], strict=False)
        ]
        == manifest["jobs"]
        and len(state["results"]) == len(manifest["jobs"]),
        "PLANNED_JOBS_CHANGED",
    )
    require(manifest["runtimeHashes"] == runtime_hashes(), "RUNTIME_CHANGED_REPREPARE_REQUIRED")
    require(manifest["dependencies"] == dependencies(), "DEPENDENCIES_CHANGED")
    require(manifest["promptVersion"] == PROMPT_VERSION, "PROMPT_CHANGED")
    policy = OperationalPolicy.model_validate(manifest["policy"])
    policy.runtime_settings()
    return manifest, state, policy


def _safe_failure(error: Exception) -> dict:
    if isinstance(error, AgentError):
        result = {"code": error.code, "statusCode": error.status_code}
        details = error.details if isinstance(error.details, dict) else {}
        for key in (
            "providerStatus",
            "providerStatusCode",
            "rateLimited",
            "retryable",
            "timeoutPhase",
            "validationFailure",
            "evaluationFailure",
            "requestDeadlineExceeded",
            "pipelineCancelled",
            "concurrencyLimited",
            "requestNotStarted",
        ):
            if key in details:
                result[key] = details[key]
        return {**result, "class": _failure_class(result)}
    return {"code": "EVALUATION_ERROR", "errorType": type(error).__name__}


def _failure_class(details):
    if details.get("evaluationFailure"):
        return "EVALUATION_LIMIT"
    if details.get("requestDeadlineExceeded"):
        return "REQUEST_DEADLINE"
    if details.get("providerStatus") in {"insufficient_quota", "credit_balance_exhausted"}:
        return "PROVIDER_QUOTA"
    if details.get("rateLimited"):
        return "PROVIDER_RATE_LIMIT"
    if details.get("concurrencyLimited"):
        return "PROVIDER_CONCURRENCY"
    if details.get("pipelineCancelled"):
        return "PIPELINE_CANCELLED"
    return details["code"]


class _Recorder:
    def __init__(self, output_dir, state, policy):
        self.output_dir, self.state, self.policy = output_dir, state, policy
        self.lock, self.local = threading.RLock(), threading.local()

    def save(self):
        atomic_save(self.output_dir / "result.json", self.state)

    def event(self, collection, value):
        with self.lock:
            self.state[collection].append(value)
            self.save()

    def create(self, create, wire, sdk_settings):
        policy = self.policy
        input_bound = len(canonical(wire)) + policy.protocol_margin_tokens
        reserve = (
            input_bound * max(policy.prices_usd_per_million[:2])
            + wire["max_output_tokens"] * policy.prices_usd_per_million[2]
        ) / Decimal(10**6)
        with self.lock:
            attempts = self.state["attempts"]
            spent = policy.previous_estimated_usd + sum(
                (
                    Decimal(a["costEstimatedUsd"]) + Decimal(a["unsettledReservedUsd"])
                    for a in attempts
                ),
                Decimal(0),
            )
            reason = self.state.get("haltReason") or (
                "CALL_LIMIT"
                if policy.previous_calls + len(attempts) >= policy.max_calls
                else "COST_LIMIT"
                if spent + reserve > policy.max_estimated_usd
                else None
            )
            if reason:
                self.event("admissionDenials", {"jobId": self.local.job["jobId"], "reason": reason})
                raise AgentError(
                    503,
                    "PROVIDER_UNAVAILABLE",
                    "평가 호출 상한에 도달했습니다.",
                    {"retryable": False, "evaluationFailure": reason},
                )
            record = {
                "attemptId": len(attempts) + 1,
                "jobId": self.local.job["jobId"],
                "stage": self.local.stage,
                "variant": "staged",
                "status": "running",
                "wire": wire,
                "wireHash": digest(wire),
                "sdkSettings": sdk_settings,
                "startedAt": stamp(),
                "costEstimatedUsd": "0",
                "unsettledReservedUsd": str(reserve),
            }
            attempts.append(record)
            self.local.job["attemptIds"].append(record["attemptId"])
            self.save()
        started = time.monotonic()
        try:
            response = create(**wire)
            usage = response.usage
            inputs = getattr(usage, "input_tokens", None)
            outputs = getattr(usage, "output_tokens", None)
            cached = getattr(getattr(usage, "input_tokens_details", None), "cached_tokens", 0)
            with self.lock:
                record.update(
                    status="success",
                    providerText=response.output_text,
                    responseStatus=response.status,
                    responseModel=response.model,
                )
                if (
                    type(inputs) is int
                    and type(outputs) is int
                    and type(cached) is int
                    and inputs >= 0
                    and outputs >= 0
                    and 0 <= cached <= inputs
                ):
                    prices = policy.prices_usd_per_million
                    cost = (
                        (inputs - cached) * prices[0] + cached * prices[1] + outputs * prices[2]
                    ) / Decimal(10**6)
                    record.update(
                        usage={
                            "inputTokens": inputs,
                            "cachedInputTokens": cached,
                            "outputTokens": outputs,
                        },
                        costEstimatedUsd=str(cost),
                        unsettledReservedUsd="0",
                    )
                    if cost > reserve:
                        self.state["haltReason"] = "TOKEN_RESERVATION_EXCEEDED"
                    require(cost <= reserve, "TOKEN_RESERVATION_EXCEEDED")
            return response
        except Exception as error:
            with self.lock:
                record.update(status="failed", failure=_safe_failure(error))
                if not isinstance(error, AgentError):
                    record["failure"].update(openai_provider._provider_error_details(error))
                    record["failure"]["code"] = "PROVIDER_UNAVAILABLE"
                record["failure"]["class"] = _failure_class(record["failure"])
                # Unknown usage retains its reservation, including interrupted requests.
            raise
        finally:
            with self.lock:
                record["latencyMs"] = round((time.monotonic() - started) * 1000, 3)
                self.save()


_PROCESS_LOCK = threading.Lock()


@contextmanager
def _observe(recorder):
    """Observe the SDK boundary; leave production provider/guard/review policies intact."""
    require(_PROCESS_LOCK.acquire(blocking=False), "ANOTHER_EVALUATION_IN_PROCESS")
    original_sdk = openai_provider.OpenAI
    original_log = structured_call._log_validation_failure
    original_review = ReportInsightPipelineProvider.can_start_optional_review

    def sdk(**kwargs):
        client = original_sdk(**kwargs)
        create = client.responses.create
        sdk_settings = {"timeoutSeconds": kwargs["timeout"], "maxRetries": kwargs["max_retries"]}
        client.responses.create = lambda **wire: recorder.create(create, wire, sdk_settings)
        return client

    def validation(target_logger, response, error, **kwargs):
        recorder.event(
            "stages",
            {
                "jobId": recorder.local.job["jobId"],
                "stage": recorder.local.stage,
                "event": "validation_failure",
                "attempt": kwargs["attempt"],
                "errorType": type(error).__name__,
                "diagnostics": structured_call._validation_failure(
                    error, recorder.local.stage, kwargs["attempt"], kwargs.get("schema")
                ),
            },
        )
        return original_log(target_logger, response, error, **kwargs)

    def review(pipeline):
        policy = recorder.local.job.get("reviewPolicy", "production")
        admitted = original_review(pipeline) if policy == "production" else False
        recorder.event(
            "reviewAdmissions",
            {
                "jobId": recorder.local.job["jobId"],
                "admitted": admitted,
                "policy": policy,
                "remainingSeconds": max(0, pipeline.deadline - time.monotonic()),
            },
        )
        return admitted

    try:
        close_analyze_providers()
        with (
            patch.object(openai_provider, "OpenAI", sdk),
            patch.object(structured_call, "_log_validation_failure", validation),
            patch.object(ReportInsightPipelineProvider, "can_start_optional_review", review),
        ):
            yield
    finally:
        close_analyze_providers()
        _PROCESS_LOCK.release()


def run(
    output_dir: Path, *, api_key: str, job_limit: int | None = None, resume: bool = False
) -> dict:
    require(bool(api_key), "EXPLICIT_API_KEY_REQUIRED")
    with evaluation_lock(output_dir):
        manifest, state, policy = check(output_dir)
        require(job_limit is None or type(job_limit) is int and job_limit > 0, "INVALID_JOB_LIMIT")
        if resume:
            require(
                all(job["status"] in {"pending", "success", "failed"} for job in state["results"])
                and all(attempt["status"] != "running" for attempt in state["attempts"]),
                "UNSETTLED_RUN_CANNOT_RESUME",
            )
            require(any(job["status"] == "pending" for job in state["results"]), "NO_PENDING_JOBS")
        else:
            require(
                not state["attempts"]
                and all(job["status"] == "pending" for job in state["results"]),
                "RUN_ALREADY_STARTED",
            )
        recorder = _Recorder(output_dir, state, policy)
        settings = policy.runtime_settings(api_key)

        class ObservedService(ReportInsightService):
            def __init__(self, job):
                super().__init__(settings)
                self.job = job

            def _repair_call(self, prompt, schema, raw, error, validate):
                repair = super()._repair_call(prompt, schema, raw, error, validate)
                with recorder.lock:
                    after_attempt = max(
                        row["attemptId"]
                        for row in recorder.state["attempts"]
                        if row["jobId"] == self.job["jobId"]
                        and row["stage"] == recorder.local.stage
                    )
                recorder.event(
                    "repairs",
                    {
                        "jobId": self.job["jobId"],
                        "stage": recorder.local.stage,
                        "afterAttemptId": after_attempt,
                        "scope": (
                            "unit"
                            if repair.response_schema.get("title") == "ReportInsightReduceRepair"
                            else "finding_or_field"
                            if repair.response_schema != schema
                            else "whole_output"
                        ),
                    },
                )
                return repair

            def _call(self, pipeline, **kwargs):
                recorder.local.job = self.job
                stage = kwargs["schema"]["description"].removeprefix("reportInsightCall:")
                recorder.local.stage = stage
                started = time.monotonic()
                event = {
                    "jobId": self.job["jobId"],
                    "stage": stage,
                    "event": "stage",
                    "status": "success",
                }
                try:
                    result = super()._call(pipeline, **kwargs)
                    mapped = getattr(result.output, "mapped", None)
                    if mapped is not None:
                        # Local measurements compare validated decisions, never
                        # infer quality merely because REVIEW changed an axis.
                        event["assessments"] = [
                            {
                                "findingId": item.finding_id,
                                "audience": insight.audience,
                                "axes": item.axes.model_dump(),
                                "basisClaimIds": item.basis_claim_ids,
                                "reasonHash": digest(item.reason),
                            }
                            for insight in mapped.insights
                            for item in insight.assessments
                        ]
                    return result
                except Exception as error:
                    event.update(status="failed", failure=_safe_failure(error))
                    raise
                finally:
                    event["latencyMs"] = round((time.monotonic() - started) * 1000, 3)
                    recorder.event("stages", event)

        def generate(job, queued_at):
            recorder.local.job = job
            started = time.monotonic()
            with recorder.lock:
                job.update(status="running", queueWaitMs=round((started - queued_at) * 1000, 3))
                recorder.save()
            request = ReportInsightRequest.model_validate(
                manifest["cases"][job["caseIndex"]]["request"]
            ).model_copy(update={"audiences": [job["audience"]]})
            try:
                response = ObservedService(job).generate(request)
                with recorder.lock:
                    job.update(
                        status="success", response=response.model_dump(mode="json", by_alias=True)
                    )
            except Exception as error:
                with recorder.lock:
                    job.update(status="failed", failure=_safe_failure(error))
            finally:
                with recorder.lock:
                    job["latencyMs"] = round((time.monotonic() - started) * 1000, 3)
                    recorder.save()

        with _observe(recorder), ThreadPoolExecutor(max_workers=policy.client_concurrency) as pool:
            # A bounded smoke run leaves pending jobs in this same durable ledger.
            # Resume never retries an already completed or failed job.
            pending = [job for job in state["results"] if job["status"] == "pending"][:job_limit]
            # Keep case/repetition groups together even after one role was run alone.
            batches = []
            for job in pending:
                if not batches or (job["caseIndex"], job["repeat"]) != (
                    batches[-1][0]["caseIndex"],
                    batches[-1][0]["repeat"],
                ):
                    batches.append([])
                batches[-1].append(job)
            for batch in batches:
                queued = time.monotonic()
                futures = [pool.submit(generate, job, queued) for job in batch]
                for future in futures:
                    future.result()
        state["summary"] = summarize(state)
        recorder.save()
        return state


def summarize(state: dict) -> dict:
    results = state["results"]
    latencies = sorted(job["latencyMs"] for job in results if "latencyMs" in job)
    assessments = [
        assessment
        for job in results
        if job["status"] == "success"
        for insight in job["response"]["insights"]
        for assessment in insight["assessments"]
    ]
    groups = {(job["caseIndex"], job["repeat"]) for job in results}
    return {
        **generation_metrics(state)["staged"],
        "allFinishedLatencyP95Ms": latencies[math.ceil(len(latencies) * 0.95) - 1]
        if latencies
        else None,
        "fourAudienceSuccessReports": sum(
            all(
                job["status"] == "success"
                for job in results
                if (job["caseIndex"], job["repeat"]) == group
            )
            for group in groups
        ),
        "providerAttemptsByStage": dict(
            Counter(a["stage"].split("-")[0] for a in state["attempts"])
        ),
        "failuresByCode": dict(
            Counter(job["failure"]["code"] for job in results if "failure" in job)
        ),
        "failuresByClass": dict(
            Counter(
                job["failure"].get("class", job["failure"]["code"])
                for job in results
                if "failure" in job
            )
        ),
        "validationFailuresByKind": dict(
            Counter(
                kind
                for event in state["stages"]
                if event["event"] == "validation_failure"
                for kind in (event.get("diagnostics") or {}).get("errorKinds", [])
            )
        ),
        "assessmentCount": len(assessments),
        "assessmentsWithoutEvidence": sum(not a["basisClaimIds"] for a in assessments),
        "abstainedAxes": sum(
            a["axes"][axis] is None
            for a in assessments
            for axis in ("directness", "impact", "urgency")
        ),
        "evidenceOmissionRate": None,
        "unnecessaryAbstentionRate": None,
        "semanticQualityMeasured": False,
        "requiresHumanLabels": True,
        "freshProviderResponses": True,
        "forcedReviewSkip": any(
            job.get("reviewPolicy", "production") == "disabled" for job in results
        ),
        "bePersistenceVerified": False,
        "httpVerified": False,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("prepare", "check", "run"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--cases", type=Path)
    parser.add_argument("--policy", type=Path)
    parser.add_argument("--execute-live", action="store_true")
    parser.add_argument("--job-limit", type=int)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument(
        "--use-injected-key",
        action="store_true",
        help="Explicitly read OPENAI_API_KEY already injected into this process",
    )
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            require(args.cases is not None and args.policy is not None, "CASES_AND_POLICY_REQUIRED")
            manifest = prepare(
                json.loads(args.cases.read_text()), json.loads(args.policy.read_text()), args.output
            )
            print(json.dumps({"manifestHash": digest(manifest), "promptVersion": PROMPT_VERSION}))
        elif args.command == "check":
            manifest, state, _ = check(args.output)
            print(
                json.dumps(
                    {
                        "manifestHash": digest(manifest),
                        "jobs": len(state["results"]),
                        "ready": not state["attempts"]
                        and all(job["status"] == "pending" for job in state["results"]),
                        "networkCalls": 0,
                    }
                )
            )
        else:
            require(args.execute_live, "EXPLICIT_EXECUTE_LIVE_REQUIRED")
            check(args.output)
            key = (
                os.environ.get("OPENAI_API_KEY", "")
                if args.use_injected_key
                else getpass.getpass("OpenAI API key (not saved): ")
            )
            options = {}
            if args.job_limit is not None:
                options["job_limit"] = args.job_limit
            if args.resume:
                options["resume"] = True
            result = run(args.output, api_key=key, **options)
            print(json.dumps(result["summary"]))
    except EvaluationStopped as error:
        parser.exit(2, f"{error}\n")
    except (ValidationError, json.JSONDecodeError):
        # Pydantic errors include input values; never echo a malformed policy or source packet.
        parser.exit(2, "INVALID_POLICY_OR_CASE_DATA\n")


if __name__ == "__main__":
    main()
