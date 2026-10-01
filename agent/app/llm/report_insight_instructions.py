"""Small stage/role-specific instructions; examples are fictional source data."""

import json
from pathlib import Path

from app.schemas.analyze import Audience

_ROOT = Path(__file__).resolve().parents[1] / "prompts"
_BASE = (_ROOT / "report-insight.ko.v5.md").read_text(encoding="utf-8").strip()
_RUBRIC = (_ROOT / "report-importance.v4.md").read_text(encoding="utf-8").strip()
_ROLES = {
    "CHIP_MAKER": "칩 제조: 공정/인증 PROCESS_QUALIFICATION, 생산 일정 PRODUCTION_SCHEDULE, "
    "수율/능력 YIELD_CAPACITY, 고객 요구·메모리 공급 약정 CUSTOMER_REQUIREMENTS, "
    "소재 확보 MATERIAL_SUPPLY. 실제 공급 약정의 관계와 생산 변경 규모·시점은 별개다.",
    "EQUIPMENT_MAKER": "장비 공급: 공정 검증 PROCESS_VALIDATION, 설계 채택 DESIGN_IN, "
    "실제 발주/수주 ORDER_BOOKING, 납품/설치 DELIVERY_INSTALLATION, 서비스 "
    "MAINTENANCE_SERVICE. 제조사 증설 계획은 장비 발주가 아니다.",
    "MARKET_INVESTOR": "투자 판단: 전망 GUIDANCE, 투자 집행 CAPEX_EXECUTION, 매출 인식 "
    "REVENUE_RECOGNITION, 이익률 PROFITABILITY, 수급 제약 SUPPLY_DEMAND_CONSTRAINT. "
    "공시 전망과 실제 실적을 구분하며 매수·매도·목표가는 쓰지 않는다.",
    "IT_INFRA": "IT 운영: 시스템 조달 SYSTEM_PROCUREMENT, 호환성 COMPATIBILITY, "
    "전력/냉각 POWER_COOLING, 네트워크 NETWORK, 도입/운영 DEPLOYMENT_OPERATIONS. "
    "소재 공장·칩 실험은 서버 도입이 아니다. 메모리 가격·공급 조건은 조달 판단에 연결될 수 있다.",
}
_POSITIVE = {
    "CHIP_MAKER": (
        "가상 제조사 M은 고객의 메모리 규격 승인이 있어야 공급을 준비한다. 인증팀은 다음 달 "
        "승인 전에 해당 규격의 사전 검증 준비만 조정한다.",
        "CUSTOMER_REQUIREMENTS",
        "해당 규격의 고객 승인 전에는 공급 가능 여부를 보류하고 인증팀 검증 준비를 조정한다.",
        "해당 메모리 규격의 고객 승인 결과",
        "고객이 해당 메모리 규격을 승인하는 경우",
        "고객 승인이 공급 준비의 전제이므로 같은 규격의 승인 전에는 공급 판단을 보류한다.",
        "같은 공급안에서 해당 규격을 제외하거나 고객 승인 요건을 삭제한 변경이 확인될 때",
    ),
    "EQUIPMENT_MAKER": (
        "가상 장비사 E는 고객의 호환성 검증 승인이 있어야 장비를 설치한다. 설치팀은 다음 달 "
        "승인 전에 해당 장비의 현장 사전 검증 준비만 조정한다.",
        "DELIVERY_INSTALLATION",
        "해당 장비의 검증 승인 전에는 설치 가능 여부를 보류하고 설치팀 준비를 조정한다.",
        "해당 장비의 고객 호환성 검증 결과",
        "고객이 해당 장비의 호환성 검증을 승인하는 경우",
        "검증 승인이 설치의 전제이므로 같은 장비의 승인 전에는 설치 판단을 보류한다.",
        "같은 설치안에서 해당 장비를 제외하거나 검증 승인 요건을 삭제한 변경이 확인될 때",
    ),
    "MARKET_INVESTOR": (
        "가상 회사 P는 고객 검수 승인이 있어야 해당 계약의 매출을 인식한다. 재무팀은 다음 달 "
        "검수 전에 이 계약의 대금 인식 조건만 확인한다.",
        "REVENUE_RECOGNITION",
        "해당 계약의 검수 승인 전에는 매출 인식 판단을 보류하고 재무팀 확인 준비를 조정한다.",
        "해당 계약의 고객 검수 승인 결과",
        "고객이 해당 계약의 검수를 승인하는 경우",
        "검수 승인이 매출 인식의 전제이므로 같은 계약의 승인 전에는 인식 판단을 보류한다.",
        "같은 계약에서 해당 거래를 제외하거나 검수 승인 요건을 삭제한 변경이 확인될 때",
    ),
    "IT_INFRA": (
        "가상 운영사 D는 냉각 호환성 검증을 통과해야 서버 도입을 승인한다. 운영팀은 다음 달 "
        "검증 전에 해당 도입안의 사전 냉각 검증 준비만 조정한다.",
        "POWER_COOLING",
        "해당 도입안의 냉각 검증 전에는 도입 승인 판단을 보류하고 운영팀 검증 준비를 조정한다.",
        "해당 도입안의 냉각 호환성 검증 결과",
        "해당 도입안의 냉각 호환성 검증을 통과하는 경우",
        "냉각 검증 통과가 도입 승인의 전제이므로 같은 도입안의 결과 전에는 승인을 보류한다.",
        "같은 도입안에서 해당 냉각 모듈을 제외하거나 검증 승인 요건을 삭제한 변경이 확인될 때",
    ),
}


def _dump(value: dict) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def _map_example(audience: Audience, *, known: bool) -> str:
    source, work, reason, _, condition, _, _ = _POSITIVE[audience]
    if not known:
        source = "가상 회사 X가 기술 사업에 관심을 표했다. 구체적인 사업 대상은 공개하지 않았다."
        reason = "원문에 사건은 있으나 해당 관점 업무에 이어지는 대상과 전제를 판단할 수 없다."
    record = {
        "findingId": 11,
        "connection": {
            "basis": {"claimId": "11:0", "sourceSpanId": "s11_0_0"} if known else None,
            "work": work if known else None,
            "condition": None,
            "relation": "DIRECT" if known else "UNDETERMINED",
        },
        "effect": {
            "basis": {"claimId": "11:0", "sourceSpanId": "s11_0_0"} if known else None,
            "impactScope": "LIMITED_PREPARATION" if known else "UNDETERMINED",
        },
        "timing": {
            "basis": {"claimId": "11:0", "sourceSpanId": "s11_0_0"} if known else None,
            "urgencyState": "SCHEDULED_PREPARATION" if known else "UNDETERMINED",
        },
        "reason": reason,
    }
    return f"가상 원문 11:0/s11_0_0={source}\n" + _dump(
        {"assessments": {audience: {"finding11": record}}}
    )


def _reduce_example(audience: Audience, *, known: bool) -> str:
    source, _, reason, indicator, condition, mechanism, falsified_by = _POSITIVE[audience]
    insight = {
        "audience": audience,
        "headline": reason if known else "이 관점의 관련 근거가 부족합니다.",
        "overview": [{"text": reason, "basisClaimIds": ["11:0"], "assumption": condition}]
        if known
        else [],
        "implications": [
            {
                "text": reason,
                "mechanism": mechanism,
                "basisClaimIds": ["11:0"],
                "assumption": "같은 대상의 승인 요건이 유지되는 경우",
                "falsifiedBy": falsified_by,
            }
        ]
        if known
        else [],
        "watchItems": [
            {
                "topic": "적용 조건",
                "indicator": indicator,
                "trigger": "해당 조건의 실제 상태가 확인되면 보류한 적용 판단을 갱신한다.",
                "basisClaimIds": ["11:0"],
            }
        ]
        if known
        else [],
    }
    if not known:
        source = "가상 회사 X가 기술 사업에 관심을 표했다. 구체적인 사업 대상은 공개하지 않았다."
    return f"가상 근거 11:0={source}\n" + _dump({"insights": [insight]})


def report_stage_instruction(audiences: list[Audience], stage: str) -> str:
    """Only requested roles and the active stage; two complete small examples."""
    if not audiences or stage not in {"MAP", "REVIEW", "REDUCE"}:
        raise ValueError("A report stage needs requested audiences and a known stage")
    roles = "\n".join(f"{audience}: {_ROLES[audience]}" for audience in audiences)
    common = _BASE + "\n\n" + roles
    if stage in {"MAP", "REVIEW"}:
        stage_text = (
            f"현재 단계는 {stage}. sourceQuoteChoices의 같은 claim/span basis를 먼저 고르고 "
            "그 사건이 바꾸는 work를 선택한다. 연결할 업무를 특정할 수 없으면 work와 basis는 "
            "null, relation은 UNDETERMINED다. 단순 정보 부족을 condition으로 쓰지 않는다. "
            "승인·검수·검증이 해당 업무 자체라면 DIRECT다. 그 결과가 아직 미정인 것은 "
            "업무 연결의 불확실성과 다르며 connection.condition에 넣지 않는다. "
            "claims=[]만 고정 reason='검증을 통과한 claim 근거가 없어 중요도 판단을 보류합니다.'다."
        )
        if stage == "REVIEW":
            stage_text += (
                " 이전 답변은 제공되지 않는다. 모든 대상의 원문을 독립적으로 다시 판정한다."
            )
        examples = [
            _map_example(audiences[0], known=True),
            _map_example(audiences[-1], known=False),
        ]
        return common + "\n\n" + _RUBRIC + "\n\n" + stage_text + "\n\n" + "\n".join(examples)
    stage_text = (
        "현재 단계는 REDUCE. decisionCandidates는 같은 원문을 묶은 판단 보조이며 새 사실이 "
        "아니다. 각 후보의 work/connectionBasis와 retrievedEvidence 원문을 먼저 읽는다. "
        "priorityRank는 기존 중요도 순서이며 unavailable은 규모 미확인이지 무관이 아니다. "
        "알려진 사건→결정하거나 보류할 구체 업무→확인할 전제를 overview에 쓴다. 업무 연결이 "
        "알려졌는데 규모·시점만 불명인 것을 관련 근거 부족으로 바꾸지 않는다. 독립 사건은 "
        "분리하고 실제 공통 업무/대상일 때만 여러 finding을 종합한다. basisClaimIds는 중복 "
        "없이 필요한 최소 claim만 선택한다. implications는 근거→중간 조건→판단의 구체 경로가 "
        "있을 때만 작성한다. assumption은 성립 조건, falsifiedBy는 같은 대상의 해석을 "
        "뒤집는 관측 사건이다. 자료 부재는 반증이 아니다. watchItems는 실제 계약 범위·이행·"
        "검증 등 관측과 그 결과가 바꾸는 판단을 쓴다. 새 수치/기한은 만들지 않는다. 모두 "
        "무관/미확인이면 관련 근거 부족 headline과 빈 배열이다. assessments는 작성하지 않는다."
    )
    examples = [
        _reduce_example(audiences[0], known=True),
        _reduce_example(audiences[-1], known=False),
    ]
    return common + "\n\n" + stage_text + "\n\n" + "\n".join(examples)
