"""Evaluation snapshots preserve supplied learning fields independently of prompts."""

import json
import runpy
import sys
from copy import deepcopy
from pathlib import Path

import pytest
from test_report_insight import request_body

from app.eval import report_insight_corpus as corpus_module
from app.eval import report_insight_run as runner
from app.eval import report_insight_version_compare as comparison
from app.eval.report_insight_corpus import request_snapshot
from app.schemas.report_insight import ReportInsightRequest


def source_payload(mode):
    payload = request_body()
    if mode in {"empty", "topic_empty", "topics", "learning"}:
        payload["findings"][0]["topicIds"] = [] if mode in {"empty", "topic_empty"} else [7]
    if mode in {"empty", "feedback_empty"}:
        payload["feedbackExamples"] = []
    elif mode == "learning":
        payload["feedbackExamples"] = [
            {
                "feedbackId": 91,
                "topicId": 7,
                "category": "SUMMARY_ERROR",
                "eventTitle": "과거 양산 일정",
                "eventSummary": "목표를 완료 사실로 표현했다.",
                "diagnosis": "계획과 완료를 구별해야 한다.",
                "evidence": [{"articleId": 9001, "quote": "양산은 다음 해의 목표라고 밝혔다."}],
            }
        ]
    return payload


def write_corpus(tmp_path, mode):
    body = source_payload(mode)
    corpus = json.loads(runner.DEFAULT_DATASET.read_text())
    case = deepcopy(corpus["cases"][0])
    case["request"] = body
    case["annotations"] = {
        "requiredSynthesisClaimIds": [],
        "forbiddenProse": [],
        "notUrgentFindingIds": [],
        "expectedImportanceByFinding": {},
    }
    corpus.update(cases=[case], synthetic=False)
    dataset = tmp_path / "corpus.json"
    dataset.write_text(json.dumps(corpus, ensure_ascii=False))
    return dataset, body


def runtime_info(profile, baseline):
    policy = comparison.COMPARISON_PROFILES[profile]

    def info(root):
        side = "baseline" if Path(root).resolve() == baseline.resolve() else "candidate"
        return {
            "runtimeRoot": str(Path(root).resolve()),
            "runtimeSourceSha256s": {"service.py": side},
            "dependencyVersions": {"openai": "3.7.0"},
            "pythonVersion": "3.13.5",
            "promptVersion": policy[f"{side}PromptVersion"],
            "rubricVersion": policy.get(f"{side}RubricVersion"),
        }

    return info


@pytest.mark.parametrize(
    "mode", ["absent", "empty", "feedback_empty", "topic_empty", "topics", "learning"]
)
def test_current_runtime_preparation_preserves_learning_field_presence_and_values(
    tmp_path, monkeypatch, mode
):
    dataset, body = write_corpus(tmp_path, mode)
    original = dataset.read_bytes()
    directory = tmp_path / "evaluation"
    monkeypatch.setattr(runner, "runtime_hashes", lambda: {"frozen.py": "original"})
    manifest = runner.prepare(dataset, directory)
    state = json.loads((directory / "result.json").read_text())
    expected = {key: value for key, value in body.items() if key != "idempotencyKey"}
    for job in [*manifest["jobs"], *state["results"]]:
        persisted = {key: value for key, value in job["request"].items() if key != "idempotencyKey"}
        assert persisted == expected
        assert job["inputSha256"] == runner.digest(job["request"])
    assert dataset.read_bytes() == original


@pytest.mark.parametrize("profile", tuple(comparison.COMPARISON_PROFILES))
@pytest.mark.parametrize(
    "mode", ["absent", "empty", "feedback_empty", "topic_empty", "topics", "learning"]
)
def test_frozen_profiles_admit_only_legacy_snapshots_before_runtime_probes_or_writes(
    tmp_path, monkeypatch, profile, mode
):
    dataset, body = write_corpus(tmp_path, mode)
    original = dataset.read_bytes()
    directory, baseline = tmp_path / "evaluation", tmp_path / "baseline-agent"
    if mode == "absent":
        monkeypatch.setattr(comparison, "_runtime_info", runtime_info(profile, baseline))
        manifest = comparison.prepare(dataset, directory, baseline, comparison_profile=profile)
        for job in manifest["jobs"]:
            assert job["request"]["findings"] == body["findings"]
            assert "feedbackExamples" not in job["request"]
            assert job["inputSha256"] == runner.digest(job["request"])
    else:
        monkeypatch.setattr(
            comparison, "_runtime_info", lambda _: pytest.fail("Unsupported input reached runtime")
        )
        with pytest.raises(runner.EvaluationStopped, match=comparison.FROZEN_LEARNING_ERROR):
            comparison.prepare(dataset, directory, baseline, comparison_profile=profile)
        assert not directory.exists()
    assert dataset.read_bytes() == original


@pytest.mark.parametrize("mode", ["empty", "feedback_empty", "topic_empty", "topics", "learning"])
def test_previously_prepared_unsupported_job_stops_before_worker_or_provider(
    tmp_path, monkeypatch, mode
):
    dataset, _ = write_corpus(tmp_path, mode)
    directory, baseline = tmp_path / "evaluation", tmp_path / "baseline-agent"
    monkeypatch.setattr(comparison, "_runtime_info", runtime_info("v3-v4", baseline))
    # Reproduce an artifact admitted by the old coordinator, with intact hashes.
    with monkeypatch.context() as old_admission:
        old_admission.setattr(comparison, "_validate_frozen_request", lambda _: None)
        manifest = comparison.prepare(dataset, directory, baseline)
    state = json.loads((directory / "result.json").read_text())
    before = {name: (directory / name).read_bytes() for name in ("manifest.json", "result.json")}
    with pytest.raises(runner.EvaluationStopped, match=comparison.FROZEN_LEARNING_ERROR):
        comparison.verify(directory, manifest, state)
    with pytest.raises(runner.EvaluationStopped, match=comparison.FROZEN_LEARNING_ERROR):
        comparison.generate_job(
            directory, 0, "offline-test", sdk_factory=lambda **_: pytest.fail("Provider created")
        )
    assert before == {name: (directory / name).read_bytes() for name in before}


def test_frozen_comparison_reports_how_to_evaluate_learning_inputs(tmp_path, monkeypatch, capsys):
    dataset, _ = write_corpus(tmp_path, "feedback_empty")
    directory = tmp_path / "evaluation"
    monkeypatch.setattr(
        sys,
        "argv",
        [
            str(comparison.DRIVER),
            "prepare",
            "--dataset",
            str(dataset),
            "--output-dir",
            str(directory),
            "--baseline-root",
            str(tmp_path / "baseline-agent"),
        ],
    )
    # The real CLI exits with logging disabled; restore it in this shared test process.
    with runner.silent_logging(), pytest.raises(SystemExit) as stopped:
        comparison.main()
    assert stopped.value.code == 2
    result = json.loads(capsys.readouterr().out)
    assert result["code"] == comparison.FROZEN_LEARNING_ERROR
    assert "모든 고정 버전 비교 프로필" in result["message"]
    assert "single/staged" in result["message"]
    assert not directory.exists()


def test_snapshot_keeps_legacy_canonical_defaults_without_inventing_learning_fields():
    body = source_payload("absent")
    body["report"].pop("reportEndDate")
    body["findings"][0].pop("publishedAt")
    body["findings"][0]["claims"][0].pop("attributedTo")
    request = ReportInsightRequest.model_validate(body)
    before = request.model_dump_json()

    snapshot = request_snapshot(request)

    assert snapshot["report"]["reportEndDate"] is None
    assert snapshot["findings"][0]["publishedAt"] is None
    assert snapshot["findings"][0]["claims"][0]["attributedTo"] is None
    assert "feedbackExamples" not in snapshot
    assert "topicIds" not in snapshot["findings"][0]
    assert request.model_dump_json() == before


def test_frozen_worker_driver_import_does_not_require_new_snapshot_helper(monkeypatch):
    # Historical corpus modules lack the coordinator-only helper. Execute the
    # driver afresh rather than relying on its already-imported module object.
    monkeypatch.delattr(corpus_module, "request_snapshot")
    driver = runpy.run_path(str(comparison.DRIVER), run_name="frozen_worker_import")
    assert driver["VERSIONS"] == ("baseline", "candidate")
