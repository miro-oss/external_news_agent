"""Report 2086's selected sources with reconstructed, grounded market prose.

The incident log contains rule/claim IDs, not provider prose. These cases use
the actual linked sentences and paraphrases that reproduced its source-binding
failure, without treating the paraphrases as captured provider responses.
"""

from copy import deepcopy
from datetime import date

import pytest
from test_report_insight_assessment import payload, request, response
from test_report_insight_connection_evidence_dependency import full_validate
from test_report_insight_v4_pipeline import V4Provider, stages

from app.core.config import Settings
from app.llm import report_insight_service as service

AUDIENCE = "MARKET_INVESTOR"
DILUTION = (
    "솔리다임의 해외 상장으로 SK하이닉스 주주의 경제적 이익이 희석되고 "
    "기업가치가 중복으로 할인될 우려가 있다는 뜻이다."
)
SCHEDULE = "이에 따라 향후 상장 일정에 제동이 걸릴 것으로 전망된다."
REASON = (
    "솔리다임의 해외 상장으로 SK하이닉스 주주의 경제적 이익이 희석될 우려와 "
    "상장 일정에 제동이 걸릴 전망은 주주가치와 상장 리스크 판단에 직접 연결된다."
)


def listing_case():
    source = request(ids=(8584,), audiences=(AUDIENCE,), text=DILUTION)
    source.report.id = 2086
    source.report.report_date = date(2026, 10, 20)
    finding = source.findings[0]
    finding.article_id = 44341
    finding.published_at = date(2026, 10, 20)
    finding.claims = [
        finding.claims[0].model_copy(
            update={
                "id": f"8584:{index}",
                "text": text,
                "claim_type": claim_type,
                "evidence_sentence_ids": [sentence_index],
            }
        )
        for index, sentence_index, claim_type, text in (
            (
                3, 21, "FACT",
                "솔리다임 해외 상장으로 SK하이닉스 주주의 경제적 이익이 희석될 우려가 제기됐다.",
            ),
            (
                4, 22, "FORECAST",
                "이로 인해 향후 솔리다임 상장 일정에 제동이 걸릴 것으로 전망된다.",
            ),
        )
    ]
    finding.sentences = [
        finding.sentences[0].model_copy(update={"index": index, "text": text})
        for index, text in ((21, DILUTION), (22, SCHEDULE))
    ]
    value = payload(source)
    value["assessments"][AUDIENCE]["finding8584"].update(
        work="GUIDANCE",
        relationBasis={"claimId": "8584:3", "quote": DILUTION},
        impactScope="UNDETERMINED",
        impactBasis=None,
        urgencyState="MONITOR",
        urgencyBasis={"claimId": "8584:4", "quote": SCHEDULE},
        reason=REASON,
    )
    return source, value


def test_market_reason_accepts_listing_risk_with_exact_selected_sources():
    source, value = listing_case()
    before = source.model_dump_json(), deepcopy(value)
    validated = full_validate(response(value, source), source)
    assessment = validated.mapped.insights[0].assessments[0]
    assert assessment.reason == REASON
    assert assessment.basis_claim_ids == ["8584:3", "8584:4"]
    assert assessment.axes.impact is None
    assert before == (source.model_dump_json(), value)


@pytest.mark.parametrize(
    "reason",
    [
        REASON.replace("솔리다임의 해외 상장", "SK하이닉스의 해외 상장"),
        "솔리다임의 해외 상장이 완료됐다. " + REASON,
        "솔리다임은 상장으로 100억달러를 조달했다. " + REASON,
        "솔리다임의 해외 상장이 취소됐다. " + REASON,
        "claim 8584:3에 따르면 " + REASON,
    ],
    ids=["wrong-issuer", "completed", "funding", "cancelled", "internal-id"],
)
def test_market_reason_still_rejects_changed_facts_and_internal_references(reason):
    source, value = listing_case()
    value["assessments"][AUDIENCE]["finding8584"]["reason"] = reason
    with pytest.raises(service.ReportAssessmentValidationError):
        full_validate(response(value, source), source)


def test_schedule_only_reason_cannot_borrow_an_issuer_from_an_unselected_claim():
    source, value = listing_case()
    record = value["assessments"][AUDIENCE]["finding8584"]
    record["relationBasis"] = deepcopy(record["urgencyBasis"])
    with pytest.raises(service.ReportAssessmentValidationError) as caught:
        full_validate(response(value, source), source)
    assert any(issue.rule == "source_binding" for issue in caught.value.validation_issues)


def test_market_listing_risk_completes_map_review_and_synthesis_without_false_repairs():
    source, value = listing_case()
    before = source.model_dump_json()

    def hook(stage, occurrence, data, generated):
        if stage.startswith(("MAP-", "REVIEW-", "EXPLAIN-")):
            return deepcopy(value)
        insight = generated["insights"][0]
        insight.update(
            headline="주주 이익 희석 우려와 상장 일정 전망을 점검한다.",
            overview=[
                {
                    "text": REASON,
                    "basisClaimIds": ["8584:3", "8584:4"],
                    "assumption": "주주 이익 희석 우려가 기업가치 평가에 반영되는 경우",
                }
            ],
        )
        return generated

    provider = V4Provider(source, hook=hook)
    result = service.ReportInsightService(
        Settings(_env_file=None, AGENT_MOCK=False), provider
    ).generate(source)
    assert stages(provider) == ["MAP-001", "REVIEW-001", "REDUCE-001"]
    assert result.insights[0].assessments[0].reason == REASON
    assert result.insights[0].overview[0].text == REASON
    assert result.meta.input_tokens == 33
    assert result.meta.output_tokens == 21
    assert source.model_dump_json() == before
