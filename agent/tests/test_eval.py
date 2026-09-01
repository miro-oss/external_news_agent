import json
from copy import deepcopy
from pathlib import Path

import pytest

from app.core.config import Settings
from app.core.errors import AgentError
from app.eval import runner as eval_runner
from app.eval.__main__ import main
from app.eval.checkpoint import CheckpointError
from app.eval.dataset import (
    GoldenClaimDataset,
    GoldenDataset,
    GoldenReportFixture,
    load_claim_dataset,
    load_dataset,
    load_report_fixture,
)
from app.eval.live_provider import LiveProviderPolicy
from app.eval.runner import ReplayProvider, run_evaluation
from app.eval.scorer import (
    ClaimControlCounts,
    ClaimControlScore,
    ComparisonError,
    compare_metrics,
    compare_results,
    korean_summary_pass,
    score_claim_controls,
)
from app.llm.base import ProviderResponse, ProviderUsage

_GOLDEN_DIR = Path(__file__).resolve().parents[1] / "app" / "eval" / "golden"
_DATASET_PATH = _GOLDEN_DIR / "semiconductor.v1.json"
_CLAIM_DATASET_PATH = _GOLDEN_DIR / "claims.ko.v1.json"
_REPORT_FIXTURE_PATH = _GOLDEN_DIR / "report.ko.v1.4.json"
_BASELINE_PATH = _GOLDEN_DIR / "analyze.ko.v5.baseline.json"


def eval_settings() -> Settings:
    return Settings(
        AGENT_MOCK=True,
        AGENT_EVIDENCE_GROUNDED_OVERLAP=0.6,
        AGENT_EVIDENCE_WEAK_OVERLAP=0.2,
        AGENT_MAX_SENTENCES=200,
        AGENT_SCHEMA_REPAIR_ATTEMPTS=1,
        AGENT_MAX_OUTPUT_TOKENS=4096,
        AGENT_REPORT_MAX_OUTPUT_TOKENS=8192,
    )


def replay_result(
    dataset: GoldenDataset | None = None,
    claim_dataset: GoldenClaimDataset | None = None,
):
    return run_evaluation(
        dataset or load_dataset(_DATASET_PATH),
        settings=eval_settings(),
        claim_dataset=claim_dataset or load_claim_dataset(_CLAIM_DATASET_PATH),
        report_fixture=load_report_fixture(_REPORT_FIXTURE_PATH),
    )


def test_replay_golden_eval_keeps_quality_and_validates_perspective_fixture() -> None:
    result = replay_result()
    baseline = json.loads(_BASELINE_PATH.read_text(encoding="utf-8"))

    assert result.errors == ()
    legacy_metrics = {
        key: value
        for key, value in result.metrics.items()
        if not key.startswith("perspectiveTag")
    }
    baseline_legacy_metrics = {
        key: value
        for key, value in baseline["metrics"].items()
        if not key.startswith("perspectiveTag")
    }

    assert legacy_metrics == baseline_legacy_metrics
    assert result.metrics["perspectiveTagChecks"] == 96
    assert result.metrics["perspectiveTagCorrectCount"] == 96
    assert result.metrics["perspectiveTagAccuracy"] == 1.0
    assert result.metrics["summaryLengthP50"] == 39
    assert result.metrics["summaryLengthP95"] == 45
    assert result.metrics["summaryLengthMax"] == 68
    assert result.metrics["highSensitivityEvidenceRate"] == 1.0
    assert result.claim_control_diagnostics == baseline["claimControlDiagnostics"]
    assert compare_results(result.to_dict(), baseline)["regressions"] == []


def test_adversarial_cases_have_expected_failure_labels() -> None:
    dataset = load_dataset(_DATASET_PATH)
    expected = {
        case.case_id: case.expected_failures for case in dataset.cases if case.expected_failures
    }

    result = replay_result(dataset)

    assert expected == {
        "export-control-ko": ["grounding"],
        "nand-price-ko": ["grounding"],
        "gan-charger-ko": ["grounding"],
        "riscv-injection-ko": ["korean-summary"],
    }
    assert result.metrics["groundedBulletCount"] == 21
    assert result.metrics["koreanSummaryPasses"] == 23
    assert result.metrics["evidenceVerificationCount"] == 21
    assert result.metrics["evidenceRuleDecisionCount"] == 11
    assert result.metrics["evidenceProviderCallCount"] == 10
    assert result.metrics["evidenceProviderCallReductionRate"] == 0.52381
    assert result.metrics["perspectiveTagAccuracy"] == 1.0
    assert result.errors == ()


def test_claim_controls_exercise_decisive_rules_and_provider_routes() -> None:
    dataset = load_claim_dataset(_CLAIM_DATASET_PATH)
    scores = score_claim_controls(dataset.controls, grounded_overlap=0.6)
    invalid_statuses = {
        score.claim_id: score.status
        for score in scores
        if score.validity == "invalid"
    }
    positive_statuses = [
        score.status for score in scores if score.validity == "valid"
    ]

    assert invalid_statuses == {
        "number-percent-invalid": "ungrounded",
        "number-year-invalid": "ungrounded",
        "number-quarter-growth-invalid": "ungrounded",
        "polarity-micron-supply-invalid": "ungrounded",
        "polarity-samsung-fab-invalid": "ungrounded",
        "polarity-intel-service-invalid": "ungrounded",
        "company-hbm4-pilot-invalid": "ungrounded",
        "company-euv-shipment-invalid": "ungrounded",
        "company-two-nanometer-invalid": "ungrounded",
        "modality-investment-decision-invalid": "ungrounded",
        "modality-expansion-start-invalid": "ungrounded",
        "modality-center-completion-invalid": "ungrounded",
        "unsupported-customer-cause-invalid": "provider-required",
        "unsupported-yield-purpose-invalid": "provider-required",
        "unsupported-order-cause-invalid": "provider-required",
    }
    assert set(positive_statuses) == {"grounded", "provider-required"}
    assert "ungrounded" not in positive_statuses

    multi_sentence_types = {
        control.failure_type for control in dataset.controls if len(control.evidence) > 1
    }
    assert multi_sentence_types == {
        "number-mismatch",
        "polarity-inversion",
        "company-substitution",
        "modality-overreach",
        "unsupported-claim",
    }
    for control in dataset.controls:
        valid = next(label for label in control.labels if label.validity == "valid")
        assert valid.claim not in {sentence.text for sentence in control.evidence}


def test_claim_control_metrics_record_modality_improvement_without_false_rejects() -> None:
    result = replay_result()

    assert result.metrics["invalidClaimCount"] == 15
    assert result.metrics["falsePassCount"] == 0
    assert result.metrics["falsePassRate"] == 0
    assert result.metrics["positiveControlCount"] == 15
    assert result.metrics["falseRejectCount"] == 0
    assert result.metrics["claimControlProviderRequiredCount"] == 9
    assert result.claim_control_diagnostics["falsePassClaimIds"] == []
    assert result.claim_control_diagnostics["falseRejectClaimIds"] == []


def test_claim_control_counts_reject_empty_denominators() -> None:
    with pytest.raises(ValueError, match="invalid/valid 분모"):
        ClaimControlCounts.from_scores([])


def test_claim_control_counts_treat_weak_invalid_as_false_pass() -> None:
    counts = ClaimControlCounts.from_scores(
        [
            ClaimControlScore(
                claim_id="invalid-weak",
                validity="invalid",
                status="weak",
            ),
            ClaimControlScore(
                claim_id="valid-provider",
                validity="valid",
                status="provider-required",
            ),
        ]
    )

    assert counts.to_dict()["falsePassCount"] == 1
    assert counts.to_dict()["falseRejectCount"] == 0


def test_claim_control_schema_requires_invalid_and_valid_pair() -> None:
    payload = json.loads(
        load_claim_dataset(_CLAIM_DATASET_PATH).model_dump_json(by_alias=True)
    )
    payload["controls"][0]["labels"][1]["validity"] = "invalid"

    with pytest.raises(ValueError, match="invalid/valid"):
        GoldenClaimDataset.model_validate(payload)


def test_claim_control_schema_requires_three_pairs_per_failure_type() -> None:
    payload = json.loads(
        load_claim_dataset(_CLAIM_DATASET_PATH).model_dump_json(by_alias=True)
    )
    payload["controls"][0]["failureType"] = "unsupported-claim"

    with pytest.raises(ValueError, match="유형별 3쌍"):
        GoldenClaimDataset.model_validate(payload)


def test_claim_control_schema_rejects_duplicate_claim_text() -> None:
    payload = json.loads(
        load_claim_dataset(_CLAIM_DATASET_PATH).model_dump_json(by_alias=True)
    )
    payload["controls"][1]["labels"][1]["claim"] = payload["controls"][0][
        "labels"
    ][1]["claim"]

    with pytest.raises(ValueError, match="claim 문장"):
        GoldenClaimDataset.model_validate(payload)


def test_claim_controls_are_separate_from_checkpointed_article_dataset() -> None:
    article_payload = load_dataset(_DATASET_PATH).model_dump(by_alias=True)

    assert "claimLabelsVersion" not in article_payload
    assert "claimControls" not in article_payload


def test_expected_failure_detects_rule_that_becomes_too_permissive() -> None:
    payload = json.loads(load_dataset(_DATASET_PATH).model_dump_json(by_alias=True))
    nand = next(case for case in payload["cases"] if case["caseId"] == "nand-price-ko")
    nand["replay"]["sections"][0]["bullets"][0]["text"] = (
        "기업용 NAND 분기 계약가격이 전분기 대비 8% 상승했다."
    )

    result = replay_result(GoldenDataset.model_validate(payload))

    assert any(
        error.check == "nand-price-ko" and error.code == "EXPECTED_OUTCOME_MISMATCH"
        for error in result.errors
    )


def test_schema_violation_is_counted_without_stopping_remaining_cases() -> None:
    payload = json.loads(load_dataset(_DATASET_PATH).model_dump_json(by_alias=True))
    del payload["cases"][0]["replay"]["classification"]["category"]

    result = replay_result(GoldenDataset.model_validate(payload))

    assert result.metrics["schemaPasses"] == 24
    assert result.metrics["schemaPassRate"] == 0.96
    assert any(error.check == "hbm4-pilot-ko" for error in result.errors)


def test_expected_replay_schema_failure_can_complete() -> None:
    payload = json.loads(load_dataset(_DATASET_PATH).model_dump_json(by_alias=True))
    expected_failure = payload["cases"][-1]
    expected_failure["expectedFailures"] = ["schema"]
    del expected_failure["replay"]["classification"]["category"]

    result = replay_result(GoldenDataset.model_validate(payload))

    assert result.complete is True
    assert result.metrics["schemaPasses"] == 24
    assert result.errors == ()


def test_report_check_is_not_counted_when_all_analyses_fail() -> None:
    payload = json.loads(load_dataset(_DATASET_PATH).model_dump_json(by_alias=True))
    for case in payload["cases"]:
        case["replay"]["sections"][0]["bullets"][0]["evidenceSentenceIds"] = [999]

    result = replay_result(GoldenDataset.model_validate(payload))

    assert result.metrics["schemaPasses"] == 0
    assert result.metrics["schemaPassRate"] == 0.0
    assert any(
        error.check == "report" and error.code == "NO_REPORT_INPUT" for error in result.errors
    )


def test_report_replay_is_validated_by_the_service_contract() -> None:
    fixture = load_report_fixture(_REPORT_FIXTURE_PATH)
    payload = json.loads(fixture.model_dump_json(by_alias=True))
    del payload["output"]["title"]

    result = run_evaluation(
        load_dataset(_DATASET_PATH),
        settings=eval_settings(),
        report_fixture=GoldenReportFixture.model_validate(payload),
    )

    assert result.metrics["schemaPasses"] == 24
    assert any(
        error.check == "report" and error.code == "SCHEMA_VIOLATION" for error in result.errors
    )


def test_report_final_validation_removes_unsupported_claims() -> None:
    result = replay_result()

    assert result.metrics["reportClaimCount"] == 9
    assert result.metrics["reportGroundedClaimCount"] == 8
    assert result.metrics["reportWeakClaimCount"] == 1
    assert result.metrics["unsupportedReportClaimCount"] == 0


def test_executive_summary_supports_independent_clauses_without_fact_laundering() -> None:
    fixture = load_report_fixture(_REPORT_FIXTURE_PATH)
    payload = json.loads(fixture.model_dump_json(by_alias=True))
    payload["output"]["executiveSummary"][0] = (
        "삼성전자는 2026년 4분기에 HBM4 파일럿 생산을 확대하고, "
        "SK하이닉스는 클라우드 고객과 3년 HBM 공급 계약을 맺었다."
    )

    result = run_evaluation(
        load_dataset(_DATASET_PATH),
        settings=eval_settings(),
        report_fixture=GoldenReportFixture.model_validate(payload),
    )

    assert result.errors == ()
    assert result.metrics["reportGroundedClaimCount"] == 8
    assert result.metrics["unsupportedReportClaimCount"] == 0


def test_replay_provider_is_not_labeled_as_real_traffic() -> None:
    response = ReplayProvider({"value": "fixture"}).generate(
        system_instruction="ignored",
        prompt="ignored",
        response_schema={},
    )

    assert response.provider == "mock"
    assert response.model == "golden-replay"


@pytest.mark.parametrize(
    ("summary", "expected"),
    [
        ("HBM4 양산 일정이 앞당겨졌다.", True),
        ("The HBM4 schedule moved forward.", False),
        ("HBM4", False),
    ],
)
def test_korean_summary_gate(summary: str, expected: bool) -> None:
    assert korean_summary_pass(summary) is expected


def test_metric_comparison_gates_quality_and_coverage() -> None:
    baseline = json.loads(_BASELINE_PATH.read_text(encoding="utf-8"))["metrics"]
    current = deepcopy(baseline)
    current["groundedRate"] = float(baseline["groundedRate"]) + 0.01
    current["bulletCount"] = int(baseline["bulletCount"]) - 1
    current["summaryLengthP95"] = int(baseline["summaryLengthP95"]) + 1
    current["unsupportedReportClaimCount"] = int(baseline["unsupportedReportClaimCount"]) + 1

    comparison = compare_metrics(current, baseline)

    assert comparison["regressions"] == [
        "bulletCount",
        "summaryLengthP95",
        "unsupportedReportClaimCount",
    ]


def test_metric_comparison_reports_missing_baseline_keys() -> None:
    metrics = json.loads(_BASELINE_PATH.read_text(encoding="utf-8"))["metrics"]

    with pytest.raises(ComparisonError, match="baseline metrics missing"):
        compare_metrics(metrics, {"schemaPassRate": 1.0})


def test_result_comparison_rejects_incompatible_metadata() -> None:
    baseline = json.loads(_BASELINE_PATH.read_text(encoding="utf-8"))
    incompatible = deepcopy(baseline)
    incompatible["datasetVersion"] = "other.v9"

    with pytest.raises(ComparisonError, match="datasetVersion"):
        compare_results(baseline, incompatible)

    changed_claim_labels = deepcopy(baseline)
    changed_claim_labels["claimLabelsVersion"] = "claims.ko.v2"
    with pytest.raises(ComparisonError, match="claimLabelsVersion"):
        compare_results(changed_claim_labels, baseline)

    changed_config = deepcopy(baseline)
    changed_config["config"]["maxSentences"] = 50
    with pytest.raises(ComparisonError, match="runtime config"):
        compare_results(changed_config, baseline)

    changed_prompt = deepcopy(baseline)
    changed_prompt["analyzePromptVersion"] = "analyze.ko.v3"
    with pytest.raises(ComparisonError, match="prompt version mismatch"):
        compare_results(changed_prompt, baseline)
    assert (
        compare_results(
            changed_prompt,
            baseline,
            allow_prompt_version_change=True,
        )["regressions"]
        == []
    )

    incomplete = deepcopy(baseline)
    incomplete["complete"] = False
    with pytest.raises(ComparisonError, match="incomplete"):
        compare_results(incomplete, baseline)


def test_main_returns_failure_for_regression_and_writes_result(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _set_eval_environment(monkeypatch)
    baseline = json.loads(_BASELINE_PATH.read_text(encoding="utf-8"))
    baseline["metrics"]["groundedRate"] = 1.0
    regressed_baseline = tmp_path / "regressed-baseline.json"
    regressed_baseline.write_text(json.dumps(baseline, ensure_ascii=False), encoding="utf-8")
    output = tmp_path / "result.json"

    exit_code = main(
        [
            "--profile",
            "replay",
            "--compare",
            str(regressed_baseline),
            "--output",
            str(output),
        ]
    )

    assert exit_code == 1
    assert json.loads(output.read_text(encoding="utf-8"))["comparison"]["regressions"] == [
        "groundedRate"
    ]


def test_main_rejects_prompt_version_override_for_replay(
    capsys: pytest.CaptureFixture[str],
) -> None:
    exit_code = main(["--profile", "replay", "--allow-prompt-version-change"])

    captured = capsys.readouterr()
    assert exit_code == 2
    assert "prompt version override is live-only" in captured.err


def test_main_returns_clear_error_for_invalid_baseline(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    _set_eval_environment(monkeypatch)
    invalid = tmp_path / "invalid-baseline.json"
    invalid.write_text(
        '{"complete": true, "errors": [], "metrics": {}}',
        encoding="utf-8",
    )

    exit_code = main(["--profile", "replay", "--compare", str(invalid)])

    captured = capsys.readouterr()
    assert exit_code == 2
    assert "baseline comparison error" in captured.err
    assert "datasetVersion" in captured.err


def test_main_rejects_normalized_output_checkpoint_collision(
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
) -> None:
    checkpoint = tmp_path / "live.checkpoint.json"
    equivalent_output = tmp_path / "nested" / ".." / "live.checkpoint.json"

    exit_code = main(
        [
            "--profile",
            "live",
            "--checkpoint",
            str(checkpoint),
            "--output",
            str(equivalent_output),
        ]
    )

    assert exit_code == 2
    assert "must use different paths" in capsys.readouterr().err


def test_live_eval_resumes_successful_analyses_from_checkpoint(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    dataset = load_dataset(_DATASET_PATH)
    report_fixture = load_report_fixture(_REPORT_FIXTURE_PATH)
    checkpoint = tmp_path / "live.checkpoint.json"
    policy = LiveProviderPolicy(
        request_interval_seconds=0,
        rate_limit_retry_attempts=0,
    )
    settings = eval_settings().model_copy(
        update={"gemini_api_key": "test-key", "gemini_model": "configured-gemini"}
    )
    first_analysis = JsonSequenceProvider(
        [case.replay for case in dataset.cases],
        fail_after=6,
    )
    unused_report = JsonSequenceProvider([report_fixture.output])
    monkeypatch.setattr(
        eval_runner,
        "_live_providers",
        lambda *_: (first_analysis, unused_report),
    )

    first = run_evaluation(
        dataset,
        profile="live",
        settings=settings,
        live_policy=policy,
        checkpoint_path=checkpoint,
    )

    assert first.complete is False
    assert first.metrics["schemaPasses"] == 6
    assert first_analysis.call_count == 7
    assert any(error.code == "INCOMPLETE_ANALYSIS" for error in first.errors)
    saved = json.loads(checkpoint.read_text(encoding="utf-8"))
    assert len(saved["analyses"]) == 6
    assert saved["report"] is None

    incompatible_settings = settings.model_copy(update={"max_sentences": 199})
    with pytest.raises(CheckpointError, match="config"):
        run_evaluation(
            dataset,
            profile="live",
            settings=incompatible_settings,
            live_policy=policy,
            checkpoint_path=checkpoint,
            resume=True,
        )

    remaining_analysis = JsonSequenceProvider(
        [case.replay for case in dataset.cases[6:]],
    )
    report_provider = JsonSequenceProvider([report_fixture.output])
    monkeypatch.setattr(
        eval_runner,
        "_live_providers",
        lambda *_: (remaining_analysis, report_provider),
    )

    resumed = run_evaluation(
        dataset,
        profile="live",
        settings=settings,
        live_policy=LiveProviderPolicy(
            request_interval_seconds=30,
            rate_limit_retry_attempts=0,
        ),
        checkpoint_path=checkpoint,
        resume=True,
    )

    assert resumed.complete is True
    assert resumed.errors == ()
    assert resumed.metrics["schemaPasses"] == 25
    assert remaining_analysis.call_count == 18
    assert report_provider.call_count == 1
    assert resumed.config.to_dict()["livePolicy"]["requestIntervalSeconds"] == 30
    saved = json.loads(checkpoint.read_text(encoding="utf-8"))
    assert len(saved["analyses"]) == 24
    assert saved["report"] is not None
    assert saved["config"]["livePolicy"]["requestIntervalSeconds"] == 30


def test_dataset_requires_twenty_to_thirty_unique_articles() -> None:
    payload = json.loads(load_dataset(_DATASET_PATH).model_dump_json(by_alias=True))
    payload["cases"] = payload["cases"][:19]

    with pytest.raises(ValueError):
        GoldenDataset.model_validate(payload)


def _set_eval_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    values = {
        "AGENT_MOCK": "1",
        "AGENT_EVIDENCE_GROUNDED_OVERLAP": "0.6",
        "AGENT_EVIDENCE_WEAK_OVERLAP": "0.2",
        "AGENT_MAX_SENTENCES": "200",
        "AGENT_SCHEMA_REPAIR_ATTEMPTS": "1",
        "AGENT_MAX_OUTPUT_TOKENS": "4096",
        "AGENT_REPORT_MAX_OUTPUT_TOKENS": "8192",
    }
    for key, value in values.items():
        monkeypatch.setenv(key, value)


class JsonSequenceProvider:
    def __init__(
        self,
        payloads: list[dict[str, object]],
        *,
        fail_after: int | None = None,
    ) -> None:
        self._payloads = iter(payloads)
        self._fail_after = fail_after
        self.call_count = 0

    def generate(self, **_: object) -> ProviderResponse:
        self.call_count += 1
        if self._fail_after is not None and self.call_count > self._fail_after:
            raise AgentError(
                status_code=503,
                code="PROVIDER_UNAVAILABLE",
                message="rate limited",
                details={"rateLimited": True, "providerStatusCode": 429},
            )
        return ProviderResponse(
            text=json.dumps(next(self._payloads), ensure_ascii=False),
            provider="gemini",
            model="configured-gemini",
            usage=ProviderUsage(),
        )
