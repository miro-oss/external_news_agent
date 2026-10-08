"""Live comparison and offline auditing share the production grounding boundary."""

import json
from copy import deepcopy

import pytest
from test_report_insight import output, request_body
from test_report_insight_replay import recorded as recorded
from test_report_insight_run import FakeSDK, RawResponse
from test_report_insight_run import prepared as prepared

from app.eval import report_insight_replay as replay
from app.eval import report_insight_run as runner
from app.eval.report_insight_review import review_saved_output
from app.llm import report_insight_service as service
from app.llm.report_insight_retrieval import retrieve_report_insight_evidence
from app.schemas.report_insight import CLAIMLESS_ASSESSMENT_REASON, ReportInsightRequest


def claimless_case():
    body = request_body(second=True)
    body["findings"][0]["claims"][0]["text"] = "삼성전자는 2028년 CPO 양산을 완료했다."
    candidate = output(second=True)
    insight = candidate["insights"][0]
    insight["assessments"][0].update(
        reason=CLAIMLESS_ASSESSMENT_REASON,
        basisClaimIds=[],
        axes={name: None for name in ("directness", "impact", "urgency", "novelty")},
    )
    insight.update(
        headline="검증 장비 도입 조건을 확인해야 한다.",
        overview=[
            {
                "text": "검증 장비 도입 추진은 검증 준비 조건 확인과 연결된다.",
                "basisClaimIds": ["502:0"],
                "assumption": "장비 도입이 추진되는 경우",
            }
        ],
        implications=[],
        watchItems=[],
    )
    return body, candidate


def wire_input(attempt):
    text = attempt["wireRequest"]["input"]
    return json.loads(
        text.split("<report-insight-input>", 1)[1].split("</report-insight-input>")[0]
    )


def test_prepare_retains_snapshot_while_single_and_staged_send_same_eligible_findings(prepared):
    dataset, initial_directory = prepared
    body, candidate = claimless_case()
    corpus = json.loads(dataset.read_text())
    corpus["cases"][0]["request"] = body
    dataset.write_text(json.dumps(corpus, ensure_ascii=False))
    dataset_bytes = dataset.read_bytes()
    directory = initial_directory.parent / "filtered-evaluation"
    manifest = runner.prepare(dataset, directory)
    manifest_bytes = (directory / "manifest.json").read_bytes()
    assert all(job["request"]["findings"] == body["findings"] for job in manifest["jobs"])

    def handler(wire, _):
        result = deepcopy(candidate)
        title = wire["text"]["format"]["name"]
        for insight in result["insights"]:
            if "MapOutput" in title:
                for key in tuple(insight):
                    if key not in {"audience", "assessments"}:
                        del insight[key]
            elif "ReduceOutput" in title:
                del insight["assessments"]
        return RawResponse(result)

    sdk = FakeSDK(handler)
    state = runner.run(directory, sdk_factory=sdk)
    assert all(result["status"] == "success" for result in state["results"])
    assert len(sdk.calls) == 3
    source_inputs = [
        wire_input(attempt)["findings"]
        for attempt in state["attempts"]
        if attempt["stage"] in {"SINGLE", "MAP"}
    ]
    assert source_inputs[0] == source_inputs[1]
    assert source_inputs[0][0]["id"] == 501
    assert source_inputs[0][0]["claims"] == source_inputs[0][0]["sentences"] == []
    assert source_inputs[0][1] == body["findings"][1]
    assert all(result["request"]["findings"] == body["findings"] for result in state["results"])
    assert dataset.read_bytes() == dataset_bytes
    assert (directory / "manifest.json").read_bytes() == manifest_bytes
    runner.verify_recorded(directory, manifest, state, revalidate_outputs=True)


def test_historical_binding_remains_valid_when_current_filter_rejects_old_references(
    recorded, monkeypatch
):
    directory, state = recorded
    manifest = json.loads((directory / "manifest.json").read_text())
    originals = {path.name: path.read_bytes() for path in directory.iterdir() if path.is_file()}
    original_grounding = service._report_factual_mismatches

    def stricter_grounding(text, evidence, **kwargs):
        if text == "삼성전자는 2027년 CPO 양산을 계획했다.":
            return ["new-grounding-rule"]
        return original_grounding(text, evidence, **kwargs)

    monkeypatch.setattr(service, "_report_factual_mismatches", stricter_grounding)
    runner.verify_recorded(directory, manifest, state, revalidate_outputs=False)
    with pytest.raises(ValueError, match="basisClaimIds"):
        runner.verify_recorded(directory, manifest, state, revalidate_outputs=True)
    report = replay.replay(directory, directory.parent / "filtered-revalidation")
    assert all(result["status"] == "rejected" for result in report["cases"])
    assert all(result["errorCode"] == "REFERENCE_SCOPE" for result in report["cases"])
    assert originals == {
        path.name: path.read_bytes() for path in directory.iterdir() if path.is_file()
    }


def original_reduce_with_unverified_claim(recorded):
    _, state = recorded
    result = deepcopy(next(item for item in state["results"] if item["variant"] == "staged"))
    attempts = [deepcopy(item) for item in state["attempts"] if item["variant"] == "staged"]
    finding = result["request"]["findings"][0]
    finding["claims"].append(
        {
            **deepcopy(finding["claims"][0]),
            "id": "501:1",
            "text": "삼성전자는 2028년 CPO 양산을 완료했다.",
            "evidenceSentenceIds": [1],
        }
    )
    finding["sentences"].append({"index": 1, "text": finding["sentences"][0]["text"]})
    original = ReportInsightRequest.model_validate(result["request"])
    map_attempt = next(item for item in attempts if item["stage"] == "MAP")
    mapped = service._validated_map_output(replay.provider_response(map_attempt), original)
    retrieved = {
        insight.audience: retrieve_report_insight_evidence(
            original, insight.audience, insight.assessments
        )
        for insight in mapped.insights
    }
    assert "501:1" in retrieved["CHIP_MAKER"].claim_ids
    reduce_attempt = next(item for item in attempts if item["stage"] == "REDUCE")
    reduce_attempt["wireRequest"]["input"] = service._reduce_prompt(original, mapped, retrieved)
    return result, attempts, original, mapped, reduce_attempt


def test_saved_reduce_authenticates_original_evidence_before_narrowing_current_scope(
    recorded, monkeypatch
):
    result, attempts, original, mapped, reduce_attempt = original_reduce_with_unverified_claim(
        recorded
    )
    original_bytes = original.model_dump_json(by_alias=True)
    monkeypatch.setattr(
        service,
        "retrieve_report_insight_evidence",
        lambda *_: pytest.fail("Retrieval was recreated"),
    )
    allowed = replay.saved_reduce_context(reduce_attempt, original, mapped)
    assert "501:0" in allowed["CHIP_MAKER"]
    assert "501:1" not in allowed["CHIP_MAKER"]
    assert replay.replay_job(result, attempts)["status"] == "accepted"
    assert original.model_dump_json(by_alias=True) == original_bytes

    reduce_attempt["wireRequest"]["input"] = reduce_attempt["wireRequest"]["input"].replace(
        "2028년 CPO 양산을 완료했다.", "2029년 CPO 양산을 완료했다."
    )
    with pytest.raises(runner.EvaluationStopped, match="RETRIEVED_SOURCE_CHANGED"):
        replay.saved_reduce_context(reduce_attempt, original, mapped)


def test_saved_reduce_cannot_reference_a_claim_removed_by_current_filter(recorded):
    result, attempts, _, _, reduce_attempt = original_reduce_with_unverified_claim(recorded)
    candidate = json.loads(reduce_attempt["providerText"])
    candidate["insights"][0]["overview"][0]["basisClaimIds"] = ["501:1"]
    reduce_attempt["providerText"] = json.dumps(candidate, ensure_ascii=False)
    diagnostic = replay.replay_job(result, attempts)
    assert diagnostic["status"] == "rejected"
    assert diagnostic["errorCode"] == "REFERENCE_SCOPE"


def test_editorial_review_uses_filtered_evidence_and_preserves_original_request():
    body, candidate = claimless_case()
    request = ReportInsightRequest.model_validate(body)
    original_bytes = request.model_dump_json(by_alias=True)
    assert review_saved_output(request, candidate)["contractPassed"] is True
    assert review_saved_output(request, output(second=True))["contractPassed"] is False
    assert request.model_dump_json(by_alias=True) == original_bytes
