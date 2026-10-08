"""The one MAP repair sees every public defect after native validation passes."""

from dataclasses import replace

import pytest
from test_report_insight_assessment import framed, payload, request, response
from test_report_insight_repair_actions import structured_diagnostics
from test_report_insight_v4_pipeline import V4Provider, generate, stages

from app.core.errors import AgentError
from app.llm import report_insight_service as service
from app.llm.report_insight_assessment import validate_draft


def long_fact_reason(finding_id):
    first = 9101 + (finding_id - 101) * 100
    numbers = " ".join(f"{number}억원" for number in range(first, first + 17))
    reason = (
        f"NVIDIA TSMC AMD 인텔 삼성전자 SK하이닉스의 {numbers} 투자 계획의 검증 준비를 확인한다."
    )
    assert len(reason) <= 180
    return reason, str(first + 16)


def public_response(value, source):
    native = response(value, source)
    # The regression is specific to a successful native check followed by a
    # public failure; a native error takes a different diagnostic path.
    draft = validate_draft(native, source)
    return replace(native, text=draft.mapped.model_dump_json(by_alias=True))


def diagnostic(prompt):
    return prompt.split("<validation-error>", 1)[1].split("</validation-error>", 1)[0]


def assert_fact_locations(details, source):
    rows = structured_diagnostics(f"<validation-error>{details}</validation-error>")
    for finding in source.findings:
        selected = [
            row
            for row in rows
            if row["field"] == f"assessments[{finding.id}].reason"
            and row["errorKind"] == "report_evidence_insufficient"
        ]
        assert {row["rule"] for row in selected} == {"unsupported_number", "company"}
        assert all(row["claimIds"] == [f"{finding.id}:0"] for row in selected)
    return rows


def long_fact_source():
    return request(
        ids=tuple(range(101, 107)),
        text="도서관은 독서 모임의 참가 신청이 현재 계속된다고 밝혔다.",
    )


def test_six_native_valid_public_failures_keep_last_finding_and_cause_in_bounded_diagnostics():
    source = long_fact_source()
    value = payload(source, relation="UNRELATED")
    for record in value["assessments"]["CHIP_MAKER"].values():
        record["reason"] = long_fact_reason(record["findingId"])[0]

    with pytest.raises(service.ReportAssessmentValidationError) as caught:
        service._validated_map_output(public_response(value, source), source)

    error = caught.value
    assert error.failed_finding_ids == tuple(range(101, 107))
    assert set(error.error_kinds) == {"report_evidence_insufficient"}
    assert len(str(error)) > 1_000
    details = service._repair_validation_diagnostics(error)
    assert len(details) <= 6_000
    for finding in source.findings:
        assert f"findingId={finding.id}" in details
        assert long_fact_reason(finding.id)[1] in details
    actions = service._repair_validation_diagnostics(error, for_prompt=True)
    assert_fact_locations(actions, source)
    assert len(actions) <= 6000
    for finding in source.findings:
        assert long_fact_reason(finding.id)[1] not in actions


def test_one_full_map_repair_receives_every_late_public_cause_without_extra_calls():
    source = long_fact_source()
    before = source.model_dump_json(by_alias=True)
    clean_reason = "원문 사건과 관점 업무의 연결 조건을 확인해야 한다."

    def hook(stage, occurrence, _, value):
        for record in value["assessments"]["CHIP_MAKER"].values():
            record["reason"] = (
                long_fact_reason(record["findingId"])[0] if occurrence == 1 else clean_reason
            )
        return value

    provider = V4Provider(source, relation="UNRELATED", hook=hook)
    result = generate(provider, source)

    assert stages(provider) == ["MAP-001", "MAP-001"]
    assert provider.schema_validity == [True, True]
    details = diagnostic(provider.calls[1]["prompt"])
    assert len(details.strip()) <= 6_000
    for finding in source.findings:
        assert long_fact_reason(finding.id)[1] not in details
    # Template and grounding guards can both diagnose the same finding.
    assert_fact_locations(details, source)
    assert [item.reason for item in result.insights[0].assessments] == [clean_reason] * 6
    assert result.meta.input_tokens == 22
    assert result.meta.output_tokens == 14
    assert result.meta.cost_usd == 0.006
    assert result.meta.credits == 0.4
    assert source.model_dump_json(by_alias=True) == before


def deadline_source():
    return request(
        ids=(101, 102),
        text="검증 장비 도입의 마감은 2026년 9월 20일이다.",
    )


def deadline_record(record, *, bad_company=False, expired_urgency=False):
    record["reason"] = (
        "NVIDIA의 " if bad_company else ""
    ) + "검증 장비 도입 마감에 따라 준비 일정을 확인할 필요가 있다."
    record["impactScope"] = "UNDETERMINED"
    record["impactBasis"] = None
    record["urgencyState"] = "IMMEDIATE" if expired_urgency else "UNDETERMINED"
    record["urgencyBasis"] = record["relationBasis"] if expired_urgency else None


def test_same_native_valid_finding_reports_company_and_expired_urgency_once():
    source = deadline_source()
    value = payload(source)
    for record in value["assessments"]["CHIP_MAKER"].values():
        deadline_record(
            record,
            bad_company=record["findingId"] == 101,
            expired_urgency=record["findingId"] == 101,
        )

    with pytest.raises(service.ReportAssessmentValidationError) as caught:
        service._validated_map_output(public_response(value, source), source)

    error = caught.value
    assert error.failed_finding_ids == (101,)
    assert set(error.error_kinds) == {"report_evidence_insufficient", "report_assessment_invalid"}
    details = service._repair_validation_diagnostics(error)
    assert "엔비디아" in details
    assert "이미 지난 기한만으로 urgency=3" in details
    assert "assessments.reason" in details
    assert "assessments.axes.urgency" in details


def test_same_reason_keeps_fact_citation_and_imminent_deadline_causes_for_one_repair():
    source = deadline_source()
    value = payload(source)
    for record in value["assessments"]["CHIP_MAKER"].values():
        deadline_record(record)
    value["assessments"]["CHIP_MAKER"]["finding101"]["reason"] = (
        "NVIDIA의 검증 장비 도입 마감이 임박했으며 원문이 상충하므로 준비 일정을 확인해야 한다."
    )

    with pytest.raises(service.ReportAssessmentValidationError) as caught:
        service._validated_map_output(public_response(value, source), source)

    error = caught.value
    assert error.failed_finding_ids == (101,)
    details = service._repair_validation_diagnostics(error)
    assert "엔비디아" in details
    assert "양쪽 claim 근거가 필요합니다" in details
    assert "이미 지난 근거 기한" in details
    assert "assessments.reason" in details
    assert "assessments.axes.urgency" not in details


def test_mixed_native_errors_keep_each_nested_public_cause_within_diagnostic_limit():
    source = request(
        ids=tuple(range(101, 107)),
        text="검증 장비 도입의 마감은 2026년 9월 20일이다.",
    )
    value = payload(source)
    numbers = " ".join(f"{number}억원" for number in range(9901, 9913))
    reason = (
        "영향 범위는 미확인이다. NVIDIA TSMC AMD 인텔 삼성전자 SK하이닉스의 "
        f"{numbers} 마감이 임박하며 원문은 상충한다."
    )
    assert len(reason) <= 180
    for record in value["assessments"]["CHIP_MAKER"].values():
        # CORE_CONSTRAINT conflicts with the unknown impact in the reason;
        # native diagnostics must also retain all independent public causes.
        record["reason"] = reason

    error = service._native_assessment_repair_errors(response(value, source), source)

    assert error is not None
    assert error.failed_finding_ids == tuple(range(101, 107))
    assert set(error.error_kinds) == {
        "report_assessment_draft_invalid",
        "report_evidence_insufficient",
        "report_assessment_invalid",
    }
    details = service._repair_validation_diagnostics(error)
    assert len(details) <= 6_000
    for finding in source.findings:
        assert f"findingId={finding.id}" in details
    for cause in (
        "effect.impactScope",
        "근거에서 확인되지 않는 숫자",
        "양쪽 claim 근거가 필요합니다",
        "이미 지난 근거 기한",
        "이미 지난 기한만으로 urgency=3",
    ):
        assert details.count(cause) >= len(source.findings)
    actions = service._repair_validation_diagnostics(error, for_prompt=True)
    assert "삼성전자" not in actions and "9912" not in actions
    rows = assert_fact_locations(actions, source)
    assert len(actions) <= 6000
    for finding in source.findings:
        assert any(row["field"] == f"assessments[{finding.id}].axes.urgency" for row in rows)
    for cause in (
        "effect.impactScope",
        "양쪽 claim 근거가 필요합니다",
        "이미 지난 근거 기한",
        "이미 지난 기한만으로 urgency=3",
    ):
        assert actions.count(cause) >= len(source.findings)


@pytest.mark.parametrize("retained_defect", [None, "company", "urgency"])
def test_one_partial_repair_must_correct_both_public_defects_and_preserve_other_record(
    retained_defect,
):
    source = deadline_source()
    before = source.model_dump_json(by_alias=True)
    preserved_reason = "검증 장비 도입 마감에 따른 준비 범위를 확인한다."

    def hook(stage, occurrence, _, value):
        if stage == "REDUCE-001":
            insight = value["insights"][0]
            insight["headline"] = "검증 장비 도입의 마감과 준비 범위를 확인한다."
            insight["overview"] = [
                {
                    "text": "검증 장비 도입 마감에 따른 준비 범위를 확인해야 한다.",
                    "basisClaimIds": ["101:0"],
                    "assumption": "같은 검증 장비 도입을 검토하는 경우",
                }
            ]
            return value
        for record in value["assessments"]["CHIP_MAKER"].values():
            target = record["findingId"] == 101 and stage == "MAP-001"
            deadline_record(
                record,
                bad_company=target and (occurrence == 1 or retained_defect == "company"),
                expired_urgency=target and (occurrence == 1 or retained_defect == "urgency"),
            )
            if record["findingId"] == 102:
                record["reason"] = preserved_reason
        return value

    provider = V4Provider(source, hook=hook)
    if retained_defect is None:
        result = generate(provider, source)
        assert stages(provider) == ["MAP-001", "MAP-001", "REVIEW-001", "REDUCE-001"]
        assert result.insights[0].assessments[1].reason == preserved_reason
        assert all(item.axes.urgency is None for item in result.insights[0].assessments)
        assert result.meta.input_tokens == 44
        assert result.meta.output_tokens == 28
        assert result.meta.cost_usd == 0.012
        assert result.meta.credits == 0.8
    else:
        with pytest.raises(AgentError) as caught:
            generate(provider, source)
        assert caught.value.code == "SCHEMA_VIOLATION"
        assert stages(provider) == ["MAP-001", "MAP-001"]
        assert caught.value.details["usage"] == {
            "inputTokens": 22,
            "outputTokens": 14,
            "costUsd": 0.006,
            "credits": 0.4,
        }

    repair = provider.calls[1]
    assert [finding["id"] for finding in framed(repair["prompt"])["findings"]] == [101]
    details = diagnostic(repair["prompt"])
    assert "report_evidence_insufficient" in details and "엔비디아" not in details
    assert "이미 지난 기한만으로 urgency=3" in details
    assert "<invalid-output>" not in repair["prompt"]
    assert all(provider.schema_validity)
    assert source.model_dump_json(by_alias=True) == before
