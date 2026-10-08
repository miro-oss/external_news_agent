"""Synthetic conditions preserve factual checks without rejecting actual hypotheses."""

from types import SimpleNamespace

import pytest
from test_report_insight_assessment import request
from test_report_insight_grounded_prose import (
    _checked_reduce,
    _provider_response,
    _reduce_payload,
)

from app.llm import report_insight_service as service

HALT_SOURCE = "제조사는 생산라인 전체의 가동 중단이 현재 계속된다고 밝혔다."
CONDITIONAL_FIELDS = (
    ("overview", "assumption"),
    ("implications", "assumption"),
    ("implications", "falsifiedBy"),
    ("watchItems", "indicator"),
    ("watchItems", "trigger"),
)
INVALID_ASSERTIONS = (
    (
        HALT_SOURCE + " 삼성전자는 공장을 완공할 계획이라고 밝혔다.",
        "삼성전자는 공장을 완공했다.",
    ),
    (HALT_SOURCE, "생산라인은 정상 가동 상태다."),
    (HALT_SOURCE, "수율이 증가했다."),
    (HALT_SOURCE, "공급 계약이 체결돼 있다."),
    (
        HALT_SOURCE + " 장비 발주 계약을 체결했고 인증을 완료했다.",
        "공급 계약은 취소 상태다. 공정 검증 영향을 확인한다.",
    ),
    (
        HALT_SOURCE,
        "수율이 증가했다. 공급 여력이 달라지는 경우 공정 검증을 확인한다.",
    ),
)
GENUINE_CONDITIONS = (
    "계획이 취소될 경우 공정 검증 일정을 확인한다.",
    "공급 계약이 체결될 경우 공정 검증 일정을 확인한다.",
    "가동 중단이 해소됐다면 공정 검증 일정을 확인한다.",
)


def validate_as_condition(source, prose):
    service._validate_prose(
        [prose],
        ["101:0"],
        {"101:0": source},
        {"101:0": SimpleNamespace(text=source)},
        conditional=True,
    )


def complete_reduce():
    value = _reduce_payload()
    insight = value["insights"][0]
    insight["implications"] = [
        {
            "text": "생산 제약의 지속은 준비 일정 판단에 영향을 줄 수 있다.",
            "mechanism": "생산라인 가동 중단 → 생산 제약 지속 → 준비 일정 판단 필요",
            "basisClaimIds": ["101:0"],
            "assumption": "같은 생산 제약이 준비 일정에 영향을 주는 경우",
            "falsifiedBy": "같은 생산라인의 가동 재개가 확인되는 경우",
        }
    ]
    insight["watchItems"] = [
        {
            "topic": "생산 제약",
            "indicator": "같은 생산라인의 가동 재개 여부",
            "trigger": "가동 재개가 확인되면 제약 영향을 다시 판단한다.",
            "basisClaimIds": ["101:0"],
        }
    ]
    return value


@pytest.mark.parametrize(("source", "prose"), INVALID_ASSERTIONS)
def test_a_conditional_field_does_not_erase_an_asserted_fact_error(source, prose):
    with pytest.raises(ValueError):
        validate_as_condition(source, prose)


@pytest.mark.parametrize("prose", GENUINE_CONDITIONS)
def test_genuine_conditional_predicates_are_allowed(prose):
    validate_as_condition(HALT_SOURCE, prose)


@pytest.mark.parametrize(("source_text", "prose"), INVALID_ASSERTIONS)
@pytest.mark.parametrize(("group", "field"), CONDITIONAL_FIELDS)
def test_reduce_condition_fields_and_repair_diagnostics_preserve_the_fact_failure(
    source_text, prose, group, field
):
    source = request(text=source_text)
    value = complete_reduce()
    _checked_reduce(source, value)
    value["insights"][0][group][0][field] = prose

    with pytest.raises(ValueError):
        _checked_reduce(source, value)

    diagnostic = service._reduce_repair_diagnostics(
        _provider_response(value), source, {"CHIP_MAKER": ["101:0"]}
    )
    assert diagnostic is not None
    assert any(
        issue.field == f"{group}[0].{field}"
        and issue.error_kind in {"report_fact_contradiction", "report_evidence_insufficient"}
        and issue.claim_ids == ("101:0",)
        for issue in diagnostic.validation_issues
    )


@pytest.mark.parametrize("prose", GENUINE_CONDITIONS)
@pytest.mark.parametrize(("group", "field"), CONDITIONAL_FIELDS)
def test_reduce_accepts_genuine_conditions_without_inventing_factual_state(prose, group, field):
    source = request(text=HALT_SOURCE)
    value = complete_reduce()
    value["insights"][0][group][0][field] = prose

    _checked_reduce(source, value)


def test_condition_does_not_drop_a_guard_error_when_its_message_has_no_known_keyword(monkeypatch):
    message = "인용 구간에 연결할 수 없는 독립 주장입니다."
    monkeypatch.setattr(service, "unsupported_fact_assertions", lambda *_: [message])

    with pytest.raises(ValueError, match=message):
        validate_as_condition(HALT_SOURCE, GENUINE_CONDITIONS[0])
