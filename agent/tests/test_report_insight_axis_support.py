"""Forecast dates cannot silently become confirmed project changes/preparation."""

from copy import deepcopy

import pytest
from test_report_insight_assessment import item, request

from app.llm.report_insight_axis_support import assessment_axis_support_problems
from app.schemas.report_insight import ReportInsightFinding
from app.schemas.report_insight_assessment import ReportFindingAssessmentDraft

RAM_FORECAST = (
    "1일(현지시간) 산제이 메흐로트라(Sanjay Mehrotra) 마이크론 최고경영자(CEO) 겸 회장이 "
    "2026 회계연도 실적 발표 콘퍼런스콜을 통해 인공지능(AI) 인프라 수요 폭증으로 인한 "
    "램(RAM) 공급 부족 사태가 2028년 이후까지 이어질 것이라고 경고했다."
)
PRICE_FORECAST = (
    "인공지능(AI) 추론 수요가 본격화하면서 메모리 반도체 시장이 내년부터 가격과 출하량이 "
    "동시에 증가하는 강한 상승 사이클에 진입할 것이란 전망이 나왔다."
)


def specimen(text, *, quote=None, claim_text=None, finding_id=101):
    source = request(ids=(finding_id,), audiences=("IT_INFRA",), text=text)
    finding = source.findings[0]
    if claim_text is not None:
        finding = finding.model_copy(
            update={"claims": [finding.claims[0].model_copy(update={"text": claim_text})]}
        )
    value = item(finding, audience="IT_INFRA")
    basis = {"claimId": finding.claims[0].id, "quote": quote or text}
    value.update(
        work="SYSTEM_PROCUREMENT",
        reason="원문 사건과 시스템 조달의 연결을 검토한다.",
        relationBasis=deepcopy(basis),
        impactScope="PROJECT_CHANGE",
        impactBasis=deepcopy(basis),
        urgencyState="SCHEDULED_PREPARATION",
        urgencyBasis=deepcopy(basis),
    )
    return ReportFindingAssessmentDraft.model_validate(value), finding


@pytest.mark.parametrize("text,finding_id", [(RAM_FORECAST, 7810), (PRICE_FORECAST, 7817)])
def test_recorded_market_forecasts_diagnose_both_axes_without_rewriting(text, finding_id):
    value, finding = specimen(text, finding_id=finding_id)
    before = value.model_dump_json(), finding.model_dump_json()
    problems = assessment_axis_support_problems(value, finding)
    assert [(p.native_field, p.problem, p.claim_ids) for p in problems] == [
        (
            "decision.effect.impactScope",
            "market_forecast_only_project_change",
            (f"{finding_id}:0",),
        ),
        (
            "decision.timing.urgencyState",
            "market_forecast_only_scheduled_preparation",
            (f"{finding_id}:0",),
        ),
    ]
    assert before == (value.model_dump_json(), finding.model_dump_json())


def test_fact_labeled_compressed_claim_cannot_strengthen_original_forecast():
    value, finding = specimen(
        "메모리 공급 부족은 2028년 이후까지 이어질 것이라고 경고했다.",
        quote="메모리 공급 부족은 2028년 이후까지 계속된다.",
        claim_text="메모리 공급 부족은 2028년 이후까지 계속된다.",
    )
    assert finding.claims[0].claim_type == "FACT"
    assert len(assessment_axis_support_problems(value, finding)) == 2


@pytest.mark.parametrize(
    "text",
    [
        "시장 수급은 2027년과 2028년에 불균형이 심화될 것이다.",
        "내년 메모리 가격은 20% 상승할 것으로 예상된다.",
        "공급 부족 사태가 장기간 이어질 전망이다.",
        "공급가격은 내년에 오를 것으로 관측된다.",
    ],
)
def test_year_or_quantity_in_forecast_is_not_an_execution_schedule(text):
    value, finding = specimen(text)
    assert len(assessment_axis_support_problems(value, finding)) == 2


@pytest.mark.parametrize(
    "text",
    [
        "시장 가격 상승 전망에 대응해 프로젝트 메모리 예산을 늘렸다.",
        "공급 부족 전망에 따라 서버 구매 계약을 체결하고 납품 조건을 확정했다.",
        "가격은 내년에 오를 전망이지만 현재 공급가격을 10% 인상했다.",
        "메모리 가격 상승이 예상되어 공급가격을 인상하기로 확정했다.",
        "공급 부족 전망에 대응해 다음 달 말까지 호환성 검증을 완료하도록 준비 일정을 확정했다.",
        "부품 공급 부족에 대비한 구매 계획을 검토하고 있다.",
        "공급가격을 내년부터 인상하고 있다고 밝혔다.",
        "특정 장비의 납품 지연 때문에 프로젝트 일정이 변경됐다.",
        "시장 수급 불균형이 현재 지속되고 있다.",
        "메모리 계약가격은 내년에 오를 전망이며 계약 조건은 아직 협의 중이다.",
        "시장 가격 상승이 관측됐다.",
        "실제 단가 상승이 관측됐다.",
        "가격이 상승한 것으로 추정된다.",
        "공급 부족이 장기간 이어질 것이라는 전망을 부인했다.",
        "가격 상승 전망은 철회됐다.",
        "공급 부족은 전망이 아니라 이미 발생한 사실이다.",
        "가격 상승은 예상되지 않는다.",
        "시장 가격 상승 전망과 달리 현재 단가 하락이 관측됐다.",
        "메모리 가격은 예상치보다 20% 급등했다.",
        "메모리 가격은 예측치보다 20% 급등했다.",
        "메모리 가격은 전망치보다 20% 급등했다.",
    ],
)
def test_actual_or_ambiguous_mixed_events_are_not_blanket_rejected(text):
    value, finding = specimen(text)
    assert assessment_axis_support_problems(value, finding) == ()


@pytest.mark.parametrize(
    "suffix",
    [
        "다른 회사는 프로젝트 예산을 늘렸다.",
        "다른 회사는 공급 계약을 체결했다.",
        "다른 회사는 다음 달까지 준비를 마친다.",
    ],
)
def test_selected_forecast_fragment_cannot_borrow_a_different_sentence_event(suffix):
    forecast = "내년 시장 가격은 오를 전망이다"
    value, finding = specimen(f"{forecast}. {suffix}", quote=forecast)
    assert len(assessment_axis_support_problems(value, finding)) == 2


def test_selected_forecast_fragment_cannot_borrow_contrasting_actor_in_same_sentence():
    forecast = "시장 가격은 내년에 오를 전망이다"
    value, finding = specimen(
        f"{forecast}; 반면 다른 회사는 서버 프로젝트를 확대했다.", quote=forecast
    )
    assert len(assessment_axis_support_problems(value, finding)) == 2


def test_unselected_claim_with_real_preparation_cannot_support_forecast_basis():
    value, finding = specimen(PRICE_FORECAST)
    data = finding.model_dump(mode="json", by_alias=True)
    data["claims"].append(
        {
            "id": "101:1",
            "text": "회사는 내달까지 설치 준비를 마친다.",
            "claimType": "FACT",
            "attributedTo": None,
            "evidenceSentenceIds": [1],
        }
    )
    data["sentences"].append({"index": 1, "text": "회사는 내달까지 설치 준비를 마친다."})
    finding = ReportInsightFinding.model_validate(data)
    assert len(assessment_axis_support_problems(value, finding)) == 2


def test_each_axis_uses_its_own_selected_basis():
    value, finding = specimen(PRICE_FORECAST)
    data = finding.model_dump(mode="json", by_alias=True)
    data["claims"].append(
        {
            "id": "101:1",
            "text": "회사는 내달까지 설치 준비를 마친다.",
            "claimType": "FACT",
            "attributedTo": None,
            "evidenceSentenceIds": [1],
        }
    )
    data["sentences"].append({"index": 1, "text": "회사는 내달까지 설치 준비를 마친다."})
    finding = ReportInsightFinding.model_validate(data)
    value = value.model_copy(
        update={
            "urgency_basis": value.urgency_basis.model_copy(
                update={"claim_id": "101:1", "quote": "회사는 내달까지 설치 준비를 마친다."}
            )
        }
    )
    problems = assessment_axis_support_problems(value, finding)
    assert [p.native_field for p in problems] == ["decision.effect.impactScope"]


@pytest.mark.parametrize(
    "changes",
    [
        {"impact_scope": "UNDETERMINED", "impact_basis": None, "urgency_state": "MONITOR"},
        {"impact_scope": "NO_CHANGE", "urgency_state": "NOT_URGENT"},
        {
            "impact_scope": "LIMITED_PREPARATION",
            "urgency_state": "UNDETERMINED",
            "urgency_basis": None,
        },
    ],
)
def test_other_categories_are_not_reclassified(changes):
    value, finding = specimen(PRICE_FORECAST)
    assert assessment_axis_support_problems(value.model_copy(update=changes), finding) == ()


def test_invalid_literal_or_foreign_basis_is_left_to_existing_source_guard():
    value, finding = specimen(PRICE_FORECAST)
    basis = value.impact_basis.model_copy(update={"claim_id": "999:0"})
    value = value.model_copy(update={"impact_basis": basis, "urgency_basis": None})
    assert assessment_axis_support_problems(value, finding) == ()
