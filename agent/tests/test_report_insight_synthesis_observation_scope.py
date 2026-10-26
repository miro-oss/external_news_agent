"""Observation instructions do not realize forecasts or hide asserted execution."""

import pytest
from test_report_insight_synthesis_quality import insight, source_request, validate

from app.core.errors import OutputValidationError
from app.llm.report_insight_synthesis_quality import _frames


@pytest.mark.parametrize(
    "mechanism",
    [
        "원문 설비 전망 점검→투자 결정이 실제 주문으로 전환되는지 확인"
        "→주문 확인 시 수주 대응 방향을 살펴본다.",
        "증설이 실제 발주로 전환되는지 확인한다.",
        "투자가 실제 집행되는지 확인한다.",
        "현재 투자 집행 여부를 확인한다.",
        "투자 집행이 완료됐는지 확인한다.",
        "실제 수주가 확인되는지 검토한다.",
        "발주 확인 시 수주 대응을 검토한다.",
        "수주 관측 시 납기 대응을 검토한다.",
        "실제 수주 확인 시 납기 대응을 검토한다.",
        "발주 확인 시, 투자 집행 여부를 검토한다.",
    ],
)
def test_event_questions_and_observation_conditions_do_not_realize_forecasts(mechanism):
    assert all(frame.stage <= 1 for frame in _frames(mechanism))
    validate(insight(text=mechanism), source_request())


@pytest.mark.parametrize(
    "text",
    [
        "투자가 현재 집행되고 있다.",
        "투자가 이미 집행됐다→수주 확인 시 영업 대응을 검토한다.",
        "현재 투자 집행이 완료됐다→발주 여부를 확인한다.",
        "증설 여부를 확인한다→투자가 현재 집행되고 있다.",
        "수주가 현재 확인됐다→투자 여부를 검토한다.",
        "투자 집행이 완료됐으며 증설 진행 여부를 확인한다.",
        "투자가 이미 집행됐으며 발주로 전환되는지 확인한다.",
        "투자가 완료됐다, 발주 확인 시 납기 대응을 검토한다.",
        "투자가 완료됐으며 발주 확인 시 납기 대응을 검토한다.",
        "투자가 실제 집행됐고 발주 확인 시 납기 대응을 검토한다.",
        "수주가 확인됐으며 발주 확인 시 납기 대응을 검토한다.",
        "발주 확인 시 납기 대응을 검토한다, 투자는 이미 집행됐다.",
        "발주 확인 시 납기 대응을 검토한다, 투자는 완료됐다.",
        "발주 확인 시 납기 대응을 검토하며 투자는 이미 집행됐다.",
    ],
)
def test_observation_question_or_later_condition_does_not_hide_actual_execution(text):
    assert any(frame.stage >= 2 for frame in _frames(text))
    with pytest.raises(OutputValidationError) as caught:
        validate(insight(text=text), source_request())
    assert "report_synthesis_stage_overreach" in caught.value.error_kinds


def test_known_current_event_in_another_arrow_step_does_not_realize_a_forecast_step():
    frames = _frames("투자는 계획 중이다→수주가 현재 확인됐다")
    assert [(frame.family, frame.stage) for frame in frames] == [
        ("investment", 0),
        ("orders", 2),
    ]
