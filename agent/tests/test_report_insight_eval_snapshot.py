"""Evaluation snapshots preserve supplied learning fields independently of prompts."""

import json
import runpy
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
    if mode in {"empty", "topics", "learning"}:
        payload["findings"][0]["topicIds"] = [] if mode == "empty" else [7]
    if mode == "empty":
        payload["feedbackExamples"] = []
    elif mode == "learning":
        payload["feedbackExamples"] = [{
            "feedbackId": 91,
            "topicId": 7,
            "category": "SUMMARY_ERROR",
            "eventTitle": "과거 양산 일정",
            "eventSummary": "목표를 완료 사실로 표현했다.",
            "diagnosis": "계획과 완료를 구별해야 한다.",
            "evidence": [{"articleId": 9001, "quote": "양산은 다음 해의 목표라고 밝혔다."}],
        }]
    return payload


@pytest.mark.parametrize("variant", ["single_staged", "versions"])
@pytest.mark.parametrize("mode", ["absent", "empty", "topics", "learning"])
def test_preparation_preserves_learning_field_presence_and_values(
    tmp_path, monkeypatch, variant, mode
):
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
    original = dataset.read_bytes()
    directory = tmp_path / "evaluation"
    monkeypatch.setattr(runner, "runtime_hashes", lambda: {"frozen.py": "original"})
    if variant == "versions":
        baseline = tmp_path / "baseline-agent"

        def runtime_info(root):
            side = "baseline" if Path(root).resolve() == baseline.resolve() else "candidate"
            return {
                "runtimeRoot": str(Path(root).resolve()),
                "runtimeSourceSha256s": {"service.py": side},
                "dependencyVersions": {"openai": "3.7.0"},
                "pythonVersion": "3.13.5",
                "promptVersion": comparison.POLICY[f"{side}PromptVersion"],
            }

        monkeypatch.setattr(comparison, "_runtime_info", runtime_info)
        manifest = comparison.prepare(dataset, directory, baseline)
    else:
        manifest = runner.prepare(dataset, directory)
    state = json.loads((directory / "result.json").read_text())
    expected = {key: value for key, value in body.items() if key != "idempotencyKey"}
    for job in [*manifest["jobs"], *state["results"]]:
        persisted = {key: value for key, value in job["request"].items() if key != "idempotencyKey"}
        assert persisted == expected
        assert job["inputSha256"] == runner.digest(job["request"])
    assert dataset.read_bytes() == original


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
