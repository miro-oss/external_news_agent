"""Unlabelled, anonymized semantic-review pilot, not a human golden dataset.

The cases intentionally exercise source-binding failure modes. No expected label
is stored: a real reviewer must supply every verdict before quality is measured.
"""

from app.eval.report_insight_run import digest

# These are newly authored examples, not copied private report sentences.
PILOT_CASES = (
    (
        "owner_quantity",
        "다온반도체의 생산량은 20개, 누리반도체의 생산량은 10개다.",
        "다온반도체의 생산량은 10개다.",
    ),
    (
        "plan_completion",
        "하람전자는 2028년에 제2공장을 완공할 계획이라고 밝혔다.",
        "하람전자는 제2공장을 완공했다.",
    ),
    (
        "joint_investment",
        "누리전자와 다온전자는 공동으로 총 80조 원을 투자할 계획이다.",
        "두 회사의 공동 투자 계획 규모는 총 80조 원이다.",
    ),
    (
        "year_capacity",
        "바람냉각은 냉각 설비 용량을 2026년 2GW에서 2030년 8GW로 늘릴 계획이다.",
        "바람냉각의 2030년 냉각 설비 용량 목표는 8GW다.",
    ),
    (
        "unmentioned_order",
        "소나무테크는 신제품의 고객 인증을 진행하고 있다.",
        "소나무테크는 신제품 장비를 발주했다.",
    ),
    (
        "speaker_forecast",
        "새길증권은 가온전자의 내년 매출이 15% 증가할 것으로 전망했다.",
        "새길증권은 가온전자의 내년 매출 증가율을 15%로 전망했다.",
    ),
    (
        "joint_allocation",
        "아람전자와 보람전자는 공동 투자 예산을 총 40조 원으로 정했다.",
        "아람전자의 개별 투자 예산은 40조 원이다.",
    ),
    (
        "contract_absence",
        "아람시스템은 고객과 공급 계약 협상을 진행 중이며 계약은 아직 체결하지 않았다.",
        "아람시스템은 고객과 공급 계약을 체결했다.",
    ),
    (
        "conditional_effect",
        "푸른메모리는 시험 생산을 시작했으며 양산 일정은 공개하지 않았다.",
        "푸른메모리의 시험 생산 개시는 확인되지만 양산 일정은 확인할 수 없다.",
    ),
    (
        "relative_change",
        "솔빛소자의 신제품은 이전 제품보다 전력 소비량이 최대 35% 낮다.",
        "솔빛소자의 신제품 전력 소비량은 35W다.",
    ),
    (
        "cross_location",
        "가온연구원의 측정에서 A 지점의 염소 농도는 700mg/L, B 지점은 600mg/L였다.",
        "B 지점의 염소 농도는 700mg/L였다.",
    ),
    (
        "unmentioned_target",
        "샘물장비는 생산 라인의 유지보수 서비스를 출시했다.",
        "샘물장비의 서비스는 반도체 공정 수율을 높인다.",
    ),
)


def pilot_packet() -> dict:
    units = []
    for index, (family, source, statement) in enumerate(PILOT_CASES, start=1):
        claim_id = f"pilot-{index:02d}:0"
        units.append(
            {
                "unitId": f"pilot-{index:02d}",
                "field": "fact",
                "statementKind": "fact",
                "statement": statement,
                "statementHash": digest(statement),
                "sourceHash": digest(source),
                "sourceGroup": family,
                "candidateContext": {},
                "candidateClaimIds": [claim_id],
                "source": [
                    {
                        "claimId": claim_id,
                        "claimText": source,
                        "claimType": "FACT",
                        "attributedTo": None,
                        "sentences": [{"sentenceId": 0, "text": source}],
                    }
                ],
            }
        )
    packet = {
        "schemaVersion": 1,
        "purpose": "unlabelled-anonymized-review-pilot",
        "origin": "newly-authored-examples-not-human-truth-or-production-corpus",
        "units": units,
        "assessments": [],
        "verdictDefinitions": {
            "SUPPORTED": "원문이 주체·수치·시점·확정성·귀속을 포함해 문장을 뒷받침함",
            "CONTRADICTED": "원문에 문장과 직접 충돌하는 근거가 있음",
            "INSUFFICIENT": "원문만으로 지지 여부 또는 모순 여부를 판단할 수 없음",
        },
        "humanLabels": "absent; must be supplied by a real reviewer",
    }
    return {**packet, "packetHash": digest(packet)}
