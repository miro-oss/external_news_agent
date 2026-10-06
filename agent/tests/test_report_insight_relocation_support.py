"""Source-event boundaries from the observed staff-to-network inference."""

from copy import deepcopy

import pytest

from app.llm.report_insight_relocation_support import relocation_support_problems
from app.schemas.report_insight import ReportInsightFinding
from app.schemas.report_insight_assessment import ReportFindingAssessmentDraft

SOURCE = (
    "하나금융지주를 비롯해 ▲은행▲증권 ▲카드 등 10개 관계사 임직원 약 2200명이 "
    "청라 그룹헤드쿼터(HQ)로 이동한다."
)
RECORDED_REASONS = (
    "10개 계열사 임직원 약 2200명의 청라 HQ 이동은 사무공간 이전에 따른 "
    "네트워크·시스템 이전·운영 준비와 직접 연관되므로 도입/운영 관점 업무에 해당한다.",
    "10개 계열사 임직원 약 2200명의 HQ 이동은 본사 인프라(사무공간 네트워크·시스템 "
    "설치·운영) 재배치가 수반될 가능성이 크므로 IT 도입·운영(DEPLOYMENT_OPERATIONS)과 "
    "직접 연결된다. 근거: claim 7819:1 및 해당 연결 문장.",
)


def case(
    source=SOURCE, *, reason=RECORDED_REASONS[0], relation="DIRECT", work="DEPLOYMENT_OPERATIONS"
):
    finding = ReportInsightFinding.model_validate(
        {
            "id": 901,
            "articleId": 1901,
            "articleTitle": "원문 사건 대조",
            "canonicalUrl": "https://example.test/relocation",
            "topicName": "산업",
            "publishedAt": "2026-10-02",
            "claims": [
                {
                    "id": "901:0",
                    "text": source,
                    "claimType": "FACT",
                    "attributedTo": None,
                    "evidenceSentenceIds": [0],
                }
            ],
            "sentences": [{"index": 0, "text": source}],
        }
    )
    related = relation not in {"UNRELATED", "UNDETERMINED"}
    item = ReportFindingAssessmentDraft.model_validate(
        {
            "findingId": 901,
            "relation": relation,
            "work": work if related else None,
            "condition": "실제로 네트워크 이전을 수행하는 경우"
            if relation in {"CONDITIONAL", "BACKGROUND"}
            else None,
            "relationBasis": {"claimId": "901:0", "quote": source}
            if relation != "UNDETERMINED"
            else None,
            "impactScope": "UNDETERMINED",
            "impactBasis": None,
            "urgencyState": "UNDETERMINED",
            "urgencyBasis": None,
            "reason": reason,
        }
    )
    return item, finding


@pytest.mark.parametrize("reason", RECORDED_REASONS)
def test_recorded_staff_only_source_rejects_low_and_medium_direct_inference(reason):
    item, finding = case(reason=reason)
    original = deepcopy((item.model_dump(), finding.model_dump()))
    problems = relocation_support_problems(item, finding, "IT_INFRA")
    assert len(problems) == 1
    assert problems[0].native_field == "decision.connection.relation"
    assert problems[0].claim_ids == ("901:0",)
    assert problems[0].problem == "physical_relocation_only_it_direct"
    assert (item.model_dump(), finding.model_dump()) == original


@pytest.mark.parametrize("work", ["NETWORK", "SYSTEM_PROCUREMENT", "DEPLOYMENT_OPERATIONS"])
@pytest.mark.parametrize(
    "source",
    [
        "IT 운영 임직원들이 새 본사로 이동한다.",
        "네트워크 담당 직원들이 새 본사로 이동한다.",
        "네트워크 기업의 본사를 서울로 이전한다.",
        "회사는 본사를 새 사옥으로 이전할 계획이다.",
    ],
)
def test_it_staff_and_company_descriptions_do_not_supply_a_system_event(source, work):
    item, finding = case(source, work=work)
    assert relocation_support_problems(item, finding, "IT_INFRA")


@pytest.mark.parametrize(
    "source",
    [
        "직원들이 본사로 이동하며 서버와 네트워크도 함께 이전한다.",
        "본사를 이전하면서 새 통신망을 구축한다.",
        "본사를 이전하고 IT 시스템을 통합할 예정이다.",
        "새 본사에 직원들이 입주한다. 서버를 설치할 계획이다.",
        "네트워크 담당 직원들이 서버 이전을 수행한다.",
        "본사 이전에 따른 전산 장비 조달 조건을 확정했다.",
        "직원들이 본사를 이전한다. 정보시스템의 운용 조건은 그대로 유지한다.",
        "직원들이 이동통신 기술을 개발한다.",
        "본사 직원들의 네트워크 운영 역량을 강화한다.",
        "본사 직원들은 이전에 라우터를 설치했다.",
        "본사는 이전에 발표한 제품을 홍보한다.",
        "직원들이 본사로 이동하면서 라우터와 스위치를 재설치한다.",
    ],
)
def test_real_it_evidence_and_nonrelocation_events_are_outside_the_rejection(source):
    item, finding = case(source)
    assert relocation_support_problems(item, finding, "IT_INFRA") == ()


@pytest.mark.parametrize("relation", ["CONDITIONAL", "BACKGROUND", "UNRELATED", "UNDETERMINED"])
def test_other_relations_are_not_forced_unrelated(relation):
    item, finding = case(relation=relation)
    assert relocation_support_problems(item, finding, "IT_INFRA") == ()


def test_only_selected_relation_claim_original_sentences_can_support_it_event():
    item, finding = case()
    # A compressed claim overstates the source; another unselected claim also
    # contains an IT event. Neither is the selected relation's original source.
    finding.claims[0].text = "직원 이동과 함께 네트워크를 이전한다."
    other = finding.claims[0].model_copy(update={"id": "901:1", "evidence_sentence_ids": [1]})
    finding.claims.append(other)
    finding.sentences.append(
        finding.sentences[0].model_copy(update={"index": 1, "text": "서버를 이전한다."})
    )
    assert relocation_support_problems(item, finding, "IT_INFRA")
    item.relation_basis = item.relation_basis.model_copy(
        update={"claim_id": "901:1", "quote": "서버를 이전한다."}
    )
    assert relocation_support_problems(item, finding, "IT_INFRA") == ()


def test_other_audiences_work_and_invalid_basis_keep_their_existing_validation():
    item, finding = case()
    assert relocation_support_problems(item, finding, "MARKET_INVESTOR") == ()
    item.work = "POWER_COOLING"
    assert relocation_support_problems(item, finding, "IT_INFRA") == ()
    item.work = "NETWORK"
    item.relation_basis = item.relation_basis.model_copy(update={"claim_id": "902:0"})
    assert relocation_support_problems(item, finding, "IT_INFRA") == ()
