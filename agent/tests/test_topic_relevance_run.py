import copy
import json
from decimal import Decimal

import pytest

from app.core.config import Settings
from app.core.errors import AgentError
from app.eval import topic_relevance_run as runner
from app.eval.topic_relevance_review import build_packet, read_json
from app.llm.base import ProviderResponse, ProviderUsage
from app.llm.topic_relevance_service import TopicRelevanceService


@pytest.fixture
def inputs(tmp_path):
    cases = [{
        "caseId": f"case-{index}",
        "topic": {
            "id": 41 + index % 2, "name": "반도체 장비", "queryText": "반도체",
            "requiredKeywords": [], "optionalKeywords": ["장비"], "excludedKeywords": [],
        },
        "article": {
            "id": 801 + index, "title": f"반도체 장비 공장 {index}호기 증설",
            "summary": "반도체 장비 공급 계약이다.", "bodyText": "반도체 제조 장비를 공급한다.",
            "publisher": "검증 매체", "url": None, "publishedAt": None, "bodyTruncated": False,
        },
    } for index in range(7)]
    packet = build_packet({"datasetId": "arbitrary-dataset.v8", "createdAt": "2026-09-22T00:00:00Z",
                           "cases": cases})
    manifest = {
        "datasetId": packet["datasetId"], "datasetSha256": packet["datasetSha256"],
        "cases": [{"caseId": case["caseId"], "articleId": case["article"]["id"],
                   "topicId": case["topic"]["id"], "stratum": "natural"}
                  for case in packet["cases"]],
    }
    packet_path, manifest_path = tmp_path / "packet.json", tmp_path / "manifest.json"
    packet_path.write_text(json.dumps(packet), encoding="utf-8")
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    return packet_path, manifest_path, tmp_path / "evaluation"


class FakeProvider:
    def __init__(self, *, cost="0.001", failure=None, model=runner.MODEL):
        self.calls = 0
        self.cost = Decimal(cost)
        self.failure = failure
        self.model = model

    def generate(self, *, system_instruction, prompt, response_schema):
        self.calls += 1
        if self.failure is not None:
            raise self.failure
        keys = response_schema["properties"]["decisions"]["properties"]
        return ProviderResponse(
            text=json.dumps({"decisions": {
                key: {"status": "RELEVANT", "reason": "기사에 장비 공급이 명시된다.",
                      "evidenceQuotes": ["quote_0"]} for key in keys
            }}),
            provider="openai", model=self.model,
            usage=ProviderUsage(input_tokens=30, output_tokens=20, cost_usd=self.cost),
        )


def install_fake(monkeypatch, provider):
    closed = []
    settings = Settings().model_copy(update=runner.LIVE_SETTINGS)
    service = TopicRelevanceService(settings, provider=provider)
    monkeypatch.setattr(runner, "live_service", lambda: (service, runner.execution_settings(),
                                                       lambda: closed.append(True)))
    return closed


def execute(inputs, phase, **kwargs):
    return runner.execute(*inputs, phase=phase, **kwargs)


def checkpoint(inputs):
    return inputs[2] / "sealed/live-results.json"


def test_preflight_never_constructs_settings_or_provider(inputs, monkeypatch):
    def forbidden(*args, **kwargs):
        pytest.fail("preflight tried to access runtime credentials or a provider")
    monkeypatch.setattr(runner, "live_service", forbidden)
    monkeypatch.setattr("app.core.config.Settings", forbidden)
    assert execute(inputs, "preflight") == {
        "phase": "preflight", "cases": 7, "pilotCases": 5, "remainingCases": 2,
    }
    lock = read_json(inputs[2] / "evaluation-lock.json")
    pins = lock["runtimeSourceSha256s"]
    assert {"app/core/config.py", "app/llm/router.py", "app/llm/openai_provider.py",
            f"app/prompts/{runner.PROMPT_VERSION}.md",
            "app/schemas/topic_relevance.py"} <= pins.keys()
    assert not any("topic_relevance_score" in path for path in pins)
    assert "openai_api_key" not in json.dumps(lock)
    assert not checkpoint(inputs).exists()


def test_single_article_pilot_remaining_and_success_resume_do_not_repeat_calls(inputs, monkeypatch):
    provider = FakeProvider()
    closed = install_fake(monkeypatch, provider)
    execute(inputs, "preflight")
    first = execute(inputs, "pilot")
    assert provider.calls == 5 and len(first["batches"]) == 5
    for batch in first["batches"]:
        assert len(batch["caseIds"]) == 1
        assert batch["inputSha256s"].keys() == set(batch["caseIds"])
        assert len(batch["requestSha256"]) == 64
        assert batch["response"]["meta"]["promptVersion"] == runner.PROMPT_VERSION
    execute(inputs, "pilot")
    assert provider.calls == 5
    final = execute(inputs, "remaining")
    assert provider.calls == 7 and len(final["batches"]) == 7
    assert final["inFlight"] is None and not final["errors"] and len(closed) == 2
    assert checkpoint(inputs).stat().st_mode & 0o777 == 0o600


@pytest.mark.parametrize("change", ["topic", "article", "duplicate", "stratum", "label"])
def test_rejects_wrong_manifest_mapping_before_lock_or_provider(inputs, change):
    manifest = read_json(inputs[1])
    if change == "topic":
        manifest["cases"][0]["topicId"] += 1
    elif change == "article":
        manifest["cases"][0]["articleId"] += 1
    elif change == "duplicate":
        manifest["cases"][0] = copy.deepcopy(manifest["cases"][1])
    elif change == "stratum":
        manifest["cases"][0]["stratum"] = ""
    else:
        manifest["cases"][0]["decision"] = "RELEVANT"
    inputs[1].write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(runner.EvaluationStopped):
        execute(inputs, "preflight")
    assert not (inputs[2] / "evaluation-lock.json").exists()


def test_requires_preflight_and_completed_pilot(inputs, monkeypatch):
    provider = FakeProvider()
    install_fake(monkeypatch, provider)
    with pytest.raises(runner.EvaluationStopped, match="PREFLIGHT_REQUIRED"):
        execute(inputs, "pilot")
    execute(inputs, "preflight")
    with pytest.raises(runner.EvaluationStopped, match="PILOT_REQUIRED"):
        execute(inputs, "remaining")
    assert provider.calls == 0


def test_runtime_change_blocks_provider_but_does_not_overwrite_frozen_lock(inputs, monkeypatch):
    execute(inputs, "preflight")
    before = (inputs[2] / "evaluation-lock.json").read_bytes()
    pins = runner.runtime_hashes()
    pins["app/llm/topic_relevance_service.py"] = "0" * 64
    monkeypatch.setattr(runner, "runtime_hashes", lambda: pins)
    provider = FakeProvider()
    install_fake(monkeypatch, provider)
    with pytest.raises(runner.EvaluationStopped, match="EVALUATION_LOCK_CHANGED"):
        execute(inputs, "pilot")
    assert provider.calls == 0
    assert before == (inputs[2] / "evaluation-lock.json").read_bytes()


def test_case_counts_pilot_and_budget_are_frozen(inputs):
    execute(inputs, "preflight", pilot_size=2)
    with pytest.raises(runner.EvaluationStopped, match="EVALUATION_LOCK_CHANGED"):
        execute(inputs, "pilot", pilot_size=3)
    with pytest.raises(runner.EvaluationStopped, match="EVALUATION_LOCK_CHANGED"):
        execute(inputs, "pilot", pilot_size=2, max_cost_usd=Decimal("0.5"))


def test_provider_failure_saved_safely_and_never_retried(inputs, monkeypatch, capsys):
    secret = "NEVER-PRINT-AUTHORIZATION-TEST"
    provider = FakeProvider(failure=AgentError(
        status_code=503, code="PROVIDER_UNAVAILABLE", message=secret,
        details={"providerBody": secret, "usage": {"costUsd": 0.002},
                 "executionMetadata": {"model": secret}},
    ))
    closed = install_fake(monkeypatch, provider)
    execute(inputs, "preflight")
    for _ in range(2):
        with pytest.raises(runner.EvaluationStopped, match="RECORDED_FAILURE_REQUIRES_REVIEW"):
            execute(inputs, "pilot")
    assert provider.calls == 1 and len(closed) == 1
    result = read_json(checkpoint(inputs))
    assert result["errors"][0]["code"] == "PROVIDER_UNAVAILABLE"
    assert result["errors"][0]["usage"]["costUsd"] == 0.002
    assert secret not in checkpoint(inputs).read_text() + capsys.readouterr().out


def test_interrupted_request_keeps_inflight_and_cannot_be_resubmitted(inputs, monkeypatch):
    provider = FakeProvider(failure=KeyboardInterrupt())
    closed = install_fake(monkeypatch, provider)
    execute(inputs, "preflight")
    with pytest.raises(KeyboardInterrupt):
        execute(inputs, "pilot")
    assert read_json(checkpoint(inputs))["inFlight"]["caseIds"]
    with pytest.raises(runner.EvaluationStopped, match="INTERRUPTED_REQUEST_REQUIRES_REVIEW"):
        execute(inputs, "pilot")
    assert provider.calls == 1 and len(closed) == 1


def test_reported_cost_cap_stops_before_another_paid_request(inputs, monkeypatch):
    provider = FakeProvider(cost="0.006")
    install_fake(monkeypatch, provider)
    execute(inputs, "preflight", max_cost_usd=Decimal("0.01"))
    for _ in range(2):
        with pytest.raises(runner.EvaluationStopped, match="REPORTED_COST_CAP_REACHED"):
            execute(inputs, "pilot", max_cost_usd=Decimal("0.01"))
    assert provider.calls == 2
    assert runner.cost_so_far(read_json(checkpoint(inputs))) == Decimal("0.012")


def test_response_metadata_mismatch_is_recorded_instead_of_counted_success(inputs, monkeypatch):
    provider = FakeProvider(model="unexpected-model")
    install_fake(monkeypatch, provider)
    execute(inputs, "preflight")
    with pytest.raises(runner.EvaluationStopped, match="RECORDED_FAILURE_REQUIRES_REVIEW"):
        execute(inputs, "pilot")
    result = read_json(checkpoint(inputs))
    assert not result["batches"]
    assert result["errors"][0]["code"] == "UNEXPECTED_RESPONSE_PROVENANCE"
    assert result["errors"][0]["usage"]["costUsd"] == 0.001


def test_checkpoint_request_tampering_blocks_remaining_calls(inputs, monkeypatch):
    provider = FakeProvider()
    install_fake(monkeypatch, provider)
    execute(inputs, "preflight")
    execute(inputs, "pilot")
    result = read_json(checkpoint(inputs))
    result["batches"][0]["requestSha256"] = "0" * 64
    runner.atomic_save(checkpoint(inputs), result)
    with pytest.raises(runner.EvaluationStopped, match="RESULT_INPUT_CHANGED"):
        execute(inputs, "remaining")
    assert provider.calls == 5


def test_exclusive_file_lock_prevents_duplicate_evaluation(inputs):
    with runner.evaluation_lock(inputs[2]):
        with pytest.raises(runner.EvaluationStopped, match="ANOTHER_RUN_IS_ACTIVE"):
            execute(inputs, "preflight")


def test_missing_credentials_stop_without_creating_paid_checkpoint(inputs, monkeypatch):
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    monkeypatch.delenv("OPENAI_MODEL", raising=False)
    execute(inputs, "preflight")
    with pytest.raises(runner.EvaluationStopped, match="PROVIDER_CONFIGURATION_MISMATCH"):
        execute(inputs, "pilot")
    assert not checkpoint(inputs).exists()


def test_cli_validation_error_does_not_echo_input_or_exception(inputs, monkeypatch, capsys):
    monkeypatch.setattr(runner, "DOCS_ROOT", inputs[2])
    inputs[0].write_text('{"secret-value": "BROKEN', encoding="utf-8")
    assert runner.main(["--packet", str(inputs[0]), "--manifest", str(inputs[1]),
                        "--output-dir", str(inputs[2]), "--phase", "preflight"]) == 2
    assert json.loads(capsys.readouterr().out)["errorCode"] == "LOCAL_VALIDATION_FAILED"


def test_manifest_cannot_silently_change_preregistered_pilot(inputs):
    manifest = read_json(inputs[1])
    manifest["pilotSize"] = 3
    inputs[1].write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(runner.EvaluationStopped, match="MANIFEST_PILOT_SIZE_CHANGED"):
        execute(inputs, "preflight")
    assert execute(inputs, "preflight", pilot_size=3)["pilotCases"] == 3


@pytest.mark.parametrize("limit", ["0", "-1", "2.01", "NaN", "Infinity"])
def test_preflight_rejects_invalid_explicit_cost_limit(inputs, limit):
    with pytest.raises(runner.EvaluationStopped, match="INVALID_COST_LIMIT"):
        execute(inputs, "preflight", max_cost_usd=Decimal(limit))
    assert not checkpoint(inputs).exists()


def test_preflight_pins_explicit_larger_cost_limit(inputs):
    execute(inputs, "preflight", max_cost_usd=Decimal("1.5"))
    lock = read_json(inputs[2] / "evaluation-lock.json")
    assert lock["maxReportedCostUsd"] == "1.5"
    with pytest.raises(runner.EvaluationStopped, match="EVALUATION_LOCK_CHANGED"):
        execute(inputs, "pilot", max_cost_usd=Decimal("2"))


def test_cli_refuses_generated_artifacts_outside_docs(inputs, capsys):
    assert runner.main(["--packet", str(inputs[0]), "--manifest", str(inputs[1]),
                        "--output-dir", str(inputs[2]), "--phase", "preflight"]) == 2
    output = json.loads(capsys.readouterr().out)
    assert output["errorCode"] == "OUTPUT_OUTSIDE_DOCUMENTATION_DIRECTORY"
    assert not inputs[2].exists()


def test_live_settings_have_pinned_positive_prices_even_with_zero_env(monkeypatch):
    monkeypatch.setenv("OPENAI_API_KEY", "fake-test-key-never-used")
    monkeypatch.setenv("OPENAI_MODEL", "gpt-4.1-nano")
    monkeypatch.setenv("TOPIC_RELEVANCE_OPENAI_MODEL", "gpt-4o-mini")
    for key in ("OPENAI_INPUT_COST_PER_MILLION", "OPENAI_CACHED_INPUT_COST_PER_MILLION",
                "OPENAI_OUTPUT_COST_PER_MILLION"):
        monkeypatch.setenv(key, "0")
    service, settings, close = runner.live_service()
    try:
        assert isinstance(service, TopicRelevanceService)
        assert settings == runner.execution_settings()
        assert settings["topic_relevance_openai_model"] == runner.MODEL
        assert settings["model"] == runner.MODEL
        assert all(Decimal(price) > 0 for price in settings["modelPricesUsdPerMillion"])
        assert settings["openai_input_cost_per_million"] is None
        assert settings["mock"] is False
    finally:
        close()
