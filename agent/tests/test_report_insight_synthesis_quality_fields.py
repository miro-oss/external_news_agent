"""Incomplete units share quality rules without fabricated companion fields."""

from copy import deepcopy

import pytest
from test_report_insight_falsifier_direction import (
    MECHANISM,
    PROPOSITION,
    expansion_request,
)
from test_report_insight_source_binding import production_request
from test_report_insight_synthesis_quality import (
    insight,
    power_implication,
    power_request,
    source_request,
    validate,
)

from app.llm.report_insight_synthesis_quality import (
    ReportSynthesisQualityValidationError,
    synthesis_unit_quality_diagnostics,
)


def diagnostics(values, source=None, *, group="implications", refs=("7750:0",)):
    return synthesis_unit_quality_diagnostics(
        "EQUIPMENT_MAKER", group, 2, values, refs, source or source_request()
    )


def rules(result):
    return {(issue.field, issue.error_kind) for issue, _ in result}


@pytest.mark.parametrize("values", [{}, {"text": None}, {"mechanism": []}, {"falsifiedBy": 5}])
def test_absent_or_non_string_fields_are_not_filled_in_or_validated(values):
    before = deepcopy(values)
    assert diagnostics(values) == ()
    assert values == before


@pytest.mark.parametrize(
    "field,value,expected",
    [
        (
            "text",
            "삼성전기의 FC-BGA 투자가 현재 집행되고 있다.",
            "report_synthesis_stage_overreach",
        ),
        (
            "mechanism",
            "삼성전기의 FC-BGA 투자가 현재 집행되고 있다.",
            "report_synthesis_stage_overreach",
        ),
        ("mechanism", "근거 → 결합 → 업무 판단", "report_synthesis_placeholder"),
        (
            "mechanism",
            "원문에 구체적 업무 연결 조건이 명시되어 있지 않음 → 영향 범위와 시급성 미확인",
            "report_synthesis_information_gap",
        ),
        (
            "falsifiedBy",
            "추가 근거가 없으면 해석을 바꾼다.",
            "report_falsification_missing_observation",
        ),
    ],
)
def test_present_scalar_keeps_its_original_quality_check_without_other_fields(
    field, value, expected
):
    result = diagnostics({field: value})
    assert rules(result) == {(f"implications[2].{field}", expected)}
    assert all(issue.claim_ids == ("7750:0",) for issue, _ in result)


@pytest.mark.parametrize("group", ["overview", "implications"])
def test_present_assumption_keeps_confirmation_check_when_text_is_missing(group):
    result = diagnostics(
        {"assumption": "경매 중단이 전력 공급에 영향을 미치는 것으로 확인됨."},
        power_request(),
        group=group,
    )
    assert rules(result) == {(f"{group}[2].assumption", "report_assumption_unconfirmed")}


@pytest.mark.parametrize("field", ["text", "mechanism"])
def test_present_event_keeps_source_binding_check_when_assumption_is_missing(field):
    result = diagnostics(
        {field: "네오팹의 6nm 공장을 신설해 내년 하반기부터 생산을 시작할 예정이다."},
        production_request(),
        refs=("1001:0", "1002:0"),
    )
    assert rules(result) == {(f"implications[2].{field}", "report_synthesis_source_binding")}


@pytest.mark.parametrize("omitted", ["text", "mechanism", "falsifiedBy", "assumption"])
@pytest.mark.parametrize("family", ["power", "expansion"])
def test_cross_field_direction_requires_complete_original_proposition(omitted, family):
    if family == "power":
        source = power_request()
        values = power_implication()
    else:
        source = expansion_request()
        values = {
            "text": PROPOSITION,
            "mechanism": MECHANISM,
            "assumption": "계획이 유지되는 경우",
            "falsifiedBy": "가람전자의 다낭 공장 증설 계획이 철회되지 않는 경우",
        }
    values.pop(omitted)
    before = deepcopy(values), source.model_dump_json()
    result = diagnostics(values, source)
    direction = ("implications[2].falsifiedBy", "report_falsification_direction")
    assert (direction in rules(result)) == (omitted == "assumption")
    assert before == (values, source.model_dump_json())


def test_complete_validator_and_partial_unit_return_the_same_diagnostics():
    source = power_request()
    values = power_implication()
    expected = synthesis_unit_quality_diagnostics(
        "EQUIPMENT_MAKER", "implications", 0, values, ("7750:0",), source
    )
    with pytest.raises(ReportSynthesisQualityValidationError) as caught:
        validate(insight(implication=values), source)
    assert caught.value.validation_issues == tuple(issue for issue, _ in expected)
    assert caught.value.repair_diagnostics == tuple(message for _, message in expected)


def test_refactoring_does_not_apply_implication_rules_to_watch_items():
    assert (
        diagnostics(
            {"mechanism": "TBD", "falsifiedBy": "추가 근거가 없으면 해석을 바꾼다."},
            group="watchItems",
        )
        == ()
    )
