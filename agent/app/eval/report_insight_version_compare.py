"""Frozen-runtime, checkpointed nano comparison of report-insight versions.

The coordinator never imports baseline application code into its own process.
Every job runs the saved version's service and guards in a separate process;
only opaque A/B results are presented to reviewers. Credentials stay in memory.
"""

from __future__ import annotations

import argparse
import fcntl
import getpass
import json
import logging
import math
import os
import re
import subprocess
import sys
import time
import warnings
from copy import deepcopy
from decimal import Decimal
from pathlib import Path

# Workers execute this file directly. Select their complete app package before
# importing any app module, rather than mixing a frozen service with live guards.
if __name__ == "__main__" and "--runtime-root" in sys.argv:
    sys.path.insert(0, str(Path(sys.argv[sys.argv.index("--runtime-root") + 1]).resolve()))

from openai import APIStatusError, DefaultHttpx2Client, OpenAI

from app.core.config import Settings
from app.core.errors import AgentError
from app.eval import report_insight_run as ledger
from app.eval.report_insight_compare import render_comparison
from app.eval.report_insight_corpus import load_corpus
from app.eval.report_insight_measure import aggregate_judgments, generation_metrics
from app.llm.deadline_transport import DeadlineHttpx2Transport
from app.llm.openai_provider import _ERROR_CODES, OpenAIAnalyzeProvider, _provider_error_details
from app.llm.report_insight_service import PROMPT_VERSION, RUBRIC_VERSION, ReportInsightService
from app.schemas.report_insight import ReportInsightRequest, ReportInsightResponse

DRIVER = Path(__file__).resolve()
MAX_OUTPUT = 8192
MAX_DIAGNOSTIC_RETRY_AFTER_SECONDS = 86_400
VERSIONS = ("baseline", "candidate")
ADAPTER_VARIANTS = {"baseline": "single_call", "candidate": "staged"}
CALL_DESCRIPTION = re.compile(r"^reportInsightCall:(MAP|REVIEW|REDUCE)-(\d{3})$")
POLICY = {
    **ledger.POLICY,
    "maxOutputTokens": MAX_OUTPUT,
    "reportDeadlineSeconds": 180,
    "comparisonKind": "frozen-version-before-after",
    "baselineCommit": "cbfb174",
    "baselinePromptVersion": "report-insight.ko.v3",
    "candidatePromptVersion": "report-insight.ko.v4",
    "mapChunkFindingLimit": 8,
    "reviewFindingLimit": 12,
}
COMPARISON_PROFILES = {
    # Keep the original policy byte-for-byte compatible with saved checkpoints.
    "v3-v4": POLICY,
    "v8-v9": {
        **POLICY,
        "comparisonProfile": "v8-v9",
        "baselineCommit": "e75f2d851f1a3b61d86232a94d59f71072055318",
        "baselinePromptVersion": "report-insight.ko.v8",
        "candidatePromptVersion": "report-insight.ko.v9",
        "baselineRubricVersion": "report-importance.v5",
        "candidateRubricVersion": "report-importance.v6",
        "baselinePipeline": "staged",
    },
    "v9-refinement": {
        **POLICY,
        "comparisonProfile": "v9-refinement",
        "comparisonKind": "frozen-same-version-refinement",
        "baselineCommit": "c555596e7ab22d13a46087888de95024585f6093",
        "baselinePromptVersion": "report-insight.ko.v9",
        "candidatePromptVersion": "report-insight.ko.v9",
        "baselineRubricVersion": "report-importance.v6",
        "candidateRubricVersion": "report-importance.v6",
        "baselinePipeline": "staged",
    },
    "v9-bounded-review": {
        **POLICY,
        "comparisonProfile": "v9-bounded-review",
        "comparisonKind": "frozen-same-version-refinement",
        "baselineCommit": "c555596e7ab22d13a46087888de95024585f6093",
        "baselinePromptVersion": "report-insight.ko.v9",
        "candidatePromptVersion": "report-insight.ko.v9",
        "baselineRubricVersion": "report-importance.v6",
        "candidateRubricVersion": "report-importance.v6",
        "baselinePipeline": "staged",
        "candidateMapChunkFindingLimit": 6,
        "candidateReviewChunkFindingLimit": 6,
    },
}


def _comparison_policy(profile: str) -> dict:
    ledger.require(profile in COMPARISON_PROFILES, "UNKNOWN_COMPARISON_PROFILE")
    return COMPARISON_PROFILES[profile]


def _is_staged_comparison(policy: dict) -> bool:
    return policy.get("baselinePipeline") == "staged"


def _validate_runtime_versions(policy: dict, runtimes: dict) -> None:
    for name in VERSIONS:
        info = runtimes[name]
        ledger.require(
            info["promptVersion"] == policy[f"{name}PromptVersion"],
            "VERSION_RUNTIME_MISMATCH",
        )
        rubric = policy.get(f"{name}RubricVersion")
        ledger.require(rubric is None or info["rubricVersion"] == rubric, "RUBRIC_RUNTIME_MISMATCH")


def _comparison_scope(policy: dict, *, snapshots: bool = False) -> str:
    baseline = policy["baselinePromptVersion"].rsplit(".", 1)[1]
    candidate = policy["candidatePromptVersion"].rsplit(".", 1)[1]
    inputs = "identical actual report snapshots" if snapshots else "the same actual report inputs"
    if policy.get("comparisonKind") == "frozen-same-version-refinement":
        return (
            f"frozen {baseline} code refinement from {policy['baselineCommit'][:7]} "
            f"versus the candidate on {inputs}; both runtimes identified by source hashes"
        )
    return f"frozen {baseline} versus {candidate} on {inputs}"


def _verify_coordinator(provenance: dict) -> None:
    source = provenance.get("coordinatorSources")
    ledger.require(isinstance(source, dict), "COORDINATOR_PROVENANCE_MISSING")
    root = Path(source.get("root", ""))
    hashes = source.get("sourceSha256s")
    ledger.require(
        root.is_absolute() and isinstance(hashes, dict) and hashes, "COORDINATOR_CHANGED"
    )
    for relative, expected in hashes.items():
        path = Path(relative)
        ledger.require(not path.is_absolute() and ".." not in path.parts, "COORDINATOR_CHANGED")
        full_path = root / path
        ledger.require(
            full_path.is_file() and ledger.file_digest(full_path) == expected,
            "COORDINATOR_CHANGED",
        )


def _process_environment(api_key: str | None = None) -> dict[str, str]:
    allowed = (
        "PATH",
        "HOME",
        "LANG",
        "LC_ALL",
        "LC_CTYPE",
        "SYSTEMROOT",
        "HTTP_PROXY",
        "HTTPS_PROXY",
        "ALL_PROXY",
        "NO_PROXY",
        "http_proxy",
        "https_proxy",
        "all_proxy",
        "no_proxy",
        "SSL_CERT_FILE",
        "SSL_CERT_DIR",
    )
    environment = {name: os.environ[name] for name in allowed if name in os.environ}
    environment["PYTHONDONTWRITEBYTECODE"] = "1"
    if api_key is not None:
        environment["OPENAI_API_KEY"] = api_key
    return environment


def runtime_info() -> dict:
    hashes = ledger.runtime_hashes()
    # The old comparison runner deliberately remains on its legacy prompt.
    # Freeze every public runtime prompt too, including the candidate stages.
    hashes.update(
        {
            str(path.relative_to(ledger.AGENT_ROOT)): ledger.file_digest(path)
            for path in sorted((ledger.AGENT_ROOT / "app/prompts").glob("*.md"))
        }
    )
    return {
        "runtimeRoot": str(ledger.AGENT_ROOT.resolve()),
        "runtimeSourceSha256s": hashes,
        "dependencyVersions": ledger.dependencies(),
        "pythonVersion": ledger.platform.python_version(),
        "promptVersion": PROMPT_VERSION,
        "rubricVersion": RUBRIC_VERSION,
    }


def _runtime_info(root: Path) -> dict:
    ledger.require((root / "app/llm/report_insight_service.py").is_file(), "RUNTIME_MISSING")
    process = subprocess.run(
        [sys.executable, "-B", str(DRIVER), "runtime", "--runtime-root", str(root.resolve())],
        cwd=root,
        env=_process_environment(),
        capture_output=True,
        text=True,
        timeout=30,
    )
    ledger.require(process.returncode == 0, "RUNTIME_PROBE_FAILED")
    try:
        return json.loads(process.stdout)
    except ValueError:
        raise ledger.EvaluationStopped("RUNTIME_PROBE_FAILED") from None


def prepare(
    dataset: Path,
    output_dir: Path,
    baseline_root: Path,
    *,
    baseline_commit: str | None = None,
    comparison_profile: str = "v3-v4",
    candidate_root: Path | None = None,
    max_cost_usd: Decimal = Decimal("1.00"),
    max_calls: int = 144,
) -> dict:
    expected_policy = _comparison_policy(comparison_profile)
    if baseline_commit is None:
        baseline_commit = expected_policy["baselineCommit"]
    ledger.require(baseline_commit == expected_policy["baselineCommit"], "BASELINE_COMMIT_CHANGED")
    ledger.require(
        ledger.number(max_cost_usd) is not None and 0 < max_cost_usd <= 1, "INVALID_COST_LIMIT"
    )
    ledger.require(type(max_calls) is int and 1 <= max_calls <= 144, "INVALID_CALL_LIMIT")
    corpus = load_corpus(dataset)
    ledger.require(not corpus.synthetic, "REAL_REPORT_CORPUS_REQUIRED")
    roots = {
        "baseline": baseline_root.resolve(),
        "candidate": (candidate_root or ledger.AGENT_ROOT).resolve(),
    }
    ledger.require(roots["baseline"] != roots["candidate"], "BASELINE_MUST_BE_ISOLATED")
    runtimes = {name: _runtime_info(root) for name, root in roots.items()}
    _validate_runtime_versions(expected_policy, runtimes)
    ledger.require(
        runtimes["baseline"]["dependencyVersions"] == runtimes["candidate"]["dependencyVersions"],
        "DEPENDENCIES_DIFFER",
    )
    jobs = []
    for case in corpus.cases:
        request = case.request.model_dump(mode="json", by_alias=True)
        request.update(plan="FREE", idempotencyKey=f"version-eval:{ledger.digest(request)[:24]}")
        request = ReportInsightRequest.model_validate(request).model_dump(
            mode="json", by_alias=True
        )
        order = VERSIONS if int(ledger.digest(case.case_id)[0], 16) % 2 == 0 else VERSIONS[::-1]
        for name in order:
            jobs.append(
                {
                    "caseId": case.case_id,
                    "variant": name,
                    "request": request,
                    "inputSha256": ledger.digest(request),
                }
            )
    policy = {**expected_policy, "maxCostEstimatedUsd": str(max_cost_usd), "maxCalls": max_calls}
    provenance = {
        "datasetVersion": corpus.version,
        "datasetSha256": ledger.file_digest(dataset),
        "datasetPath": str(dataset.resolve()),
        "scheduleSha256": ledger.digest(jobs),
        "inputSha256": ledger.digest([job["request"] for job in jobs]),
        "policySha256": ledger.digest(policy),
        "runtimeVersions": runtimes,
        "driverSourceSha256": ledger.file_digest(DRIVER),
        "model": ledger.MODEL,
        "baselineCommit": baseline_commit,
    }
    if _is_staged_comparison(expected_policy):
        provenance["coordinatorSources"] = {
            "root": str(ledger.AGENT_ROOT.resolve()),
            "sourceSha256s": ledger.runtime_hashes(),
        }
    maximum_base = sum(len(_allowed_calls(job, policy)) for job in jobs)
    manifest = {
        "schemaVersion": 2,
        "provenance": provenance,
        "policy": policy,
        "jobs": jobs,
        "caseCount": len(corpus.cases),
        "baseCallUpperBound": maximum_base,
        "repairCallUpperBound": maximum_base * 2,
    }
    manifest["manifestSha256"] = ledger.digest(manifest)
    with ledger.evaluation_lock(output_dir):
        if (output_dir / "manifest.json").exists():
            ledger.require(_read(output_dir / "manifest.json") == manifest, "PREPARED_RUN_CHANGED")
            verify(output_dir, manifest, _read(output_dir / "result.json"))
        else:
            ledger.require(not (output_dir / "result.json").exists(), "UNBOUND_CHECKPOINT")
            ledger.atomic_save(output_dir / "manifest.json", manifest)
            ledger.save(
                output_dir,
                {
                    "schemaVersion": 2,
                    "manifestSha256": manifest["manifestSha256"],
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


def _read(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def verify(output_dir: Path, manifest: dict, state: dict) -> None:
    ledger.require(
        manifest.get("schemaVersion") == 2 and state.get("schemaVersion") == 2,
        "INVALID_RESULT_VERSION",
    )
    ledger.require(
        manifest.get("manifestSha256")
        == ledger.digest(
            {key: value for key, value in manifest.items() if key != "manifestSha256"}
        ),
        "MANIFEST_CHANGED",
    )
    ledger.require(
        state.get("checkpointSha256")
        == ledger.digest({key: value for key, value in state.items() if key != "checkpointSha256"}),
        "CHECKPOINT_CHANGED",
    )
    ledger.require(
        state.get("manifestSha256") == manifest["manifestSha256"]
        and state.get("provenance") == manifest["provenance"]
        and state.get("policy") == manifest["policy"],
        "CHECKPOINT_PROVENANCE_CHANGED",
    )
    ledger.require(_read(output_dir / "manifest.json") == manifest, "MANIFEST_CHANGED")
    provenance = manifest["provenance"]
    ledger.require(
        provenance["datasetSha256"] == ledger.file_digest(Path(provenance["datasetPath"])),
        "DATASET_CHANGED",
    )
    ledger.require(
        ledger.digest(manifest["jobs"]) == provenance["scheduleSha256"]
        and ledger.digest([job["request"] for job in manifest["jobs"]]) == provenance["inputSha256"]
        and ledger.digest(manifest["policy"]) == provenance["policySha256"],
        "MANIFEST_CHANGED",
    )
    expected_policy = _comparison_policy(manifest["policy"].get("comparisonProfile", "v3-v4"))
    ledger.require(
        all(manifest["policy"].get(key) == value for key, value in expected_policy.items()),
        "POLICY_CHANGED",
    )
    # A profile without variant overrides still fixes their effective values.
    # Additional checkpoint keys must not widen stage admission after hashing.
    ledger.require(
        all(
            manifest["policy"].get(
                f"{variant}{stage}ChunkFindingLimit", manifest["policy"][fallback]
            )
            == expected_policy.get(f"{variant}{stage}ChunkFindingLimit", expected_policy[fallback])
            for variant in VERSIONS
            for stage, fallback in (
                ("Map", "mapChunkFindingLimit"),
                ("Review", "reviewFindingLimit"),
            )
        ),
        "POLICY_CHANGED",
    )
    ledger.require(
        provenance["baselineCommit"] == expected_policy["baselineCommit"],
        "BASELINE_COMMIT_CHANGED",
    )
    _validate_runtime_versions(expected_policy, provenance["runtimeVersions"])
    if _is_staged_comparison(expected_policy):
        # Workers inspect these declared coordinator files, not their own frozen
        # package. The renderer/metrics may intentionally postdate both runtimes.
        _verify_coordinator(provenance)
    cap = ledger.number(manifest["policy"].get("maxCostEstimatedUsd"))
    ledger.require(
        cap is not None
        and 0 < cap <= 1
        and type(manifest["policy"].get("maxCalls")) is int
        and 1 <= manifest["policy"]["maxCalls"] <= 144,
        "INVALID_BUDGET",
    )
    active = [item["attemptId"] for item in state["attempts"] if item["status"] == "in_flight"]
    ledger.require(
        active == ([] if state["inFlight"] is None else [state["inFlight"]]),
        "CHECKPOINT_IN_FLIGHT_CHANGED",
    )
    ledger.require(
        state["caseIds"] == list(dict.fromkeys(job["caseId"] for job in manifest["jobs"])),
        "CHECKPOINT_CASES_CHANGED",
    )
    ledger.require(len(state["results"]) == len(manifest["jobs"]), "CHECKPOINT_JOBS_CHANGED")
    for result, job in zip(state["results"], manifest["jobs"], strict=True):
        ledger.require(
            all(result.get(key) == value for key, value in job.items()), "CHECKPOINT_INPUT_CHANGED"
        )
    _verify_attempts(manifest, state)
    ledger.require(state["totals"] == ledger.totals(state), "CHECKPOINT_TOTALS_CHANGED")


def _verify_attempts(manifest: dict, state: dict) -> None:
    groups = {}
    results = {(item["caseId"], item["variant"]): item for item in state["results"]}
    ledger.require(len(results) == len(state["results"]), "DUPLICATE_CHECKPOINT_JOB")
    ledger.require(
        len(state["attempts"]) <= manifest["policy"]["maxCalls"], "CHECKPOINT_CALL_LIMIT"
    )
    for index, record in enumerate(state["attempts"], 1):
        _verify_record(record, index)
        key = (record["caseId"], record["variant"])
        ledger.require(key in results, "UNBOUND_CHECKPOINT_ATTEMPT")
        call_id = record["logicalCallId"]
        ledger.require(
            re.fullmatch(r"(?:MAP|REVIEW|REDUCE)-\d{3}", call_id) is not None
            and record["stage"] == call_id.split("-")[0],
            "CHECKPOINT_STAGE_CHANGED",
        )
        wire_format = record["wireRequest"]["text"]["format"]
        ledger.require(
            _logical_call(
                wire_format["schema"], key[1], wire_format.get("name"), policy=manifest["policy"]
            )
            == call_id,
            "CHECKPOINT_CALL_ID_CHANGED",
        )
        ledger.require(
            call_id in _allowed_calls(results[key], manifest["policy"]), "CHECKPOINT_STAGE_CHANGED"
        )
        identity = ledger.digest([*key, call_id])
        count = groups.get(identity, 0)
        ledger.require(count < 2 and record["repairIndex"] == count, "CHECKPOINT_REPAIR_CHANGED")
        groups[identity] = count + 1
    for key, result in results.items():
        ledger.require(
            result["status"] in {"pending", "running", "success", "failed"}, "INVALID_JOB_STATUS"
        )
        records = [
            record for record in state["attempts"] if (record["caseId"], record["variant"]) == key
        ]
        ledger.require(
            result["attemptIds"] == [item["attemptId"] for item in records],
            "CHECKPOINT_ATTEMPT_MAPPING_CHANGED",
        )
        if result["status"] == "pending":
            ledger.require(not records and result["response"] is None, "PENDING_JOB_HAS_CALLS")
        if result["status"] == "success":
            ledger.require(
                records and all(item["status"] == "success" for item in records),
                "SUCCESS_WITH_FAILED_ATTEMPT",
            )
            response = ReportInsightResponse.model_validate(result["response"])
            ledger.require(
                response.meta.provider == "openai"
                and response.meta.model == ledger.MODEL
                and response.meta.prompt_version
                == manifest["provenance"]["runtimeVersions"][key[1]]["promptVersion"]
                and not response.meta.mock
                and not response.meta.truncated,
                "CHECKPOINT_RESPONSE_CHANGED",
            )
            ledger.require(
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
            safe = (
                result["errorCode"] == "SCHEMA_VIOLATION"
                and bool(records)
                and all(
                    item["status"] == "success" and Decimal(item["unsettledReservedUsd"]) == 0
                    for item in records
                )
            )
            ledger.require(
                result.get("terminalFailureSafe") is safe, "CHECKPOINT_FAILURE_POLICY_CHANGED"
            )
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
    ledger.require(state["errors"] == failures, "CHECKPOINT_FAILURE_MAPPING_CHANGED")


def _allowed_calls(result: dict, policy: dict = POLICY) -> set[str]:
    if result["variant"] == "baseline" and policy.get("baselinePipeline") != "staged":
        return {"MAP-001", "REDUCE-001"}
    variant = result["variant"]
    finding_count = len(result["request"]["findings"])
    map_limit = policy.get(f"{variant}MapChunkFindingLimit", policy["mapChunkFindingLimit"])
    review_limit = policy.get(f"{variant}ReviewChunkFindingLimit", policy["reviewFindingLimit"])
    map_count = math.ceil(finding_count / map_limit)
    review_count = max(
        1, math.ceil(min(finding_count, policy["reviewFindingLimit"]) / review_limit)
    )
    return {
        *(f"MAP-{index:03d}" for index in range(1, map_count + 1)),
        *(f"REVIEW-{index:03d}" for index in range(1, review_count + 1)),
        "REDUCE-001",
    }


def reservation(wire: dict) -> tuple[int, Decimal]:
    # Reuse the legacy input/framing bound with the production-sized output cap.
    upper, maximum = ledger.reservation(wire)
    maximum += Decimal(MAX_OUTPUT - ledger.MAX_OUTPUT) * ledger.PRICES[2] / 1_000_000
    return upper, maximum


def _verify_record(record: dict, index: int) -> None:
    ledger.require(record["attemptId"] == index, "CHECKPOINT_ATTEMPT_IDS_CHANGED")
    wire = record["wireRequest"]
    upper, maximum = reservation(wire)
    ledger.require(
        record["requestSha256"] == ledger.digest(wire)
        and record["inputTokenUpperBound"] == upper
        and Decimal(record["reservedCostUsd"]) == maximum,
        "CHECKPOINT_RESERVATION_CHANGED",
    )
    ledger.require(
        wire["model"] == ledger.MODEL
        and wire["max_output_tokens"] == MAX_OUTPUT
        and wire["temperature"] == 0
        and wire["store"] is False,
        "CHECKPOINT_MODEL_CHANGED",
    )
    ledger.require(record["status"] in {"success", "failed", "in_flight"}, "INVALID_ATTEMPT_STATUS")
    http_status, provider_code = record.get("providerHttpStatus"), record.get("providerErrorCode")
    ledger.require(
        (http_status is None and provider_code is None)
        or (
            type(http_status) is int
            and 100 <= http_status <= 599
            and isinstance(provider_code, str)
            and provider_code in _ERROR_CODES | {"UNKNOWN"}
        ),
        "CHECKPOINT_PROVIDER_DIAGNOSTICS_CHANGED",
    )
    retry_after = record.get("providerRetryAfterSeconds")
    ledger.require(
        retry_after is None
        or (
            _valid_diagnostic_retry_after(retry_after)
            and record["status"] == "failed"
            and http_status is not None
        ),
        "CHECKPOINT_PROVIDER_DIAGNOSTICS_CHANGED",
    )
    if record["status"] != "failed":
        ledger.require(
            http_status is None and provider_code is None, "CHECKPOINT_PROVIDER_DIAGNOSTICS_CHANGED"
        )
    raw = record["providerRawResponse"]
    if raw is not None:
        raw_usage = raw.get("usage")
        ledger.require(raw_usage == record["providerRawUsage"], "CHECKPOINT_RAW_USAGE_CHANGED")
        text = "".join(
            content["text"]
            for item in raw.get("output", [])
            if item.get("type") == "message"
            for content in item.get("content", [])
            if content.get("type") == "output_text"
        )
        ledger.require(record["providerText"] == text, "CHECKPOINT_PROVIDER_TEXT_CHANGED")
        ledger.require(
            record["resolvedModel"] == raw.get("model"), "CHECKPOINT_RESOLVED_MODEL_CHANGED"
        )
    else:
        raw_usage = record["providerRawUsage"]
        if raw_usage is not None:
            raw_usage = {
                "input_tokens": raw_usage.get("input_tokens"),
                "output_tokens": raw_usage.get("output_tokens"),
                "input_tokens_details": {"cached_tokens": raw_usage.get("cached_tokens", 0)},
            }
    if raw_usage is not None:
        usage, cost = ledger.observed_usage(raw_usage)
        ledger.require(record["usage"] == usage, "CHECKPOINT_NORMALIZED_USAGE_CHANGED")
        unsettled = Decimal(0)
        if raw is not None and record["resolvedModel"] not in {
            ledger.MODEL,
            "gpt-4.1-nano-2025-04-14",
        }:
            cost, unsettled = Decimal(0), maximum
        elif cost is None:
            cost = (
                Decimal(usage.get("input_tokens", 0)) * ledger.PRICES[1]
                + Decimal(usage.get("output_tokens", 0)) * ledger.PRICES[2]
            ) / 1_000_000
            unsettled = max(Decimal(0), maximum - cost)
        ledger.require(
            Decimal(record["costEstimatedUsd"]) == cost
            and Decimal(record["unsettledReservedUsd"]) == unsettled,
            "CHECKPOINT_COST_CHANGED",
        )
    else:
        ledger.require(
            Decimal(record["costEstimatedUsd"]) == 0
            and Decimal(record["unsettledReservedUsd"]) == maximum,
            "CHECKPOINT_UNOBSERVED_COST_CHANGED",
        )
    if record["status"] == "success":
        ledger.require(
            raw is not None
            and record["resolvedModel"] in {ledger.MODEL, "gpt-4.1-nano-2025-04-14"}
            and Decimal(record["unsettledReservedUsd"]) == 0
            and record["usage"]["input_tokens"] <= upper
            and record["usage"]["output_tokens"] <= MAX_OUTPUT,
            "INVALID_SUCCESSFUL_ATTEMPT",
        )


def _logical_call(
    schema: dict, variant: str, format_name: str | None = None, *, policy: dict = POLICY
) -> str:
    match = CALL_DESCRIPTION.fullmatch(schema.get("description", ""))
    if match:
        return f"{match[1]}-{match[2]}"
    ledger.require(
        variant == "baseline" and policy.get("baselinePipeline") != "staged", "CALL_ID_REQUIRED"
    )
    title = schema.get("title") or format_name or ""
    stage = "MAP" if "MapOutput" in title else "REDUCE" if "ReduceOutput" in title else None
    ledger.require(stage is not None, "UNKNOWN_STAGE")
    return f"{stage}-001"


class VersionAttemptProvider:
    """Reserve/fsync the exact native SDK body before each logical call/repair."""

    def __init__(
        self,
        output_dir,
        manifest,
        state,
        result,
        api_key,
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
        self.call_counts = {}
        self.stop_code = None

    def generate(self, *, system_instruction, prompt, response_schema):
        verify(self.output_dir, self.manifest, self.state)
        remaining = self.deadline - self.clock()
        ledger.require(remaining > 0, "REPORT_DEADLINE_EXCEEDED")
        call_id = _logical_call(
            response_schema, self.result["variant"], policy=self.manifest["policy"]
        )
        ledger.require(
            call_id in _allowed_calls(self.result, self.manifest["policy"]), "UNKNOWN_STAGE"
        )
        self.call_counts[call_id] = self.call_counts.get(call_id, 0) + 1
        ledger.require(self.call_counts[call_id] <= 2, "EXCESS_SCHEMA_REPAIR")
        attempt_deadline = min(self.deadline, self.clock() + POLICY["attemptTimeoutSeconds"])
        http_client = DefaultHttpx2Client(
            timeout=min(60, remaining), transport=DeadlineHttpx2Transport(attempt_deadline)
        )
        client = None
        owner = self

        class Capture:
            def create(self, **wire):
                try:
                    return owner._submit(client, call_id, wire)
                except ledger.EvaluationStopped as error:
                    owner.stop_code = str(error)
                    raise

        class Proxy:
            responses = Capture()

        try:
            client = self.sdk_factory(
                api_key=self.api_key,
                base_url="https://api.openai.com/v1",
                max_retries=0,
                timeout=min(60, remaining),
                http_client=http_client,
            )
            provider = OpenAIAnalyzeProvider(_settings(self.api_key), client=Proxy())
            try:
                return provider.generate(
                    system_instruction=system_instruction,
                    prompt=prompt,
                    response_schema=response_schema,
                )
            except AgentError:
                if self.stop_code:
                    raise ledger.EvaluationStopped(self.stop_code) from None
                raise
        finally:
            if client is not None:
                client.close()
            http_client.close()

    def _submit(self, client, call_id: str, wire: dict):
        verify(self.output_dir, self.manifest, self.state)
        ledger.require(
            wire.get("model") == ledger.MODEL
            and wire.get("max_output_tokens") == MAX_OUTPUT
            and wire.get("temperature") == 0
            and wire.get("store") is False,
            "MODEL_POLICY_CHANGED",
        )
        ledger.require(self.clock() < self.deadline, "REPORT_DEADLINE_EXCEEDED")
        upper, maximum = reservation(wire)
        total = ledger.totals(self.state)
        ledger.require(
            total["attempts"] < self.manifest["policy"]["maxCalls"], "CALL_LIMIT_REACHED"
        )
        committed = Decimal(total["observedCostEstimatedUsd"]) + Decimal(
            total["unsettledReservedUsd"]
        )
        ledger.require(
            committed + maximum <= Decimal(self.manifest["policy"]["maxCostEstimatedUsd"]),
            "COST_RESERVATION_LIMIT_REACHED",
        )
        record = {
            "attemptId": len(self.state["attempts"]) + 1,
            "caseId": self.result["caseId"],
            "variant": self.result["variant"],
            "stage": call_id.split("-")[0],
            "logicalCallId": call_id,
            "repairIndex": self.call_counts[call_id] - 1,
            "status": "in_flight",
            "requestSha256": ledger.digest(wire),
            "wireRequest": wire,
            "startedAt": ledger.stamp(),
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
            "providerHttpStatus": None,
            "providerErrorCode": None,
        }
        self.state["attempts"].append(record)
        self.result["attemptIds"].append(record["attemptId"])
        self.state["inFlight"] = record["attemptId"]
        ledger.save(self.output_dir, self.state)
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
            usage, cost = ledger.observed_usage(payload.get("usage"))
            record["usage"] = usage
            ledger.require(
                payload.get("model") in {ledger.MODEL, "gpt-4.1-nano-2025-04-14"},
                "UNEXPECTED_RESOLVED_MODEL",
            )
            if cost is None and usage:
                _partial_cost(record, usage, maximum)
            ledger.require(cost is not None, "PROVIDER_USAGE_UNKNOWN")
            record.update(costEstimatedUsd=str(cost), unsettledReservedUsd="0")
            ledger.require(
                usage["input_tokens"] <= upper and usage["output_tokens"] <= MAX_OUTPUT,
                "PROVIDER_USAGE_EXCEEDED_RESERVATION",
            )
            ledger.require(cost <= maximum, "PROVIDER_COST_EXCEEDED_RESERVATION")
            ledger.require(
                self.clock() - started < record["attemptTimeoutSeconds"],
                "ATTEMPT_DEADLINE_EXCEEDED",
            )
            ledger.require(self.clock() < self.deadline, "REPORT_DEADLINE_EXCEEDED")
            record["status"] = "success"
            return raw
        except Exception as error:
            record.update(_provider_failure_diagnostics(error))
            if record["providerRawResponse"] is None:
                supplied = getattr(error, "body", None)
                supplied = supplied.get("usage") if isinstance(supplied, dict) else None
                if isinstance(error, AgentError) and isinstance(error.details, dict):
                    supplied = error.details.get("usage", supplied)
                    if isinstance(supplied, dict):
                        supplied = {
                            "input_tokens": supplied.get("inputTokens"),
                            "output_tokens": supplied.get("outputTokens"),
                        }
                usage, cost = ledger.observed_usage(supplied)
                record.update(providerRawUsage=usage or None, usage=usage or None)
                if cost is not None:
                    record.update(costEstimatedUsd=str(cost), unsettledReservedUsd="0")
                elif usage:
                    _partial_cost(record, usage, maximum)
            code = (
                str(error)
                if isinstance(error, ledger.EvaluationStopped)
                else "PROVIDER_ATTEMPT_FAILED"
            )
            record.update(status="failed", errorCode=code)
            raise ledger.EvaluationStopped(code) from None
        finally:
            record["latencyMs"] = round((self.clock() - started) * 1000, 3)
            self.state["inFlight"] = None
            ledger.save(self.output_dir, self.state)


def _partial_cost(record: dict, usage: dict, maximum: Decimal) -> None:
    lower = (
        Decimal(usage.get("input_tokens", 0)) * ledger.PRICES[1]
        + Decimal(usage.get("output_tokens", 0)) * ledger.PRICES[2]
    ) / 1_000_000
    record.update(
        costEstimatedUsd=str(lower), unsettledReservedUsd=str(max(Decimal(0), maximum - lower))
    )


def _valid_diagnostic_retry_after(value) -> bool:
    # Oversized values stay unknown; clamping could suggest retrying too early.
    return type(value) in (int, float) and 0 <= value <= MAX_DIAGNOSTIC_RETRY_AFTER_SECONDS


def _provider_failure_diagnostics(error: Exception) -> dict:
    """Keep closed provider metadata, never raw error bodies or response headers."""
    if not isinstance(error, APIStatusError):
        return {"providerHttpStatus": None, "providerErrorCode": None}
    status = error.status_code
    if type(status) is not int or not 100 <= status <= 599:
        return {"providerHttpStatus": None, "providerErrorCode": None}
    details = _provider_error_details(error)
    diagnostics = {
        "providerHttpStatus": status,
        "providerErrorCode": details["providerStatus"],
    }
    retry_after = details.get("retryAfterSeconds")
    if _valid_diagnostic_retry_after(retry_after):
        diagnostics["providerRetryAfterSeconds"] = retry_after
    return diagnostics


def _settings(api_key: str) -> Settings:
    return ledger.settings(api_key).model_copy(
        update={
            "report_provider_timeout_seconds": 180,
            "report_max_output_tokens": MAX_OUTPUT,
            "max_output_tokens": MAX_OUTPUT,
        }
    )


def _verify_runtime(manifest: dict, variant: str) -> None:
    ledger.require(
        ledger.file_digest(DRIVER) == manifest["provenance"]["driverSourceSha256"], "DRIVER_CHANGED"
    )
    ledger.require(
        runtime_info() == manifest["provenance"]["runtimeVersions"][variant], "RUNTIME_CHANGED"
    )


def generate_job(
    output_dir: Path, job_index: int, api_key: str, *, sdk_factory=OpenAI, clock=time.monotonic
) -> dict:
    """Run one isolated-runtime job under the same exclusive admission lock."""
    with ledger.evaluation_lock(output_dir), ledger.silent_logging():
        return _generate_job(output_dir, job_index, api_key, sdk_factory=sdk_factory, clock=clock)


def _generate_job(
    output_dir: Path, job_index: int, api_key: str, *, sdk_factory=OpenAI, clock=time.monotonic
) -> dict:
    manifest, state = _read(output_dir / "manifest.json"), _read(output_dir / "result.json")
    verify(output_dir, manifest, state)
    ledger.require(
        type(job_index) is int and 0 <= job_index < len(state["results"]), "INVALID_JOB_INDEX"
    )
    ledger.require(
        all(item.get("safeToContinue") for item in state["errors"]),
        "RECORDED_FAILURE_REQUIRES_REVIEW",
    )
    ledger.require(
        not any(item["status"] == "running" for item in state["results"]),
        "INTERRUPTED_ATTEMPT_REQUIRES_REVIEW",
    )
    result = state["results"][job_index]
    _verify_runtime(manifest, result["variant"])
    ledger.require(
        result["status"] == "pending" and state["inFlight"] is None, "JOB_ALREADY_STARTED"
    )
    ledger.require(
        not any(Decimal(item["unsettledReservedUsd"]) > 0 for item in state["attempts"]),
        "UNOBSERVED_CHARGES_REQUIRE_REVIEW",
    )
    ledger.require(bool(api_key.strip()), "OPENAI_API_KEY_REQUIRED")
    result["status"], state["status"] = "running", "running"
    ledger.save(output_dir, state)
    started = clock()
    provider = VersionAttemptProvider(
        output_dir, manifest, state, result, api_key, sdk_factory=sdk_factory, clock=clock
    )
    try:
        request = ReportInsightRequest.model_validate(result["request"])
        response = ReportInsightService(_settings(api_key), provider).generate(request)
        ledger.require(
            response.meta.provider == "openai"
            and response.meta.model == ledger.MODEL
            and not response.meta.mock
            and response.meta.prompt_version == PROMPT_VERSION,
            "UNEXPECTED_OUTPUT_MODEL",
        )
        result.update(status="success", response=response.model_dump(mode="json", by_alias=True))
    except Exception as error:
        code = (
            str(error)
            if isinstance(error, ledger.EvaluationStopped)
            else (error.code if isinstance(error, AgentError) else "EVALUATION_JOB_FAILED")
        )
        records = [state["attempts"][identifier - 1] for identifier in result["attemptIds"]]
        safe = (
            isinstance(error, AgentError)
            and error.code == "SCHEMA_VIOLATION"
            and bool(records)
            and all(
                item["status"] == "success" and Decimal(item["unsettledReservedUsd"]) == 0
                for item in records
            )
        )
        result.update(status="failed", errorCode=code, terminalFailureSafe=safe)
        state["errors"].append(
            {
                "caseId": result["caseId"],
                "variant": result["variant"],
                "code": code,
                "safeToContinue": safe,
            }
        )
        state["status"] = "running" if safe else "stopped"
    finally:
        result["latencyMs"] = round((clock() - started) * 1000, 3)
        ledger.save(output_dir, state)
    verify(output_dir, manifest, state)
    return state


def _require_coordinator_lock(output_dir: Path) -> None:
    # Only a coordinator holding the output lock may launch the hidden worker.
    # Its child has a separate file description, so acquiring this lock would
    # prove that admission is unprotected. Never continue in that condition.
    with (output_dir / "run.lock").open("a", encoding="utf-8") as stream:
        try:
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return
        raise ledger.EvaluationStopped("COORDINATOR_LOCK_REQUIRED")


def run(output_dir: Path, *, api_key: str | None = None, max_jobs: int | None = None) -> dict:
    """Serial isolated jobs share one lock, ledger, call limit and cost ceiling."""
    with ledger.evaluation_lock(output_dir), ledger.silent_logging():
        manifest, state = _read(output_dir / "manifest.json"), _read(output_dir / "result.json")
        verify(output_dir, manifest, state)
        ledger.require(
            state["inFlight"] is None
            and not any(
                item["status"] in {"in_flight", "running"}
                for item in [*state["attempts"], *state["results"]]
            ),
            "INTERRUPTED_ATTEMPT_REQUIRES_REVIEW",
        )
        ledger.require(
            all(item.get("safeToContinue") for item in state["errors"]),
            "RECORDED_FAILURE_REQUIRES_REVIEW",
        )
        ledger.require(
            ledger.file_digest(DRIVER) == manifest["provenance"]["driverSourceSha256"],
            "DRIVER_CHANGED",
        )
        for variant in VERSIONS:
            info = manifest["provenance"]["runtimeVersions"][variant]
            ledger.require(_runtime_info(Path(info["runtimeRoot"])) == info, "RUNTIME_CHANGED")
        pending = [
            index for index, item in enumerate(state["results"]) if item["status"] == "pending"
        ]
        if max_jobs is not None:
            ledger.require(type(max_jobs) is int and max_jobs >= 1, "INVALID_JOB_LIMIT")
            pending = pending[:max_jobs]
        if not pending:
            return state
        credential = api_key if api_key is not None else Settings(_env_file=None).openai_api_key
        ledger.require(bool(credential.strip()), "OPENAI_API_KEY_REQUIRED")
        for index in pending:
            variant = state["results"][index]["variant"]
            root = Path(manifest["provenance"]["runtimeVersions"][variant]["runtimeRoot"])
            try:
                process = subprocess.run(
                    [
                        sys.executable,
                        "-B",
                        str(DRIVER),
                        "worker",
                        "--runtime-root",
                        str(root),
                        "--output-dir",
                        str(output_dir.resolve()),
                        "--job-index",
                        str(index),
                    ],
                    cwd=root,
                    env=_process_environment(credential),
                    capture_output=True,
                    text=True,
                    timeout=POLICY["reportDeadlineSeconds"] + 30,
                )
            except subprocess.TimeoutExpired:
                raise ledger.EvaluationStopped("WORKER_DEADLINE_EXCEEDED") from None
            state = _read(output_dir / "result.json")
            verify(output_dir, manifest, state)
            ledger.require(process.returncode == 0, "WORKER_FAILED_REQUIRES_REVIEW")
            if state["status"] == "stopped":
                raise ledger.EvaluationStopped(state["results"][index]["errorCode"])
        state["status"] = (
            "complete"
            if all(
                item["status"] == "success" or item.get("terminalFailureSafe")
                for item in state["results"]
            )
            else "partial"
        )
        ledger.save(output_dir, state)
        return state


def _adapter(state: dict) -> dict:
    result = deepcopy(state)
    result["schemaVersion"] = 1
    if _is_staged_comparison(state["policy"]):
        result["importanceRubricVersions"] = {
            ADAPTER_VARIANTS[name]: state["provenance"]["runtimeVersions"][name]["rubricVersion"]
            for name in VERSIONS
        }
    for item in [*result["results"], *result["attempts"]]:
        item["variant"] = ADAPTER_VARIANTS[item["variant"]]
    return result


def _blind_review(state: dict) -> tuple[str, dict]:
    page, key = render_comparison(_adapter(state))
    page = page.replace(
        '<details class="detailed-ratings"><summary>세부 점수 입력 (선택)</summary>',
        '<details class="detailed-ratings" open><summary>세부 점수 입력 (필수)</summary>',
    )
    page = page.replace(
        "세부 점수와 메모는 선택 사항입니다.",
        "두 결과의 여섯 기준 점수와 최종 선호를 선택해 주세요. 메모는 선택 사항입니다.",
    )
    page = page.replace(
        "function complete(rating){return winners.has(rating.winner);}",
        'function complete(rating){return winners.has(rating.winner)&&["A","B"].every(side=>'
        "manifest.criteria.every(criterion=>Number.isInteger(rating.sides[side].scores[criterion])));}",
    )
    inverse = {value: name for name, value in ADAPTER_VARIANTS.items()}
    key["versionMapping"] = {
        side["variant"]: inverse[side["variant"]]
        for case in key["cases"]
        for side in case["sides"].values()
    }
    key["comparisonCheckpointSha256"] = state["checkpointSha256"]
    key["privateIdentitySha256"] = ledger.digest(key)
    return page, key


def summary(output_dir: Path) -> dict:
    manifest, state = _read(output_dir / "manifest.json"), _read(output_dir / "result.json")
    verify(output_dir, manifest, state)
    metrics = generation_metrics(_adapter(state))
    _, key = _blind_review(state)
    eligible = sum(
        all(side["status"] == "success" for side in case["sides"].values()) for case in key["cases"]
    )
    return {
        "schemaVersion": 2,
        "comparisonScope": _comparison_scope(state["policy"]),
        "provenance": state["provenance"],
        "status": state["status"],
        "model": ledger.MODEL,
        "plannedCases": manifest["caseCount"],
        "eligiblePairs": eligible,
        "excludedPairs": manifest["caseCount"] - eligible,
        "qualityMeasured": False,
        "qualityImprovementClaimed": False,
        "requiresHumanReview": True,
        "baseCallUpperBound": manifest["baseCallUpperBound"],
        "repairCallUpperBound": manifest["repairCallUpperBound"],
        "variants": {name: metrics[ADAPTER_VARIANTS[name]] for name in VERSIONS},
        "totals": state["totals"],
        "errors": state["errors"],
        "resultPath": str((output_dir / "result.json").resolve()),
    }


def export_review(output_dir: Path) -> Path:
    with ledger.evaluation_lock(output_dir):
        manifest, state = _read(output_dir / "manifest.json"), _read(output_dir / "result.json")
        verify(output_dir, manifest, state)
        page, key = _blind_review(state)
        path = output_dir / "blind-review.html"
        path.write_text(page, encoding="utf-8")
        path.chmod(0o600)
        ledger.atomic_save(output_dir / "blind-key.private.json", key)
        ledger.atomic_save(output_dir / "generation-summary.json", summary(output_dir))
        return path


def score(output_dir: Path, judgments: dict) -> dict:
    with ledger.evaluation_lock(output_dir):
        manifest, state = _read(output_dir / "manifest.json"), _read(output_dir / "result.json")
        verify(output_dir, manifest, state)
        reviewer_kind = judgments.get("reviewerKind", "HUMAN")
        ledger.require(reviewer_kind in {"HUMAN", "AI"}, "INVALID_REVIEWER_KIND")
        decoded = aggregate_judgments(_adapter(state), judgments)
        reviewers = []
        for rating in judgments["ratings"]:
            kind = rating.get("reviewerKind", reviewer_kind)
            ledger.require(kind in {"HUMAN", "AI"}, "INVALID_REVIEWER_KIND")
            if rating.get("winner"):
                reviewers.append({"reviewId": rating["reviewId"], "reviewerKind": kind})
        kinds = {item["reviewerKind"] for item in reviewers}
        reviewer_kind = (
            next(iter(kinds)) if len(kinds) == 1 else "MIXED" if kinds else reviewer_kind
        )
        inverse = {value: name for name, value in ADAPTER_VARIANTS.items()}
        for mapping in (
            decoded["wins"],
            decoded["factualIntegrityZeroCounts"],
            decoded["generation"],
        ):
            for old, name in inverse.items():
                mapping[name] = mapping.pop(old)
        for case in decoded["cases"]:
            case["preference"] = inverse.get(case["preference"], case["preference"])
        for counts in decoded["audiencePreferences"].values():
            for old, name in inverse.items():
                counts[name] = counts.pop(old)
        for values in decoded["optionalPairedCriteria"].values():
            values["baselineMean"] = values.pop("singleCallMean")
            values["candidateMean"] = values.pop("stagedMean")
            values["candidateMinusBaselineMean"] = values.pop("stagedMinusSingleMean")
        decoded.update(
            schemaVersion=2,
            reviewerKind=reviewer_kind,
            reviewers=reviewers,
            reviewerKindCounts={
                kind: sum(item["reviewerKind"] == kind for item in reviewers)
                for kind in ("HUMAN", "AI")
            },
            qualityMeasured=bool(decoded["judgedCount"]),
            humanQualityMeasured=reviewer_kind == "HUMAN" and bool(decoded["judgedCount"]),
            comparisonScope=_comparison_scope(state["policy"], snapshots=True),
            candidateWinShareOfJudged=decoded.pop("stagedWinShareOfJudged"),
            limitations=[
                "Selected actual report snapshots; production-wide quality is unmeasured.",
                "Audience cases share reports; no independent-case confidence interval is claimed.",
                "Missing ratings and generation failures are excluded; coverage shows them.",
            ],
        )
        criteria = set(decoded["optionalPairedCriteria"])
        complete = sum(
            bool(item.get("winner"))
            and all(
                set(item.get("sides", {}).get(side, {}).get("scores", {})) == criteria
                for side in ("A", "B")
            )
            for item in judgments["ratings"]
        )
        decoded["completeRubricPairs"] = complete
        ledger.atomic_save(output_dir / "quality-summary.json", decoded)
        return decoded


def _credential() -> str:
    configured = Settings(_env_file=None).openai_api_key
    if configured.strip():
        return configured
    ledger.require(sys.stdin.isatty(), "KEY_INPUT_REQUIRES_TERMINAL")
    with warnings.catch_warnings():
        warnings.simplefilter("error", getpass.GetPassWarning)
        try:
            credential = getpass.getpass("OpenAI API 키 입력 (숨김): ").strip()
        except getpass.GetPassWarning:
            raise ledger.EvaluationStopped("HIDDEN_KEY_INPUT_UNAVAILABLE") from None
    ledger.require(bool(credential), "OPENAI_API_KEY_REQUIRED")
    return credential


def main() -> None:
    logging.disable(logging.CRITICAL)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "command", choices=("prepare", "run", "status", "review", "score", "worker", "runtime")
    )
    parser.add_argument("--dataset", type=Path)
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--baseline-root", type=Path)
    parser.add_argument("--candidate-root", type=Path)
    parser.add_argument("--comparison-profile", choices=tuple(COMPARISON_PROFILES), default="v3-v4")
    parser.add_argument("--max-cost-usd", type=Decimal, default=Decimal("1.00"))
    parser.add_argument("--max-calls", type=int, default=144)
    parser.add_argument("--runtime-root", type=Path)
    parser.add_argument("--job-index", type=int)
    parser.add_argument("--max-jobs", type=int)
    parser.add_argument("--judgments", type=Path)
    args = parser.parse_args()
    try:
        if args.command == "runtime":
            value = runtime_info()
        else:
            ledger.require(args.output_dir is not None, "OUTPUT_DIRECTORY_REQUIRED")
            if args.command == "prepare":
                ledger.require(
                    args.dataset is not None and args.baseline_root is not None,
                    "DATASET_AND_BASELINE_REQUIRED",
                )
                value = prepare(
                    args.dataset,
                    args.output_dir,
                    args.baseline_root,
                    candidate_root=args.candidate_root,
                    comparison_profile=args.comparison_profile,
                    max_cost_usd=args.max_cost_usd,
                    max_calls=args.max_calls,
                )
                value = {
                    key: value[key]
                    for key in (
                        "caseCount",
                        "baseCallUpperBound",
                        "repairCallUpperBound",
                        "manifestSha256",
                    )
                }
            elif args.command == "worker":
                ledger.require(args.job_index is not None, "JOB_INDEX_REQUIRED")
                _require_coordinator_lock(args.output_dir)
                _generate_job(args.output_dir, args.job_index, os.environ.get("OPENAI_API_KEY", ""))
                value = {"workerComplete": True}
            elif args.command == "run":
                run(args.output_dir, api_key=_credential(), max_jobs=args.max_jobs)
                value = summary(args.output_dir)
            elif args.command == "review":
                value = {"reviewHtml": str(export_review(args.output_dir))}
            elif args.command == "score":
                ledger.require(args.judgments is not None, "JUDGMENTS_REQUIRED")
                value = score(args.output_dir, _read(args.judgments))
            else:
                value = summary(args.output_dir)
        print(json.dumps(value, ensure_ascii=False, indent=2))
    except ledger.EvaluationStopped as error:
        print(json.dumps({"status": "stopped", "code": str(error)}))
        raise SystemExit(2) from None
    except Exception:
        print(json.dumps({"status": "stopped", "code": "VERSION_COMPARISON_FAILED"}))
        raise SystemExit(2) from None


if __name__ == "__main__":
    main()
