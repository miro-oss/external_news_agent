"""Offline replay preserves native keyed MAPs and their bounded partial repair."""

import json
from copy import deepcopy

import pytest
from pydantic_ai.profiles.openai import OpenAIJsonSchemaTransformer
from test_report_insight_repair_diagnostics import (
    StageSequenceProvider,
    partial_map_fixture,
    stage_output,
)

from app.core.config import Settings
from app.eval import report_insight_replay as replay
from app.llm.openai_contract import output_contract
from app.llm.report_insight_service import ReportInsightLegacyService as ReportInsightService
from app.llm.request_contract import report_insight_map_schema


def recorded_attempt(call, payload, attempt_id):
    contract = output_contract(call["response_schema"])
    native = deepcopy(payload)
    if contract.report_insight_keys:
        for insight in native["insights"]:
            insight["assessments"] = {
                f"finding{item['findingId']}": item for item in insight["assessments"]
            }
    return {
        "attemptId": attempt_id,
        "stage": "MAP" if contract.report_insight_keys else "REDUCE",
        "status": "success",
        "resolvedModel": "gpt-4.1-nano",
        "providerText": json.dumps(native, ensure_ascii=False),
        "providerRawResponse": {"status": "completed"},
        "wireRequest": {
            "input": call["prompt"],
            "text": {
                "format": {
                    "name": call["response_schema"]["title"],
                    "schema": OpenAIJsonSchemaTransformer(contract.schema, strict=True).walk(),
                }
            },
        },
    }


def partial_record():
    request, valid, invalid, subset = partial_map_fixture()
    provider = StageSequenceProvider(("MAP", invalid), ("MAP", subset), ("REDUCE", valid))
    generated = ReportInsightService(
        Settings(AGENT_MOCK=False, AGENT_SCHEMA_REPAIR_ATTEMPTS=1), provider
    ).generate(request)
    attempts = [
        recorded_attempt(call, stage_output(payload, stage=stage), index)
        for index, (call, payload, stage) in enumerate(
            zip(provider.calls, [invalid, subset, valid], ["MAP", "MAP", "REDUCE"], strict=True), 1
        )
    ]
    result = {
        "caseId": "partial-native",
        "variant": "staged",
        "status": "success",
        "request": request.model_dump(by_alias=True, mode="json"),
    }
    return result, attempts, generated


def test_recorded_native_map_converts_with_its_frozen_wire_without_mutating_raw():
    request, valid, _, _ = partial_map_fixture()
    call = {"response_schema": report_insight_map_schema(request), "prompt": "unused"}
    attempt = recorded_attempt(call, stage_output(valid), 1)
    snapshot = deepcopy(attempt)
    converted = replay.provider_response(attempt)
    assert json.loads(converted.text) == stage_output(valid)
    assert attempt == snapshot


def test_partial_replay_reconstructs_exact_saved_merge_without_model_calls():
    result, attempts, generated = partial_record()
    snapshot = deepcopy(attempts)
    diagnostic = replay.replay_job(result, attempts)
    assert diagnostic["status"] == "accepted"
    assert diagnostic["usedAttemptIds"] == [1, 2, 3]
    assert diagnostic["insights"] == generated.model_dump(by_alias=True, mode="json")["insights"]
    assert attempts == snapshot


@pytest.mark.parametrize(
    "change", ["missing", "extra", "wrong_id", "wrong_claim", "source", "preserved"]
)
def test_partial_replay_rejects_tampered_replacement_or_preserved_evidence(change):
    result, attempts, _ = partial_record()
    last = attempts[1]
    native = json.loads(last["providerText"])
    assessments = native["insights"][0]["assessments"]
    if change == "missing":
        assessments.clear()
    elif change == "extra":
        assessments["finding501"] = deepcopy(assessments["finding503"])
    elif change == "wrong_id":
        assessments["finding503"]["findingId"] = 501
    elif change == "wrong_claim":
        assessments["finding503"]["basisClaimIds"] = ["501:0"]
    elif change == "source":
        last["wireRequest"]["input"] = last["wireRequest"]["input"].replace(
            '"articleId": 12', '"articleId": 999'
        )
    else:
        first_native = json.loads(attempts[0]["providerText"])
        first_native["insights"][0]["assessments"]["finding501"]["reason"] = (
            "NVIDIA가 999조원 투자했다."
        )
        attempts[0]["providerText"] = json.dumps(first_native)
    last["providerText"] = json.dumps(native)
    diagnostic = replay.replay_job(result, attempts)
    assert diagnostic["status"] == "rejected"
    assert diagnostic["insights"] is None


def test_full_native_repair_remains_authoritative_over_earlier_answer():
    request, valid, invalid, _ = partial_map_fixture()
    provider = StageSequenceProvider(("MAP", valid), ("REDUCE", valid))
    ReportInsightService(Settings(AGENT_MOCK=False), provider).generate(request)
    calls = [provider.calls[0], provider.calls[0], provider.calls[1]]
    attempts = [
        recorded_attempt(call, stage_output(payload, stage=stage), index)
        for index, (call, payload, stage) in enumerate(
            zip(calls, [valid, invalid, valid], ["MAP", "MAP", "REDUCE"], strict=True), 1
        )
    ]
    result = {
        "caseId": "full-native",
        "variant": "staged",
        "status": "failed",
        "request": request.model_dump(by_alias=True, mode="json"),
    }
    diagnostic = replay.replay_job(result, attempts)
    assert diagnostic["status"] == "rejected"
    assert diagnostic["usedAttemptIds"] == [2]


@pytest.mark.parametrize("changed", ["truncated", "model"])
def test_partial_replay_never_preserves_a_truncated_or_different_model_response(changed):
    result, attempts, _ = partial_record()
    if changed == "truncated":
        attempts[0]["providerRawResponse"]["status"] = "incomplete"
    else:
        attempts[0]["resolvedModel"] = "gpt-4.1-nano-2025-04-14"
    diagnostic = replay.replay_job(result, attempts)
    assert diagnostic["status"] == "rejected"
    assert diagnostic["errorCode"] == (
        "TRUNCATED_OUTPUT" if changed == "truncated" else "PARTIAL_MAP_MODEL_CHANGED"
    )
