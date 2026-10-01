"""Offline frozen-version admission, accounting, isolation and blind-review checks."""

import json
import subprocess
from copy import deepcopy
from dataclasses import replace
from decimal import Decimal
from pathlib import Path
from types import SimpleNamespace

import httpx2
import pytest
from jsonschema import Draft202012Validator
from jsonschema import ValidationError as JsonSchemaValidationError
from openai import APIStatusError
from test_report_insight import output, request_body
from test_report_insight_run import FakeSDK, RawResponse

from app.eval import report_insight_version_compare as runner
from app.eval.report_insight_review import QUALITY_RUBRIC
from app.llm import report_insight_assessment as assessment
from app.llm.report_insight_service import _validated_map_output
from app.llm.structured_call import structured_call
from app.schemas.report_insight import ReportInsightRequest, ReportInsightResponse


@pytest.fixture
def prepared(tmp_path, monkeypatch):
    corpus = json.loads(runner.ledger.DEFAULT_DATASET.read_text())
    case = deepcopy(corpus["cases"][0])
    case.update(caseId="actual-report-chip", scenario="actual saved report")
    body = request_body()
    for index in range(1, 9):
        finding = deepcopy(body["findings"][0])
        finding.update(id=501 + index, articleId=10 + index)
        finding["claims"][0]["id"] = f"{501 + index}:0"
        finding["claims"][0]["text"] = f"삼성전자는 {2027 + index}년 CPO 양산을 계획했다."
        finding["sentences"][0]["text"] = finding["claims"][0]["text"]
        body["findings"].append(finding)
    case["request"] = body
    case["annotations"] = {
        "requiredSynthesisClaimIds": [],
        "forbiddenProse": ["hidden-label"],
        "notUrgentFindingIds": [],
        "expectedImportanceByFinding": {},
    }
    corpus.update(synthetic=False, qualityMeasured=False, cases=[case])
    dataset = tmp_path / "reports.json"
    dataset.write_text(json.dumps(corpus, ensure_ascii=False))
    baseline = tmp_path / "baseline-agent"
    candidate = runner.ledger.AGENT_ROOT.resolve()

    def info(root):
        version = "baseline" if Path(root).resolve() == baseline.resolve() else "candidate"
        return {
            "runtimeRoot": str(Path(root).resolve()),
            "runtimeSourceSha256s": {"service.py": version},
            "dependencyVersions": {"openai": "3.7.0"},
            "pythonVersion": "3.13.5",
            "promptVersion": runner.POLICY[f"{version}PromptVersion"],
            "rubricVersion": "report-importance.v2"
            if version == "baseline"
            else "report-importance.v3",
        }

    monkeypatch.setattr(runner, "_runtime_info", info)
    monkeypatch.setattr(runner, "runtime_info", lambda: info(candidate))
    directory = tmp_path / "evaluation"
    runner.prepare(dataset, directory, baseline)
    return dataset, directory, baseline


def read_state(directory):
    return runner._read(directory / "result.json")


def call_schema(identifier, *, baseline=False):
    return {
        "title": "ReportInsightMapOutput" if baseline else "EvidenceMapOutput",
        **({} if baseline else {"description": f"reportInsightCall:{identifier}"}),
        "type": "object",
        "properties": {"ok": {"type": "boolean"}},
        "required": ["ok"],
        "additionalProperties": False,
    }


def start_provider(directory, variant="candidate", *, sdk=None, clock=None):
    manifest = runner._read(directory / "manifest.json")
    state = read_state(directory)
    result = next(item for item in state["results"] if item["variant"] == variant)
    result["status"] = "running"
    runner.ledger.save(directory, state)
    sdk = sdk or FakeSDK(lambda wire, count: RawResponse({"ok": True}))
    keywords = {"clock": clock} if clock else {}
    return runner.VersionAttemptProvider(
        directory, manifest, state, result, "offline-key", sdk_factory=sdk, **keywords
    ), sdk


def invoke(provider, identifier, *, baseline=False):
    return provider.generate(
        system_instruction="instruction",
        prompt="same public evidence",
        response_schema=call_schema(identifier, baseline=baseline),
    )


def public_response(result, records):
    payload = output()
    original = payload["insights"][0]["assessments"][0]
    payload["insights"][0]["assessments"] = [
        {
            **deepcopy(original),
            "findingId": finding["id"],
            "basisClaimIds": [finding["claims"][0]["id"]],
        }
        for finding in result["request"]["findings"]
    ]
    payload["meta"] = {
        "provider": "openai",
        "model": runner.ledger.MODEL,
        "promptVersion": runner.POLICY[f"{result['variant']}PromptVersion"],
        "inputTokens": sum(item["usage"]["input_tokens"] for item in records),
        "outputTokens": sum(item["usage"]["output_tokens"] for item in records),
        "costUsd": float(sum((Decimal(item["costEstimatedUsd"]) for item in records), Decimal(0))),
        "credits": 0,
        "mock": False,
        "truncated": False,
    }
    return ReportInsightResponse.model_validate(payload)


def finish(provider):
    records = [
        provider.state["attempts"][identifier - 1] for identifier in provider.result["attemptIds"]
    ]
    response = public_response(provider.result, records)
    provider.result.update(
        status="success", latencyMs=1, response=response.model_dump(mode="json", by_alias=True)
    )
    runner.ledger.save(provider.output_dir, provider.state)
    runner.verify(provider.output_dir, provider.manifest, provider.state)


def complete_pair(directory):
    baseline, _ = start_provider(directory, "baseline")
    invoke(baseline, "MAP-001", baseline=True)
    finish(baseline)
    candidate, _ = start_provider(directory)
    invoke(candidate, "MAP-001")
    finish(candidate)
    candidate.state["status"] = "complete"
    runner.ledger.save(directory, candidate.state)
    return candidate.state


def test_prepare_same_report_sources_frozen_separately_without_credentials(prepared, monkeypatch):
    dataset, directory, baseline = prepared
    monkeypatch.setenv("OPENAI_API_KEY", "never-serialize-this-key")
    manifest = runner.prepare(dataset, directory, baseline)
    assert manifest["baseCallUpperBound"] == 6
    assert manifest["repairCallUpperBound"] == 12
    assert manifest["jobs"][0]["request"] == manifest["jobs"][1]["request"]
    assert manifest["jobs"][0]["inputSha256"] == manifest["jobs"][1]["inputSha256"]
    saved = json.dumps(manifest) + json.dumps(read_state(directory))
    assert "never-serialize-this-key" not in saved and "hidden-label" not in saved
    assert manifest["policy"]["maxCalls"] == 144
    assert manifest["provenance"]["runtimeVersions"]["baseline"]["promptVersion"].endswith("v3")
    assert runner.summary(directory)["excludedPairs"] == 1


@pytest.mark.parametrize(
    "kwargs,code",
    [
        ({"max_calls": 145}, "INVALID_CALL_LIMIT"),
        ({"max_cost_usd": Decimal("1.01")}, "INVALID_COST_LIMIT"),
        ({"baseline_commit": "other"}, "BASELINE_COMMIT_CHANGED"),
    ],
)
def test_prepare_hard_limits(prepared, kwargs, code):
    dataset, directory, baseline = prepared
    with pytest.raises(runner.ledger.EvaluationStopped, match=code):
        runner.prepare(dataset, directory, baseline, **kwargs)


def test_prepare_rejects_synthetic_and_shared_runtime(prepared):
    dataset, directory, baseline = prepared
    corpus = runner._read(dataset)
    corpus["synthetic"] = True
    dataset.write_text(json.dumps(corpus))
    with pytest.raises(runner.ledger.EvaluationStopped, match="REAL_REPORT_CORPUS_REQUIRED"):
        runner.prepare(dataset, directory, baseline)
    corpus["synthetic"] = False
    dataset.write_text(json.dumps(corpus))
    with pytest.raises(runner.ledger.EvaluationStopped, match="BASELINE_MUST_BE_ISOLATED"):
        runner.prepare(dataset, directory, runner.ledger.AGENT_ROOT)


def test_each_map_chunk_review_reduce_has_independent_repair_budget_and_exact_wire(prepared):
    _, directory, _ = prepared

    def observed(wire, count):
        persisted = read_state(directory)
        record = persisted["attempts"][-1]
        assert persisted["inFlight"] == count and record["status"] == "in_flight"
        assert record["wireRequest"] == wire
        assert record["requestSha256"] == runner.ledger.digest(wire)
        assert Decimal(record["unsettledReservedUsd"]) > 0
        return RawResponse({"ok": True})

    provider, sdk = start_provider(directory, sdk=FakeSDK(observed))
    for identifier in ("MAP-001", "MAP-002", "REVIEW-001", "REDUCE-001"):
        for _ in range(2):
            invoke(provider, identifier)
    assert len(sdk.calls) == 8 and sdk.closed == 8
    assert all(item["max_retries"] == 0 for item in sdk.configs)
    assert [item["repairIndex"] for item in provider.state["attempts"]] == [0, 1] * 4
    assert {item["stage"] for item in provider.state["attempts"]} == {"MAP", "REVIEW", "REDUCE"}
    assert all(
        wire["model"] == runner.ledger.MODEL
        and wire["store"] is False
        and wire["max_output_tokens"] == 8192
        for wire in sdk.calls
    )
    runner.verify(directory, provider.manifest, provider.state)
    with pytest.raises(runner.ledger.EvaluationStopped, match="EXCESS_SCHEMA_REPAIR"):
        invoke(provider, "MAP-002")
    assert len(sdk.calls) == 8


def test_candidate_requires_explicit_call_id_baseline_infers_legacy_title(prepared):
    _, directory, _ = prepared
    baseline, sdk = start_provider(directory, "baseline")
    invoke(baseline, "MAP-001", baseline=True)
    assert baseline.state["attempts"][0]["logicalCallId"] == "MAP-001"
    wire_format = baseline.state["attempts"][0]["wireRequest"]["text"]["format"]
    assert "title" not in wire_format["schema"]
    assert wire_format["name"] == "ReportInsightMapOutput"
    runner.verify(directory, baseline.manifest, baseline.state)
    candidate, sdk = start_provider(directory)
    with pytest.raises(runner.ledger.EvaluationStopped, match="CALL_ID_REQUIRED"):
        invoke(candidate, "MAP-001", baseline=True)
    assert not sdk.calls


def test_reservation_cost_ceiling_and_global_call_cap_stop_before_sdk(prepared):
    _, directory, _ = prepared
    provider, sdk = start_provider(directory)
    provider.manifest["policy"]["maxCalls"] = 1
    rebind(directory, provider.manifest, provider.state)
    invoke(provider, "MAP-001")
    with pytest.raises(runner.ledger.EvaluationStopped, match="CALL_LIMIT_REACHED"):
        invoke(provider, "REVIEW-001")
    assert len(sdk.calls) == 1


def rebind(directory, manifest, state):
    manifest["provenance"]["policySha256"] = runner.ledger.digest(manifest["policy"])
    manifest.pop("manifestSha256", None)
    manifest["manifestSha256"] = runner.ledger.digest(manifest)
    state["manifestSha256"] = manifest["manifestSha256"]
    state["provenance"] = manifest["provenance"]
    state["policy"] = manifest["policy"]
    runner.ledger.atomic_save(directory / "manifest.json", manifest)
    runner.ledger.save(directory, state)


def test_cost_limit_does_not_invoke_or_consume_attempt(prepared):
    _, directory, _ = prepared
    provider, sdk = start_provider(directory)
    provider.manifest["policy"]["maxCostEstimatedUsd"] = "0.000001"
    rebind(directory, provider.manifest, provider.state)
    with pytest.raises(runner.ledger.EvaluationStopped, match="COST_RESERVATION_LIMIT_REACHED"):
        invoke(provider, "MAP-001")
    assert not sdk.calls and not provider.state["attempts"]


@pytest.mark.parametrize("usage", [{}, {"input_tokens": 100}])
def test_unknown_usage_preserves_reservation_and_blocks_resume(prepared, usage):
    _, directory, _ = prepared
    provider, sdk = start_provider(
        directory, sdk=FakeSDK(lambda wire, count: RawResponse({"ok": True}, usage=usage))
    )
    with pytest.raises(runner.ledger.EvaluationStopped, match="PROVIDER_USAGE_UNKNOWN"):
        invoke(provider, "MAP-001")
    record = read_state(directory)["attempts"][0]
    assert Decimal(record["unsettledReservedUsd"]) > 0
    assert len(sdk.calls) == 1
    runner.verify(directory, provider.manifest, read_state(directory))
    with pytest.raises(
        runner.ledger.EvaluationStopped, match="INTERRUPTED_ATTEMPT_REQUIRES_REVIEW"
    ):
        runner.run(directory, api_key="offline-key")


def test_error_body_keeps_only_numeric_usage_no_key_or_message(prepared):
    _, directory, _ = prepared

    def fail(wire, count):
        error = RuntimeError("secret-key-must-not-appear")
        error.body = {
            "message": "private-header",
            "usage": {"input_tokens": 100, "output_tokens": 5},
        }
        raise error

    provider, _ = start_provider(directory, sdk=FakeSDK(fail))
    with pytest.raises(runner.ledger.EvaluationStopped, match="PROVIDER_ATTEMPT_FAILED"):
        invoke(provider, "MAP-001")
    text = (directory / "result.json").read_text()
    assert "secret-key-must-not-appear" not in text and "private-header" not in text
    assert read_state(directory)["attempts"][0]["unsettledReservedUsd"] == "0"
    runner.verify(directory, provider.manifest, read_state(directory))


@pytest.mark.parametrize(
    "status,code,expected",
    [
        (400, "invalid_json_schema", "invalid_json_schema"),
        (429, "rate_limit_exceeded", "rate_limit_exceeded"),
        (503, "private-provider-code", "UNKNOWN"),
        (500, None, "UNKNOWN"),
    ],
)
def test_http_diagnostics_keep_only_status_allowlisted_code_and_unknown_reservation(
    prepared, status, code, expected
):
    _, directory, _ = prepared

    def fail(wire, count):
        response = httpx2.Response(
            status,
            headers={"authorization": "private-response-header"},
            request=httpx2.Request(
                "POST",
                "https://api.openai.com/v1/responses",
                headers={"authorization": "Bearer private-request-key"},
            ),
        )
        raise APIStatusError(
            "private-exception-message",
            response=response,
            body={"code": code, "message": "private-body-message"},
        )

    provider, sdk = start_provider(directory, sdk=FakeSDK(fail))
    with pytest.raises(runner.ledger.EvaluationStopped, match="PROVIDER_ATTEMPT_FAILED"):
        invoke(provider, "MAP-001")
    state = read_state(directory)
    record = state["attempts"][0]
    assert record["providerHttpStatus"] == status and record["providerErrorCode"] == expected
    assert record["unsettledReservedUsd"] == record["reservedCostUsd"]
    assert Decimal(record["costEstimatedUsd"]) == 0 and record["providerRawResponse"] is None
    assert len(sdk.calls) == 1
    serialized = (directory / "result.json").read_text()
    assert all(
        value not in serialized
        for value in (
            "private-response-header",
            "private-request-key",
            "private-exception-message",
            "private-body-message",
            "private-provider-code",
        )
    )
    runner.verify(directory, provider.manifest, state)
    with pytest.raises(
        runner.ledger.EvaluationStopped, match="INTERRUPTED_ATTEMPT_REQUIRES_REVIEW"
    ):
        runner.run(directory, api_key="offline-key")


def test_http_diagnostic_fields_do_not_make_unknown_usage_job_safe_to_continue(prepared):
    _, directory, _ = prepared

    def fail(wire, count):
        response = httpx2.Response(
            400, request=httpx2.Request("POST", "https://api.openai.com/v1/responses")
        )
        raise APIStatusError(
            "private-message", response=response, body={"code": "invalid_json_schema"}
        )

    index = next(
        index
        for index, result in enumerate(read_state(directory)["results"])
        if result["variant"] == "candidate"
    )
    sdk = FakeSDK(fail)
    state = runner.generate_job(directory, index, "offline-key", sdk_factory=sdk)
    assert state["status"] == "stopped"
    assert state["results"][index]["terminalFailureSafe"] is False
    assert state["errors"][0]["safeToContinue"] is False
    assert state["attempts"][0]["providerHttpStatus"] == 400
    assert Decimal(state["totals"]["unsettledReservedUsd"]) > 0
    with pytest.raises(runner.ledger.EvaluationStopped, match="RECORDED_FAILURE_REQUIRES_REVIEW"):
        runner.run(directory, api_key="offline-key")
    assert len(sdk.calls) == 1


def test_diagnostic_checkpoint_tampering_is_rejected_and_non_http_errors_stay_null(prepared):
    _, directory, _ = prepared
    provider, _ = start_provider(directory)
    invoke(provider, "MAP-001")
    assert provider.state["attempts"][0]["providerHttpStatus"] is None
    provider.state["attempts"][0].update(
        providerHttpStatus=429, providerErrorCode="rate_limit_exceeded"
    )
    runner.ledger.save(directory, provider.state)
    with pytest.raises(
        runner.ledger.EvaluationStopped, match="CHECKPOINT_PROVIDER_DIAGNOSTICS_CHANGED"
    ):
        runner.verify(directory, provider.manifest, provider.state)
    assert runner._provider_failure_diagnostics(RuntimeError("private-exception-message")) == {
        "providerHttpStatus": None,
        "providerErrorCode": None,
    }


@pytest.mark.parametrize("model", ["gpt-4.1-mini", "unknown-model"])
def test_unexpected_actual_model_keeps_full_reservation(prepared, model):
    _, directory, _ = prepared
    provider, _ = start_provider(
        directory, sdk=FakeSDK(lambda wire, count: RawResponse({"ok": True}, model=model))
    )
    with pytest.raises(runner.ledger.EvaluationStopped, match="UNEXPECTED_RESOLVED_MODEL"):
        invoke(provider, "MAP-001")
    record = read_state(directory)["attempts"][0]
    assert record["unsettledReservedUsd"] == record["reservedCostUsd"]
    assert record["costEstimatedUsd"] == "0"
    runner.verify(directory, provider.manifest, read_state(directory))


@pytest.mark.parametrize(
    "change,code",
    [
        (lambda state: state.update(inFlight=42), "CHECKPOINT_IN_FLIGHT_CHANGED"),
        (
            lambda state: state["attempts"][0].update(logicalCallId="MAP-002"),
            "CHECKPOINT_CALL_ID_CHANGED",
        ),
        (
            lambda state: state["attempts"][0].update(providerText="altered"),
            "CHECKPOINT_PROVIDER_TEXT_CHANGED",
        ),
        (
            lambda state: state["attempts"][0].update(costEstimatedUsd="0"),
            "CHECKPOINT_COST_CHANGED",
        ),
    ],
)
def test_rehashed_tampering_still_rejected(prepared, change, code):
    _, directory, _ = prepared
    provider, _ = start_provider(directory)
    invoke(provider, "MAP-001")
    change(provider.state)
    runner.ledger.save(directory, provider.state)
    with pytest.raises(runner.ledger.EvaluationStopped, match=code):
        runner.verify(directory, provider.manifest, provider.state)


def test_deadline_covers_all_stages_and_tardy_sdk_usage_stays_recorded(prepared):
    _, directory, _ = prepared
    clock = [100.0]

    def slow(wire, count):
        clock[0] += 61
        return RawResponse({"ok": True})

    provider, sdk = start_provider(directory, sdk=FakeSDK(slow), clock=lambda: clock[0])
    with pytest.raises(runner.ledger.EvaluationStopped, match="ATTEMPT_DEADLINE_EXCEEDED"):
        invoke(provider, "MAP-001")
    record = read_state(directory)["attempts"][0]
    assert record["usage"]["input_tokens"] == 100 and record["unsettledReservedUsd"] == "0"
    assert len(sdk.calls) == 1
    clock[0] = 281
    with pytest.raises(runner.ledger.EvaluationStopped, match="REPORT_DEADLINE_EXCEEDED"):
        invoke(provider, "MAP-002")


def test_blind_export_hides_versions_identity_and_requires_six_criteria(prepared):
    _, directory, _ = prepared
    state = complete_pair(directory)
    path = runner.export_review(directory)
    page = path.read_text()
    for value in (
        "baseline",
        "candidate",
        "single_call",
        "staged",
        "report-insight.ko.v3",
        "report-insight.ko.v4",
    ):
        assert value not in page
    assert "manifest.criteria.every" in page
    assert '<details class="detailed-ratings" open>' in page
    assert "세부 점수 입력 (필수)" in page and "세부 점수 입력 (선택)" not in page
    key = runner._read(directory / "blind-key.private.json")
    assert key["versionMapping"] == {"single_call": "baseline", "staged": "candidate"}
    assert key["comparisonCheckpointSha256"] == state["checkpointSha256"]
    assert key["privateIdentitySha256"] == runner.ledger.digest(
        {name: value for name, value in key.items() if name != "privateIdentitySha256"}
    )
    assert runner.summary(directory)["eligiblePairs"] == 1


def test_score_complete_and_missing_rubric_counts_do_not_invent_quality(prepared):
    _, directory, _ = prepared
    complete_pair(directory)
    _, key = runner._blind_review(read_state(directory))
    case = key["cases"][0]
    side = next(name for name, identity in case["sides"].items() if identity["variant"] == "staged")
    judgments = {
        "schemaVersion": 1,
        "manifestHash": key["manifestHash"],
        "ratings": [
            {
                "reviewId": case["reviewId"],
                "winner": side,
                "sides": {
                    name: {"scores": dict.fromkeys(QUALITY_RUBRIC, 2)} for name in ("A", "B")
                },
            }
        ],
    }
    measured = runner.score(directory, judgments)
    assert measured["wins"]["candidate"] == 1 and measured["completeRubricPairs"] == 1
    assert measured["candidateWinShareOfJudged"] == 1
    assert measured["synthetic"] is False and measured["qualityImprovementClaimed"] is False
    assert measured["reviewerKind"] == "HUMAN" and measured["humanQualityMeasured"] is True
    judgments["reviewerKind"] = "AI"
    ai = runner.score(directory, judgments)
    assert ai["reviewerKind"] == "AI" and ai["humanQualityMeasured"] is False
    del judgments["reviewerKind"]
    judgments["ratings"][0]["reviewerKind"] = "AI"
    individual = runner.score(directory, judgments)
    assert individual["reviewerKind"] == "AI"
    assert individual["reviewerKindCounts"] == {"HUMAN": 0, "AI": 1}
    judgments["ratings"][0]["sides"] = {}
    missing = runner.score(directory, judgments)
    assert missing["completeRubricPairs"] == 0
    assert all(
        values["candidateMean"] is None for values in missing["optionalPairedCriteria"].values()
    )


def test_only_complete_pairs_eligible_and_judgments_bound_to_exact_experiment(prepared):
    _, directory, _ = prepared
    assert runner.summary(directory)["eligiblePairs"] == 0
    complete_pair(directory)
    with pytest.raises(runner.ledger.EvaluationStopped, match="JUDGMENTS_EXPERIMENT_MISMATCH"):
        runner.score(directory, {"schemaVersion": 1, "manifestHash": "other", "ratings": []})


def test_worker_requires_exclusive_parent_lock_and_direct_jobs_take_same_lock(prepared):
    _, directory, _ = prepared
    with pytest.raises(runner.ledger.EvaluationStopped, match="COORDINATOR_LOCK_REQUIRED"):
        runner._require_coordinator_lock(directory)
    with runner.ledger.evaluation_lock(directory):
        runner._require_coordinator_lock(directory)
        with pytest.raises(runner.ledger.EvaluationStopped, match="ANOTHER_RUN_IS_ACTIVE"):
            runner.generate_job(directory, 0, "offline-key")


def test_runtime_and_driver_changes_block_before_any_worker(prepared, monkeypatch):
    _, directory, _ = prepared
    monkeypatch.setattr(runner, "_runtime_info", lambda root: {"changed": True})
    with pytest.raises(runner.ledger.EvaluationStopped, match="RUNTIME_CHANGED"):
        runner.run(directory, api_key="offline-key")


def test_process_environment_is_allowlisted_key_memory_only(monkeypatch):
    monkeypatch.setenv("OPENAI_API_KEY", "ambient-ignored")
    monkeypatch.setenv("OPENAI_BASE_URL", "https://wrong-provider.example")
    monkeypatch.setenv("PYTHONPATH", "unsafe-app-package")
    monkeypatch.setenv("HTTPS_PROXY", "http://proxy.example")
    plain = runner._process_environment()
    assert not {"OPENAI_API_KEY", "OPENAI_BASE_URL", "PYTHONPATH"} & plain.keys()
    assert plain["HTTPS_PROXY"] == "http://proxy.example"
    assert (
        runner._process_environment("explicit-in-memory")["OPENAI_API_KEY"] == "explicit-in-memory"
    )


def test_probe_selects_runtime_before_import_and_discards_stderr(tmp_path, monkeypatch):
    runtime = tmp_path / "archive"
    (runtime / "app/llm").mkdir(parents=True)
    (runtime / "app/llm/report_insight_service.py").write_text("# frozen public source")
    calls = []

    def fake_run(command, **options):
        calls.append((command, options))
        return SimpleNamespace(
            returncode=0,
            stdout='{"promptVersion":"report-insight.ko.v3"}',
            stderr="secret-error-never-returned",
        )

    monkeypatch.setattr(runner.subprocess, "run", fake_run)
    assert runner._runtime_info(runtime)["promptVersion"].endswith("v3")
    command, options = calls[0]
    assert command[-2:] == ["--runtime-root", str(runtime.resolve())]
    assert options["cwd"] == runtime and options["capture_output"] is True
    assert "OPENAI_API_KEY" not in options["env"]


def test_worker_process_timeout_preserves_checkpoint_no_automatic_retry(prepared, monkeypatch):
    _, directory, _ = prepared
    calls = []

    def timeout(command, **options):
        calls.append(command)
        raise subprocess.TimeoutExpired(command, options["timeout"])

    monkeypatch.setattr(runner.subprocess, "run", timeout)
    with pytest.raises(runner.ledger.EvaluationStopped, match="WORKER_DEADLINE_EXCEEDED"):
        runner.run(directory, api_key="offline-key")
    assert len(calls) == 1
    assert "offline-key" not in " ".join(calls[0])


def test_production_output_cap_reservation_settings_and_legacy_unchanged(prepared):
    _, directory, _ = prepared
    provider, _ = start_provider(directory)
    invoke(provider, "MAP-001")
    wire = provider.state["attempts"][0]["wireRequest"]
    upper, original = runner.ledger.reservation(wire)
    expanded_upper, expanded = runner.reservation(wire)
    assert expanded_upper == upper
    assert expanded - original == Decimal(4096) * runner.ledger.PRICES[2] / 1_000_000
    assert runner.ledger.MAX_OUTPUT == 4096
    assert runner._settings("offline").max_output_tokens == 8192
    assert runner._settings("offline").report_max_output_tokens == 8192
    assert runner._settings("offline").report_provider_timeout_seconds == 180


def test_credential_settings_explicitly_disable_dotenv(monkeypatch):
    observed = []

    def fake_settings(**kwargs):
        observed.append(kwargs)
        return SimpleNamespace(openai_api_key="environment-memory-key")

    monkeypatch.setattr(runner, "Settings", fake_settings)
    assert runner._credential() == "environment-memory-key"
    assert observed == [{"_env_file": None}]


def test_invalid_stage_rejected_before_native_invocation(prepared):
    _, directory, _ = prepared
    provider, sdk = start_provider(directory)
    with pytest.raises(runner.ledger.EvaluationStopped, match="UNKNOWN_STAGE"):
        invoke(provider, "MAP-999")
    assert not sdk.calls and not provider.state["attempts"]


def native_draft_call(provider):
    source = ReportInsightRequest.model_validate(provider.result["request"])
    source = source.model_copy(update={"findings": source.findings[:8]})
    flat = {"assessments": {"CHIP_MAKER": {}}}
    for finding in source.findings:
        basis = {"claimId": finding.claims[0].id, "quote": finding.claims[0].text}
        flat["assessments"]["CHIP_MAKER"][f"finding{finding.id}"] = {
            "findingId": finding.id,
            "work": "PROCESS_QUALIFICATION",
            "relation": "CONDITIONAL",
            "relationBasis": deepcopy(basis),
            "condition": "양산 목표가 유지되고 해당 공정에 인증이 필요한 경우",
            "impactScope": "LIMITED_PREPARATION",
            "impactBasis": deepcopy(basis),
            "urgencyState": "UNDETERMINED",
            "urgencyBasis": None,
            "reason": "양산 계획이 유지된다면 공정 검증 준비 일정을 확인할 필요가 있다.",
        }
    native = assessment.draft_to_wire(flat, request=source)
    schema = assessment.draft_schema(source)
    schema["description"] = "reportInsightCall:MAP-001"
    return source, native, schema


def test_actual_correlated_native_shape_preserves_stage_raw_response_and_public_mapping(prepared):
    _, directory, _ = prepared
    provider, sdk = start_provider(directory)
    source, native, schema = native_draft_call(provider)

    def respond(wire, count):
        Draft202012Validator(wire["text"]["format"]["schema"]).validate(native)
        return RawResponse(native)

    sdk.handler = respond
    generated = provider.generate(
        system_instruction="same report instruction",
        prompt="same original evidence",
        response_schema=schema,
    )
    validated = assessment.validate_draft(generated, source)
    assert len(validated.mapped.insights[0].assessments) == 8
    assert all(
        item.axes.directness == 2 and item.axes.impact == 1 and item.axes.urgency is None
        for item in validated.mapped.insights[0].assessments
    )
    assert (
        validated.evidence["CHIP_MAKER"][501].relation_basis.quote
        == source.findings[0].claims[0].text
    )
    record = provider.state["attempts"][0]
    assert json.loads(record["providerText"]) == native
    assert record["logicalCallId"] == "MAP-001" and record["stage"] == "MAP"
    assert sdk.calls[0]["text"]["format"]["name"] == "ReportAssessmentDraft"
    runner.verify(directory, provider.manifest, provider.state)


@pytest.mark.parametrize(
    "block,field,value",
    [
        ("connection", "work", None),
        ("connection", "condition", None),
        ("connection", "basis", None),
        ("effect", "basis", None),
        ("timing", "urgencyState", "MONITOR"),
        ("connection", "relation", "UNRELATED"),
    ],
)
def test_uniform_native_fields_keep_category_correlations_in_post_validation(
    prepared, block, field, value
):
    _, directory, _ = prepared
    provider, sdk = start_provider(directory)
    source, native, schema = native_draft_call(provider)
    sdk.handler = lambda wire, count: RawResponse(native)
    generated = provider.generate(
        system_instruction="same report instruction", prompt="source", response_schema=schema
    )
    native_schema = sdk.calls[0]["text"]["format"]["schema"]
    invalid = deepcopy(native)
    invalid["assessments"]["CHIP_MAKER"]["finding501"][block][field] = value
    Draft202012Validator(native_schema).validate(invalid)
    with pytest.raises(assessment.ReportAssessmentDraftValidationError):
        assessment.validate_draft(replace(generated, text=json.dumps(invalid)), source)


def test_native_schema_rejects_unknown_and_other_finding_source_span_ids(prepared):
    _, directory, _ = prepared
    provider, sdk = start_provider(directory)
    source, native, schema = native_draft_call(provider)
    sdk.handler = lambda wire, count: RawResponse(native)
    provider.generate(
        system_instruction="same report instruction", prompt="source", response_schema=schema
    )
    native_schema = sdk.calls[0]["text"]["format"]["schema"]
    other_span_id = native["assessments"]["CHIP_MAKER"]["finding502"]["connection"]["basis"][
        "sourceSpanId"
    ]
    for wrong_span_id in ("unknown_source_span", other_span_id):
        invalid = deepcopy(native)
        invalid["assessments"]["CHIP_MAKER"]["finding501"]["connection"]["basis"][
            "sourceSpanId"
        ] = wrong_span_id
        with pytest.raises(JsonSchemaValidationError):
            Draft202012Validator(native_schema).validate(invalid)
    assert len(sdk.calls) == 1
    runner.verify(directory, provider.manifest, provider.state)


def test_native_valid_but_factually_wrong_reason_uses_one_logical_repair(prepared):
    _, directory, _ = prepared
    provider, sdk = start_provider(directory)
    source, native, schema = native_draft_call(provider)
    invalid = deepcopy(native)
    invalid["assessments"]["CHIP_MAKER"]["finding501"]["reason"] = (
        "삼성전자는 2027년 CPO 양산을 완료했다."
    )

    def respond(wire, count):
        payload = invalid if count == 1 else native
        Draft202012Validator(wire["text"]["format"]["schema"]).validate(payload)
        return RawResponse(payload)

    sdk.handler = respond

    def validate(response):
        draft = assessment.validate_draft(response, source)
        _validated_map_output(
            replace(response, text=draft.mapped.model_dump_json(by_alias=True)), source
        )
        return draft

    generated = structured_call(
        provider,
        system_instruction="same report instruction",
        prompt="same original evidence",
        response_schema=schema,
        validate=validate,
        repair_attempts=1,
        task_name="native-correlated-draft",
        input_tag="report-insight",
        schema_violation_message="invalid report draft",
        logger=runner.ledger.LOGGER,
    )
    assert (
        generated.output.evidence["CHIP_MAKER"][501].relation_basis.quote
        == source.findings[0].claims[0].text
    )
    assert len(sdk.calls) == 2
    assert [item["repairIndex"] for item in provider.state["attempts"]] == [0, 1]
    assert {item["logicalCallId"] for item in provider.state["attempts"]} == {"MAP-001"}
    runner.verify(directory, provider.manifest, provider.state)
