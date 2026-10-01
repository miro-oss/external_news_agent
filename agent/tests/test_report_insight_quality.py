"""Curated editorial contrasts, not claims about a live model's measured quality."""

from copy import deepcopy
from types import SimpleNamespace

import pytest
from test_report_insight import FakeProvider, output, request_body, run

from app.eval.report_insight_review import QUALITY_RUBRIC, review_saved_output
from app.llm.report_insight_service import LEGACY_SYSTEM_INSTRUCTION as SYSTEM_INSTRUCTION
from app.llm.report_insight_service import _validate_prose
from app.schemas.report_insight import ReportInsightRequest


def test_grounded_conditional_decision_is_a_good_review_candidate():
    diagnostics = review_saved_output(
        ReportInsightRequest.model_validate(request_body()),
        output(),
    )
    assert diagnostics["contractPassed"] is True
    assert diagnostics["flags"] == []
    assert diagnostics["qualityMeasured"] is False
    assert diagnostics["requiresHumanReview"] is True


def test_generic_candidate_passes_contract_but_independent_review_flags_low_value():
    candidate = output()
    insight = candidate["insights"][0]
    insight["assessments"][0]["reason"] = (
        "기술 발전에 주목해야 하며 관련 변수를 확인할 필요가 있다."
    )
    insight["overview"][0]["text"] = "관련 변수가 유지되면 기술 발전에 주목해야 한다."
    implication = insight["implications"][0]
    implication.update(
        text="현재 관측이 유지되면 관련 영향 변수를 확인해야 한다.",
        mechanism="관련 변수에 긍정적인 영향이 있을 수 있다.",
        assumption="현재 관측이 유지될 경우",
        falsifiedBy="후속 뉴스 확인 후 관련 변수가 달라질 경우",
    )
    insight["watchItems"][0].update(indicator="후속 동향 확인", trigger="후속 뉴스 확인")
    diagnostics = review_saved_output(
        ReportInsightRequest.model_validate(request_body()), candidate
    )
    assert diagnostics["contractPassed"] is True
    assert "CHIP_MAKER:generic_interpretation" in diagnostics["flags"]
    assert "CHIP_MAKER:generic_condition:0" in diagnostics["flags"]
    assert "CHIP_MAKER:generic_watch:0" in diagnostics["flags"]


def test_forecast_with_missing_impact_is_retained_without_manufacturing_a_high_score():
    candidate = output()
    assessment = candidate["insights"][0]["assessments"][0]
    assessment["axes"].update(impact=None, urgency=None)
    assessment["reason"] = "공정 검증 준비와 연결되지만 발주 규모와 생산능력 근거가 부족하다."
    result = run(FakeProvider(candidate))
    assert result.insights[0].assessments[0].axes.impact is None
    assert result.insights[0].assessments[0].axes.urgency is None


@pytest.mark.parametrize(
    "generated",
    [
        "장비 발주 계약이 없으므로 확인이 필요하다.",
        "발주 계약이 없다는 점을 확인해야 한다.",
        "발주가 중단되어 검토가 필요하다.",
    ],
)
def test_recommendation_does_not_hide_a_reversed_factual_assertion(generated):
    source = "장비 발주 계약을 체결했다."
    with pytest.raises(ValueError, match="사실값"):
        _validate_prose(
            [generated], ["1:0"], {"1:0": source}, {"1:0": SimpleNamespace(text=source)}
        )


@pytest.mark.parametrize(
    "generated",
    [
        "삼성전자는 CPO 양산 계획 발표로 양산을 완료했다.",
        "삼성전자는 CPO 양산 계획에 따라 양산을 완료했다.",
        "삼성전자는 CPO 양산 계획에 따라 계약을 체결했다.",
        "삼성전자는 CPO 양산 계획에 따라 설치를 시작했다.",
    ],
)
def test_plan_word_does_not_hide_completed_contract_or_started_assertion(generated):
    source = "삼성전자는 CPO 양산 계획을 발표했다."
    with pytest.raises(ValueError, match="사실값"):
        _validate_prose(
            [generated], ["1:0"], {"1:0": source}, {"1:0": SimpleNamespace(text=source)}
        )


@pytest.mark.parametrize(
    "generated",
    [
        "양산 계획이 유지된다면 인증 완료 여부를 확인할 필요가 있다.",
        "양산을 완료했다면 인증 결과를 다시 확인할 필요가 있다.",
        "양산을 완료했다고 가정하면 인증 결과를 다시 확인할 필요가 있다.",
    ],
)
def test_status_questions_and_explicit_hypotheticals_do_not_assert_completion(generated):
    source = "삼성전자는 CPO 양산 계획을 발표했다."
    _validate_prose([generated], ["1:0"], {"1:0": source}, {"1:0": SimpleNamespace(text=source)})


def test_completion_grounded_in_a_plan_sentence_is_still_permitted():
    source = "양산 계획에 따라 CPO 공정 인증을 완료했다."
    _validate_prose(
        ["CPO 공정 인증을 완료했다."],
        ["1:0"],
        {"1:0": source},
        {"1:0": SimpleNamespace(text=source)},
    )


def test_contradictory_original_evidence_keeps_both_refs_and_uncertainty():
    body = request_body(second=True)
    first, second = body["findings"]
    first["claims"][0].update(
        text="CPO 공정 인증이 완료됐다.",
        claimType="FACT",
    )
    first["sentences"][0]["text"] = first["claims"][0]["text"]
    second["claims"][0].update(
        text="CPO 공정 인증이 완료되지 않았다.",
        claimType="FACT",
    )
    second["sentences"][0]["text"] = second["claims"][0]["text"]
    candidate = output(second=True)
    insight = candidate["insights"][0]
    insight.update(
        headline="공정 인증 완료 여부의 상충 근거를 확인해야 한다.",
        overview=[
            {
                "text": "공정 인증 완료 여부의 보도가 상충해 생산 준비 판단을 보류할 필요가 있다.",
                "basisClaimIds": ["501:0", "502:0"],
                "assumption": "같은 공정과 인증 범위를 다룬 보도인 경우",
            }
        ],
        implications=[],
        watchItems=[
            {
                "topic": "공정 인증 완료 여부",
                "indicator": "동일 공정·범위의 인증 완료에 대한 공식 발표",
                "trigger": "동일 범위의 인증 결과가 확인되면 생산 준비 판단을 다시 평가한다.",
                "basisClaimIds": ["501:0", "502:0"],
            }
        ],
    )
    for assessment in insight["assessments"]:
        assessment["axes"].update(impact=None, urgency=None)
        assessment["reason"] = "공정 인증 상태 확인이 필요하며 영향은 추가 확인 뒤 판단해야 한다."
    result = run(FakeProvider(candidate), body)
    assert result.insights[0].overview[0].basis_claim_ids == ["501:0", "502:0"]
    assert all(item.axes.impact is None for item in result.insights[0].assessments)
    invented = deepcopy(candidate)
    invented["insights"][0]["overview"][0]["text"] = "삼성전자의 인증이 완료됐다."
    # Metadata must not become factual evidence even when the original article
    # title or another saved case mentioned the company.
    assert (
        review_saved_output(
            ReportInsightRequest.model_validate(body),
            invented,
        )["contractPassed"]
        is False
    )


def test_irrelevant_infra_case_legitimately_has_no_report_synthesis():
    candidate = output(audience="IT_INFRA")
    insight = candidate["insights"][0]
    insight.update(
        headline="이 관점의 관련 근거가 부족합니다.", overview=[], implications=[], watchItems=[]
    )
    insight["assessments"][0].update(
        reason="공정 계획이며 시스템 조달·운영과 직접 연결하는 정보는 판단하기 어렵다.",
        axes={"directness": 0, "impact": None, "urgency": None, "novelty": None},
    )
    diagnostics = review_saved_output(
        ReportInsightRequest.model_validate(request_body(audiences=["IT_INFRA"])),
        candidate,
    )
    assert diagnostics["contractPassed"] is True
    assert diagnostics["flags"] == []


def test_missing_dates_do_not_force_urgency_and_report_time_is_explicit_in_rubric():
    body = request_body()
    body["report"].update(reportDate=None, reportEndDate=None)
    body["findings"][0]["publishedAt"] = None
    candidate = output()
    candidate["insights"][0]["assessments"][0]["axes"]["urgency"] = None
    result = run(FakeProvider(candidate), body)
    assert result.insights[0].assessments[0].axes.urgency is None
    assert "reportEndDate" in SYSTEM_INSTRUCTION
    assert "모델의 현재 날짜" in SYSTEM_INSTRUCTION
    assert "이미 지난 기한" in SYSTEM_INSTRUCTION


def test_manual_quality_rubric_covers_independent_product_goals_and_factual_release_gate():
    assert set(QUALITY_RUBRIC) == {
        "factual_integrity",
        "audience_fit",
        "importance_calibration",
        "report_synthesis",
        "conditional_mechanism",
        "actionable_watch",
    }
    assert all(len(anchors) == 4 for anchors in QUALITY_RUBRIC.values())
    assert "숫자" in QUALITY_RUBRIC["factual_integrity"][0]
    assert "날짜 기준" in QUALITY_RUBRIC["importance_calibration"][3]
