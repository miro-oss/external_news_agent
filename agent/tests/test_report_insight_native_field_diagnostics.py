"""Native condition failures retain their origin after public reason projection."""

from copy import deepcopy
from dataclasses import replace

import pytest
from test_report_insight_assessment import payload, request, response
from test_report_insight_v4_pipeline import V4Provider, generate, stages

from app.core.errors import AgentError
from app.llm.report_insight_assessment import validate_draft
from app.llm.report_insight_service import (
    ReportAssessmentValidationError,
    _prose_validation_errors,
    _source_context,
    _validated_map_output,
)


def recorded_source():
    source = request(
        ids=(7815, 7816),
        audiences=("IT_INFRA",),
        text="화웨이도 스마트폰 출고가를 평균 200달러 인상했다.",
    )
    finding = source.findings[0]
    finding.claims[0].id = "7815:2"
    finding.claims.append(
        finding.claims[0].model_copy(
            update={
                "id": "7815:0",
                "text": "삼성전자와 화웨이가 플래그십 스마트폰 출고가를 인상했다.",
                "evidence_sentence_ids": [1],
            }
        )
    )
    finding.sentences.append(
        finding.sentences[0].model_copy(update={"index": 1, "text": finding.claims[-1].text})
    )
    return source


def test_recorded_condition_failure_names_native_field_and_rejects_bad_repair():
    # Real MAP attempts 13/14: the first reason has no unsupported company;
    # Samsung occurs in the appended condition. The repair then moves that
    # unsupported company into the previously valid reason. The narrowed schema
    # and server-side preservation check must both reject that change.
    source = recorded_source()

    def hook(stage, occurrence, _, value):
        for item in value["assessments"]["IT_INFRA"].values():
            item["reason"] = "원문 사건과 관점 업무의 연결 여부를 검토해야 한다."
        item = value["assessments"]["IT_INFRA"].get("finding7815")
        if stage == "MAP-001" and item is not None:
            if occurrence == 1:
                item.update(
                    relation="CONDITIONAL",
                    work="DEPLOYMENT_OPERATIONS",
                    condition=(
                        "인공지능(AI) 인프라 확장에 따른 글로벌 메모리 공급 부족 여파로 "
                        "삼성전자와 화웨이가 주력 플래그십 스마트폰 출고가를 인상했다."
                    ),
                    reason=(
                        "스마트폰 출고가 인상 사건이 해당 업무와 연결되며, "
                        "구체적 영향 범위와 시점은 미확인이다."
                    ),
                )
            else:
                item.update(
                    relation="CONDITIONAL",
                    work="DEPLOYMENT_OPERATIONS",
                    condition="화웨이의 스마트폰 출고가 인상이 해당 업무에 연결되는 경우",
                )
                item["reason"] = (
                    "원문에 명시된 화웨이의 스마트폰 출고가 인상과 관련된 사건을 근거로 "
                    "해당 업무와 연결되었으며, 삼성전자에 대한 언급은 원문에 포함되어 "
                    "있지 않기 때문에 관련성을 판단할 수 없다."
                )
        return value

    # Deliberately emulate a provider violating its strict schema, so the
    # independent server-side guard must also reject the invalid repair.
    provider = V4Provider(source, relation="UNRELATED", hook=hook, validate_wire=False)
    with pytest.raises(AgentError) as caught:
        generate(provider, source)

    assert caught.value.code == "SCHEMA_VIOLATION"
    assert stages(provider) == ["MAP-001", "MAP-001"]
    assert provider.schema_validity == [True, False]
    repair = provider.calls[1]["prompt"]
    assert "field=assessments.reason nativeFields=decision.connection.condition" in repair
    details = repair.split("<validation-error>", 1)[1].split("</validation-error>", 1)[0]
    assert "report_fact_mismatch" in details
    assert "삼성전자" not in details
    assert "refs=['7815:2']" in repair
    assert "condition 오류를 reason 수정만으로 해결하지 마세요" in repair


def conditional_payload(source):
    value = payload(source, relation="CONDITIONAL")
    for item in value["assessments"][source.audiences[0]].values():
        item.update(
            work="DEPLOYMENT_OPERATIONS",
            reason="스마트폰 출고가 인상 사건의 업무 연결 조건을 확인한다.",
            condition="화웨이의 스마트폰 출고가 인상이 해당 업무에 연결되는 경우",
            impactScope="UNDETERMINED",
            impactBasis=None,
            urgencyState="UNDETERMINED",
            urgencyBasis=None,
        )
    return value


def rejected_projection(source, value, *, with_native=True):
    raw = response(value, source)
    draft = validate_draft(raw, source)
    with pytest.raises(ReportAssessmentValidationError) as caught:
        _validated_map_output(
            replace(raw, text=draft.mapped.model_dump_json(by_alias=True)),
            source,
            native_assessments=draft.evidence if with_native else None,
        )
    return caught.value


@pytest.mark.parametrize("invalid_fields", [("reason",), ("condition",), ("reason", "condition")])
def test_native_diagnostic_attributes_actual_components_without_weakening_public_guard(
    invalid_fields,
):
    source = recorded_source()
    value = conditional_payload(source)
    item = value["assessments"]["IT_INFRA"]["finding7815"]
    for field in invalid_fields:
        item[field] = "삼성전자의 스마트폰 출고가 인상과 업무 연결 조건을 검토한다."
    original = deepcopy(value)
    legacy = rejected_projection(source, value, with_native=False)
    diagnostic = rejected_projection(source, value)

    paths = [
        "reason" if field == "reason" else "decision.connection.condition"
        for field in invalid_fields
    ]
    assert f"nativeFields={','.join(paths)}" in diagnostic.repair_summary
    assert "nativeFields=" not in str(legacy)
    assert diagnostic.error_kinds == legacy.error_kinds == ("report_fact_mismatch",)
    assert diagnostic.failed_finding_ids == legacy.failed_finding_ids == (7815,)
    assert "근거에서 확인되지 않는 기업명: 삼성전자" in str(diagnostic)
    assert value == original


def test_combined_context_failure_keeps_original_error_and_names_both_native_fields():
    source = request(audiences=("IT_INFRA",), text="2026년 9월 1일에 계약 신청이 마감된다.")
    finding = source.findings[0]
    finding.claims.append(
        finding.claims[0].model_copy(
            update={
                "id": "101:1",
                "text": "별도 계약 신청은 2026년 10월 1일에 마감된다.",
                "evidence_sentence_ids": [1],
            }
        )
    )
    finding.sentences.append(
        finding.sentences[0].model_copy(update={"index": 1, "text": finding.claims[-1].text})
    )
    value = conditional_payload(source)
    item = value["assessments"]["IT_INFRA"]["finding101"]
    item.update(
        reason="원문은 2026년 9월 1일 마감을 언급한다.",
        condition="별도 마감이 임박한 경우",
        impactScope="PROJECT_CHANGE",
        impactBasis={"claimId": "101:1", "quote": finding.claims[-1].text},
    )
    claims, evidence = _source_context(source)
    for field in ("reason", "condition"):
        assert not _prose_validation_errors(
            [item[field]], ["101:0", "101:1"], evidence, claims, request=source
        )
    legacy = rejected_projection(source, value, with_native=False)
    diagnostic = rejected_projection(source, value)
    assert diagnostic.error_kinds == legacy.error_kinds == ("report_assessment_invalid",)
    assert "이미 지난 근거 기한" in str(diagnostic)
    assert "nativeFields=reason,decision.connection.condition" in diagnostic.repair_summary
