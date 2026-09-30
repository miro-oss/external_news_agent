"""Offline review of saved report-insight candidates; never calls a provider.

Diagnostic flags identify review targets, not measured semantic quality. Human
reviewers score the rubric against the original evidence and compare candidates
blindly. A contract pass alone must not be reported as a quality improvement.
"""

import argparse
import json
import re
from pathlib import Path

from pydantic import ValidationError

from app.llm.base import ProviderResponse, ProviderUsage
from app.llm.report_insight_service import _validated_output
from app.schemas.report_insight import ReportInsightRequest

# Four anchors make reviewer disagreements concrete. Zero in factual integrity
# blocks release regardless of another dimension's score.
QUALITY_RUBRIC = {
    "factual_integrity": (
        "0: 근거에 없는 사실·숫자·기업·확정성을 추가한다.",
        "1: 근거는 있지만 계획·견해·완료를 혼동하거나 귀속을 잃는다.",
        "2: 주장 유형과 사실값을 유지하나 범위 설명이 일부 부족하다.",
        "3: 모든 사실과 귀속을 보존하고 근거가 없는 부분을 명확히 보류한다.",
    ),
    "audience_fit": (
        "0: 관점과 무관하거나 모든 관점에 같은 설명이다.",
        "1: 관점 명칭만 바꾸고 산업 일반론을 반복한다.",
        "2: 관점의 구체 업무와 연결되나 판단 변화가 모호하다.",
        "3: 어떤 업무 판단이 왜 달라지는지 근거와 함께 설명한다.",
    ),
    "importance_calibration": (
        "0: 모든 기사를 높음으로 부풀리거나 판정 불가를 확정한다.",
        "1: 관련성과 영향·시급성을 섞어 평가한다.",
        "2: 축을 구별하고 대부분의 근거 부족을 null로 처리한다.",
        "3: 관련 없음과 판단 보류를 구별하고 날짜 기준·범위에 맞춰 평가한다.",
    ),
    "report_synthesis": (
        "0: 관계없는 이슈를 연결하거나 모순을 숨긴다.",
        "1: 기사별 재요약만 나열한다.",
        "2: 보고서의 공통 판단을 설명하지만 일부 근거 범위가 불명확하다.",
        "3: 공통 결정·제약을 종합하고 독립 이슈·모순은 분리해서 처리한다.",
    ),
    "conditional_mechanism": (
        "0: 확인되지 않은 결과를 사실로 단정한다.",
        "1: 가능성을 말하지만 전제·경로·반증이 일반론이다.",
        "2: 경로와 조건이 구체적이나 반증 대상이 일부 모호하다.",
        "3: 근거→업무 영향 경로, 성립 조건, 해석을 철회할 관측을 특정한다.",
    ),
    "actionable_watch": (
        "0: 원문 없는 새 수치·기한을 확인 목표로 만든다.",
        "1: 후속 동향 확인 같은 관찰 문구만 있다.",
        "2: 지표·발표·일정을 특정하지만 판단 변화가 모호하다.",
        "3: 무엇을 확인하고 어떤 관측이 우선순위·해석을 바꾸는지 설명한다.",
    ),
}

_GENERIC = re.compile(
    r"(?:관련\s*(?:영향\s*)?변수|현재\s*관측이\s*유지|기술\s*발전에\s*주목|"
    r"산업에\s*중요|후속\s*(?:동향|뉴스)\s*(?:을\s*)?확인|긍정적인\s*영향)"
)
_DECISION_TERMS = {
    "CHIP_MAKER": re.compile(r"공정|생산|수율|인증|출시|원재료|가동"),
    "EQUIPMENT_MAKER": re.compile(r"검증|인증|발주|설치|장비|소재|납기|수주"),
    "MARKET_INVESTOR": re.compile(r"가이던스|공시|매출|실적|지출|이익|수급|투자"),
    "IT_INFRA": re.compile(r"조달|호환|전력|냉각|네트워크|운영|도입|시스템"),
}


def review_saved_output(request: ReportInsightRequest, payload: dict) -> dict:
    """Return contract status and independent, conservative editorial diagnostics."""
    try:
        output = _validated_output(
            ProviderResponse(
                text=json.dumps(payload, ensure_ascii=False),
                provider="mock",
                model="saved-output",
                usage=ProviderUsage(),
            ),
            request,
        )
    except (ValueError, ValidationError):
        return {
            "contractPassed": False,
            "flags": ["contract_failure"],
            "requiresHumanReview": True,
            "qualityMeasured": False,
        }
    flags = []
    for insight in output.insights:
        prose = "\n".join(
            [
                *(item.reason for item in insight.assessments),
                *(item.text for item in insight.overview),
                *(item.text for item in insight.implications),
            ]
        )
        if _GENERIC.search(prose):
            flags.append(f"{insight.audience}:generic_interpretation")
        relevant = any(
            item.axes.directness is not None and item.axes.directness > 0
            for item in insight.assessments
        )
        if relevant and not _DECISION_TERMS[insight.audience].search(prose):
            flags.append(f"{insight.audience}:missing_audience_decision")
        for index, implication in enumerate(insight.implications):
            if _GENERIC.search(
                " ".join(
                    [
                        implication.mechanism,
                        implication.assumption,
                        implication.falsified_by,
                    ]
                )
            ):
                flags.append(f"{insight.audience}:generic_condition:{index}")
        for index, item in enumerate(insight.watch_items):
            if _GENERIC.search(item.indicator + " " + item.trigger):
                flags.append(f"{insight.audience}:generic_watch:{index}")
    return {
        "contractPassed": True,
        "flags": flags,
        "requiresHumanReview": True,
        "qualityMeasured": False,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--request", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    args = parser.parse_args()
    request = ReportInsightRequest.model_validate_json(args.request.read_text(encoding="utf-8"))
    payload = json.loads(args.candidate.read_text(encoding="utf-8"))
    print(
        json.dumps(
            {
                "diagnostics": review_saved_output(request, payload),
                "humanRubric": QUALITY_RUBRIC,
                "releaseGate": (
                    "factual_integrity=0은 배포 불가; 관점별 원문 검토와 후보 블라인드 비교 필요"
                ),
            },
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
