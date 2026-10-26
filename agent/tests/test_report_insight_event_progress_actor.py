"""An event's progress is not the named owner of a later event."""

from copy import deepcopy

import pytest

from app.core.errors import OutputValidationError
from app.llm.report_insight_synthesis_quality import validate_synthesis_quality
from app.schemas.report_insight import ReportInsightReduceAudience, ReportInsightRequest
from tests.test_report_insight import request_body
from tests.test_report_insight_synthesis_quality import insight, source_request, validate

RECORDED_TEXT = (
    "외부 공급·설비 계약의 지역별 영향: 가스·유틸리티 증설 투자 진행은 "
    "현지 팹의 증설 일정 안정성에 기여할 수 있다."
)


def gas_request():
    body = request_body(audiences=["CHIP_MAKER"])
    template = deepcopy(body["findings"][0])
    sources = {
        7881: [
            "Under a long-term agreement, the Group will support the expansion of its "
            "customer’s existing logic chip production facility.",
            "Air Liquide will build, own and operate several production units to deliver "
            "large volumes of ultra-pure nitrogen, oxygen, argon and hydrogen, including "
            "significant backup storage capacity.",
        ],
        7885: [
            "Dnotitia는 VDPU ASIC 샘플을 제작 후 회수했고, 칩 수준 특성 분석이 진행 중이다.",
            "삼성전기의 증설을 계획했다.",
        ],
    }
    body["findings"] = []
    for finding_id, texts in sources.items():
        finding = deepcopy(template)
        finding.update(
            id=finding_id,
            articleId=finding_id,
            claims=[
                {
                    "id": f"{finding_id}:{index}",
                    "text": text,
                    "claimType": "FACT",
                    "attributedTo": None,
                    "evidenceSentenceIds": [index],
                }
                for index, text in enumerate(texts)
            ],
            sentences=[{"index": index, "text": text} for index, text in enumerate(texts)],
        )
        body["findings"].append(finding)
    return ReportInsightRequest.model_validate(body)


def validate_gas_text(text):
    request = gas_request()
    before = request.model_dump_json(by_alias=True)
    node = ReportInsightReduceAudience.model_validate(
        {
            "audience": "CHIP_MAKER",
            "headline": "가스 공급 계획의 이행 조건 확인",
            "overview": [
                {
                    "text": text,
                    "basisClaimIds": ["7881:0", "7881:1"],
                    "assumption": "투자·장기계약이 예정대로 이행되어 공급이 증대된다",
                }
            ],
            "implications": [],
            "watchItems": [],
        }
    )
    validate_synthesis_quality(
        node, request, [c.id for finding in request.findings for c in finding.claims]
    )
    assert request.model_dump_json(by_alias=True) == before
    assert node.overview[0].text == text
    assert node.overview[0].basis_claim_ids == ["7881:0", "7881:1"]


def test_recorded_investment_progress_does_not_borrow_an_actor_from_another_finding():
    # The unrelated ASIC sentence contains '진행'. It cannot turn this action
    # noun into a company and create a false cross-finding reference gap.
    validate_gas_text(RECORDED_TEXT)


def test_event_progress_does_not_hide_a_real_uncited_company_after_it():
    with pytest.raises(OutputValidationError) as caught:
        validate_gas_text("가스 투자 진행은 삼성전기의 증설 일정에 기여한다.")
    assert "report_synthesis_reference_gap" in caught.value.error_kinds
    assert "삼성전기" in str(caught.value)


def test_actual_investment_owner_cannot_be_replaced_by_its_equipment_supplier():
    with pytest.raises(OutputValidationError) as caught:
        validate(insight(text="태성의 FC-BGA 투자가 현재 집행되고 있다."), source_request())
    assert "report_synthesis_subject_mismatch" in caught.value.error_kinds


def test_progress_word_does_not_turn_an_investment_plan_into_current_execution():
    with pytest.raises(OutputValidationError) as caught:
        validate(insight(text="삼성전기의 투자 진행이 현재 관측된다."), source_request())
    assert "report_synthesis_stage_overreach" in caught.value.error_kinds
