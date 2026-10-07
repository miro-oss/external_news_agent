"""Unknown relevance still has source text; it does not gain scoring evidence."""

import pytest
from test_report_insight_assessment import payload, request, response
from test_report_insight_v4_pipeline import V4Provider, generate, stages

from app.core.errors import OutputValidationError
from app.llm.base import ProviderResponse, ProviderUsage
from app.llm.report_insight_assessment import validate_draft
from app.llm.report_insight_service import _eligible_report_request, _validated_map_output


def mapped_reason(source, reason):
    value = payload(source, relation="UNDETERMINED")
    value["assessments"][source.audiences[0]][f"finding{source.findings[0].id}"]["reason"] = reason
    mapped = validate_draft(response(value, source), source).mapped
    return ProviderResponse(
        text=mapped.model_dump_json(by_alias=True),
        provider="openai",
        model="offline-test",
        usage=ProviderUsage(),
    )


def test_unknown_relation_can_explain_known_source_entities_without_repair_or_score_promotion():
    source = request(text="마이크론은 2026년 메모리 판매 전망을 발표했다.")
    interpretation = "메모리 판매 전망과 공정 업무의 관련성은 미확인이다."
    reason = f"원문: 「{source.findings[0].sentences[0].text}」 해석: {interpretation}"
    original = source.model_dump_json()

    def hook(stage, _, data, value):
        for record in value["assessments"]["CHIP_MAKER"].values():
            slot = data["findings"][0]["factTextSlots"][0]["slotId"]
            record["reason"] = "{{fact:" + slot + "}} " + interpretation
        return value

    provider = V4Provider(source, relation="UNDETERMINED", hook=hook)
    result = generate(provider, source)

    assert stages(provider) == ["MAP-001", "REVIEW-001"]
    insight = result.insights[0]
    assessment = insight.assessments[0]
    assert assessment.reason == reason
    assert assessment.basis_claim_ids == []
    assert set(assessment.axes.model_dump().values()) == {None}
    assert insight.overview == insight.implications == insight.watch_items == []
    assert source.model_dump_json() == original


@pytest.mark.parametrize(
    "reason",
    [
        "TSMC의 999억원 투자와 공정 업무의 관련성은 미확인이다.",
        "마이크론의 2031년 메모리 판매 전망과 공정 업무의 관련성은 미확인이다.",
        "마이크론의 메모리 판매가 30% 증가했으나 공정 업무의 관련성은 미확인이다.",
    ],
)
def test_unknown_reason_cannot_borrow_other_findings_metadata_or_invent_numbers(reason):
    source = request(ids=(101, 102), text="마이크론은 2026년 메모리 판매 전망을 발표했다.")
    # Both a different finding and the first finding's title contain this fact.
    other = source.findings[1]
    other.claims[0].text = other.sentences[0].text = "TSMC는 999억원 투자를 발표했다."
    assert "TSMC" in source.findings[0].article_title

    with pytest.raises(OutputValidationError):
        _validated_map_output(mapped_reason(source, reason), source)


def test_unknown_reason_cannot_turn_a_source_plan_into_a_completed_event():
    source = request(text="마이크론은 메모리 공급 확대를 계획했다.")
    reason = "마이크론은 메모리 공급 확대를 완료했으나 공정 업무의 관련성은 미확인이다."
    with pytest.raises(OutputValidationError):
        _validated_map_output(mapped_reason(source, reason), source)


def test_known_axes_still_require_the_selected_claim_to_support_the_reason():
    source = request(text="마이크론은 메모리 공급 확대를 계획했다.")
    finding = source.findings[0]
    finding.claims.append(
        finding.claims[0].model_copy(
            update={
                "id": "101:1",
                "text": "TSMC는 999억원 투자를 발표했다.",
                "evidence_sentence_ids": [1],
            }
        )
    )
    finding.sentences.append(
        finding.sentences[0].model_copy(
            update={"index": 1, "text": "TSMC는 999억원 투자를 발표했다."}
        )
    )
    value = payload(source)
    value["assessments"]["CHIP_MAKER"]["finding101"]["reason"] = "TSMC의 999억원 투자다."
    mapped = validate_draft(response(value, source), source).mapped
    assert mapped.insights[0].assessments[0].basis_claim_ids == ["101:0"]
    with pytest.raises(OutputValidationError):
        _validated_map_output(
            ProviderResponse(
                text=mapped.model_dump_json(by_alias=True),
                provider="openai",
                model="offline-test",
                usage=ProviderUsage(),
            ),
            source,
        )


def test_unknown_reason_only_uses_sources_remaining_after_eligibility_filter():
    source = request(text="마이크론은 2026년 메모리 판매 전망을 발표했다.")
    finding = source.findings[0]
    finding.claims.append(
        finding.claims[0].model_copy(update={"id": "101:1", "text": "2031년 전망이다."})
    )
    eligible = _eligible_report_request(source)
    assert [claim.id for claim in eligible.findings[0].claims] == ["101:0"]
    reason = "마이크론의 2031년 전망과 공정 업무의 관련성은 미확인이다."
    with pytest.raises(OutputValidationError):
        _validated_map_output(mapped_reason(eligible, reason), eligible)
