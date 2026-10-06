"""Review admission preserves validated work without hiding started-call failures."""

from copy import deepcopy

import pytest
from test_report_insight_assessment import framed, request
from test_report_insight_v4_pipeline import V4Provider, generate, stages

from app.core.errors import AgentError
from app.llm import report_insight_service as service


def timed_report(monkeypatch, *, map_elapsed, first_review_elapsed=None, fail_stage=None):
    # Descending IDs make an accidental sort during skipping or merging visible.
    source = request(ids=tuple(range(128, 100, -1)))
    clock = [0.0]
    monkeypatch.setattr("app.llm.report_insight_pipeline.monotonic", lambda: clock[0])
    original_select = service.select_review
    selection, mapped = [], []

    def select(full_source, draft):
        mapped.extend(deepcopy(draft.mapped.insights[0].assessments))
        chosen = original_select(full_source, draft)
        selection.append(chosen)
        return chosen

    monkeypatch.setattr(service, "select_review", select)

    def hook(stage, _occurrence, _data, value):
        if stage.startswith("MAP"):
            clock[0] = int(stage.rsplit("-", 1)[1]) * map_elapsed / 5
            for entry in value["assessments"]["CHIP_MAKER"].values():
                entry.update(impactScope="UNDETERMINED", impactBasis=None)
        elif stage == "REVIEW-001" and first_review_elapsed is not None:
            clock[0] = first_review_elapsed
        elif stage == "REDUCE-001":
            clock[0] += 5
        if stage == fail_stage:
            clock[0] = 180.0
        return value

    return source, V4Provider(source, hook=hook), clock, mapped, selection


def assert_usage(actual, calls):
    assert actual["inputTokens"] == 11 * calls
    assert actual["outputTokens"] == 7 * calls
    assert actual["costUsd"] == pytest.approx(0.003 * calls)
    assert actual["credits"] == pytest.approx(0.2 * calls)


@pytest.mark.parametrize(
    "request_seconds,map_elapsed,review_count",
    [(180, 60.0, 2), (180, 60.001, 0), (180, 119.0, 0), (120, 40.0, 2), (120, 40.001, 0)],
)
def test_review_admission_preserves_all_mandatory_map_records_and_selection(
    monkeypatch, request_seconds, map_elapsed, review_count
):
    source, provider, _clock, mapped, selection = timed_report(monkeypatch, map_elapsed=map_elapsed)
    snapshot = source.model_dump_json(by_alias=True)

    result = generate(provider, source, AGENT_REPORT_PROVIDER_TIMEOUT_SECONDS=request_seconds)

    expected = [f"MAP-{index:03d}" for index in range(1, 6)]
    expected.extend(f"REVIEW-{index:03d}" for index in range(1, review_count + 1))
    assert stages(provider) == expected + ["REDUCE-001"]
    original_ids = [finding.id for finding in source.findings]
    assert selection == [tuple(original_ids[:12])]
    assert [
        finding["id"]
        for call in provider.calls[:5]
        for finding in framed(call["prompt"])["findings"]
    ] == original_ids
    reviewed_ids = [
        finding["id"]
        for call in provider.calls[5:-1]
        for finding in framed(call["prompt"])["findings"]
    ]
    assert reviewed_ids == (original_ids[:12] if review_count else [])
    final = result.insights[0].assessments
    assert [entry.finding_id for entry in final] == original_ids
    if review_count:
        assert all(entry.axes.impact == 3 for entry in final[:12])
        assert final[12:] == mapped[12:]
    else:
        assert final == mapped
        assert all(entry.axes.directness == 3 and entry.axes.impact is None for entry in final)
    assert_usage(result.meta.model_dump(by_alias=True), 6 + review_count)
    assert source.model_dump_json(by_alias=True) == snapshot


def test_later_review_is_skipped_after_first_review_consumes_admission_headroom(monkeypatch):
    source, provider, _clock, mapped, selection = timed_report(
        monkeypatch, map_elapsed=40.0, first_review_elapsed=90.0
    )

    result = generate(provider, source, AGENT_REPORT_PROVIDER_TIMEOUT_SECONDS=180)

    assert stages(provider) == [
        "MAP-001",
        "MAP-002",
        "MAP-003",
        "MAP-004",
        "MAP-005",
        "REVIEW-001",
        "REDUCE-001",
    ]
    assert len(selection) == 1 and len(selection[0]) == 12
    final = result.insights[0].assessments
    assert [entry.finding_id for entry in final] == [finding.id for finding in source.findings]
    assert all(entry.axes.impact == 3 for entry in final[:6])
    assert final[6:] == mapped[6:]
    assert_usage(result.meta.model_dump(by_alias=True), 7)


def test_review_draft_and_repair_share_one_deadline_and_restore_request_deadline(monkeypatch):
    source, provider, clock, _mapped, _selection = timed_report(monkeypatch, map_elapsed=40.0)
    instances, review_deadlines = [], []
    pipeline_class = service.ReportInsightPipelineProvider
    original_hook = provider.hook

    def capture_pipeline(*args, **kwargs):
        pipeline = pipeline_class(*args, **kwargs)
        instances.append(pipeline)
        return pipeline

    def consume_time(stage, occurrence, data, value):
        value = original_hook(stage, occurrence, data, value)
        if stage == "REVIEW-001":
            review_deadlines.append(instances[0].deadline)
            # The initial draft consumes 40 seconds. Its repair consumes 21
            # more, exceeding their shared 60-second window before REDUCE.
            clock[0] = 80.0 if occurrence == 1 else 101.0
        return value

    def malformed_first_review(stage, occurrence, _data, raw):
        return "{" if stage == "REVIEW-001" and occurrence == 1 else raw

    monkeypatch.setattr(service, "ReportInsightPipelineProvider", capture_pipeline)
    provider.hook = consume_time
    provider.raw_hook = malformed_first_review

    with pytest.raises(AgentError) as caught:
        generate(provider, source, AGENT_REPORT_PROVIDER_TIMEOUT_SECONDS=180)

    assert caught.value.code == "PROVIDER_UNAVAILABLE"
    assert caught.value.details["requestDeadlineExceeded"] is True
    assert stages(provider) == [f"MAP-{index:03d}" for index in range(1, 6)] + [
        "REVIEW-001",
        "REVIEW-001",
    ]
    assert review_deadlines == [100.0, 100.0]
    assert len(instances) == 1 and instances[0].deadline == 180.0
    assert_usage(caught.value.details["usage"], 7)


@pytest.mark.parametrize("expired_at", ["REVIEW-001", "REDUCE-001", "REDUCE_VALIDATION"])
def test_started_call_and_final_validation_deadlines_still_fail_with_cumulative_usage(
    monkeypatch, expired_at
):
    source, provider, clock, _mapped, _selection = timed_report(
        monkeypatch,
        map_elapsed=10.0 if expired_at == "REVIEW-001" else 119.0,
        fail_stage=expired_at,
    )
    if expired_at == "REDUCE_VALIDATION":
        original_validate = service._validated_v4_reduce_output

        def expire_after_validation(*args, **kwargs):
            result = original_validate(*args, **kwargs)
            clock[0] = 180.0
            return result

        monkeypatch.setattr(service, "_validated_v4_reduce_output", expire_after_validation)

    with pytest.raises(AgentError) as caught:
        generate(provider, source, AGENT_REPORT_PROVIDER_TIMEOUT_SECONDS=180)

    assert caught.value.code == "PROVIDER_UNAVAILABLE"
    assert caught.value.details["requestDeadlineExceeded"] is True
    expected_last = "REVIEW-001" if expired_at == "REVIEW-001" else "REDUCE-001"
    assert stages(provider) == [f"MAP-{index:03d}" for index in range(1, 6)] + [expected_last]
    assert_usage(caught.value.details["usage"], 6)
    assert caught.value.details["executionMetadata"]["usageCompleteness"] == "COMPLETE"
