"""An offline judge cannot exceed a reservation or promote uncertainty to a pass."""

import json
from copy import deepcopy
from decimal import Decimal

import pytest

from app.eval.report_insight_run import EvaluationStopped, digest
from app.eval.report_insight_semantic_cases import pilot_packet
from app.eval.report_insight_semantic_judge import (
    JudgePolicy,
    merge_runs,
    prepare,
    rebind_saved_run,
    run,
    saved_annotations,
    shadow_observation,
)
from app.llm.base import ProviderResponse, ProviderUsage


def policy(**overrides):
    return {
        "model": "gpt-5-mini",
        "prices_usd_per_million": ["0.25", "0.025", "2.00"],
        "max_calls": 10,
        "max_estimated_usd": "1",
        "authorization_reference": "unit-test-no-real-provider",
        **overrides,
    }


class Provider:
    def __init__(self, mutate=None, verdicts=None, confidences=None):
        self.calls = []
        self.mutate = mutate
        self.verdicts = verdicts or {}
        self.confidences = confidences or {}
        self.closed = 0

    def generate(self, *, system_instruction, prompt, response_schema):
        self.calls.append(json.loads(prompt))
        judgments = []
        for unit in self.calls[-1]["units"]:
            source = unit["source"][0]
            sentence = source["sentences"][0]
            verdict = self.verdicts.get(unit["unitId"], "INSUFFICIENT")
            judgments.append(
                {
                    "unit_id": unit["unitId"],
                    "verdict": verdict,
                    "confidence": self.confidences.get(unit["unitId"], 90),
                    "rationale": "Synthetic provider fixture, not measured model semantics.",
                    "anchors": []
                    if verdict == "INSUFFICIENT"
                    else [
                        {
                            "claim_id": source["claimId"],
                            "sentence_id": sentence["sentenceId"],
                            "start": 0,
                            "end": len(sentence["text"]),
                            "quote": sentence["text"],
                        }
                    ],
                }
            )
        output = {"judgments": judgments}
        if self.mutate:
            self.mutate(output)
        return ProviderResponse(
            text=json.dumps(output, ensure_ascii=False),
            provider="openai",
            model="gpt-5-mini",
            usage=ProviderUsage(input_tokens=200, output_tokens=200, cost_usd=Decimal("0.00045")),
        )

    def close(self):
        self.closed += 1


def execute(tmp_path, *, packet=None, provider=None, **overrides):
    packet = packet or pilot_packet()
    provider = provider or Provider()
    manifest = prepare(packet, policy(**overrides), tmp_path)
    result = run(tmp_path, provider_factory=lambda policy, deadline: provider)
    return packet, manifest, result, provider


def test_batched_judge_is_bound_shadow_and_persists_charges(tmp_path):
    packet, manifest, result, provider = execute(tmp_path)
    assert result["status"] == "completed"
    assert len(provider.calls) == provider.closed == 3
    assert len(result["judgments"]) == 12
    assert result["cumulativeCalls"] == 3
    assert result["cumulativeChargedOrReservedUsd"] == "0.00135"
    assert result["automaticReleaseDecision"] is None
    assert manifest["humanAnnotationsUsed"] is False
    assert saved_annotations(manifest, result)["packet_hash"] == packet["packetHash"]
    assert saved_annotations(manifest, result)["provenance"]["origin"] == "model_generated"
    for call in provider.calls:
        assert "human" not in json.dumps(call).lower()
    assert all(not shadow_observation(row)["automaticPass"] for row in result["judgments"])
    with pytest.raises(EvaluationStopped, match="JUDGE_RUN_NOT_FRESH"):
        run(tmp_path, provider_factory=lambda policy, deadline: provider)


def test_remaining_call_budget_includes_prior_usage(tmp_path):
    _, _, result, provider = execute(tmp_path, previous_calls=9)
    assert len(provider.calls) == 1
    assert result["cumulativeCalls"] == 10
    assert result["missingUnits"] == 8
    assert result["stopCode"] == "JUDGE_CALL_LIMIT"


def test_reservation_prevents_call_that_might_exceed_remaining_cost(tmp_path):
    _, _, result, provider = execute(tmp_path, previous_estimated_usd="0.999")
    assert provider.calls == []
    assert result["stopCode"] == "JUDGE_COST_LIMIT"
    assert result["cumulativeChargedOrReservedUsd"] == "0.999"


@pytest.mark.parametrize(
    "mutate,code",
    [
        (lambda out: out["judgments"].pop(), "JUDGE_UNIT_SET_CHANGED"),
        (lambda out: out["judgments"][0].update(unit_id="foreign"), "JUDGE_UNIT_SET_CHANGED"),
        (lambda out: out["judgments"][0].update(anchors=[]), "SOURCE_ANCHOR_REQUIRED"),
        (
            lambda out: out["judgments"][0]["anchors"][0].update(quote="invented"),
            "ANCHOR_QUOTE_MISMATCH",
        ),
        (lambda out: out["judgments"][0].update(verdict="PASS"), "JUDGE_SCHEMA_INVALID"),
    ],
)
def test_invalid_judge_result_is_never_retried_or_silently_counted(tmp_path, mutate, code):
    provider = Provider(mutate=mutate, verdicts={"pilot-01": "SUPPORTED"})
    _, _, result, _ = execute(tmp_path, provider=provider)
    assert len(provider.calls) == 1
    assert not result["judgments"]
    assert result["stopCode"] == code
    assert result["cumulativeChargedOrReservedUsd"] == "0.00045"


def test_failure_retains_full_reservation_without_secret_exception_text(tmp_path):
    class Failing(Provider):
        def generate(self, **kwargs):
            raise RuntimeError("Authorization: Bearer DO-NOT-LOG-THIS")

    _, _, result, _ = execute(tmp_path, provider=Failing())
    record = result["records"][0]
    assert record["chargedOrReservedUsd"] == record["reservedUsd"]
    assert result["stopCode"] == "JUDGE_PROVIDER_FAILURE"
    assert "DO-NOT-LOG-THIS" not in json.dumps(result)


def test_checkpoint_before_provider_submission(tmp_path):
    provider = Provider()
    manifest = prepare(pilot_packet(), policy(), tmp_path)

    def factory(policy, deadline):
        persisted = json.loads((tmp_path / "result.json").read_text())
        assert persisted["inFlight"] == len(provider.calls) + 1
        assert Decimal(persisted["records"][-1]["chargedOrReservedUsd"]) > 0
        return provider

    result = run(tmp_path, provider_factory=factory)
    changed = deepcopy(result)
    changed["judgments"][0]["verdict"] = "SUPPORTED"
    with pytest.raises(EvaluationStopped, match="JUDGE_RESULT_CHANGED"):
        saved_annotations(manifest, changed)


def test_deadline_stops_before_any_provider_submission(tmp_path):
    prepare(pilot_packet(), policy(total_timeout_seconds=1), tmp_path)
    ticks = iter((0, 2))
    provider = Provider()
    result = run(
        tmp_path, provider_factory=lambda policy, deadline: provider, clock=lambda: next(ticks)
    )
    assert result["stopCode"] == "JUDGE_DEADLINE_EXCEEDED"
    assert not provider.calls


def test_source_and_prompt_tampering_stop_before_provider(tmp_path):
    prepare(pilot_packet(), policy(), tmp_path)
    packet = json.loads((tmp_path / "packet.json").read_text())
    packet["units"][0]["statement"] = "tampered"
    (tmp_path / "packet.json").write_text(json.dumps(packet))
    provider = Provider()
    with pytest.raises(EvaluationStopped, match="REVIEW_PACKET_CHANGED"):
        run(tmp_path, provider_factory=lambda policy, deadline: provider)
    assert not provider.calls


def test_unknowns_do_not_become_passes_even_at_full_confidence():
    for verdict in ("INSUFFICIENT", "CONTRADICTED", "SUPPORTED"):
        assert shadow_observation({"verdict": verdict, "confidence": 100}) == {
            "mode": "shadow",
            "verdict": verdict,
            "calibratedVerdict": None,
            "mayOverrideDeterministicValidation": False,
            "automaticPass": False,
        }


def test_pilot_has_no_stored_truth_and_no_duplicate_ids():
    packet = pilot_packet()
    assert len(packet["units"]) == 12
    assert not any("verdict" in unit for unit in packet["units"])
    assert packet["packetHash"] == digest({k: v for k, v in packet.items() if k != "packetHash"})


@pytest.mark.parametrize(
    "changes",
    [
        {"previous_calls": 10},
        {"previous_estimated_usd": "1"},
        {"prices_usd_per_million": ["0", "0.025", "2"]},
    ],
)
def test_invalid_budget_baselines_cannot_be_prepared(changes):
    with pytest.raises((EvaluationStopped, ValueError)):
        JudgePolicy.model_validate(policy(**changes))


def test_observed_cost_over_reservation_is_preserved_and_stops_run(tmp_path):
    class OverBudget(Provider):
        def generate(self, **kwargs):
            original = super().generate(**kwargs)
            return ProviderResponse(
                text=original.text,
                provider=original.provider,
                model=original.model,
                usage=ProviderUsage(input_tokens=200, output_tokens=200, cost_usd=Decimal("1.25")),
            )

    _, _, result, provider = execute(tmp_path, provider=OverBudget())
    assert len(provider.calls) == 1
    assert result["cumulativeChargedOrReservedUsd"] == "1.25"
    assert result["stopCode"] == "JUDGE_USAGE_INVALID_OR_OVER_RESERVATION"


def test_unique_quote_gets_server_offsets_without_rewriting_model_reply(tmp_path):
    provider = Provider(
        verdicts={"pilot-01": "SUPPORTED"},
        mutate=lambda out: (
            out["judgments"][0]["anchors"][0].update(start=1, end=2)
            if out["judgments"][0]["anchors"]
            else None
        ),
    )
    _, _, state, _ = execute(tmp_path, provider=provider)
    assert state["status"] == "completed"
    binding = state["records"][0]["anchorBindings"][0]
    assert binding["reportedOffsets"] == [1, 2]
    assert binding["bindingMethod"] == "unique_exact_quote"
    assert binding["boundOffsets"][0] == 0
    raw = json.loads(state["records"][0]["responseText"])
    assert raw["judgments"][0]["anchors"][0]["start"] == 1
    assert state["judgments"][0]["anchors"][0]["start"] == 0


def test_ambiguous_quote_without_correct_explicit_offsets_cannot_be_bound(tmp_path):
    packet = pilot_packet()
    packet["units"][0]["source"][0]["sentences"][0]["text"] = "20개와 20개다."
    packet["packetHash"] = digest({k: v for k, v in packet.items() if k != "packetHash"})

    def ambiguous(out):
        out["judgments"][0]["anchors"][0].update(quote="20개", start=2, end=3)

    _, _, state, _ = execute(
        tmp_path,
        packet=packet,
        provider=Provider(verdicts={"pilot-01": "SUPPORTED"}, mutate=ambiguous),
    )
    assert state["stopCode"] == "ANCHOR_QUOTE_AMBIGUOUS"
    assert not state["judgments"]


def test_offline_recovery_and_nonoverlapping_merge_preserve_budget_chain(tmp_path):
    original_dir, recovered_dir = tmp_path / "original", tmp_path / "recovered"
    packet, _, state, _ = execute(
        original_dir, max_calls=1, provider=Provider(verdicts={"pilot-01": "SUPPORTED"})
    )
    # Simulate the legacy offset-only failure, preserving its immutable raw response.
    state["judgments"] = []
    state["status"] = "stopped"
    state["stopCode"] = "ANCHOR_QUOTE_MISMATCH"
    record = state["records"][0]
    out = json.loads(record["responseText"])
    out["judgments"][0]["anchors"][0].update(start=1, end=2)
    record.update(
        status="failed",
        errorCode="ANCHOR_QUOTE_MISMATCH",
        responseText=json.dumps(out, ensure_ascii=False),
    )
    state["resultHash"] = digest({k: v for k, v in state.items() if k != "resultHash"})
    (original_dir / "result.json").write_text(json.dumps(state))
    before = (original_dir / "result.json").read_bytes()
    recovered = rebind_saved_run(original_dir, recovered_dir)
    assert (original_dir / "result.json").read_bytes() == before
    assert recovered["recovery"]["providerCalls"] == 0
    assert recovered["missingUnits"] == 8
    remaining = [u["unitId"] for u in packet["units"][4:]]
    next_dir = tmp_path / "next"
    prepare(
        packet,
        policy(previous_calls=1, previous_estimated_usd="0.00045", max_calls=3),
        next_dir,
        unit_ids=remaining,
    )
    provider = Provider()
    second = run(next_dir, provider_factory=lambda p, d: provider)
    assert len(provider.calls) == 2
    merged = merge_runs([recovered_dir, next_dir], tmp_path / "merged")
    assert merged["status"] == "merged_completed"
    assert len(merged["judgments"]) == 12
    assert merged["cumulativeCalls"] == second["cumulativeCalls"] == 3
    assert merged["cumulativeChargedOrReservedUsd"] == "0.00135"
    assert merged["mergeProviderCalls"] == 0

    # A model alias must not hide different resolved model snapshots.
    first_result = json.loads((recovered_dir / "result.json").read_text())
    second_result = json.loads((next_dir / "result.json").read_text())
    for value, directory, snapshot in (
        (first_result, recovered_dir, "snapshot-a"),
        (second_result, next_dir, "snapshot-b"),
    ):
        for record in value["records"]:
            record["resolvedModel"] = snapshot
        value["resultHash"] = digest({k: v for k, v in value.items() if k != "resultHash"})
        (directory / "result.json").write_text(json.dumps(value))
    with pytest.raises(EvaluationStopped, match="JUDGE_MERGE_MODEL_SNAPSHOT_CHANGED"):
        merge_runs([recovered_dir, next_dir], tmp_path / "different-snapshots")
