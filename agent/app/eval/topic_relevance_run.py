"""Freeze and run a label-blind, single-article topic relevance evaluation.

Preflight only reads explicit non-secret inputs and source files. Live phases use
the existing process environment; no dotenv file is loaded. A recorded failure or
interrupted request requires review and is never automatically resubmitted.
The cost limit stops subsequent requests at the reported-cost limit; one request
(including configured provider/schema retries) can cross it.
"""

from __future__ import annotations

import argparse
import ast
import fcntl
import hashlib
import json
import logging
import math
import os
import platform
import subprocess
import tempfile
import time
from contextlib import contextmanager
from datetime import UTC, datetime
from decimal import Decimal, InvalidOperation
from importlib.metadata import version
from pathlib import Path

from app.eval.topic_relevance_review import read_json, sha256, validate_packet
from app.llm.topic_relevance_service import PROMPT_VERSION
from app.schemas.topic_relevance import TopicRelevanceRequest, TopicRelevanceResponse

AGENT_ROOT = Path(__file__).resolve().parents[2]
DOCS_ROOT = AGENT_ROOT.parent / "docs"
MODEL = "gpt-5.6-terra"
PLAN = "FREE"
# Fixed evaluation policy makes preflight credential-free while pinning every
# relevant setting. The relevance service uses built-in model prices, isolated
# from global model/price overrides. These are estimates, not invoice guarantees.
MODEL_PRICES = (Decimal("2.00"), Decimal("0.20"), Decimal("12.00"))
LIVE_SETTINGS = {
    "mock": False, "max_output_tokens": 6144, "provider_timeout_seconds": 60.0,
    "topic_relevance_openai_model": MODEL,
    "schema_repair_attempts": 1, "provider_retry_attempts": 1,
    "insight_provider_timeout_seconds": 60.0, "openai_request_interval_seconds": 1.0,
    "rate_limit_retry_attempts": 2, "rate_limit_backoff_seconds": 4.0,
    "rate_limit_max_backoff_seconds": 10.0, "rate_limit_max_wait_seconds": 60.0,
    "provider_concurrency": 1, "provider_acquire_timeout_seconds": 1.0,
    "circuit_failure_threshold": 3, "circuit_cooldown_seconds": 30.0,
    "hard_cap_credits_per_request": 5.0,
    "openai_input_cost_per_million": None,
    "openai_cached_input_cost_per_million": None,
    "openai_output_cost_per_million": None,
}


def execution_settings() -> dict:
    return {"provider": "openai", "model": MODEL,
            "modelPricesUsdPerMillion": [str(price) for price in MODEL_PRICES],
            "reasoningEffort": "medium", "temperature": "omitted", **{
        key: str(value) if isinstance(value, Decimal) else value
        for key, value in LIVE_SETTINGS.items()
    }}


class EvaluationStopped(Exception):
    """Only controlled, content-free error codes may be exposed to the CLI."""


def require(condition: bool, code: str) -> None:
    if not condition:
        raise EvaluationStopped(code)


def stamp() -> str:
    return datetime.now(UTC).isoformat()


def file_digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_object(path: Path) -> dict:
    value = read_json(path)
    require(isinstance(value, dict), "INVALID_OBJECT")
    return value


def runtime_hashes() -> dict[str, str]:
    """Pin the local import closure, not unrelated evaluation modules or the whole app."""
    pending = [
        "app.eval.topic_relevance_run", "app.llm.topic_relevance_service",
    ]
    paths: set[Path] = set()
    visited: set[str] = set()
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
            initializer = parent / "__init__.py"
            if initializer.is_file():
                name = ".".join(parent.relative_to(AGENT_ROOT).parts)
                pending.append(name)
        for node in ast.walk(ast.parse(path.read_text(encoding="utf-8"))):
            if isinstance(node, ast.ImportFrom) and node.module and node.module.startswith("app."):
                pending.append(node.module)
            elif isinstance(node, ast.Import):
                pending.extend(alias.name for alias in node.names if alias.name.startswith("app."))
    paths.update({
        AGENT_ROOT / "pyproject.toml", AGENT_ROOT / "uv.lock",
        AGENT_ROOT / "app/prompts" / f"{PROMPT_VERSION}.md",
    })
    return {str(path.relative_to(AGENT_ROOT)): file_digest(path) for path in sorted(paths)}


def validate_manifest(packet: dict, manifest: dict) -> None:
    require(manifest.get("datasetId") == packet["datasetId"], "MANIFEST_DATASET_CHANGED")
    for key, expected in (
        ("datasetSha256", packet["datasetSha256"]), ("model", MODEL),
        ("promptVersion", PROMPT_VERSION), ("plan", PLAN),
    ):
        require(key not in manifest or manifest[key] == expected, "MANIFEST_PROVENANCE_CHANGED")
    cases = manifest.get("cases")
    require(isinstance(cases, list) and len(cases) == len(packet["cases"]),
            "MANIFEST_CASES_CHANGED")
    by_id = {case["caseId"]: case for case in packet["cases"]}
    seen = set()
    for entry in cases:
        require(isinstance(entry, dict), "INVALID_MANIFEST_CASE")
        case_id = entry.get("caseId")
        require(isinstance(case_id, str) and case_id in by_id and case_id not in seen,
                "MANIFEST_CASES_CHANGED")
        seen.add(case_id)
        case = by_id[case_id]
        require(type(entry.get("articleId")) is int
                and entry["articleId"] == case["article"]["id"]
                and type(entry.get("topicId")) is int
                and entry["topicId"] == case["topic"]["id"], "MANIFEST_MAPPING_CHANGED")
        require(isinstance(entry.get("stratum"), str) and bool(entry["stratum"].strip()),
                "INVALID_MANIFEST_STRATUM")
        require("inputSha256" not in entry or entry["inputSha256"] == case["inputSha256"],
                "MANIFEST_INPUT_CHANGED")
        # Evaluation inputs must not carry answer keys or prior model predictions.
        require(not ({"decision", "expected", "expectedDecision", "gold", "label", "prediction"}
                     & entry.keys()), "MANIFEST_CONTAINS_LABELS")


def build_schedule(packet: dict, manifest: dict, pilot_size: int = 5) -> list[dict]:
    validate_manifest(packet, manifest)
    require(type(pilot_size) is int and pilot_size >= 1, "INVALID_PILOT_SIZE")
    require("pilotSize" not in manifest or (type(manifest["pilotSize"]) is int
            and manifest["pilotSize"] == min(pilot_size, len(packet["cases"]))),
            "MANIFEST_PILOT_SIZE_CHANGED")
    seed = manifest.get("seed", packet["datasetSha256"])
    require(isinstance(seed, str), "INVALID_SEED")
    ordered = sorted(packet["cases"], key=lambda case: sha256([seed, case["caseId"]]))
    schedule = []
    for index, case in enumerate(ordered):
        article = case["article"]
        request = TopicRelevanceRequest.model_validate({
            "idempotencyKey": f"eval:{PROMPT_VERSION}:{packet['datasetSha256'][:16]}:{index + 1}",
            "plan": PLAN, "topic": case["topic"],
            "articles": [{
                "articleId": article["id"], "title": article["title"],
                "summary": article["summary"], "bodyText": article["bodyText"],
            }],
        })
        schedule.append({
            "batchId": index + 1, "phase": "pilot" if index < pilot_size else "remaining",
            "caseId": case["caseId"], "inputSha256": case["inputSha256"],
            "request": request.model_dump(mode="json", by_alias=True),
        })
    return schedule


def prepare(packet_path: Path, manifest_path: Path, pilot_size: int,
            max_cost_usd: Decimal) -> tuple[list[dict], dict]:
    require(max_cost_usd.is_finite() and 0 < max_cost_usd <= 2, "INVALID_COST_LIMIT")
    packet = validate_packet(read_object(packet_path))
    manifest = read_object(manifest_path)
    schedule = build_schedule(packet, manifest, pilot_size)
    prompt = AGENT_ROOT / "app/prompts" / f"{PROMPT_VERSION}.md"
    prompt_hash = hashlib.sha256(prompt.read_text(encoding="utf-8").strip().encode()).hexdigest()
    require("promptSha256" not in manifest or manifest["promptSha256"] == prompt_hash,
            "MANIFEST_PROMPT_CHANGED")
    lock = {
        "schemaVersion": 1, "datasetId": packet["datasetId"],
        "datasetSha256": packet["datasetSha256"], "packetFileSha256": file_digest(packet_path),
        "manifestSha256": file_digest(manifest_path), "scheduleSha256": sha256(schedule),
        "model": MODEL, "plan": PLAN, "promptVersion": PROMPT_VERSION,
        "promptSha256": prompt_hash, "runtimeSourceSha256s": runtime_hashes(),
        "pilotSize": min(pilot_size, len(schedule)), "caseCount": len(schedule),
        "maxReportedCostUsd": str(max_cost_usd.normalize()),
        "executionMode": "single-article-per-request",
        "executionSettings": execution_settings(),
        "pythonVersion": platform.python_version(),
        "dependencyVersions": {name: version(name) for name in (
            "openai", "pydantic", "pydantic-settings", "pydantic-ai-slim", "httpx", "httpx2",
        )},
    }
    return schedule, lock


def atomic_save(path: Path, value: dict) -> None:
    descriptor, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2, allow_nan=False)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
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


def info_for(item: dict) -> dict:
    return {
        "batchId": item["batchId"], "phase": item["phase"], "caseIds": [item["caseId"]],
        "inputSha256s": {item["caseId"]: item["inputSha256"]},
        "requestSha256": sha256(item["request"]),
    }


def validate_response(response: TopicRelevanceResponse, request: dict) -> None:
    meta = response.meta
    require(not meta.mock and not meta.truncated and meta.provider == "openai"
            and meta.model == MODEL and meta.prompt_version == PROMPT_VERSION,
            "UNEXPECTED_RESPONSE_PROVENANCE")
    require(known_number(meta.cost_usd) is not None, "INVALID_RESPONSE_COST")
    article = request["articles"][0]
    require(len(response.decisions) == 1
            and response.decisions[0].article_id == article["articleId"],
            "UNEXPECTED_RESPONSE_ARTICLE")
    require(bool(response.decisions[0].evidence_quotes), "MISSING_RESPONSE_EVIDENCE")
    for quote in response.decisions[0].evidence_quotes:
        require(any(quote in (article[key] or "") for key in ("title", "summary", "bodyText")),
                "UNBOUND_RESPONSE_EVIDENCE")


def validate_checkpoint(result: dict, provenance: dict, schedule: list[dict]) -> None:
    require(all(result.get(key) == value for key, value in provenance.items()),
            "RESULT_PROVENANCE_CHANGED")
    require(isinstance(result.get("batches"), list) and isinstance(result.get("errors"), list),
            "INVALID_RESULT_LISTS")
    by_id = {item["batchId"]: item for item in schedule}
    seen: set[int] = set()
    for kind in ("batches", "errors"):
        for record in result[kind]:
            require(isinstance(record, dict), "INVALID_RESULT_RECORD")
            batch_id = record.get("batchId")
            require(type(batch_id) is int and batch_id in by_id and batch_id not in seen,
                    "INVALID_RESULT_ID")
            seen.add(batch_id)
            expected = info_for(by_id[batch_id])
            require(all(record.get(key) == value for key, value in expected.items()),
                    "RESULT_INPUT_CHANGED")
            if kind == "batches":
                validate_response(TopicRelevanceResponse.model_validate(record["response"]),
                                  by_id[batch_id]["request"])
    require("inFlight" in result and result["inFlight"] is None,
            "INTERRUPTED_REQUEST_REQUIRES_REVIEW")
    require(not result["errors"], "RECORDED_FAILURE_REQUIRES_REVIEW")


def known_number(value, integer: bool = False):
    if isinstance(value, bool) or not isinstance(value, (int, float, str, Decimal)):
        return None
    try:
        number = Decimal(str(value))
        if (number.is_finite() and number >= 0 and math.isfinite(float(number))
                and (not integer or number == int(number))):
            return int(number) if integer else float(number)
    except (InvalidOperation, ValueError, OverflowError):
        pass
    return None


def cost_so_far(result: dict) -> Decimal:
    amounts = [record["response"]["meta"].get("costUsd") for record in result["batches"]]
    amounts.extend(record.get("usage", {}).get("costUsd") for record in result["errors"])
    return sum((Decimal(str(number)) for value in amounts
                if (number := known_number(value)) is not None), Decimal(0))


def failure_record(error: Exception, info: dict, response=None) -> dict:
    from app.core.errors import AgentError

    # Only allow known program codes. Never persist exception text, args, cause,
    # provider body, arbitrary metadata strings, or supplied authorization data.
    code = str(error) if isinstance(error, EvaluationStopped) else "EVALUATION_ERROR"
    if isinstance(error, AgentError):
        code = error.code if error.code in {
            "SCHEMA_VIOLATION", "PROVIDER_UNAVAILABLE", "API_KEY_MISSING", "RATE_LIMITED",
            "CIRCUIT_OPEN", "BUDGET_EXCEEDED",
        } else "AGENT_ERROR"
    details = (error.details if isinstance(error, AgentError)
               and isinstance(error.details, dict) else {})
    usage = details.get("usage", {})
    if response is not None:
        usage = response.meta.model_dump(mode="json", by_alias=True)
    usage = usage if isinstance(usage, dict) else {}
    safe_usage = {
        key: number for key in ("inputTokens", "outputTokens", "costUsd", "credits")
        if (number := known_number(usage.get(key), key.endswith("Tokens"))) is not None
    }
    return {**info, "code": code, "usage": safe_usage,
            "usageMayBeIncomplete": response is None or len(safe_usage) != 4}


def live_service():
    """Called only after the frozen inputs and saved checkpoint have been checked."""
    from app.core.config import Settings
    from app.llm.openai_provider import _MODEL_PRICES
    from app.llm.router import close_analyze_providers
    from app.llm.topic_relevance_service import SYSTEM_INSTRUCTION, TopicRelevanceService

    settings = Settings().model_copy(update=LIVE_SETTINGS)
    require(settings.topic_relevance_openai_model == MODEL
            and bool(settings.openai_api_key.strip())
            and _MODEL_PRICES.get(MODEL) == MODEL_PRICES,
            "PROVIDER_CONFIGURATION_MISMATCH")
    require(hashlib.sha256(SYSTEM_INSTRUCTION.encode()).hexdigest()
            == hashlib.sha256((AGENT_ROOT / "app/prompts" / f"{PROMPT_VERSION}.md")
                              .read_text(encoding="utf-8").strip().encode()).hexdigest(),
            "LOADED_PROMPT_CHANGED")
    public = {**execution_settings(), "model": settings.topic_relevance_openai_model,
              **settings.model_dump(mode="json", include=set(LIVE_SETTINGS))}
    return TopicRelevanceService(settings), public, close_analyze_providers


def run_live(phase: str, schedule: list[dict], provenance: dict, output_dir: Path) -> dict:
    result_path = output_dir / "sealed/live-results.json"
    if result_path.exists():
        result = read_object(result_path)
        validate_checkpoint(result, provenance, schedule)
    else:
        require(phase == "pilot", "PILOT_REQUIRED")
        result = {**provenance, "startedAt": stamp(), "batches": [], "errors": [], "inFlight": None}
    done = {record["batchId"] for record in result["batches"]}
    pilot_ids = {item["batchId"] for item in schedule if item["phase"] == "pilot"}
    require(phase != "remaining" or pilot_ids <= done, "SUCCESSFUL_PILOT_REQUIRED")
    pending = [item for item in schedule if item["phase"] == phase and item["batchId"] not in done]
    if not pending:
        return result
    limit = Decimal(provenance["maxReportedCostUsd"])
    require(cost_so_far(result) < limit, "REPORTED_COST_CAP_REACHED")
    service, public_settings, close = live_service()
    try:
        require("executionSettings" not in result or result["executionSettings"] == public_settings,
                "EXECUTION_SETTINGS_CHANGED")
        result["executionSettings"] = public_settings
        result["retryPolicyNote"] = "Each article may include configured schema/provider retries."
        result_path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        for item in pending:
            require(cost_so_far(result) < limit, "REPORTED_COST_CAP_REACHED")
            info = {**info_for(item), "startedAt": stamp()}
            result["inFlight"] = info
            result["updatedAt"] = stamp()
            atomic_save(result_path, result)
            started = time.monotonic()
            response = None
            try:
                response = service.classify(TopicRelevanceRequest.model_validate(item["request"]))
                validate_response(response, item["request"])
            except Exception as error:  # noqa: BLE001 - retain every failed attempt without raw text.
                result["errors"].append({**failure_record(error, info, response),
                                         "finishedAt": stamp(),
                                         "elapsedSeconds": round(time.monotonic() - started, 3)})
                result["inFlight"] = None
                result["updatedAt"] = stamp()
                atomic_save(result_path, result)
                raise EvaluationStopped("RECORDED_FAILURE_REQUIRES_REVIEW") from None
            result["batches"].append({
                **info, "response": response.model_dump(mode="json", by_alias=True),
                "finishedAt": stamp(), "elapsedSeconds": round(time.monotonic() - started, 3),
            })
            result["inFlight"] = None
            result["updatedAt"] = stamp()
            atomic_save(result_path, result)
            print_progress(phase, result)
        return result
    finally:
        close()


def print_progress(phase: str, result: dict) -> None:
    print(json.dumps({"phase": phase, "successfulCases": len(result["batches"]),
                      "failedCases": len(result["errors"]),
                      "knownCostUsd": float(cost_so_far(result))}), flush=True)


def execute(packet: Path, manifest: Path, output_dir: Path, phase: str,
            pilot_size: int = 5, max_cost_usd: Decimal = Decimal(1)) -> dict:
    require(phase in {"preflight", "pilot", "remaining"}, "INVALID_PHASE")
    with evaluation_lock(output_dir):
        schedule, expected = prepare(packet, manifest, pilot_size, max_cost_usd)
        lock_path = output_dir / "evaluation-lock.json"
        if lock_path.exists():
            frozen = read_object(lock_path)
            require(all(frozen.get(key) == value for key, value in expected.items()),
                    "EVALUATION_LOCK_CHANGED")
        else:
            require(phase == "preflight", "PREFLIGHT_REQUIRED")
            require(not (output_dir / "sealed/live-results.json").exists(), "ORPHANED_CHECKPOINT")
            commit = subprocess.run(["git", "rev-parse", "HEAD"], cwd=AGENT_ROOT,
                                    capture_output=True, text=True, check=False)
            frozen = {**expected, "createdAt": stamp(),
                      "codeCommit": commit.stdout.strip() if commit.returncode == 0 else None}
            atomic_save(lock_path, frozen)
        if phase == "preflight":
            return {"phase": phase, "cases": len(schedule), "pilotCases": expected["pilotSize"],
                    "remainingCases": len(schedule) - expected["pilotSize"]}
        provenance = {**frozen, "evaluationLockSha256": file_digest(lock_path)}
        return run_live(phase, schedule, provenance, output_dir)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--packet", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--phase", choices=("preflight", "pilot", "remaining"), required=True)
    parser.add_argument("--pilot-size", type=int, default=5)
    parser.add_argument("--max-cost-usd", type=Decimal, default=Decimal(1))
    args = parser.parse_args(argv)
    previous_logging = logging.root.manager.disable
    logging.disable(logging.CRITICAL)
    try:
        require(args.output_dir.resolve().is_relative_to(DOCS_ROOT.resolve()),
                "OUTPUT_OUTSIDE_DOCUMENTATION_DIRECTORY")
        result = execute(args.packet, args.manifest, args.output_dir, args.phase,
                         args.pilot_size, args.max_cost_usd)
        if args.phase == "preflight":
            print(json.dumps(result), flush=True)
        else:
            print_progress(args.phase, result)
        return 0
    except KeyboardInterrupt:
        print(json.dumps({"phase": args.phase, "stopped": True,
                          "errorCode": "EVALUATION_INTERRUPTED_REQUIRES_REVIEW"}), flush=True)
        return 130
    except Exception as error:  # noqa: BLE001 - never echo arbitrary provider/config exception data.
        code = str(error) if isinstance(error, EvaluationStopped) else "LOCAL_VALIDATION_FAILED"
        print(json.dumps({"phase": args.phase, "stopped": True, "errorCode": code}), flush=True)
        return 2
    finally:
        logging.disable(previous_logging)


if __name__ == "__main__":
    raise SystemExit(main())
