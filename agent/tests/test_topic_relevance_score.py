import copy
import json

import pytest

from app.eval import topic_relevance_score as scorer
from app.eval.topic_relevance_review import build_packet


@pytest.fixture
def inputs():
    labels = ["RELEVANT"] * 4 + ["IRRELEVANT"] * 3 + ["UNCERTAIN"]
    packet = build_packet({
        "datasetId": "synthetic.relevance.v8",
        "createdAt": "2026-09-22T00:00:00Z",
        "cases": [{
            "caseId": f"case-{index}",
            "topic": {"id": index % 2 + 1, "name": "반도체", "queryText": "HBM",
                      "requiredKeywords": [], "optionalKeywords": [], "excludedKeywords": []},
            "article": {"id": 100 + index, "title": f"검증 기사 {index}", "summary": None,
                        "bodyText": f"본문 근거 {index}", "publisher": "검증 매체",
                        "url": None, "publishedAt": None, "bodyTruncated": False},
        } for index in range(len(labels))],
    })
    review = {
        "schemaVersion": 1, "purpose": "topic-relevance-human-labels",
        "datasetId": packet["datasetId"], "datasetSha256": packet["datasetSha256"],
        "exportKind": "final", "exportedAt": "2026-09-22T01:00:00Z",
        "records": [{"caseId": case["caseId"], "inputSha256": case["inputSha256"],
                     "decision": label, "reason": "", "updatedAt": "2026-09-22T01:00:00Z",
                     "sourceOpened": False}
                    for case, label in zip(packet["cases"], labels, strict=True)],
    }
    manifest = {
        "datasetId": packet["datasetId"], "model": "test-model",
        "promptVersion": "topic-relevance.ko.v8", "promptSha256": "a" * 64,
        "cases": [{"caseId": case["caseId"], "articleId": case["article"]["id"],
                   "topicId": case["topic"]["id"],
                   "stratum": "natural" if index < 5 else "lexical_challenge"}
                  for index, case in enumerate(packet["cases"])],
    }
    results = {
        "datasetId": packet["datasetId"], "datasetSha256": packet["datasetSha256"],
        "model": "test-model", "promptVersion": "topic-relevance.ko.v8",
        "promptSha256": "a" * 64, "plan": "FREE", "errors": [], "batches": [],
    }
    for index, (case, label) in enumerate(zip(packet["cases"], labels, strict=True)):
        results["batches"].append({
            "batchId": index + 1, "caseIds": [case["caseId"]],
            "inputSha256s": {case["caseId"]: case["inputSha256"]}, "phase": "regression",
            "response": {
                "meta": {"mock": False, "truncated": False, "provider": "test-provider",
                         "model": "test-model", "promptVersion": "topic-relevance.ko.v8"},
                "decisions": [{"articleId": case["article"]["id"], "status": label,
                               "reason": "본문 근거를 확인했다.",
                               "evidenceQuotes": [case["article"]["bodyText"]]}],
            },
        })
    return packet, review, manifest, results


def test_planned_denominators_distinguish_failed_missing_holds_and_human_uncertainty(inputs):
    packet, review, manifest, results = inputs
    batches = results["batches"]
    batches[1]["response"]["decisions"][0]["status"] = "UNCERTAIN"
    batches[5]["response"]["decisions"][0]["status"] = "RELEVANT"
    batches[6]["response"]["decisions"][0]["status"] = "UNCERTAIN"
    batches[7]["response"]["decisions"][0]["status"] = "RELEVANT"
    failure = batches[2].copy()
    failure.pop("response")
    results["errors"] = [{**failure, "code": "PROVIDER_UNAVAILABLE"}]
    results["batches"] = [row for i, row in enumerate(batches) if i not in (2, 3)]
    before = copy.deepcopy(inputs)

    summary = scorer.evaluate(packet, review, manifest, results)

    assert inputs == before
    overall = summary["overall"]
    assert tuple(overall[key] for key in ("total", "covered", "failed", "missing")) == (8, 6, 1, 1)
    assert overall["relevantOutcomes"] == {
        "accepted": 1, "rejected": 0, "heldUncertain": 1,
        "denominator": 4, "failed": 1, "missing": 1,
    }
    assert overall["relevantRetentionAllPlanned"]["rate"] == 0.25
    assert overall["irrelevantRejectionAllPlanned"]["rate"] == pytest.approx(1 / 3)
    assert overall["irrelevantNonAcceptanceAllPlanned"]["rate"] == pytest.approx(2 / 3)
    assert overall["irrelevantPassAllPlanned"]["rate"] == pytest.approx(1 / 3)
    assert overall["irrelevantHoldAllPlanned"]["rate"] == pytest.approx(1 / 3)
    assert overall["acceptedPrecisionDecisiveHumans"]["rate"] == 0.5
    assert overall["humanUncertainModelCounts"]["RELEVANT"] == 1
    assert summary["humanUncertainCases"][0]["caseId"] == "case-7"
    assert not summary["complete"]
    assert "qualityAccepted" not in summary and "requestedGates" not in summary


def test_natural_challenge_and_topic_metrics_keep_separate_denominators(inputs):
    summary = scorer.score(*inputs)
    natural = summary["byStratum"]["natural"]
    challenge = summary["byStratum"]["lexical_challenge"]
    assert natural["total"] == 5 and challenge["total"] == 3
    assert natural["relevantRetentionAllPlanned"]["denominator"] == 4
    assert challenge["relevantRetentionAllPlanned"] == {
        "numerator": 0, "denominator": 0, "rate": None, "wilson95": None,
    }
    assert challenge["irrelevantRejectionAllPlanned"]["denominator"] == 2
    assert [row["total"] for row in summary["byTopic"]] == [4, 4]
    assert summary["byTopic"][0]["byStratum"]["natural"]["total"] == 3


@pytest.mark.parametrize("violation", [
    "duplicate_case", "duplicate_manifest", "input_hash", "dataset_hash", "manifest_article",
    "model", "prompt", "prompt_hash", "provider", "foreign_article", "duplicate_article",
    "foreign_quote", "missing_quote", "mock", "truncated", "human_input", "human_blank",
])
def test_rejects_incomparable_or_unbound_results(inputs, violation):
    packet, review, manifest, results = inputs
    batch = results["batches"][0]
    decision = batch["response"]["decisions"][0]
    if violation == "duplicate_case":
        extra = copy.deepcopy(batch)
        extra["batchId"] = 99
        results["batches"].append(extra)
    elif violation == "duplicate_manifest":
        manifest["cases"].append(copy.deepcopy(manifest["cases"][0]))
    elif violation == "input_hash":
        batch["inputSha256s"]["case-0"] = "b" * 64
    elif violation == "dataset_hash":
        results["datasetSha256"] = "b" * 64
    elif violation == "manifest_article":
        manifest["cases"][0]["articleId"] = 999
    elif violation in {"model", "prompt", "provider"}:
        key = "promptVersion" if violation == "prompt" else violation
        batch["response"]["meta"][key] = "different"
    elif violation == "prompt_hash":
        results["promptSha256"] = "b" * 64
    elif violation == "foreign_article":
        decision["articleId"] = 999
    elif violation == "duplicate_article":
        batch["response"]["decisions"].append(copy.deepcopy(decision))
    elif violation == "foreign_quote":
        decision["evidenceQuotes"] = ["본문 근거 7"]
    elif violation == "missing_quote":
        decision.update(status="UNCERTAIN", evidenceQuotes=[])
    elif violation in {"mock", "truncated"}:
        batch["response"]["meta"][violation] = True
    elif violation == "human_input":
        review["records"][0]["inputSha256"] = "b" * 64
    elif violation == "human_blank":
        review["records"][0]["decision"] = None
    with pytest.raises(ValueError):
        scorer.evaluate(packet, review, manifest, results)


def test_missing_response_decision_is_a_failed_case_and_not_uncertain(inputs):
    inputs[3]["batches"][0]["response"]["decisions"] = []
    summary = scorer.evaluate(*inputs)
    assert summary["failedCases"] == [{
        "caseId": "case-0", "inputSha256": inputs[0]["cases"][0]["inputSha256"],
        "articleId": 100, "topicId": 1, "stratum": "natural", "human": "RELEVANT",
        "model": None, "failure": {"batchId": 1, "kind": "MISSING_DECISION"},
    }]
    assert summary["overall"]["relevantRetentionAllPlanned"]["rate"] == 0.75
    assert summary["overall"]["relevantHoldAllPlanned"]["numerator"] == 0


def test_failure_cannot_duplicate_success_or_claim_a_response(inputs):
    results = inputs[3]
    results["errors"] = [copy.deepcopy(results["batches"][0])]
    with pytest.raises(ValueError, match="Duplicate batch"):
        scorer.evaluate(*inputs)
    results["batches"].pop(0)
    with pytest.raises(ValueError, match="Failed batch"):
        scorer.evaluate(*inputs)


def test_wilson_is_descriptive_and_handles_small_samples():
    interval = scorer.wilson(44, 64)
    assert interval["rate"] == 0.6875
    assert interval["wilson95"]["lower"] == pytest.approx(0.5661, abs=0.0001)
    assert interval["wilson95"]["upper"] == pytest.approx(0.7877, abs=0.0001)


def test_cli_preserves_inputs_and_requires_explicit_gate_and_new_docs_output(inputs, tmp_path,
                                                                           monkeypatch):
    docs = tmp_path / "docs"
    monkeypatch.setattr(scorer, "_DOCS_ROOT", docs)
    arguments = []
    for name, payload in zip(("packet", "review", "manifest", "results"), inputs, strict=True):
        path = tmp_path / f"{name}.json"
        path.write_text(json.dumps(payload), encoding="utf-8")
        arguments.extend([f"--{name}", str(path)])
    output = docs / "first"
    assert scorer.main([*arguments, "--output-dir", str(output)]) == 0
    summary = json.loads((output / "evaluation-summary.json").read_text())
    assert "requestedGates" not in summary
    assert set(summary["sourceFileSha256s"]) == {"packet", "review", "manifest", "results"}
    with pytest.raises(SystemExit) as caught:
        scorer.main([*arguments, "--output-dir", str(output)])
    assert caught.value.code == 2
    gated = docs / "gated"
    assert scorer.main([*arguments, "--output-dir", str(gated), "--min-retention", "0.95"]) == 1
    gate = json.loads((gated / "evaluation-summary.json").read_text())["requestedGates"]
    assert gate["metrics"]["relevantRetentionAllPlanned"]["status"] == "INCONCLUSIVE"
    with pytest.raises(SystemExit):
        scorer.main([*arguments, "--output-dir", str(tmp_path / "outside")])
    assert not (tmp_path / "outside").exists()
