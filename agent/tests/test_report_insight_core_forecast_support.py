"""A forecast-only citation cannot establish a current production constraint."""

from copy import deepcopy

import pytest
from test_report_insight_assessment import payload, request, response
from test_report_insight_axis_support import specimen
from test_report_insight_work_repair_actions import full_validate

from app.core.config import Settings
from app.llm import report_insight_service as service
from app.llm.report_insight_assessment import (
    ReportAssessmentDraftValidationError,
    draft_prompt,
    draft_schema,
    validate_draft,
)
from app.llm.report_insight_axis_support import assessment_axis_support_problems
from app.schemas.report_insight import ReportInsightRequest

# Recorded v20 REVIEW: the forecast and current capacity evidence are distinct.
FORECAST = (
    "마이크론이 글로벌 메모리반도체(이하 메모리) 시장의 공급부족 현상이 "
    "2028년까지 이어질 것이라고 전망했다."
)
CURRENT_CAPACITY = "메모리 생산능력 확대가 수요를 따라가지 못하는 상황이 계속되기 때문이다."


def recorded_case():
    source = request(text=FORECAST)
    data = source.model_dump(mode="json", by_alias=True)
    finding = data["findings"][0]
    finding["claims"].append(
        {
            "id": "101:1",
            "text": "메모리 생산능력 확대가 수요를 따라가지 못하는 상황이 계속된다.",
            "claimType": "FACT",
            "attributedTo": None,
            "evidenceSentenceIds": [1],
        }
    )
    finding["sentences"].append({"index": 1, "text": CURRENT_CAPACITY})
    source = ReportInsightRequest.model_validate(data)
    value = payload(source)
    value["assessments"]["CHIP_MAKER"]["finding101"].update(
        work="YIELD_CAPACITY",
        relationBasis={"claimId": "101:1", "quote": CURRENT_CAPACITY},
        urgencyState="SCHEDULED_PREPARATION",
        reason=(
            "기사들은 메모리 시장의 공급부족이 2028년까지 이어질 것이라고 전망하고 그 원인으로 "
            "생산능력 확대가 수요를 따라가지 못한다고 설명하고 있다. 이는 칩 제조업의 "
            "생산능력·배분 관점에서 직접적인 제약 요인으로 판단된다."
        ),
    )
    return source, value


def test_recorded_forecast_core_constraint_has_typed_effect_diagnostic():
    source, value = recorded_case()
    before = source.model_dump_json(), deepcopy(value)
    with pytest.raises(ReportAssessmentDraftValidationError) as caught:
        validate_draft(response(value, source), source)
    assert [(d.native_field, d.problem, d.claim_ids) for d in caught.value.work_diagnostics] == [
        (
            "decision.effect.impactScope",
            "market_forecast_only_core_constraint",
            ("101:0",),
        ),
        (
            "decision.timing.urgencyState",
            "market_forecast_only_scheduled_preparation",
            ("101:0",),
        ),
    ]
    assert before == (source.model_dump_json(), value)


def test_core_repair_can_keep_category_by_selecting_actual_capacity_basis():
    source, value = recorded_case()
    # Isolate the previously missed impact diagnostic from the existing timing one.
    value["assessments"]["CHIP_MAKER"]["finding101"]["urgencyState"] = "MONITOR"
    raw = response(value, source)
    error = service._native_assessment_repair_errors(raw, source)
    assert error is not None
    repair = service.ReportInsightService(Settings(_env_file=None, mock=True))._repair_call(
        draft_prompt(source),
        draft_schema(source),
        raw.text,
        error,
        lambda candidate: full_validate(candidate, source),
    )
    assert "report_axis_market_forecast_only_core_constraint" in repair.prompt
    assert "nativeFields=decision.effect.impactScope refs=['101:0']" in repair.prompt
    assert repair.response_schema == draft_schema(source)
    with pytest.raises(ReportAssessmentDraftValidationError):
        repair.validate(raw)

    fixed = deepcopy(value)
    fixed["assessments"]["CHIP_MAKER"]["finding101"]["impactBasis"] = {
        "claimId": "101:1",
        "quote": CURRENT_CAPACITY,
    }
    accepted = repair.validate(response(fixed, source))
    item = accepted.evidence["CHIP_MAKER"][101]
    assert item.impact_scope == "CORE_CONSTRAINT"
    assert item.impact_basis.claim_id == "101:1"
    assert item.relation == "DIRECT" and item.condition is None
    assert accepted.mapped.insights[0].assessments[0].axes.impact == 3


@pytest.mark.parametrize(
    "text",
    [
        CURRENT_CAPACITY,
        "공급 부족이 장기간 이어질 것이라는 전망을 부인했다.",
        "공급 부족은 전망이 아니라 이미 발생한 사실이다.",
        "가격은 내년에 오를 전망이지만 현재 공급가격을 10% 인상했다.",
        "시장 가격 상승 전망에 대응해 프로젝트 메모리 예산을 늘렸다.",
        "메모리 가격은 예상치보다 20% 급등했다.",
        "메모리 생산능력 확대가 수요를 따라가지 못하는 상황이 계속돼 "
        "공급 부족이 내년까지 이어질 전망이다.",
        "현재 생산능력이 수요에 못 미쳐 공급 부족이 내년까지 이어질 전망이다.",
        "이미 생산에 제약이 발생했고 공급 부족은 내년에도 이어질 전망이다.",
        "수요를 충족하지 못해 공급 부족이 내년까지 이어질 전망이다.",
        "지난 분기 설비 제약의 여파로 공급 부족이 내년에도 이어질 전망이다.",
        "메모리 공급 부족이 발생해 내년까지 이어질 전망이다.",
        "메모리 공급 부족이 현실화해 내년까지 지속될 전망이다.",
    ],
)
def test_core_forecast_guard_abstains_on_actual_denied_or_mixed_sources(text):
    item, finding = specimen(text)
    item = item.model_copy(update={"impact_scope": "CORE_CONSTRAINT"})
    # Abstaining does not establish CORE or a schedule; only reject pure forecasts.
    item = item.model_copy(update={"urgency_state": "MONITOR"})
    assert assessment_axis_support_problems(item, finding) == ()


@pytest.mark.parametrize(
    "text",
    [
        "메모리 생산능력 확대가 수요를 따라가지 못하는 상황이 계속되면 "
        "공급 부족이 내년까지 이어질 전망이다.",
        "메모리 생산능력 확대가 수요를 따라가지 못하는 상황이 계속될 것으로 예상되며 "
        "공급 부족이 우려된다.",
    ],
)
def test_ambiguous_conditional_or_forecast_capacity_is_left_to_other_validation(text):
    item, finding = specimen(text)
    item = item.model_copy(update={"impact_scope": "CORE_CONSTRAINT", "urgency_state": "MONITOR"})
    assert assessment_axis_support_problems(item, finding) == ()


def test_selected_forecast_cannot_borrow_separate_current_capacity_sentence():
    forecast = "메모리 공급 부족은 내년까지 이어질 전망이다."
    item, finding = specimen(f"{forecast} {CURRENT_CAPACITY}", quote=forecast)
    item = item.model_copy(update={"impact_scope": "CORE_CONSTRAINT", "urgency_state": "MONITOR"})
    assert [p.problem for p in assessment_axis_support_problems(item, finding)] == [
        "market_forecast_only_core_constraint"
    ]
