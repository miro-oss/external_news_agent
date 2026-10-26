"""Work guards bind a procedure or team to its own assertion, not distant text."""

import pytest
from test_report_insight_assessment import payload, request, validate_flat

from app.llm.report_insight_work_grounding import work_prose_problems

SOURCE = "가온전자는 내년 메모리 가격을 현재보다 높이는 방안을 추진한다."
UNRELATED = (
    "가격 인상 보도는 장비 공급(칠러·냉각장비)의 주문·공정검증·설계채택과 "
    "직접 연결되는 사건이 아니다."
)


@pytest.mark.parametrize(
    "prose",
    [
        UNRELATED,
        "가격 인상 보도는 냉각 검증 업무와 직접 연결되는 사건이 아니다.",
        "가격 인상은 냉각 검증 업무와 관련이 없다.",
    ],
)
def test_denied_work_relation_does_not_assert_a_new_procedure(prose):
    assert work_prose_problems(prose, SOURCE) == ()


@pytest.mark.parametrize(
    "prose",
    [
        "가격 인상 보도는 냉각 검증 업무와 직접 연결된다.",
        "냉각 검증이 필요하다.",
        "이미 완료된 냉각 검증과 가격 인상은 관련이 없다.",
        "이미 마친 냉각 검증과 직접 관련이 없다.",
        "이미 끝낸 냉각 검증과 직접 관련이 없다.",
        "필수 냉각 검증과 직접 연결되는 사건이 아니다.",
        "냉각 검증을 완료했다. " + UNRELATED,
        UNRELATED + " 냉각 검증을 완료했다.",
        "냉각 검증을 완료했지만 가격 인상과 관련이 없다.",
        "냉각 검증은 완료되었으며 가격 인상과 관련이 없다.",
    ],
)
def test_relation_denial_cannot_hide_an_asserted_or_required_procedure(prose):
    assert "cooling_procedure" in work_prose_problems(prose, SOURCE)


def test_native_map_accepts_unrelated_work_explanation_without_repair():
    source = request(ids=(101,), audiences=("EQUIPMENT_MAKER",), text=SOURCE)
    value = payload(source, relation="UNRELATED")
    value["assessments"]["EQUIPMENT_MAKER"]["finding101"]["reason"] = UNRELATED
    validated = validate_flat(value, source)
    assert validated.mapped.insights[0].assessments[0].axes.directness == 0


@pytest.mark.parametrize("team", ["연구팀", "개발팀", "해솔팀"])
@pytest.mark.parametrize(
    "prose",
    [
        "{team}이 재료로 새 소자를 만들었다는 사실은 소재 연구 결과로, 운영 변경 대상이 아니다.",
        "기사는 {team}의 소재 연구 성과를 설명하며 운영 대상 변경을 제시하지 않는다.",
        "{team}이 만든 소자는 운영 변경 대상이 아니다.",
        "{team}의 성과 설명, 운영 대상과는 관련이 없다.",
    ],
)
def test_distant_object_does_not_assign_a_team_to_an_operational_role(team, prose):
    assert "organization_prerequisite" not in work_prose_problems(prose.format(team=team), SOURCE)


@pytest.mark.parametrize("team", ["연구팀", "개발팀", "해솔팀"])
@pytest.mark.parametrize(
    "prose",
    [
        "{team}이 도입 승인 주체다.",
        "{team}의 승인이 전제다.",
        "{team} 검증 준비를 조정한다.",
        "{team}이 도입안을 승인했다.",
        "승인 주체는 {team}이다.",
        "{team}이 조달 담당이다. 별도 운영 대상은 미정이다.",
    ],
)
def test_explicit_unsupported_team_role_or_procedure_is_still_rejected(team, prose):
    text = prose.format(team=team)
    assert "organization_prerequisite" in work_prose_problems(text, SOURCE)
    assert "organization_prerequisite" not in work_prose_problems(text, SOURCE + f" {team}이 있다.")


def test_a_different_team_or_recommendation_does_not_supply_the_asserted_role():
    text = "해솔팀이 결과를 설명하며 운영팀 검증 준비를 조정한다. 비용을 재검토해야 한다."
    assert "organization_prerequisite" in work_prose_problems(text, SOURCE + " 해솔팀이 있다.")
