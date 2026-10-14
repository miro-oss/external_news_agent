"""EXPLAIN writes selected-source prose without reopening assessment decisions."""

import pytest

from app.llm import report_insight_instructions as instructions


@pytest.mark.parametrize("repair", [False, True])
def test_explain_keeps_roles_and_fixed_decisions_with_selected_basis_prose_rules(repair):
    instruction = instructions.report_stage_instruction(
        ["CHIP_MAKER", "MARKET_INVESTOR"], "EXPLAIN", repair=repair
    )

    assert instruction.startswith(instructions._BASE)
    assert "CHIP_MAKER: 칩 제조:" in instruction
    assert "MARKET_INVESTOR: 투자 판단:" in instruction
    assert "EQUIPMENT_MAKER:" not in instruction
    assert "현재 단계는 EXPLAIN." in instruction
    assert "Schema const로 고정된 decision의 판단·work·basis" in instruction
    assert "null condition은 유지한다" in instruction
    assert "reason과 non-null condition을 작성한다" in instruction
    assert instructions.ASSESSMENT_SELECTED_BASIS_RULE in instruction
    assert instructions.REPORT_ANALYSIS_ONLY_RULE in instruction
    assert "basis를 새로 선택·교체·삭제하지 않는다" in instruction
    assert instructions._RUBRIC not in instruction
    assert instructions._REDUCE_RULES not in instruction
