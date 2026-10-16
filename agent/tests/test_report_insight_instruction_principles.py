"""Instruction contract checks; these do not measure live model judgment quality."""

import re

import pytest

from app.llm import report_insight_instructions as instructions
from app.llm.report_insight_assessment import ROLE_WORK


@pytest.mark.parametrize("audience", ROLE_WORK)
def test_role_definitions_keep_the_complete_closed_work_vocabulary(audience):
    declared = re.findall(r"\b[A-Z]{3,}(?:_[A-Z]+)*\b", instructions._ROLES[audience])
    assert declared == list(ROLE_WORK[audience])


@pytest.mark.parametrize("audience", ROLE_WORK)
@pytest.mark.parametrize("stage", ["MAP", "REVIEW", "EXPLAIN", "REDUCE"])
def test_stages_use_source_and_work_principles_without_event_specific_role_anchors(audience, stage):
    instruction = instructions.report_stage_instruction([audience], stage)
    assert "사실 근거는 현재 입력의 claim과 연결 sentence뿐" in instruction
    assert "역할 정의를 사건·조직·프로젝트·승인 요건의 근거로 쓰지 않는다" in instruction
    assert "주체·대상·사건·단계를 유지한다" in instruction
    assert "하나와 직접 연결되면 다른 업무의 조건을 요구하지 않는다" in instruction
    assert "원문 대상이 다르면 해당 업무의 사실로 대체하지 않는다" in instruction
    assert "수반될 수 있는 업무와 실제 확인된 업무를 구분" in instruction
    assert "이행 결과·영향 규모·행동 시점이 불명이어도 확인된 업무 관계는 유지" in instruction
    assert "과거 기한·발행일·전망 기간은 즉시 대응이나 준비 기한이 아니다" in instruction
    assert all(anchor not in instruction for anchor in ("메모리/HBM", "직원·본사", "자금 조달"))
    if audience == "MARKET_INVESTOR":
        assert "거래 동향·기록과 목표주가 조정 보도는 서술할 수" in instruction
        assert "독자의 매매를 권고하거나 목표가격을 제시하지 않는다" in instruction


def test_selected_basis_principle_applies_to_each_axis_and_the_final_explanation():
    rule = instructions.ASSESSMENT_SELECTED_BASIS_RULE
    assert "각 축은 그 축의 최종 non-null basis가 선택한 claim·연결 원문" in rule
    assert "같은 finding의 미선택 claim을 섞지 않는다" in rule
    assert "주체·대상·사건·단계와 조건·가능성을 유지" in rule
    assert "지원되지 않는 연결을 사실로 단정하지" in rule
    assert rule in instructions.ASSESSMENT_FINAL_BASIS_RULE
    assert rule in instructions.report_stage_instruction(["IT_INFRA"], "EXPLAIN")


def test_reduce_preserves_selected_source_scope_and_invalidation_direction():
    instruction = instructions.report_stage_instruction(["MARKET_INVESTOR"], "REDUCE")
    assert "같은 finding의 미선택 사실을 섞지 말고 선택 근거의 대상·단계·조건을 유지" in instruction
    assert "각 finding의 주체·대상·단계·날짜는 그 finding의 원문 묶음 안에서" in instruction
    assert "취소·연기가 발생하지 않았다는 관측은 계획 실현의 반증이 아니다" in instruction
    assert "자료 부재는 반증이 아니다" in instruction
    assert "원문 부재와 업무 관련성 미확인은 다르" in instruction
