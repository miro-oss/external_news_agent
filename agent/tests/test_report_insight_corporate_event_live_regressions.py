"""Minimal selected evidence and prose captured from report 2005's MAP retries."""

import pytest

from app.llm.report_insight_service import _prose_validation_errors, _source_context
from app.schemas.report_insight import ReportInsightRequest

VALUATION = (
    "SK하이닉스의 밸류에이션(실적 대비 주가 수준)이 미국 메모리 반도체 기업 "
    "마이크론테크놀로지에 비해 큰 폭으로 할인받고 있다는 분석이 나왔다."
)
FUNDING = "솔리다임은 이번 IPO를 통해 약 100억달러(약 13조4000억원)를 조달할 것으로 예상된다."
REASONS = {
    8307: (
        "기사의 SK하이닉스 밸류에이션 할인 및 자회사 솔리다임의 IPO(약 100억달러 예상)는 "
        "주주가치·밸류에이션 구조에 직접적 영향 요인으로, 투자 관점의 밸류에이션·수익성 "
        "판단과 직접 연결된다.",
        "원문 선택 근거에 따르면 SK하이닉스의 밸류에이션이 마이크론 대비 할인된 상태로 "
        "분석되며, 동일 기사에서 언급된 IPO 규모 전망은 특정 자금조달·프로젝트 여건 변화를 "
        "시사해 관련 준비·조건 변동 가능성을 초래할 수 있다.",
    ),
    8288: (
        "솔리다임의 나스닥 상장 추진과 신주 발행 논의는 조달 자금을 미국 내 생산시설 투자에 "
        "투입할 경우 현지 생산·증설 자금 조달과 통상 대응에 영향을 주어 자금조달·CAPEX "
        "판단과 조건부로 연결된다.",
        "신주 발행으로 조달자금이 솔리다임에 유입된다는 근거와(신주 발행 관련 문장), "
        "블룸버그 보도로 이르면 내년 약 100억달러 조달 전망이 제시된 점을 근거로, 회사가 "
        "실제로 IPO로 자금을 확보해 이를 미국 내 생산시설 투자에 배분할 경우 낸드 투자 "
        "재원 확보와 현지 투자 확대 영향이 발생할 수 있다는 조건부 판단이다.",
    ),
}


def source_request(finding_id):
    # Keep only claims cited by the captured decisions, and their raw sentences.
    if finding_id == 8307:
        article_id = 34901
        claims = (
            (0, "SK하이닉스는 마이크론 대비 밸류에이션이 큰 폭으로 할인받고 있다.", [0, 4, 7]),
            (4, "IPO를 통해 약 100억달러를 조달할 것으로 예상된다고 보도됐다.", [20]),
        )
        sentences = {
            0: VALUATION,
            4: "김준영 iM증권 연구원은 12일 보고서에서 마이크론의 SK하이닉스 대비 "
            "주가수익비율(PER) 프리미엄이 최근 1.60배까지 확대됐다고 분석했다.",
            7: "SK하이닉스의 12개월 선행 PER은 지난달 23일 4.46배에서 최근 3.70배로 "
            "약 17% 낮아졌지만 마이크론은 5.9~6.9배 수준을 유지했다.",
            20: FUNDING,
        }
        forecast_claims = {4}
    else:
        assert finding_id == 8288
        article_id = 34888
        claims = (
            (
                7,
                "상장으로 조달한 자금을 미국 내 생산시설에 투입하면 미국 통상정책 대응과 "
                "현지 협력 강화에 도움이 될 수 있다.",
                [119, 121],
            ),
            (
                3,
                "신주 발행이면 조달자금이 솔리다임으로 유입돼 낸드 투자 재원 확보에 유리하다.",
                [41, 94],
            ),
            (
                1,
                "블룸버그 등에 따르면 대표 주관사를 선정했고 이르면 내년 약 100억달러 조달을 "
                "예상하는 보도도 있다.",
                [37],
            ),
        )
        sentences = {
            37: "블룸버그통신에 따르면 솔리다임은 미국 IPO를 위한 대표 주관사를 선정했고, "
            "이르면 내년 상장을 통해 약 100억달러(13조4000억원)를 조달할 것으로 예상된다.",
            41: "신주 발행은 솔리다임이 새 주식을 발행하는 방식으로 기존 주주의 지분율은 "
            "낮아지지만 자금은 솔리다임에 들어온다.",
            94: "기존 주주가 보유한 주식을 매각하는 구주매출과 달리 신주 발행은 조달 자금이 "
            "솔리다임에 직접 유입되기 때문에 낸드 사업 투자 재원 확보에 유리하다.",
            119: "도널드 트럼프 행정부가 반도체 기업들의 현지 투자를 압박하는 가운데 나스닥 "
            "상장이 생산시설 투자 재원을 마련하는 통로가 될 수 있어서다.",
            121: "솔리다임이 IPO 자금을 미국 내 생산시설에 투입한다면 현지 생산 확대를 요구하는 "
            "미국 정부의 정책 기조에 대응하고 고객사와의 협력도 강화할 수 있을 것으로 보인다.",
        }
        forecast_claims = {7}
    return ReportInsightRequest.model_validate(
        {
            "idempotencyKey": "local-corporate-event-regression",
            "plan": "FREE",
            "audiences": ["MARKET_INVESTOR"],
            "report": {
                "id": 2005,
                "title": "반도체 산업 동향",
                "reportScope": "RUN",
                "reportDate": "2026-10-13",
            },
            "findings": [
                {
                    "id": finding_id,
                    "articleId": article_id,
                    "articleTitle": "솔리다임 IPO",
                    "canonicalUrl": "https://example.test/captured-corporate-event",
                    "publishedAt": "2026-10-13",
                    "topicName": "반도체 산업 동향",
                    "claims": [
                        {
                            "id": f"{finding_id}:{index}",
                            "text": text,
                            "claimType": "FORECAST" if index in forecast_claims else "FACT",
                            "evidenceSentenceIds": ids,
                        }
                        for index, text, ids in claims
                    ],
                    "sentences": [
                        {"index": index, "text": text} for index, text in sentences.items()
                    ],
                }
            ],
        }
    )


def prose_errors(request, reason, refs, *, conditional=False):
    claims, evidence = _source_context(request)
    return _prose_validation_errors(
        [reason], refs, evidence, claims, request=request, conditional=conditional
    )


@pytest.mark.parametrize("finding_id", [8307, 8288])
@pytest.mark.parametrize("attempt", [0, 1], ids=["initial", "repair"])
def test_captured_reason_accepts_its_selected_ipo_and_funding_evidence(finding_id, attempt):
    request = source_request(finding_id)
    before = request.model_dump_json()
    refs = [claim.id for claim in request.findings[0].claims]
    assert prose_errors(request, REASONS[finding_id][attempt], refs) == []
    assert request.model_dump_json() == before


def test_captured_capex_condition_accepts_selected_conditional_investment_evidence():
    request = source_request(8288)
    condition = (
        "솔리다임이 실제로 IPO로 자금을 조달하고 그 자금을 미국 내 생산시설 투자에 배분해야 "
        "CAPEX 실행 영향이 발생한다."
    )
    refs = [claim.id for claim in request.findings[0].claims]
    assert prose_errors(request, condition, refs, conditional=True) == []


@pytest.mark.parametrize("scope", ["unselected_claim", "claim_summary", "title"])
@pytest.mark.parametrize("reason", REASONS[8307], ids=["initial", "repair"])
def test_valuation_only_selection_cannot_borrow_ipo_or_funding_from_elsewhere(scope, reason):
    request = source_request(8307)
    finding = request.findings[0]
    if scope != "unselected_claim":
        finding.claims = finding.claims[:1]
        finding.sentences = [sentence for sentence in finding.sentences if sentence.index != 20]
    if scope == "claim_summary":
        finding.claims[0].text += " " + FUNDING
    elif scope == "title":
        finding.article_title = FUNDING
    before = request.model_dump_json()
    errors = prose_errors(request, reason, ["8307:0"])
    assert any("source_binding" in getattr(error, "fact_repair_kinds", ()) for error in errors)
    assert request.model_dump_json() == before
