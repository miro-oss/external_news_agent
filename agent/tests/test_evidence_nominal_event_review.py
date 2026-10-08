"""Reviewing an event noun is not evidence that the event has happened."""

import pytest
from test_report_insight_assessment import request

from app.core.evidence import _modality_stage, modality_overreach
from app.llm.report_insight_service import _prose_validation_errors, _source_context


@pytest.mark.parametrize("event", ["체결", "승인", "확정", "결정", "합의"])
@pytest.mark.parametrize("ending", ["검토한다", "검토했다", "검토 중이다", "검토"])
def test_review_of_event_object_is_not_completed_event(event, ending):
    particle = "를" if event == "합의" else "을"
    source = f"제조사는 {event}{particle} {ending}."
    assert _modality_stage(source)[0] == 1
    overreach = modality_overreach(f"제조사는 {event}했다.", source)
    assert overreach is not None
    assert overreach.claim_stage == 4
    assert overreach.evidence_stage == 1


@pytest.mark.parametrize(
    ("source", "stage"),
    [
        ("계약을 체결했고 설계를 검토한다.", 4),
        ("계약을 체결하며 설계를 검토한다.", 4),
        ("체결을 검토하면서 별도 계약을 체결했다.", 4),
        ("승인을 검토하면서 설계를 확정했다.", 4),
        ("설계를 확정하며 승인을 검토한다.", 4),
        ("체결을 검토하며 장비를 공급했다.", 5),
        ("장비를 공급하며 지난달 설치를 완료했다.", 6),
        ("계약을 체결했다. 승인을 검토한다.", 4),
    ],
)
def test_review_cannot_erase_another_asserted_event_in_the_same_clause(source, stage):
    assert _modality_stage(source)[0] == stage


@pytest.mark.parametrize(
    "source",
    [
        "계약 체결을 계획한다.",
        "승인을 계획했다.",
        "계약 체결을 준비한다.",
        "승인을 추진한다.",
        "승인을 논의한다.",
    ],
)
def test_preparing_or_discussing_event_object_does_not_establish_confirmation(source):
    assert _modality_stage(source)[0] < 4
    assert modality_overreach("계약을 체결했다.", source) is not None


@pytest.mark.parametrize(
    "claim",
    [
        "계약을 확정했다.",
        "이미 확정된 계약서 확인이 필요하다.",
        "실제로 확정된 계약서 확인이 필요하다.",
        "이미 확정된 설계 추가 확인이 필요하다.",
        "계약을 확정했다. 확정된 계약서의 존재는 명시되어 있지 않다.",
    ],
)
def test_report_rejects_actual_confirmation_against_review_only_source(claim):
    source = request(text="제조사는 공급 계약 체결을 검토한다.")
    claims, evidence = _source_context(source)
    assert _prose_validation_errors([claim], ["101:0"], evidence, claims, request=source)


@pytest.mark.parametrize(
    "claim",
    [
        "계약을 체결했다.",
        "승인을 검토하면서 별도 계약을 체결했다.",
        "확정된 계약서의 존재는 명시되어 있지 않다.",
    ],
)
def test_report_keeps_grounded_assertions_and_evidence_inquiries(claim):
    source = request(text="승인을 검토하면서 별도 계약을 체결했다.")
    claims, evidence = _source_context(source)
    assert _prose_validation_errors([claim], ["101:0"], evidence, claims, request=source) == []
