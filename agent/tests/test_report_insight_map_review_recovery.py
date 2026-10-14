"""Only a current, native-valid MAP prose failure may require fresh REVIEW."""

import pytest
from test_report_insight_assessment import framed, request
from test_report_insight_review_time_admission import assert_usage
from test_report_insight_v4_pipeline import V4Provider, generate, stages

from app.core.errors import AgentError
from app.core.parser import JsonObjectParseError
from app.llm import report_insight_service as service

BAD_REASON = "생산 제약으로 9999개의 공정 검증 준비가 필요하다."
NORMAL_REASON = "알려진 생산 제약에 맞춰 공정 검증 준비의 영향을 확인한다."


def fail_first_finding(stage, _occurrence, _data, value):
    if stage.startswith("MAP"):
        records = value["assessments"]["CHIP_MAKER"]
        if "finding101" in records:
            records["finding101"]["reason"] = BAD_REASON
    return value


def test_failed_map_finding_is_freshly_reviewed_for_all_audiences_then_fully_validated(
    monkeypatch,
):
    source = request(ids=(102, 101, 103), audiences=("CHIP_MAKER", "EQUIPMENT_MAKER"))
    snapshot = source.model_dump_json(by_alias=True)
    full_validations = []
    original_validate = service._validated_map_output

    def capture_full(response, validation_source, **kwargs):
        result = original_validate(response, validation_source, **kwargs)
        if len(validation_source.findings) == 3:
            full_validations.append(result)
        return result

    monkeypatch.setattr(service, "_validated_map_output", capture_full)
    monkeypatch.setattr(service, "select_review", lambda *_: (101, 102, 103))
    monkeypatch.setattr(service, "MAX_REVIEW_FINDINGS", 2)
    provider = V4Provider(source, hook=fail_first_finding)

    result = generate(provider, source)

    assert stages(provider) == ["MAP-001", "MAP-001", "REVIEW-001", "REVIEW-002", "REDUCE-001"]
    mandatory = framed(provider.calls[2]["prompt"])
    optional = framed(provider.calls[3]["prompt"])
    assert [finding["id"] for finding in mandatory["findings"]] == [101]
    assert mandatory["audiences"] == source.audiences
    assert [finding["id"] for finding in optional["findings"]] == [102]
    assert BAD_REASON not in provider.calls[2]["prompt"]
    assert "validation-errors" not in provider.calls[2]["prompt"]
    assert source.findings[1].sentences[0].text in provider.calls[2]["prompt"]
    assert full_validations
    for insight in result.insights:
        assert [entry.finding_id for entry in insight.assessments] == [102, 101, 103]
        assert all(NORMAL_REASON in entry.reason for entry in insight.assessments)
    assert source.model_dump_json(by_alias=True) == snapshot
    assert_usage(result.meta.model_dump(by_alias=True), 5)


def test_mandatory_review_failure_cannot_retain_invalid_map_or_reach_reduce():
    source = request(ids=(101, 102))

    def keep_invalid(stage, occurrence, data, value):
        return fail_first_finding("MAP-001", occurrence, data, value)

    provider = V4Provider(source, hook=keep_invalid)
    with pytest.raises(AgentError) as caught:
        generate(provider, source)

    assert caught.value.code == "SCHEMA_VIOLATION"
    assert caught.value.details["validationFailure"]["stage"] == "REVIEW-001"
    assert stages(provider) == ["MAP-001", "MAP-001", "REVIEW-001", "REVIEW-001"]
    assert_usage(caught.value.details["usage"], 4)


def test_repair_parse_failure_cannot_defer_stale_public_failure_snapshot():
    source = request(ids=(101, 102))
    provider = V4Provider(
        source,
        hook=fail_first_finding,
        raw_hook=lambda stage, occurrence, _data, raw: (
            "{" if stage == "MAP-001" and occurrence == 2 else raw
        ),
    )

    with pytest.raises(AgentError) as caught:
        generate(provider, source)

    assert caught.value.code == "SCHEMA_VIOLATION"
    assert isinstance(caught.value.__cause__, JsonObjectParseError)
    assert stages(provider) == ["MAP-001", "MAP-001"]
    assert_usage(caught.value.details["usage"], 2)


def test_insufficient_mandatory_review_time_fails_with_original_map_error(monkeypatch, caplog):
    source = request()
    clock = [0.0]
    monkeypatch.setattr("app.llm.report_insight_pipeline.monotonic", lambda: clock[0])

    def slow_map(stage, occurrence, data, value):
        if occurrence == 2:
            clock[0] = 90.001
        return fail_first_finding(stage, occurrence, data, value)

    provider = V4Provider(source, hook=slow_map)
    with caplog.at_level("INFO"), pytest.raises(AgentError) as caught:
        generate(provider, source, AGENT_REPORT_INSIGHT_TIMEOUT_SECONDS=180)

    assert caught.value.code == "SCHEMA_VIOLATION"
    assert caught.value.details["validationFailure"]["stage"] == "MAP-001"
    assert stages(provider) == ["MAP-001", "MAP-001"]
    assert "stage=REVIEW-001 outcome=SKIPPED_TIME_BUDGET required=True fallback=NONE" in caplog.text
    assert_usage(caught.value.details["usage"], 2)


def test_mandatory_review_deadline_is_not_a_validation_fallback(monkeypatch):
    source = request()
    clock = [0.0]
    monkeypatch.setattr("app.llm.report_insight_pipeline.monotonic", lambda: clock[0])

    def slow_review(stage, occurrence, data, value):
        if stage.startswith("MAP"):
            clock[0] = 80.0
        elif stage == "REVIEW-001":
            clock[0] = 120.001
        return fail_first_finding(stage, occurrence, data, value)

    provider = V4Provider(source, hook=slow_review)
    with pytest.raises(AgentError) as caught:
        generate(provider, source, AGENT_REPORT_INSIGHT_TIMEOUT_SECONDS=180)

    assert caught.value.code == "PROVIDER_UNAVAILABLE"
    assert caught.value.details["requestDeadlineExceeded"] is True
    assert stages(provider) == ["MAP-001", "MAP-001", "REVIEW-001"]
    assert_usage(caught.value.details["usage"], 3)
