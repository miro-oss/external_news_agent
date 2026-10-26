"""Shared audience work must not collapse separate source events in REDUCE."""

from datetime import date

from test_report_insight_assessment import framed, payload, request, validate_flat
from test_report_insight_v4_pipeline import V4Provider, generate

from app.llm.report_insight_service import _decision_candidates


def test_same_work_keeps_each_source_and_publication_date_with_its_own_evidence():
    source = request(ids=(101, 102))
    texts = (
        "제조사 A는 생산라인 전체의 가동 중단이 현재 계속된다고 밝혔다.",
        "제조사 B는 생산라인 전체의 가동 중단이 현재 계속된다고 밝혔다.",
    )
    for finding, text, published in zip(
        source.findings, texts, (date(2026, 9, 24), date(2026, 9, 25)), strict=True
    ):
        finding.published_at = published
        finding.claims[0].text = finding.sentences[0].text = text
    original = source.model_dump_json(by_alias=True)
    provider = V4Provider(source)

    result = generate(provider, source)

    data = framed(provider.calls[-1]["prompt"])
    groups = data["decisionCandidates"]["CHIP_MAKER"]
    assert len(groups) == 2
    assert groups[0]["work"] == groups[1]["work"]
    for group, finding in zip(groups, source.findings, strict=True):
        assert group["findingId"] == finding.id
        assert group["articleId"] == finding.article_id
        assert group["publishedAt"] == finding.published_at.isoformat()
        assert [item["findingId"] for item in group["findings"]] == [finding.id]
        candidate = group["findings"][0]
        for field in ("connectionBasis", "impactBasis", "urgencyBasis"):
            assert candidate[field]["claimId"] == finding.claims[0].id
            assert candidate[field]["text"] == finding.claims[0].text
    assert [item.finding_id for item in result.insights[0].assessments] == [101, 102]
    assert source.model_dump_json(by_alias=True) == original


def test_source_group_uses_missing_date_as_unknown_and_only_retrieved_connections():
    source = request(ids=(101, 102))
    source.findings[1].published_at = None
    validated = validate_flat(payload(source), source)

    groups = _decision_candidates(source, validated, {"CHIP_MAKER": ("102:0",)})["CHIP_MAKER"]

    assert len(groups) == 1
    assert groups[0]["findingId"] == 102
    assert groups[0]["publishedAt"] is None
    assert groups[0]["findings"][0]["connectionBasis"]["claimId"] == "102:0"
    assert "101:0" not in str(groups)
