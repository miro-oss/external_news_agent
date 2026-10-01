"""Frozen v1 answers can be audited without paid calls or rewriting judgments."""

import json
from copy import deepcopy

import pytest
from test_report_insight_run import FakeSDK
from test_report_insight_run import prepared as prepared

from app.eval import report_insight_replay as replay
from app.eval import report_insight_run as runner
from app.llm.report_insight_retrieval import retrieve_report_insight_evidence
from app.llm.report_insight_service import _reduce_prompt, _validated_map_output
from app.schemas.report_insight import ReportInsightRequest


@pytest.fixture
def recorded(prepared):
    _, directory = prepared
    state = runner.run(directory, sdk_factory=FakeSDK())
    (directory / "quality-summary.json").write_text('{"originalJudgments": true}')
    return directory, state


def test_offline_replay_preserves_original_inputs_outputs_and_judgments(recorded, monkeypatch):
    directory, state = recorded
    snapshots = {path.name: path.read_bytes() for path in directory.iterdir() if path.is_file()}
    monkeypatch.delenv("OPENAI_API_KEY")
    monkeypatch.setattr(runner, "OpenAI", lambda **_: pytest.fail("A paid SDK was constructed"))
    monkeypatch.setattr(runner, "run", lambda *_: pytest.fail("Live resume was invoked"))
    report = replay.replay(directory, directory.parent / "revalidation")
    assert report["extraPaidCalls"] == 0
    assert report["qualityImprovementClaimed"] is False
    assert report["originalProvenance"] == state["provenance"]
    assert all(item["status"] == "accepted" for item in report["cases"])
    assert snapshots == {
        path.name: path.read_bytes() for path in directory.iterdir() if path.is_file()
    }


def test_changed_runtime_can_replay_but_cannot_resume_original_generation(recorded, monkeypatch):
    directory, _ = recorded
    monkeypatch.setattr(runner, "runtime_hashes", lambda: {"frozen.py": "changed"})
    monkeypatch.setattr(replay, "runtime_hashes", lambda: {"frozen.py": "changed"})
    report = replay.replay(directory, directory.parent / "revalidation")
    assert (
        report["validationRuntime"]["sourceSha256s"]
        != report["originalProvenance"]["runtimeSourceSha256s"]
    )
    with pytest.raises(runner.EvaluationStopped, match="RUNTIME_CHANGED"):
        runner.run(directory, sdk_factory=FakeSDK())


@pytest.mark.parametrize("nested", [False, True])
def test_cannot_export_over_original_bundle(recorded, nested):
    directory, _ = recorded
    target = directory / "nested" if nested else directory
    with pytest.raises(runner.EvaluationStopped, match="ORIGINAL_OUTPUT_PROTECTED"):
        replay.replay(directory, target)


def test_tampered_checkpoint_is_rejected_before_revalidation(recorded):
    directory, state = recorded
    state["attempts"][0]["providerText"] = "changed"
    (directory / "result.json").write_text(json.dumps(state))
    with pytest.raises(runner.EvaluationStopped, match="CHECKPOINT_CHANGED"):
        replay.replay(directory, directory.parent / "revalidation")


def test_rehashed_text_still_must_match_frozen_raw_response(recorded):
    directory, state = recorded
    state["attempts"][0]["providerText"] = "changed"
    runner.save(directory, state)
    with pytest.raises(runner.EvaluationStopped, match="CHECKPOINT_PROVIDER_TEXT_CHANGED"):
        replay.replay(directory, directory.parent / "revalidation")


def test_last_repair_is_used_without_cherry_picking_earlier_valid_answer(recorded):
    _, state = recorded
    result = next(item for item in state["results"] if item["variant"] == "single_call")
    first = next(item for item in state["attempts"] if item["stage"] == "SINGLE")
    last = deepcopy(first)
    last.update(attemptId=999, repairIndex=1, providerText="invalid private output")
    diagnostic = replay.replay_job(result, [first, last])
    assert diagnostic["status"] == "rejected"
    assert diagnostic["usedAttemptIds"] == [999]
    assert "private output" not in json.dumps(diagnostic)


def test_recovered_map_without_saved_reduce_is_incomplete(recorded):
    _, state = recorded
    result = next(item for item in state["results"] if item["variant"] == "staged")
    mapped = next(item for item in state["attempts"] if item["stage"] == "MAP")
    diagnostic = replay.replay_job({**result, "status": "failed"}, [mapped])
    assert diagnostic["status"] == "incomplete"
    assert diagnostic["errorCode"] == "MISSING_REDUCE_RESPONSE"
    assert diagnostic["insights"] is None


def test_reduce_evidence_cannot_be_silently_expanded(recorded):
    _, state = recorded
    result = next(item for item in state["results"] if item["variant"] == "staged")
    attempts = [deepcopy(item) for item in state["attempts"] if item["variant"] == "staged"]
    reduce = next(item for item in attempts if item["stage"] == "REDUCE")
    reduce["wireRequest"]["input"] = reduce["wireRequest"]["input"].replace(
        '"claimId": "501:0"', '"claimId": "unseen:0"'
    )
    diagnostic = replay.replay_job(result, attempts)
    assert diagnostic["status"] == "rejected"
    assert diagnostic["errorCode"] == "INVALID_RETRIEVED_REFERENCE"


def test_truncation_remains_failure_even_if_saved_prefix_parses(recorded):
    _, state = recorded
    result = next(item for item in state["results"] if item["variant"] == "single_call")
    attempt = deepcopy(next(item for item in state["attempts"] if item["stage"] == "SINGLE"))
    attempt["providerRawResponse"]["status"] = "incomplete"
    diagnostic = replay.replay_job(result, [attempt])
    assert diagnostic["status"] == "rejected"
    assert diagnostic["errorCode"] == "TRUNCATED_OUTPUT"


def test_stored_retrieval_retains_unsorted_source_ids_and_sorted_sentence_order(recorded):
    _, state = recorded
    result = next(item for item in state["results"] if item["variant"] == "staged")
    body = deepcopy(result["request"])
    finding = body["findings"][0]
    finding["sentences"].append({"index": 1, "text": finding["sentences"][0]["text"]})
    finding["claims"][0]["evidenceSentenceIds"] = [1, 0]
    request = ReportInsightRequest.model_validate(body)
    attempt = next(item for item in state["attempts"] if item["stage"] == "MAP")
    mapped = _validated_map_output(replay.provider_response(attempt), request)
    retrieved = {
        item.audience: retrieve_report_insight_evidence(request, item.audience, item.assessments)
        for item in mapped.insights
    }
    wire = {"wireRequest": {"input": _reduce_prompt(request, mapped, retrieved)}}
    allowed = replay.saved_reduce_context(wire, request, mapped)
    assert allowed["CHIP_MAKER"] == list(retrieved["CHIP_MAKER"].claim_ids)
