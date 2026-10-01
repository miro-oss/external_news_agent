"""Offline SDK doubles exercise paid-call admission and interrupted-run safety."""

import json
import logging
import sys
from copy import deepcopy
from decimal import Decimal
from types import SimpleNamespace

import pytest
from test_report_insight import output, request_body

from app.eval import report_insight_run as runner


class RawResponse:
    def __init__(self, payload, *, usage=None, model=runner.MODEL):
        self.output_text = json.dumps(payload, ensure_ascii=False)
        self.status = "completed"
        self.output = []
        self.model = model
        self.raw_usage = (
            usage
            if usage is not None
            else {
                "input_tokens": 100,
                "output_tokens": 50,
                "input_tokens_details": {"cached_tokens": 20},
            }
        )
        self.usage = SimpleNamespace(**self.raw_usage)
        if isinstance(self.raw_usage.get("input_tokens_details"), dict):
            self.usage.input_tokens_details = SimpleNamespace(
                **self.raw_usage["input_tokens_details"]
            )

    def model_dump(self, **_):
        return {
            "model": self.model,
            "status": self.status,
            "usage": self.raw_usage,
            "output": [
                {"type": "message", "content": [{"type": "output_text", "text": self.output_text}]}
            ],
        }


class FakeSDK:
    def __init__(self, handler=None):
        self.handler = handler
        self.calls = []
        self.configs = []
        self.closed = 0

    def __call__(self, **config):
        self.configs.append(config)
        owner = self

        class Responses:
            def create(self, **wire):
                owner.calls.append(wire)
                if owner.handler:
                    return owner.handler(wire, len(owner.calls))
                candidate = output(second=True)
                name = wire["text"]["format"]["name"]
                if "MapOutput" in name:
                    candidate = {
                        "insights": [
                            {
                                key: value
                                for key, value in insight.items()
                                if key in {"audience", "assessments"}
                            }
                            for insight in candidate["insights"]
                        ]
                    }
                elif "ReduceOutput" in name:
                    candidate = {
                        "insights": [
                            {key: value for key, value in insight.items() if key != "assessments"}
                            for insight in candidate["insights"]
                        ]
                    }
                return RawResponse(candidate)

        class Client:
            responses = Responses()

            def close(self):
                owner.closed += 1

        return Client()


@pytest.fixture
def prepared(tmp_path, monkeypatch):
    monkeypatch.setenv("OPENAI_API_KEY", "offline-fake-key")
    monkeypatch.setattr(runner, "runtime_hashes", lambda: {"frozen.py": "original"})
    corpus = json.loads(runner.DEFAULT_DATASET.read_text())
    case = deepcopy(corpus["cases"][0])
    case["caseId"] = "offline-case"
    case["request"] = request_body(second=True)
    case["annotations"] = {
        "requiredSynthesisClaimIds": ["501:0"],
        "forbiddenProse": ["secret-label"],
        "notUrgentFindingIds": [],
        "expectedImportanceByFinding": {},
    }
    corpus["cases"] = [case]
    dataset = tmp_path / "cases.json"
    dataset.write_text(json.dumps(corpus, ensure_ascii=False))
    directory = tmp_path / "evaluation"
    runner.prepare(dataset, directory)
    return dataset, directory


def read_state(directory):
    return json.loads((directory / "result.json").read_text())


def test_prepare_is_credential_free_and_never_carries_annotations_to_requests(
    prepared, monkeypatch
):
    dataset, directory = prepared
    monkeypatch.delenv("OPENAI_API_KEY")
    manifest = runner.prepare(dataset, directory)
    assert manifest["baseCallUpperBound"] == 3
    assert manifest["repairCallUpperBound"] == 6
    assert "secret-label" not in json.dumps(manifest)
    assert all(job["request"]["plan"] == "FREE" for job in manifest["jobs"])
    assert runner.summary(directory)["status"] == "prepared"


def test_same_input_comparison_records_final_wire_raw_usage_and_no_duplicate_resume(prepared):
    _, directory = prepared
    sdk = FakeSDK()
    state = runner.run(directory, sdk_factory=sdk)
    assert state["status"] == "complete"
    assert len(sdk.calls) == 3
    assert sdk.closed == 3
    assert all(config["max_retries"] == 0 and config["timeout"] <= 60 for config in sdk.configs)
    assert all(item["status"] == "success" for item in state["results"])
    assert state["results"][0]["request"] == state["results"][1]["request"]
    assert {item["stage"] for item in state["attempts"]} == {"SINGLE", "MAP", "REDUCE"}
    assert all(
        item["providerText"] and item["providerRawUsage"]["input_tokens"] == 100
        for item in state["attempts"]
    )
    assert all(item["resolvedModel"] == runner.MODEL for item in state["attempts"])
    assert Decimal(state["totals"]["observedCostEstimatedUsd"]) == Decimal("0.0000855")
    assert state["totals"]["unsettledReservedUsd"] == "0"
    assert all("secret-label" not in json.dumps(wire) for wire in sdk.calls)
    runner.run(directory, sdk_factory=sdk)
    assert len(sdk.calls) == 3


def test_checkpoint_precedes_every_paid_attempt_and_raw_invalid_output_survives_repair(prepared):
    _, directory = prepared
    sdk = FakeSDK()

    def handler(wire, index):
        checkpoint = read_state(directory)
        assert checkpoint["inFlight"] == index
        assert checkpoint["attempts"][-1]["status"] == "in_flight"
        assert Decimal(checkpoint["totals"]["unsettledReservedUsd"]) > 0
        if index == 1:
            return RawResponse({"insights": []})
        sdk.handler = None
        sdk.calls.pop()
        return sdk().responses.create(**wire)

    sdk.handler = handler
    state = runner.run(directory, sdk_factory=sdk)
    assert len(state["attempts"]) == 4
    assert state["attempts"][0]["providerText"] == '{"insights": []}'
    assert state["attempts"][1]["repairIndex"] == 1
    assert "<validation-error>" in state["attempts"][1]["wireRequest"]["input"]


@pytest.mark.parametrize(
    "max_cost,max_calls,expected",
    [
        (Decimal("0.0000001"), 144, "COST_RESERVATION_LIMIT_REACHED"),
        (Decimal("1"), 1, "CALL_LIMIT_REACHED"),
    ],
)
def test_next_attempt_is_not_submitted_beyond_whole_budget(prepared, max_cost, max_calls, expected):
    dataset, directory = prepared
    other = directory.parent / "capped"
    runner.prepare(dataset, other, max_cost_usd=max_cost, max_calls=max_calls)
    sdk = FakeSDK()
    with pytest.raises(runner.EvaluationStopped, match=expected):
        runner.run(other, sdk_factory=sdk)
    assert len(sdk.calls) == (0 if max_calls == 144 else 1)
    assert len(read_state(other)["attempts"]) == len(sdk.calls)


def test_unknown_provider_failure_has_no_retry_leaks_no_exception_and_holds_reservation(
    prepared, caplog
):
    _, directory = prepared
    sdk = FakeSDK(lambda *_: (_ for _ in ()).throw(RuntimeError("offline-fake-key echoed")))
    with pytest.raises(runner.EvaluationStopped, match="PROVIDER_ATTEMPT_FAILED"):
        runner.run(directory, sdk_factory=sdk)
    state = read_state(directory)
    assert len(sdk.calls) == 1
    assert Decimal(state["totals"]["unsettledReservedUsd"]) > 0
    assert "offline-fake-key" not in (directory / "result.json").read_text()
    assert "offline-fake-key" not in caplog.text
    with pytest.raises(runner.EvaluationStopped, match="RECORDED_FAILURE"):
        runner.run(directory, sdk_factory=sdk)
    assert len(sdk.calls) == 1


def test_known_provider_error_usage_preserved_without_body_or_headers(prepared):
    _, directory = prepared

    class ChargedError(Exception):
        body = {
            "error": {"message": "offline-fake-key"},
            "usage": {"input_tokens": 100, "output_tokens": 50},
        }
        headers = {"Authorization": "offline-fake-key"}

    sdk = FakeSDK(lambda *_: (_ for _ in ()).throw(ChargedError("offline-fake-key")))
    with pytest.raises(runner.EvaluationStopped):
        runner.run(directory, sdk_factory=sdk)
    state = read_state(directory)
    assert Decimal(state["totals"]["observedCostEstimatedUsd"]) == Decimal("0.00003")
    assert state["totals"]["unsettledReservedUsd"] == "0"
    assert "offline-fake-key" not in (directory / "result.json").read_text()


@pytest.mark.parametrize(
    "mutation",
    ["runtime", "input", "attempt_cost", "attempt_id", "wire", "mapping", "normalized_usage"],
)
def test_frozen_runtime_input_and_attempt_accounting_fail_closed(prepared, monkeypatch, mutation):
    _, directory = prepared
    sdk = FakeSDK()
    runner.run(directory, sdk_factory=sdk)
    state = read_state(directory)
    if mutation == "runtime":
        monkeypatch.setattr(runner, "runtime_hashes", lambda: {"frozen.py": "changed"})
    elif mutation == "input":
        state["results"][0]["request"]["report"]["title"] = "changed"
    elif mutation == "attempt_cost":
        state["attempts"][0]["costEstimatedUsd"] = "0"
    elif mutation == "attempt_id":
        state["attempts"][0]["attemptId"] = 9
    elif mutation == "wire":
        state["attempts"][0]["wireRequest"]["model"] = "another-model"
    elif mutation == "normalized_usage":
        state["attempts"][0]["usage"]["input_tokens"] += 100
        state["results"][0]["response"]["meta"]["inputTokens"] += 100
    else:
        state["results"][0]["attemptIds"] = []
    runner.save(directory, state)  # Even correctly reserialized tampering must fail validation.
    with pytest.raises(runner.EvaluationStopped):
        runner.run(directory, sdk_factory=sdk)
    assert len(sdk.calls) == 3


def test_interrupted_active_job_requires_review_even_between_stages(prepared):
    _, directory = prepared
    state = read_state(directory)
    state["results"][0]["status"] = "running"
    runner.save(directory, state)
    sdk = FakeSDK()
    with pytest.raises(runner.EvaluationStopped, match="RECORDED_FAILURE"):
        runner.run(directory, sdk_factory=sdk)
    assert sdk.calls == []


def test_model_overrides_and_settings_sources_cannot_change_nano(monkeypatch):
    monkeypatch.setenv("OPENAI_MODEL", "different-model")
    monkeypatch.setenv("AGENT_REPORT_MAX_OUTPUT_TOKENS", "99999")
    config = runner.settings("offline-fake-key")
    assert config.openai_model == runner.MODEL
    assert config.max_output_tokens == config.report_max_output_tokens == 4096
    assert config.provider_retry_attempts == config.rate_limit_retry_attempts == 0


def test_missing_key_and_incomplete_pilot_do_not_create_clients(prepared, monkeypatch):
    _, directory = prepared
    monkeypatch.delenv("OPENAI_API_KEY")
    sdk = FakeSDK()
    with pytest.raises(runner.EvaluationStopped, match="OPENAI_API_KEY_REQUIRED"):
        runner.run(directory, sdk_factory=sdk)
    with pytest.raises(runner.EvaluationStopped, match="PILOT_NOT_COMPLETE"):
        runner.run(directory, phase="remaining", sdk_factory=sdk)
    assert sdk.configs == []


def test_duplicate_process_cannot_acquire_run_lock(prepared):
    _, directory = prepared
    with (
        runner.evaluation_lock(directory),
        pytest.raises(runner.EvaluationStopped, match="ANOTHER_RUN"),
    ):
        runner.run(directory, sdk_factory=FakeSDK())


def test_reservation_counts_all_final_wire_bytes_and_output_ceiling():
    wire = {
        "instructions": "한글" * 200,
        "input": "x" * 1000,
        "text": {"format": {"schema": {"enum": ["long-id" * 40]}}},
    }
    upper, maximum = runner.reservation(wire)
    assert upper == len(runner.canonical(wire)) + runner.PROTOCOL_MARGIN_TOKENS
    assert maximum == (Decimal(upper) * Decimal("0.1") + Decimal(4096) * Decimal("0.4")) / 1000000


@pytest.mark.parametrize(
    "usage", [{}, {"input_tokens": 100}, {"input_tokens": 100, "output_tokens": True}]
)
def test_missing_or_partial_usage_stops_and_retains_unobserved_exposure(prepared, usage):
    _, directory = prepared
    sdk = FakeSDK(lambda *_: RawResponse(output(second=True), usage=usage))
    with pytest.raises(runner.EvaluationStopped, match="PROVIDER_USAGE_UNKNOWN"):
        runner.run(directory, sdk_factory=sdk)
    state = read_state(directory)
    assert len(sdk.calls) == 1
    assert Decimal(state["totals"]["unsettledReservedUsd"]) > 0
    with pytest.raises(runner.EvaluationStopped, match="RECORDED_FAILURE"):
        runner.run(directory, sdk_factory=sdk)


def test_unexpected_model_is_recorded_without_price_assumption_or_resubmission(prepared):
    _, directory = prepared
    sdk = FakeSDK(lambda *_: RawResponse(output(second=True), model="different-model"))
    with pytest.raises(runner.EvaluationStopped, match="UNEXPECTED_RESOLVED_MODEL"):
        runner.run(directory, sdk_factory=sdk)
    state = read_state(directory)
    assert state["attempts"][0]["resolvedModel"] == "different-model"
    assert Decimal(state["totals"]["unsettledReservedUsd"]) > 0
    with pytest.raises(runner.EvaluationStopped, match="RECORDED_FAILURE"):
        runner.run(directory, sdk_factory=sdk)
    assert len(sdk.calls) == 1


def test_known_contract_failures_are_measured_and_never_resubmitted(prepared):
    _, directory = prepared
    sdk = FakeSDK(lambda *_: RawResponse({"insights": []}))
    state = runner.run(directory, sdk_factory=sdk)
    assert len(sdk.calls) == 4
    assert state["status"] == "complete"
    assert all(
        item["status"] == "failed" and item["terminalFailureSafe"] for item in state["results"]
    )
    assert all(item["safeToContinue"] for item in state["errors"])
    assert state["totals"]["unsettledReservedUsd"] == "0"
    runner.run(directory, sdk_factory=sdk)
    assert len(sdk.calls) == 4


def test_unknown_failure_cannot_be_relabeled_safe_in_a_reserialized_checkpoint(prepared):
    _, directory = prepared
    sdk = FakeSDK(lambda *_: (_ for _ in ()).throw(RuntimeError("unknown charge")))
    with pytest.raises(runner.EvaluationStopped):
        runner.run(directory, sdk_factory=sdk)
    state = read_state(directory)
    state["errors"][0]["safeToContinue"] = True
    state["results"][0]["terminalFailureSafe"] = True
    runner.save(directory, state)
    with pytest.raises(runner.EvaluationStopped, match="CHECKPOINT_FAILURE_POLICY_CHANGED"):
        runner.run(directory, sdk_factory=sdk)
    assert len(sdk.calls) == 1


def test_interruption_inside_submission_requires_review_without_retry(prepared):
    _, directory = prepared
    sdk = FakeSDK(lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    with pytest.raises(KeyboardInterrupt):
        runner.run(directory, sdk_factory=sdk)
    state = read_state(directory)
    assert state["attempts"][0]["status"] == "in_flight"
    with pytest.raises(runner.EvaluationStopped, match="INTERRUPTED_ATTEMPT"):
        runner.run(directory, sdk_factory=sdk)
    assert len(sdk.calls) == 1


def test_attempt_deadline_keeps_observed_cost_and_stops_next_stage(prepared):
    _, directory = prepared
    clock = [0.0]

    def slow(*_):
        clock[0] += 61
        return RawResponse(output(second=True))

    sdk = FakeSDK(slow)
    with pytest.raises(runner.EvaluationStopped, match="ATTEMPT_DEADLINE_EXCEEDED"):
        runner.run(directory, sdk_factory=sdk, clock=lambda: clock[0])
    state = read_state(directory)
    assert Decimal(state["totals"]["observedCostEstimatedUsd"]) > 0
    assert state["attempts"][0]["latencyMs"] == 61000
    assert len(sdk.calls) == 1


def test_cli_restores_global_logging_policy(prepared, monkeypatch, capsys):
    _, directory = prepared
    previous = logging.root.manager.disable
    monkeypatch.setattr(sys, "argv", ["runner", "status", "--output-dir", str(directory)])
    try:
        logging.disable(logging.ERROR)
        runner.main()
        assert logging.root.manager.disable == logging.ERROR
        assert json.loads(capsys.readouterr().out)["status"] == "prepared"
    finally:
        logging.disable(previous)
