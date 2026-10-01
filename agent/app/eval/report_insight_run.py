"""Checkpointed, label-blind nano comparison; live calls require process credentials."""

from __future__ import annotations

import argparse
import ast
import fcntl
import hashlib
import json
import logging
import os
import platform
import tempfile
import time
from contextlib import contextmanager
from datetime import UTC, datetime
from decimal import Decimal, InvalidOperation
from importlib.metadata import version
from pathlib import Path

from openai import OpenAI

from app.core.config import Settings
from app.core.errors import AgentError
from app.eval.report_insight_corpus import DEFAULT_DATASET, load_corpus
from app.llm.base import ProviderResponse, ProviderUsage
from app.llm.openai_provider import OpenAIAnalyzeProvider
from app.llm.report_insight_service import (
    LEGACY_PROMPT_VERSION as PROMPT_VERSION,
)
from app.llm.report_insight_service import (
    LEGACY_RUBRIC_VERSION as RUBRIC_VERSION,
)
from app.llm.report_insight_service import (
    LEGACY_SYSTEM_INSTRUCTION as SYSTEM_INSTRUCTION,
)
from app.llm.report_insight_service import (
    ReportInsightLegacyService as ReportInsightService,
)
from app.llm.report_insight_service import (
    _eligible_report_request,
    _report_insight_prompt,
    _validated_output,
)
from app.llm.request_contract import report_insight_schema
from app.llm.structured_call import structured_call
from app.schemas.report import ReportResponseMeta
from app.schemas.report_insight import ReportInsightRequest, ReportInsightResponse

AGENT_ROOT = Path(__file__).resolve().parents[2]
MODEL = "gpt-4.1-nano"
PLAN = "FREE"
VARIANTS = ("single_call", "staged")
PRICES = (Decimal("0.10"), Decimal("0.025"), Decimal("0.40"))
MAX_OUTPUT = 4096
PROTOCOL_MARGIN_TOKENS = 16384
LOGGER = logging.getLogger(__name__)
POLICY = {
    "provider": "openai",
    "model": MODEL,
    "plan": PLAN,
    "temperature": 0,
    "maxOutputTokens": MAX_OUTPUT,
    "schemaRepairs": 1,
    "nativeRetries": 0,
    "rateLimitRetries": 0,
    "reportDeadlineSeconds": 120,
    "attemptTimeoutSeconds": 60,
    "protocolMarginTokens": PROTOCOL_MARGIN_TOKENS,
    "modelPricesUsdPerMillion": [str(value) for value in PRICES],
    "costKind": "token-price-estimate-not-invoice",
    "dotenv": False,
}


class EvaluationStopped(Exception):
    """Content-free error codes only; never expose provider errors or credentials."""


def require(condition: bool, code: str) -> None:
    if not condition:
        raise EvaluationStopped(code)


def canonical(value) -> bytes:
    return json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False
    ).encode("utf-8")


def digest(value) -> str:
    return hashlib.sha256(canonical(value)).hexdigest()


def file_digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def stamp() -> str:
    return datetime.now(UTC).isoformat()


def atomic_save(path: Path, value: dict) -> None:
    descriptor, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2, allow_nan=False)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        Path(temporary).unlink(missing_ok=True)


@contextmanager
def evaluation_lock(output_dir: Path):
    output_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (output_dir / "run.lock").open("a", encoding="utf-8") as stream:
        try:
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise EvaluationStopped("ANOTHER_RUN_IS_ACTIVE") from error
        yield


def runtime_hashes() -> dict[str, str]:
    pending = [
        "app.eval.report_insight_run",
        "app.llm.report_insight_service",
        "app.eval.report_insight_measure",
        "app.eval.report_insight_compare",
    ]
    visited = set()
    paths = set()
    while pending:
        module = pending.pop()
        if module in visited:
            continue
        visited.add(module)
        path = AGENT_ROOT.joinpath(*module.split(".")).with_suffix(".py")
        if not path.is_file():
            path = AGENT_ROOT.joinpath(*module.split("."), "__init__.py")
        require(path.is_file(), "RUNTIME_DEPENDENCY_MISSING")
        paths.add(path)
        for parent in path.parents:
            if parent == AGENT_ROOT:
                break
            if (parent / "__init__.py").is_file():
                pending.append(".".join(parent.relative_to(AGENT_ROOT).parts))
        for node in ast.walk(ast.parse(path.read_text(encoding="utf-8"))):
            if isinstance(node, ast.ImportFrom) and node.module and node.module.startswith("app."):
                pending.append(node.module)
            elif isinstance(node, ast.Import):
                pending.extend(alias.name for alias in node.names if alias.name.startswith("app."))
    paths.update({AGENT_ROOT / "pyproject.toml", AGENT_ROOT / "uv.lock"})
    paths.update(
        AGENT_ROOT / "app/prompts" / name
        for name in (
            f"{PROMPT_VERSION}.md",
            f"{RUBRIC_VERSION}.md",
            "insight.ko.v2.md",
            "perspective.ko.v1.md",
        )
    )
    return {str(path.relative_to(AGENT_ROOT)): file_digest(path) for path in sorted(paths)}


def dependencies() -> dict:
    return {
        name: version(name)
        for name in (
            "openai",
            "pydantic",
            "pydantic-settings",
            "pydantic-ai-slim",
            "httpx",
            "httpx2",
        )
    }


def number(value) -> Decimal | None:
    if isinstance(value, bool):
        return None
    try:
        parsed = Decimal(str(value))
        return parsed if parsed.is_finite() and parsed >= 0 else None
    except (InvalidOperation, ValueError, TypeError):
        return None


def totals(state: dict) -> dict:
    observed = sum((Decimal(item["costEstimatedUsd"]) for item in state["attempts"]), Decimal(0))
    reserved = sum(
        (Decimal(item["unsettledReservedUsd"]) for item in state["attempts"]), Decimal(0)
    )
    return {
        "attempts": len(state["attempts"]),
        "observedCostEstimatedUsd": str(observed),
        "unsettledReservedUsd": str(reserved),
    }


def save(output_dir: Path, state: dict) -> None:
    state["totals"] = totals(state)
    state["updatedAt"] = stamp()
    state["checkpointSha256"] = digest(
        {key: value for key, value in state.items() if key != "checkpointSha256"}
    )
    atomic_save(output_dir / "result.json", state)


def prepare(
    dataset: Path, output_dir: Path, *, max_cost_usd=Decimal("1.00"), max_calls=144, pilot_size=4
) -> dict:
    require(number(max_cost_usd) is not None and 0 < max_cost_usd <= 2, "INVALID_COST_LIMIT")
    require(type(max_calls) is int and 1 <= max_calls <= 144, "INVALID_CALL_LIMIT")
    require(type(pilot_size) is int and pilot_size >= 1, "INVALID_PILOT_SIZE")
    corpus = load_corpus(dataset)
    jobs = []
    for index, case in enumerate(corpus.cases):
        source = case.request.model_dump(mode="json", by_alias=True)
        source.update(plan=PLAN, idempotencyKey=f"eval:{index + 1}:{file_digest(dataset)[:16]}")
        request = ReportInsightRequest.model_validate(source)
        require(len(request.audiences) == 1, "SINGLE_AUDIENCE_REQUIRED")
        payload = request.model_dump(mode="json", by_alias=True)
        variants = (
            VARIANTS if int(digest(case.case_id)[0], 16) % 2 == 0 else tuple(reversed(VARIANTS))
        )
        for variant in variants:
            jobs.append(
                {
                    "caseId": case.case_id,
                    "variant": variant,
                    "request": payload,
                    "inputSha256": digest(payload),
                    "phase": "pilot" if index < pilot_size else "remaining",
                }
            )
    policy = {
        **POLICY,
        "maxCostEstimatedUsd": str(max_cost_usd),
        "maxCalls": max_calls,
        "pilotSize": min(pilot_size, len(corpus.cases)),
    }
    provenance = {
        "datasetVersion": corpus.version,
        "datasetSha256": file_digest(dataset),
        "datasetPath": str(dataset.resolve()),
        "scheduleSha256": digest(jobs),
        "inputSha256": digest([job["request"] for job in jobs]),
        "runtimeSourceSha256s": runtime_hashes(),
        "policySha256": digest(policy),
        "model": MODEL,
        "promptVersion": PROMPT_VERSION,
        "rubricVersion": RUBRIC_VERSION,
        "pythonVersion": platform.python_version(),
        "dependencyVersions": dependencies(),
    }
    manifest = {
        "schemaVersion": 1,
        "provenance": provenance,
        "policy": policy,
        "jobs": jobs,
        "caseCount": len(corpus.cases),
        "baseCallUpperBound": 3 * len(corpus.cases),
        "repairCallUpperBound": 6 * len(corpus.cases),
    }
    with evaluation_lock(output_dir):
        manifest_path = output_dir / "manifest.json"
        if manifest_path.exists():
            require(json.loads(manifest_path.read_text()) == manifest, "PREPARED_RUN_CHANGED")
            require((output_dir / "result.json").exists(), "CHECKPOINT_MISSING")
        else:
            require(not (output_dir / "result.json").exists(), "UNBOUND_CHECKPOINT")
            atomic_save(manifest_path, manifest)
            save(
                output_dir,
                {
                    "schemaVersion": 1,
                    "provenance": provenance,
                    "policy": policy,
                    "status": "prepared",
                    "inFlight": None,
                    "attempts": [],
                    "errors": [],
                    "caseIds": [case.case_id for case in corpus.cases],
                    "results": [
                        {
                            **job,
                            "status": "pending",
                            "response": None,
                            "attemptIds": [],
                            "latencyMs": None,
                            "errorCode": None,
                        }
                        for job in jobs
                    ],
                },
            )
    return manifest


def verify(output_dir: Path, manifest: dict, state: dict) -> None:
    provenance = manifest["provenance"]
    require(provenance["runtimeSourceSha256s"] == runtime_hashes(), "RUNTIME_CHANGED")
    require(
        provenance["dependencyVersions"] == dependencies()
        and provenance["pythonVersion"] == platform.python_version(),
        "DEPENDENCIES_CHANGED",
    )
    verify_recorded(output_dir, manifest, state, revalidate_outputs=True)


def verify_recorded(
    output_dir: Path, manifest: dict, state: dict, *, revalidate_outputs: bool = False
) -> None:
    """Verify a frozen ledger without equating it to today's generation runtime.

    Only live resume uses current source/dependency checks and output validation.
    Offline replay keeps the original versions and never resumes paid calls.
    """
    require(
        state.get("checkpointSha256")
        == digest({key: value for key, value in state.items() if key != "checkpointSha256"}),
        "CHECKPOINT_CHANGED",
    )
    require(state.get("schemaVersion") == 1, "INVALID_RESULT_VERSION")
    require(
        state.get("provenance") == manifest["provenance"]
        and state.get("policy") == manifest["policy"],
        "CHECKPOINT_PROVENANCE_CHANGED",
    )
    require(
        all(manifest["policy"].get(key) == value for key, value in POLICY.items()), "POLICY_CHANGED"
    )
    provenance = manifest["provenance"]
    require(
        provenance["datasetSha256"] == file_digest(Path(provenance["datasetPath"])),
        "DATASET_CHANGED",
    )
    require(
        digest(manifest["jobs"]) == provenance["scheduleSha256"]
        and digest([job["request"] for job in manifest["jobs"]]) == provenance["inputSha256"]
        and digest(manifest["policy"]) == provenance["policySha256"],
        "MANIFEST_CHANGED",
    )
    current = json.loads((output_dir / "manifest.json").read_text())
    require(current == manifest, "MANIFEST_CHANGED")
    require(len(state["results"]) == len(manifest["jobs"]), "CHECKPOINT_JOBS_CHANGED")
    for result, job in zip(state["results"], manifest["jobs"], strict=True):
        require(
            all(result.get(key) == value for key, value in job.items()), "CHECKPOINT_INPUT_CHANGED"
        )
    _verify_attempts(manifest, state, revalidate_outputs=revalidate_outputs)
    require(state["totals"] == totals(state), "CHECKPOINT_TOTALS_CHANGED")


def _verify_attempts(manifest: dict, state: dict, *, revalidate_outputs: bool = True) -> None:
    jobs = {(item["caseId"], item["variant"]): item for item in state["results"]}
    require(len(jobs) == len(state["results"]), "DUPLICATE_CHECKPOINT_JOB")
    require(len(state["attempts"]) <= manifest["policy"]["maxCalls"], "CHECKPOINT_CALL_LIMIT")
    grouped = {key: [] for key in jobs}
    for index, item in enumerate(state["attempts"], 1):
        require(item["attemptId"] == index, "CHECKPOINT_ATTEMPT_IDS_CHANGED")
        key = (item["caseId"], item["variant"])
        require(key in jobs, "UNBOUND_CHECKPOINT_ATTEMPT")
        grouped[key].append(item)
        wire = item["wireRequest"]
        upper, maximum = reservation(wire)
        require(
            item["requestSha256"] == digest(wire)
            and item["inputTokenUpperBound"] == upper
            and Decimal(item["reservedCostUsd"]) == maximum,
            "CHECKPOINT_RESERVATION_CHANGED",
        )
        require(
            wire["model"] == MODEL
            and wire["max_output_tokens"] == MAX_OUTPUT
            and wire["temperature"] == 0
            and wire["store"] is False,
            "CHECKPOINT_MODEL_CHANGED",
        )
        require(item["status"] in {"success", "failed", "in_flight"}, "INVALID_ATTEMPT_STATUS")
        if item["providerRawResponse"] is not None:
            raw_usage = item["providerRawResponse"].get("usage")
            require(raw_usage == item["providerRawUsage"], "CHECKPOINT_RAW_USAGE_CHANGED")
            raw_text = "".join(
                content["text"]
                for output in item["providerRawResponse"].get("output", [])
                if output.get("type") == "message"
                for content in output.get("content", [])
                if content.get("type") == "output_text"
            )
            require(item["providerText"] == raw_text, "CHECKPOINT_PROVIDER_TEXT_CHANGED")
        else:
            raw_usage = item["providerRawUsage"]
            if raw_usage is not None:
                raw_usage = {
                    "input_tokens": raw_usage.get("input_tokens"),
                    "output_tokens": raw_usage.get("output_tokens"),
                    "input_tokens_details": {"cached_tokens": raw_usage.get("cached_tokens", 0)},
                }
        if raw_usage is not None:
            usage, cost = observed_usage(raw_usage)
            require(item["usage"] == usage, "CHECKPOINT_NORMALIZED_USAGE_CHANGED")
            expected_reserved = Decimal(0)
            if item["providerRawResponse"] is not None and item["resolvedModel"] not in {
                MODEL,
                "gpt-4.1-nano-2025-04-14",
            }:
                cost, expected_reserved = Decimal(0), maximum
            elif cost is None:
                cost = (
                    Decimal(usage.get("input_tokens", 0)) * PRICES[1]
                    + Decimal(usage.get("output_tokens", 0)) * PRICES[2]
                ) / 1_000_000
                expected_reserved = max(Decimal(0), maximum - cost)
            require(
                Decimal(item["costEstimatedUsd"]) == cost
                and Decimal(item["unsettledReservedUsd"]) == expected_reserved,
                "CHECKPOINT_COST_CHANGED",
            )
        else:
            require(
                Decimal(item["costEstimatedUsd"]) == 0
                and Decimal(item["unsettledReservedUsd"]) == maximum,
                "CHECKPOINT_UNOBSERVED_COST_CHANGED",
            )
        if item["status"] == "success":
            require(
                item["providerRawResponse"] is not None
                and item["resolvedModel"] in {MODEL, "gpt-4.1-nano-2025-04-14"}
                and item["resolvedModel"] == item["providerRawResponse"].get("model")
                and Decimal(item["unsettledReservedUsd"]) == 0,
                "INVALID_SUCCESSFUL_ATTEMPT",
            )
    for key, result in jobs.items():
        records = grouped[key]
        require(
            result["attemptIds"] == [item["attemptId"] for item in records],
            "CHECKPOINT_ATTEMPT_MAPPING_CHANGED",
        )
        stages = {}
        for item in records:
            stage = item["stage"]
            require(
                stage in ({"SINGLE"} if key[1] == "single_call" else {"MAP", "REDUCE"}),
                "CHECKPOINT_STAGE_CHANGED",
            )
            stages[stage] = stages.get(stage, 0) + 1
            require(
                stages[stage] <= 2 and item["repairIndex"] == stages[stage] - 1,
                "CHECKPOINT_REPAIR_CHANGED",
            )
        if result["status"] == "success":
            require(
                records and all(item["status"] == "success" for item in records),
                "SUCCESS_WITH_FAILED_ATTEMPT",
            )
            response = ReportInsightResponse.model_validate(result["response"])
            require(
                response.meta.model == MODEL
                and response.meta.provider == "openai"
                and response.meta.prompt_version == manifest["provenance"]["promptVersion"]
                and not response.meta.mock
                and not response.meta.truncated,
                "CHECKPOINT_RESPONSE_CHANGED",
            )
            if revalidate_outputs:
                _validated_output(
                    ProviderResponse(
                        text=json.dumps({"insights": result["response"]["insights"]}),
                        provider="openai",
                        model=MODEL,
                        usage=ProviderUsage(),
                    ),
                    _eligible_report_request(
                        ReportInsightRequest.model_validate(result["request"])
                    ),
                )
            require(
                response.meta.input_tokens == sum(item["usage"]["input_tokens"] for item in records)
                and response.meta.output_tokens
                == sum(item["usage"]["output_tokens"] for item in records)
                and abs(
                    Decimal(str(response.meta.cost_usd))
                    - sum((Decimal(item["costEstimatedUsd"]) for item in records), Decimal(0))
                )
                <= Decimal("0.000000000001"),
                "CHECKPOINT_RESPONSE_USAGE_CHANGED",
            )
        if result["status"] == "failed":
            expected_safe = (
                result["errorCode"] == "SCHEMA_VIOLATION"
                and bool(records)
                and all(
                    item["status"] == "success" and Decimal(item["unsettledReservedUsd"]) == 0
                    for item in records
                )
            )
            require(
                result.get("terminalFailureSafe") is expected_safe,
                "CHECKPOINT_FAILURE_POLICY_CHANGED",
            )
        if result["status"] == "pending":
            require(not records and result["response"] is None, "PENDING_JOB_HAS_OBSERVED_CALLS")
    failures = [
        {
            "caseId": item["caseId"],
            "variant": item["variant"],
            "code": item["errorCode"],
            "safeToContinue": item.get("terminalFailureSafe"),
        }
        for item in state["results"]
        if item["status"] == "failed"
    ]
    require(state["errors"] == failures, "CHECKPOINT_FAILURE_MAPPING_CHANGED")


def reservation(wire: dict) -> tuple[int, Decimal]:
    # A byte is a conservative token bound for explicit UTF-8 input. The large
    # extra allowance covers framing outside the complete SDK request JSON.
    upper = len(canonical(wire)) + PROTOCOL_MARGIN_TOKENS
    return upper, (Decimal(upper) * PRICES[0] + Decimal(MAX_OUTPUT) * PRICES[2]) / 1_000_000


def observed_usage(raw) -> tuple[dict, Decimal | None]:
    usage = raw if isinstance(raw, dict) else {}
    clean = {}
    for key in ("input_tokens", "output_tokens"):
        value = number(usage.get(key))
        if value is not None and value == int(value):
            clean[key] = int(value)
    details = usage.get("input_tokens_details")
    cached = number(details.get("cached_tokens", 0) if isinstance(details, dict) else 0)
    if cached is not None and cached == int(cached):
        clean["cached_tokens"] = int(cached)
    if not {"input_tokens", "output_tokens", "cached_tokens"} <= clean.keys():
        return clean, None
    require(clean["cached_tokens"] <= clean["input_tokens"], "INVALID_PROVIDER_USAGE")
    cost = (
        Decimal(clean["input_tokens"] - clean["cached_tokens"]) * PRICES[0]
        + Decimal(clean["cached_tokens"]) * PRICES[1]
        + Decimal(clean["output_tokens"]) * PRICES[2]
    ) / 1_000_000
    return clean, cost


class AttemptProvider:
    """Intercept the final SDK body, then reserve and checkpoint before submitting."""

    def __init__(
        self,
        output_dir: Path,
        manifest: dict,
        state: dict,
        result: dict,
        api_key: str,
        *,
        sdk_factory=OpenAI,
        clock=time.monotonic,
    ):
        self.output_dir, self.manifest, self.state, self.result = (
            output_dir,
            manifest,
            state,
            result,
        )
        self.api_key, self.sdk_factory, self.clock = api_key, sdk_factory, clock
        self.deadline = clock() + POLICY["reportDeadlineSeconds"]
        self.stage_counts = {}
        self.stop_code = None

    def generate(self, *, system_instruction, prompt, response_schema):
        verify(self.output_dir, self.manifest, self.state)
        remaining = self.deadline - self.clock()
        require(remaining > 0, "REPORT_DEADLINE_EXCEEDED")
        title = response_schema.get("title", "")
        stage = "MAP" if "MapOutput" in title else "REDUCE" if "ReduceOutput" in title else "SINGLE"
        self.stage_counts[stage] = self.stage_counts.get(stage, 0) + 1
        require(self.stage_counts[stage] <= 2, "EXCESS_SCHEMA_REPAIR")
        client = self.sdk_factory(
            api_key=self.api_key,
            base_url="https://api.openai.com/v1",
            max_retries=0,
            timeout=min(60, remaining),
        )
        owner = self

        class Capture:
            def create(self, **wire):
                try:
                    return owner._submit(client, stage, wire)
                except EvaluationStopped as error:
                    owner.stop_code = str(error)
                    raise

        class Proxy:
            responses = Capture()

        provider = OpenAIAnalyzeProvider(settings(self.api_key), client=Proxy())
        try:
            return provider.generate(
                system_instruction=system_instruction,
                prompt=prompt,
                response_schema=response_schema,
            )
        except AgentError:
            if self.stop_code:
                raise EvaluationStopped(self.stop_code) from None
            raise
        finally:
            client.close()

    def _submit(self, client, stage, wire):
        verify(self.output_dir, self.manifest, self.state)
        require(
            wire.get("model") == MODEL
            and wire.get("max_output_tokens") == MAX_OUTPUT
            and wire.get("temperature") == 0
            and wire.get("store") is False,
            "MODEL_POLICY_CHANGED",
        )
        require(self.clock() < self.deadline, "REPORT_DEADLINE_EXCEEDED")
        upper, maximum = reservation(wire)
        total = totals(self.state)
        require(total["attempts"] < self.manifest["policy"]["maxCalls"], "CALL_LIMIT_REACHED")
        committed = Decimal(total["observedCostEstimatedUsd"]) + Decimal(
            total["unsettledReservedUsd"]
        )
        require(
            committed + maximum <= Decimal(self.manifest["policy"]["maxCostEstimatedUsd"]),
            "COST_RESERVATION_LIMIT_REACHED",
        )
        attempt_id = len(self.state["attempts"]) + 1
        record = {
            "attemptId": attempt_id,
            "caseId": self.result["caseId"],
            "variant": self.result["variant"],
            "stage": stage,
            "repairIndex": self.stage_counts[stage] - 1,
            "status": "in_flight",
            "requestSha256": digest(wire),
            "wireRequest": wire,
            "startedAt": stamp(),
            "attemptTimeoutSeconds": min(60, self.deadline - self.clock()),
            "latencyMs": None,
            "reservedCostUsd": str(maximum),
            "inputTokenUpperBound": upper,
            "unsettledReservedUsd": str(maximum),
            "costEstimatedUsd": "0",
            "providerRawResponse": None,
            "providerRawUsage": None,
            "providerText": None,
            "resolvedModel": None,
            "usage": None,
            "errorCode": None,
        }
        self.state["attempts"].append(record)
        self.result["attemptIds"].append(attempt_id)
        self.state["inFlight"] = attempt_id
        save(self.output_dir, self.state)
        started = self.clock()
        try:
            client.timeout = min(60, self.deadline - started)
            raw = client.responses.create(**wire)
            payload = raw.model_dump(mode="json")
            record.update(
                providerRawResponse=payload,
                providerRawUsage=payload.get("usage"),
                resolvedModel=payload.get("model"),
                providerText=raw.output_text,
            )
            usage, cost = observed_usage(payload.get("usage"))
            record["usage"] = usage
            require(
                payload.get("model") in {MODEL, "gpt-4.1-nano-2025-04-14"},
                "UNEXPECTED_RESOLVED_MODEL",
            )
            if cost is None and usage:
                lower = (
                    Decimal(usage.get("input_tokens", 0)) * PRICES[1]
                    + Decimal(usage.get("output_tokens", 0)) * PRICES[2]
                ) / 1_000_000
                record.update(
                    costEstimatedUsd=str(lower),
                    unsettledReservedUsd=str(max(Decimal(0), maximum - lower)),
                )
            require(cost is not None, "PROVIDER_USAGE_UNKNOWN")
            record.update(costEstimatedUsd=str(cost), unsettledReservedUsd="0")
            require(
                usage["input_tokens"] <= upper and usage["output_tokens"] <= MAX_OUTPUT,
                "PROVIDER_USAGE_EXCEEDED_RESERVATION",
            )
            require(cost <= maximum, "PROVIDER_COST_EXCEEDED_RESERVATION")
            require(
                self.clock() - started < record["attemptTimeoutSeconds"],
                "ATTEMPT_DEADLINE_EXCEEDED",
            )
            require(self.clock() < self.deadline, "REPORT_DEADLINE_EXCEEDED")
            record["status"] = "success"
            return raw
        except Exception as error:
            if record["providerRawResponse"] is None:
                # Read numeric usage only. Never retain the exception body itself.
                body = getattr(error, "body", None)
                supplied = body.get("usage") if isinstance(body, dict) else None
                if isinstance(error, AgentError) and isinstance(error.details, dict):
                    supplied = error.details.get("usage", supplied)
                    if isinstance(supplied, dict):
                        supplied = {
                            "input_tokens": supplied.get("inputTokens"),
                            "output_tokens": supplied.get("outputTokens"),
                        }
                clean, cost = observed_usage(supplied)
                record["providerRawUsage"] = clean or None
                record["usage"] = clean or None
                if cost is not None:
                    record.update(costEstimatedUsd=str(cost), unsettledReservedUsd="0")
                elif clean:
                    lower = (
                        Decimal(clean.get("input_tokens", 0)) * PRICES[1]
                        + Decimal(clean.get("output_tokens", 0)) * PRICES[2]
                    ) / 1_000_000
                    record.update(
                        costEstimatedUsd=str(lower),
                        unsettledReservedUsd=str(max(Decimal(0), maximum - lower)),
                    )
            record.update(
                status="failed",
                errorCode=str(error)
                if isinstance(error, EvaluationStopped)
                else "PROVIDER_ATTEMPT_FAILED",
            )
            # Provider exception strings, bodies and headers can contain keys.
            # Unobserved charges retain the full reservation and block resubmission.
            raise EvaluationStopped(record["errorCode"]) from None
        finally:
            record["latencyMs"] = round((self.clock() - started) * 1000, 3)
            self.state["inFlight"] = None
            save(self.output_dir, self.state)


def settings(api_key: str) -> Settings:
    # Construct from fixed defaults and policy, bypassing all environment/dotenv
    # settings sources. Only this explicit in-memory credential is retained.
    return Settings.model_construct(
        mock=False,
        openai_api_key=api_key,
        openai_model=MODEL,
        max_output_tokens=MAX_OUTPUT,
        report_max_output_tokens=MAX_OUTPUT,
        provider_timeout_seconds=60,
        report_provider_timeout_seconds=120,
        schema_repair_attempts=1,
        provider_retry_attempts=0,
        rate_limit_retry_attempts=0,
        openai_input_cost_per_million=PRICES[0],
        openai_cached_input_cost_per_million=PRICES[1],
        openai_output_cost_per_million=PRICES[2],
    )


def single_call(request: ReportInsightRequest, provider: AttemptProvider) -> ReportInsightResponse:
    request = _eligible_report_request(request)
    result = structured_call(
        provider,
        system_instruction=SYSTEM_INSTRUCTION,
        prompt=_report_insight_prompt(request),
        response_schema=report_insight_schema(request),
        validate=lambda response: _validated_output(response, request),
        repair_attempts=1,
        task_name="리포트 관점 인사이트 SINGLE",
        input_tag="report-insight",
        schema_violation_message="평가 후보가 리포트 계약을 위반했습니다.",
        logger=LOGGER,
        failure_prompt_version=PROMPT_VERSION,
    )
    return ReportInsightResponse(
        insights=result.output.insights,
        meta=ReportResponseMeta(
            provider=result.response.provider,
            model=result.response.model,
            prompt_version=PROMPT_VERSION,
            input_tokens=result.usage.input_tokens,
            output_tokens=result.usage.output_tokens,
            cost_usd=float(result.usage.cost_usd),
            credits=0,
            mock=False,
            truncated=False,
        ),
    )


@contextmanager
def silent_logging():
    previous = logging.root.manager.disable
    logging.disable(logging.CRITICAL)
    try:
        yield
    finally:
        logging.disable(previous)


def run(output_dir: Path, *, phase="pilot", sdk_factory=OpenAI, clock=time.monotonic) -> dict:
    require(phase in {"pilot", "remaining"}, "INVALID_PHASE")
    with evaluation_lock(output_dir), silent_logging():
        manifest = json.loads((output_dir / "manifest.json").read_text())
        state = json.loads((output_dir / "result.json").read_text())
        verify(output_dir, manifest, state)
        require(
            state["inFlight"] is None
            and not any(item["status"] == "in_flight" for item in state["attempts"]),
            "INTERRUPTED_ATTEMPT_REQUIRES_REVIEW",
        )
        require(
            all(item.get("safeToContinue") for item in state["errors"])
            and not any(
                item["status"] == "running"
                or (item["status"] == "failed" and not item.get("terminalFailureSafe"))
                for item in state["results"]
            ),
            "RECORDED_FAILURE_REQUIRES_REVIEW",
        )
        if phase == "remaining":
            require(
                all(
                    item["status"] == "success" or item.get("terminalFailureSafe")
                    for item in state["results"]
                    if item["phase"] == "pilot"
                ),
                "PILOT_NOT_COMPLETE",
            )
        pending = [
            item
            for item in state["results"]
            if item["phase"] == phase and item["status"] == "pending"
        ]
        if not pending:
            return state
        api_key = os.environ.get("OPENAI_API_KEY", "").strip()
        require(bool(api_key), "OPENAI_API_KEY_REQUIRED")
        for result in pending:
            request = ReportInsightRequest.model_validate(result["request"])
            require(request.plan == PLAN and len(request.audiences) == 1, "REQUEST_POLICY_CHANGED")
            result["status"] = "running"
            state["status"] = "running"
            save(output_dir, state)
            started = clock()
            provider = AttemptProvider(
                output_dir, manifest, state, result, api_key, sdk_factory=sdk_factory, clock=clock
            )
            try:
                response = (
                    single_call(request, provider)
                    if result["variant"] == "single_call"
                    else ReportInsightService(settings(api_key), provider).generate(request)
                )
                require(
                    not response.meta.mock and response.meta.model == MODEL,
                    "UNEXPECTED_OUTPUT_MODEL",
                )
                result.update(
                    status="success", response=response.model_dump(mode="json", by_alias=True)
                )
            except Exception as error:
                code = (
                    str(error)
                    if isinstance(error, EvaluationStopped)
                    else (error.code if isinstance(error, AgentError) else "EVALUATION_JOB_FAILED")
                )
                result.update(status="failed", errorCode=code)
                records = [state["attempts"][index - 1] for index in result["attemptIds"]]
                safe = (
                    isinstance(error, AgentError)
                    and error.code == "SCHEMA_VIOLATION"
                    and bool(records)
                    and all(
                        item["status"] == "success" and Decimal(item["unsettledReservedUsd"]) == 0
                        for item in records
                    )
                )
                result["terminalFailureSafe"] = safe
                state["errors"].append(
                    {
                        "caseId": result["caseId"],
                        "variant": result["variant"],
                        "code": code,
                        "safeToContinue": safe,
                    }
                )
                state["status"] = "running" if safe else "stopped"
                result["latencyMs"] = round((clock() - started) * 1000, 3)
                save(output_dir, state)
                if not safe:
                    raise EvaluationStopped(code) from None
            result["latencyMs"] = round((clock() - started) * 1000, 3)
            save(output_dir, state)
        state["status"] = (
            "complete"
            if all(
                item["status"] == "success" or item.get("terminalFailureSafe")
                for item in state["results"]
            )
            else "pilot_complete"
        )
        save(output_dir, state)
        return state


def summary(output_dir: Path) -> dict:
    manifest = json.loads((output_dir / "manifest.json").read_text())
    state = json.loads((output_dir / "result.json").read_text())
    return {
        "schemaVersion": 1,
        "status": state["status"],
        "caseCount": manifest["caseCount"],
        "model": MODEL,
        "successResults": sum(item["status"] == "success" for item in state["results"]),
        "baseCallUpperBound": manifest["baseCallUpperBound"],
        "repairCallUpperBound": manifest["repairCallUpperBound"],
        "totals": totals(state),
        "errors": state["errors"],
        "resultPath": str((output_dir / "result.json").resolve()),
    }


def _main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("prepare", "run", "status"))
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--max-cost-usd", type=Decimal, default=Decimal("1.00"))
    parser.add_argument("--max-calls", type=int, default=144)
    parser.add_argument("--pilot-size", type=int, default=4)
    parser.add_argument("--phase", choices=("pilot", "remaining"), default="pilot")
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            prepare(
                args.dataset,
                args.output_dir,
                max_cost_usd=args.max_cost_usd,
                max_calls=args.max_calls,
                pilot_size=args.pilot_size,
            )
        elif args.command == "run":
            run(args.output_dir, phase=args.phase)
        print(json.dumps(summary(args.output_dir), ensure_ascii=False))
    except Exception as error:
        code = str(error) if isinstance(error, EvaluationStopped) else "EVALUATION_INPUT_ERROR"
        print(json.dumps({"status": "stopped", "code": code}))
        raise SystemExit(2) from None


def main() -> None:
    with silent_logging():
        _main()


if __name__ == "__main__":
    main()
