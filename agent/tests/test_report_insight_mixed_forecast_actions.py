"""Separate forecast-only evidence from locally selected transaction actions."""

from copy import deepcopy

import pytest
from test_report_insight_axis_support import specimen

from app.llm.report_insight_axis_support import assessment_axis_support_problems
from app.schemas.report_insight import ReportInsightFinding

RECORDED_SOURCE = (
    "D램과 SSD 가격은 내년에도 지속 상승이 예상되지만, "
    "주요 PC 기업이 LTA 체결로 방어한 영향이다."
)
FORECAST = "메모리 가격은 내년에 상승할 것으로 예상된다."
EXPECTED = {
    "market_forecast_only_project_change",
    "market_forecast_only_scheduled_preparation",
}


@pytest.mark.parametrize(
    "source",
    [
        RECORDED_SOURCE,
        "메모리 가격 상승이 예상되지만 공급사와 LTA 체결을 완료했다.",
        "메모리 가격 상승이 예상되어 공급사와 합의서에 서명했다.",
        "메모리 가격 상승이 예상되지만 공급사와 MOU를 체결했다.",
        "메모리 가격 상승이 예상되어 다음 달 공급사와 합의서에 서명할 계획이다.",
    ],
)
def test_selected_signing_or_execution_action_is_not_misclassified_as_forecast_only(source):
    item, finding = specimen(source)
    before = item.model_dump_json(), finding.model_dump_json()

    assert assessment_axis_support_problems(item, finding) == ()

    # This narrow guard abstains on mixed or unresolved action clauses. It
    # neither proves a project/schedule nor modifies categories, scores or bases.
    assert before == (item.model_dump_json(), finding.model_dump_json())


@pytest.mark.parametrize(
    "suffix",
    [
        "공급사는 LTA 체결을 완료했다.",
        "공급사는 합의서에 서명했다.",
    ],
)
@pytest.mark.parametrize("boundary", [". ", "\n", "; ", " 반면 "])
def test_a_selected_forecast_cannot_borrow_a_separate_signing_clause(suffix, boundary):
    forecast = FORECAST.removesuffix(".")
    item, finding = specimen(forecast + boundary + suffix, quote=forecast)
    before = item.model_dump_json(), finding.model_dump_json()

    problems = assessment_axis_support_problems(item, finding)
    assert {problem.problem for problem in problems} == EXPECTED

    assert before == (item.model_dump_json(), finding.model_dump_json())


@pytest.mark.parametrize("action", ["LTA 체결을 완료했다", "합의서에 서명했다"])
@pytest.mark.parametrize("scope", ["claim", "metadata"])
def test_other_claim_or_title_cannot_supply_the_transaction_action(action, scope):
    item, finding = specimen(FORECAST)
    data = finding.model_dump(mode="json", by_alias=True)
    other_source = f"공급사는 {action}."
    if scope == "claim":
        data["claims"].append(
            {
                "id": "101:1",
                "text": other_source,
                "claimType": "FACT",
                "evidenceSentenceIds": [1],
            }
        )
        data["sentences"].append({"index": 1, "text": other_source})
    else:
        data["articleTitle"] = other_source
    finding = ReportInsightFinding.model_validate(data)

    problems = assessment_axis_support_problems(item, finding)
    assert {problem.problem for problem in problems} == EXPECTED


@pytest.mark.parametrize("action", ["LTA 체결을 완료했다", "합의서에 서명했다"])
def test_an_action_added_only_to_the_claim_summary_cannot_override_the_raw_forecast(action):
    summary = FORECAST + f" 공급사는 {action}."
    item, finding = specimen(FORECAST, quote=summary, claim_text=summary)

    problems = assessment_axis_support_problems(item, finding)
    assert {problem.problem for problem in problems} == EXPECTED


@pytest.mark.parametrize(
    "source",
    [
        FORECAST,
        "내년 PC 출하량은 감소할 것으로 예상된다.",
        "PC 기업의 메모리 반도체 수급난이 내년에는 개선될 것으로 예상된다.",
    ],
)
def test_pure_market_forecasts_still_cannot_establish_projects_or_preparation(source):
    item, finding = specimen(source)

    problems = assessment_axis_support_problems(item, finding)
    assert {problem.problem for problem in problems} == EXPECTED


def test_mixed_evidence_does_not_upgrade_unknown_or_monitoring_categories():
    item, finding = specimen(RECORDED_SOURCE)
    changes = {
        "impact_scope": "UNDETERMINED",
        "impact_basis": None,
        "urgency_state": "MONITOR",
    }
    item = item.model_copy(update=deepcopy(changes))
    before = item.model_dump_json(), finding.model_dump_json()

    assert assessment_axis_support_problems(item, finding) == ()

    assert item.impact_scope == "UNDETERMINED" and item.impact_basis is None
    assert item.urgency_state == "MONITOR"
    assert before == (item.model_dump_json(), finding.model_dump_json())
