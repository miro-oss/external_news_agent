"""Original source relationships survive prompts, summaries and prose guards."""

from types import SimpleNamespace

import pytest
from test_report_insight_assessment import framed, request

from app.llm.report_insight_assessment import draft_prompt, review_prompt
from app.llm.report_insight_service import _eligible_report_request, _validate_prose


@pytest.mark.parametrize(
    "summary",
    [
        "삼성전자와 SK하이닉스의 생산량은 각각 20개와 10개다.",
        "두 회사의 생산량은 각각 20개와 10개로 집계됐다.",
    ],
)
def test_summary_does_not_disable_original_subject_quantity_binding(summary):
    source = "삼성전자와 SK하이닉스의 생산량은 각각 20개와 10개다."
    evidence = {"101:0": source}
    claims = {"101:0": SimpleNamespace(text=summary)}
    with pytest.raises(ValueError, match="동일 주체·대상·시점의 수치 충돌"):
        _validate_prose(["삼성전자의 생산량은 10개다."], ["101:0"], evidence, claims)
    _validate_prose(["삼성전자의 생산량은 20개다."], ["101:0"], evidence, claims)


def test_wrong_claim_is_excluded_locally_without_rewriting_snapshot():
    source = request(text="삼성전자와 SK하이닉스의 생산량은 각각 20개와 10개다.")
    source.findings[0].claims[0].text = "삼성전자의 생산량은 10개다."
    original = source.model_dump_json(by_alias=True)
    eligible = _eligible_report_request(source)
    assert not eligible.findings[0].claims
    assert len(eligible.findings) == len(source.findings)
    assert source.model_dump_json(by_alias=True) == original


def test_unparsed_sources_do_not_add_empty_hints_or_remove_original_context():
    source = request(text="원문은 시스템의 구성 변경을 설명한다.")
    finding = framed(draft_prompt(source))["findings"][0]
    assert "sourceFactHints" not in finding
    assert finding["sourceFactIndex"]["facts"] == []
    assert finding["sourceFactIndex"]["uncertainty"]
    assert finding["sentences"][0]["text"] == source.findings[0].sentences[0].text
    assert finding["sourceQuoteChoices"]


def test_map_and_independent_review_share_original_bound_hints_and_keep_claim_type():
    source = request(text="삼성전자와 SK하이닉스의 생산량은 각각 20개와 10개다.")
    source.findings[0].claims[0].claim_type = "FORECAST"
    source.findings[0].claims[0].attributed_to = "삼성전자"
    original = source.model_dump_json(by_alias=True)
    for prompt in (draft_prompt(source), review_prompt(source)):
        item = framed(prompt)["findings"][0]
        hints = item["sourceFactIndex"]
        assert hints["extractionScope"] == "partial_explicit_relations"
        assert [
            (fact["subjects"][0]["value"], fact["quantity"]["value"]) for fact in hints["facts"]
        ] == [
            ("삼성전자", "20"),
            ("sk하이닉스", "10"),
        ]
        assert item["claims"][0]["claimType"] == "FORECAST"
        assert item["claims"][0]["attributedTo"] == "삼성전자"
        for fact in hints["facts"]:
            assert fact["span"]["end"] <= len(item["sentences"][0]["text"])
    assert source.model_dump_json(by_alias=True) == original
