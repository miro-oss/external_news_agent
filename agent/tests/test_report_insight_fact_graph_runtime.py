"""Source relations participate in production guards and existing partial repair."""

import pytest
from test_report_insight_assessment import request

from app.llm.report_insight_service import (
    _eligible_report_request,
    _prose_validation_errors,
    _source_context,
)


def test_bound_conflict_is_not_hidden_by_unparsed_source_or_repeated_bad_summary():
    source = (
        "삼성전자의 생산량은 20개다. SK하이닉스의 생산량은 10개다. "
        "시장 상황에 관한 추가 설명도 있다."
    )
    req = request(text=source)
    req.findings[0].claims[0].text = "삼성전자의 생산량은 10개다."
    claims, evidence = _source_context(req)
    errors = _prose_validation_errors(
        ["삼성전자의 생산량은 10개다."], ["101:0"], evidence, claims, request=req
    )
    factual = [error for error in errors if "수치 충돌" in str(error)]
    assert factual
    assert factual[0].error_kinds == ("report_fact_mismatch",)
    assert "numeric_context" in factual[0].fact_repair_kinds


def test_source_relation_exclusion_preserves_all_original_snapshot_fields():
    req = request(text="삼성전자의 매출은 20억원이다. 영업 환경은 불확실하다.")
    req.findings[0].claims[0].text = "삼성전자의 매출은 10억원이다."
    snapshot = req.model_dump_json(by_alias=True)
    eligible = _eligible_report_request(req)
    assert eligible.findings[0].claims == []
    assert eligible.findings[0].sentences == []
    assert len(eligible.findings) == len(req.findings)
    assert req.model_dump_json(by_alias=True) == snapshot


@pytest.mark.parametrize("selected", ["101:0", "102:0"])
def test_validation_uses_only_original_sentences_of_selected_claims(selected):
    req = request(ids=(101, 102), text="삼성전자의 생산량은 20개다.")
    req.findings[1].sentences[0].text = "삼성전자의 생산량은 10개다."
    req.findings[1].claims[0].text = req.findings[1].sentences[0].text
    claims, evidence = _source_context(req)
    errors = _prose_validation_errors(
        ["삼성전자의 생산량은 10개다."], [selected], evidence, claims, request=req
    )
    assert bool(errors) == (selected == "101:0")


def test_another_owners_completed_event_does_not_upgrade_selected_plan():
    req = request(text="삼성전자는 공장을 완공할 계획이다. SK하이닉스는 공장을 완공했다.")
    claims, evidence = _source_context(req)
    errors = _prose_validation_errors(
        ["삼성전자는 공장을 완공했다."], ["101:0"], evidence, claims, request=req
    )
    assert any("주체·사건 연결" in str(error) for error in errors)
    assert any("source_binding" in getattr(error, "fact_repair_kinds", ()) for error in errors)


def test_known_quantity_conflict_still_applies_to_conditional_fields():
    req = request(text="삼성전자의 생산량은 20개다. SK하이닉스의 생산량은 10개다.")
    claims, evidence = _source_context(req)
    errors = _prose_validation_errors(
        ["삼성전자의 생산량은 10개다."],
        ["101:0"],
        evidence,
        claims,
        request=req,
        conditional=True,
    )
    assert any("수치 충돌" in str(error) for error in errors)
