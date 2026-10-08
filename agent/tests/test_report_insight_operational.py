"""The operational evaluator observes the current service, never a legacy substitute."""

import json
import sys
import threading
import time
from copy import deepcopy
from decimal import Decimal
from types import SimpleNamespace

import pytest
from pydantic import ValidationError
from test_report_insight_assessment import request
from test_report_insight_v4_pipeline import V4Provider

from app.eval import report_insight_operational as operational
from app.eval.report_insight_run import EvaluationStopped
from app.llm.report_insight_service import PROMPT_VERSION


@pytest.fixture
def prepared(tmp_path, monkeypatch):
    # Other threads may edit files during local development; these tests isolate the hash probe.
    monkeypatch.setattr(operational, "runtime_hashes", lambda: {"current-service": "fixture"})
    source = request(audiences=operational.AUDIENCES)
    policy = {
        "model": "gpt-5-mini",
        "prices_usd_per_million": ["0.25", "0.025", "2"],
        "settings": {"openai_request_interval_seconds": 0},
        "max_calls": 60,
        "max_estimated_usd": "2",
    }
    cases = [
        {
            "case_id": "four-role-source",
            "split": "holdout",
            "request": source.model_dump(mode="json", by_alias=True),
        }
    ]
    return tmp_path, source, policy, cases


class FakeSDK:
    def __init__(self, source, monkeypatch, *, raw=None):
        self.provider = V4Provider(source)
        self.calls, self.clients = [], []
        self.active, self.peak = 0, 0
        self.lock = threading.Lock()
        self.raw = raw
        monkeypatch.setattr(operational.openai_provider, "OpenAI", self.client)

    def client(self, **kwargs):
        self.clients.append({"timeout": kwargs["timeout"], "max_retries": kwargs["max_retries"]})
        return SimpleNamespace(
            responses=SimpleNamespace(create=self.create), close=kwargs["http_client"].close
        )

    def create(self, **wire):
        with self.lock:
            self.active += 1
            self.peak = max(self.peak, self.active)
            self.calls.append(wire)
        try:
            time.sleep(0.01)
            if self.raw:
                return self.raw(wire)
            schema = {**wire["text"]["format"]["schema"], "title": wire["text"]["format"]["name"]}
            response = self.provider.generate(
                system_instruction=wire["instructions"],
                prompt=wire["input"],
                response_schema=schema,
            )
            return SimpleNamespace(
                output_text=response.text,
                status="completed",
                model=wire["model"],
                output=[],
                usage=SimpleNamespace(
                    input_tokens=11,
                    output_tokens=7,
                    input_tokens_details=SimpleNamespace(cached_tokens=2),
                ),
            )
        finally:
            with self.lock:
                self.active -= 1


def test_current_native_service_runs_all_stages_with_real_review_admission(prepared, monkeypatch):
    output, source, policy, cases = prepared
    # Policy construction must not discover ambient credentials/settings.
    monkeypatch.setenv("OPENAI_MODEL", "wrong-model")
    monkeypatch.setenv("AGENT_MOCK", "true")
    monkeypatch.setenv("AGENT_REPORT_INSIGHT_TIMEOUT_SECONDS", "1")
    sdk = FakeSDK(source, monkeypatch)
    manifest = operational.prepare(cases, policy, output)
    state = operational.run(output, api_key="explicit-test-key")

    assert manifest["promptVersion"] == PROMPT_VERSION
    assert manifest["policy"]["settings"]["report_insight_timeout_seconds"] == 180
    assert state["summary"]["successResults"] == 4, state["results"]
    assert state["summary"]["providerAttemptsByStage"] == {"MAP": 4, "REVIEW": 4, "REDUCE": 4}
    assert len(state["reviewAdmissions"]) == 4
    assert all(item["admitted"] for item in state["reviewAdmissions"])
    assert 1 < sdk.peak <= 2
    assert all(call["model"] == "gpt-5-mini" and call["store"] is False for call in sdk.calls)
    assert all(
        client["max_retries"] == 0 and 0 < client["timeout"] <= 180 for client in sdk.clients
    )
    assert {attempt["wire"]["text"]["format"]["name"] for attempt in state["attempts"]} == {
        "ReportAssessmentDraft",
        "ReportInsightReduceOutput",
    }
    assert all(
        job["response"]["meta"]["promptVersion"] == PROMPT_VERSION for job in state["results"]
    )
    assert all(job["latencyMs"] > 0 for job in state["results"])
    assert state["results"][2]["queueWaitMs"] > 0
    assert "explicit-test-key" not in (output / "result.json").read_text()
    assert "explicit-test-key" not in (output / "manifest.json").read_text()
    assert Decimal(state["summary"]["observedCostEstimatedUsd"]) > 0
    assert state["summary"]["evidenceOmissionRate"] is None
    assert state["summary"]["unnecessaryAbstentionRate"] is None
    assert not state["summary"]["bePersistenceVerified"]
    assert (output / "result.json").stat().st_mode & 0o777 == 0o600
    with pytest.raises(EvaluationStopped, match="RUN_ALREADY_STARTED"):
        operational.run(output, api_key="test-key")


@pytest.mark.parametrize(
    "change,expected_calls", [({"max_calls": 1}, 1), ({"max_estimated_usd": "0.00001"}, 0)]
)
def test_global_budget_is_reserved_before_concurrent_sdk_calls(
    prepared, monkeypatch, change, expected_calls
):
    output, source, policy, cases = prepared
    policy.update(change)
    sdk = FakeSDK(source, monkeypatch)
    operational.prepare(cases, policy, output)
    state = operational.run(output, api_key="key")
    assert len(sdk.calls) == expected_calls
    assert len(state["attempts"]) == expected_calls
    assert state["admissionDenials"]
    assert state["summary"]["failedResults"] == 4
    assert state["summary"]["allFinishedLatencyP95Ms"] is not None


def test_unknown_failure_reserves_cost_and_never_copies_exception_text(prepared, monkeypatch):
    output, source, policy, cases = prepared

    def fail(_wire):
        raise RuntimeError("sensitive-provider-body-never-record")

    sdk = FakeSDK(source, monkeypatch, raw=fail)
    operational.prepare(cases, policy, output)
    state = operational.run(output, api_key="key")
    assert len(sdk.calls) == len(state["attempts"])
    assert state["summary"]["successResults"] == 0
    assert Decimal(state["summary"]["unsettledReservedUsd"]) > 0
    assert "sensitive-provider-body-never-record" not in (output / "result.json").read_text()


def test_dry_check_binds_source_policy_runtime_and_rejects_secrets(prepared, monkeypatch):
    output, _, policy, cases = prepared
    secret_policy = deepcopy(policy)
    secret_policy["settings"]["openai_api_key"] = "not-allowed"
    with pytest.raises(ValidationError):
        operational.prepare(cases, secret_policy, output)
    operational.prepare(cases, policy, output)
    assert operational.check(output)[1]["attempts"] == []
    monkeypatch.setattr(operational, "runtime_hashes", lambda: {"current-service": "changed"})
    with pytest.raises(EvaluationStopped, match="RUNTIME_CHANGED"):
        operational.run(output, api_key="key")
    manifest = json.loads((output / "manifest.json").read_text())
    manifest["cases"][0]["request"]["report"]["reportDate"] = "2026-09-26"
    (output / "manifest.json").write_text(json.dumps(manifest))
    with pytest.raises(EvaluationStopped, match="MANIFEST_CHANGED"):
        operational.check(output)


def test_settings_cross_field_validation_does_not_read_environment(prepared):
    _, _, policy, _ = prepared
    policy["settings"].update(rate_limit_backoff_seconds=10, rate_limit_max_backoff_seconds=2)
    with pytest.raises(ValueError, match="BACKOFF"):
        operational.OperationalPolicy.model_validate(policy).runtime_settings()


def test_full_36_findings_use_six_maps_and_normal_reviews_per_audience(prepared, monkeypatch):
    output, _, policy, cases = prepared
    source = request(ids=tuple(range(101, 137)), audiences=operational.AUDIENCES)
    cases[0]["request"] = source.model_dump(mode="json", by_alias=True)
    sdk = FakeSDK(source, monkeypatch)
    operational.prepare(cases, policy, output)
    state = operational.run(output, api_key="test-key")
    assert state["summary"]["fourAudienceSuccessReports"] == 1, state["results"]
    assert state["summary"]["providerAttemptsByStage"] == {"MAP": 24, "REVIEW": 4, "REDUCE": 4}
    assert state["summary"]["assessmentCount"] == 144
    assert 1 < sdk.peak <= 4
    for job in state["results"]:
        assert len(job["response"]["insights"][0]["assessments"]) == 36


def test_dropped_job_cannot_shrink_success_denominator(prepared):
    output, _, policy, cases = prepared
    operational.prepare(cases, policy, output)
    state = json.loads((output / "result.json").read_text())
    state["results"].pop()
    (output / "result.json").write_text(json.dumps(state))
    with pytest.raises(EvaluationStopped, match="PLANNED_JOBS_CHANGED"):
        operational.check(output)


@pytest.mark.parametrize("injected", [False, True])
def test_cli_only_uses_process_key_with_explicit_opt_in(tmp_path, monkeypatch, capsys, injected):
    keys = []
    monkeypatch.setenv("OPENAI_API_KEY", "injected-test-value")
    monkeypatch.setattr(operational, "check", lambda _path: None)
    monkeypatch.setattr(operational.getpass, "getpass", lambda _prompt: "typed-test-value")

    def fake_run(_path, *, api_key):
        keys.append(api_key)
        return {"summary": {"fake": True}}

    monkeypatch.setattr(operational, "run", fake_run)
    args = ["operational", "run", "--output", str(tmp_path), "--execute-live"]
    if injected:
        args.append("--use-injected-key")
    monkeypatch.setattr(sys, "argv", args)
    operational.main()
    assert keys == ["injected-test-value" if injected else "typed-test-value"]
    assert "test-value" not in capsys.readouterr().out


@pytest.mark.parametrize(
    ("audiences", "statuses", "expected"),
    [
        (operational.AUDIENCES, ("success",) * 4, 1),
        (operational.AUDIENCES[:2], ("success",) * 2, 0),
        (operational.AUDIENCES, ("success", "success", "success", "failed"), 0),
        (operational.AUDIENCES, ("success", "success", "success", "pending"), 0),
        (("CHIP_MAKER", "CHIP_MAKER", "IT_INFRA", "MARKET_INVESTOR"), ("success",) * 4, 0),
        ((*operational.AUDIENCES, "UNKNOWN"), ("success",) * 5, 0),
    ],
)
def test_four_audience_summary_requires_exactly_one_success_per_canonical_audience(
    audiences, statuses, expected
):
    results = [
        {
            "caseIndex": 0,
            "repeat": 0,
            "audience": audience,
            "status": status,
            "variant": "staged",
            "attemptIds": [],
            "response": {"insights": []},
        }
        for audience, status in zip(audiences, statuses, strict=True)
    ]
    state = {"results": results, "attempts": [], "stages": []}
    assert operational.summarize(state)["fourAudienceSuccessReports"] == expected


@pytest.mark.parametrize("group_field", ["caseIndex", "repeat"])
def test_four_audience_summary_does_not_combine_different_case_or_repeat_groups(group_field):
    results = [
        {
            "caseIndex": 0,
            "repeat": 0,
            group_field: index // 2,
            "audience": audience,
            "status": "success",
            "variant": "staged",
            "attemptIds": [],
            "response": {"insights": []},
        }
        for index, audience in enumerate(operational.AUDIENCES)
    ]
    state = {"results": results, "attempts": [], "stages": []}
    assert operational.summarize(state)["successResults"] == 4
    assert operational.summarize(state)["fourAudienceSuccessReports"] == 0
