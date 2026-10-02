"""Source-bound stage instructions without fictional answer-copying anchors."""

from pathlib import Path

from app.schemas.analyze import Audience

_ROOT = Path(__file__).resolve().parents[1] / "prompts"
_BASE = (_ROOT / "report-insight.ko.v9.md").read_text(encoding="utf-8").strip()
_RUBRIC = (_ROOT / "report-importance.v6.md").read_text(encoding="utf-8").strip()
_ROLES = {
    "CHIP_MAKER": "칩 제조: 공정 인증·설계 IP/공정 적용 PROCESS_QUALIFICATION, "
    "생산 일정 PRODUCTION_SCHEDULE, "
    "수율/능력 YIELD_CAPACITY, 고객 요구·공급 약정 CUSTOMER_REQUIREMENTS, 소재 확보 "
    "MATERIAL_SUPPLY. 메모리/HBM 제조사의 실제 고객 공급 계약은 CUSTOMER_REQUIREMENTS의 "
    "직접 관계일 수 있다. 공정·수율이 없다는 이유로 그 계약을 무관 처리하지 않는다. "
    "계약은 생산 증가·규격 승인·납품 완료를 뜻하지 않는다. 공정 인증·설계 적용은 "
    "고객 계약이 없어도 PROCESS_QUALIFICATION에서 판단한다. 생산능력·생산 배분은 "
    "YIELD_CAPACITY/PRODUCTION_SCHEDULE에서 판단한다.",
    "EQUIPMENT_MAKER": "장비 공급: 공정 검증 PROCESS_VALIDATION, 설계 채택 DESIGN_IN, "
    "실제 발주/수주 ORDER_BOOKING, 납품/설치 DELIVERY_INSTALLATION, 서비스 "
    "MAINTENANCE_SERVICE. 공정 검증·설계 채택은 발주 확인을 전제로 하지 않는다. "
    "제조사 증설 계획은 장비 발주가 아니다.",
    "MARKET_INVESTOR": "투자 판단: 전망 GUIDANCE, 투자 집행 CAPEX_EXECUTION, 매출 인식 "
    "REVENUE_RECOGNITION, 이익률 PROFITABILITY, 수급 제약 SUPPLY_DEMAND_CONSTRAINT. "
    "공시 전망과 실제 실적을 구분하며 매수·매도·목표가는 쓰지 않는다.",
    "IT_INFRA": "IT 운영: 시스템 조달 SYSTEM_PROCUREMENT, 호환성 COMPATIBILITY, "
    "전력/냉각 POWER_COOLING, 네트워크 NETWORK, 도입/운영 DEPLOYMENT_OPERATIONS. "
    "시스템 구성품의 가격·공급 조건과 전망은 시스템 조달 판단에 연결될 수 있다. "
    "확정 공급 조건과 전망을 구분한다. 그것만으로 이미 "
    "조달 비용이 변했거나 냉각 승인 절차가 존재한다고 만들지 않는다. 소재 공장은 서버 "
    "운영이 아니다.",
}


def report_stage_instruction(audiences: list[Audience], stage: str) -> str:
    """Requested roles, active stage and source rules; schema defines the shape."""
    if not audiences or stage not in {"MAP", "REVIEW", "REDUCE"}:
        raise ValueError("A report stage needs requested audiences and a known stage")
    roles = "\n".join(f"{audience}: {_ROLES[audience]}" for audience in audiences)
    common = _BASE + "\n\n" + roles
    if stage in {"MAP", "REVIEW"}:
        stage_text = (
            f"현재 단계는 {stage}. finding마다 원문의 주체·대상·사건·단계를 확인하고, "
            "연결되는 업무와 실제로 명시된 변경 범위·시점을 각각 찾는다. 그 근거로 "
            "connection.relation, effect.impactScope, timing.urgencyState를 독립 판정한 뒤 "
            "해당 범주의 Schema 필드를 작성한다. 관계를 찾았다고 영향 확인을 생략하거나 "
            "모든 effect를 같은 값으로 채우지 않는다. 알려진 축의 basis는 같은 "
            "finding/claim의 실제 원문을 선택하며 축별로 다른 claim을 사용할 수 있다. "
            "알려진 축은 basis 필수, UNDETERMINED인 축은 basis=null이다. "
            "확인된 업무 관계를 유지하면서 영향·시점만 UNDETERMINED로 둘 수 있다. "
            "reason은 180자 이내로 이 사건의 업무 연결, 확인된 영향 범위 또는 영향 범위를 "
            "판정하지 못한 구체적 이유를 설명한다. 다른 항목의 이유를 복사하지 않는다. "
            "관계를 판정했다면 reason에 관계 판단 불가를 동시에 선언하지 않는다. "
            "실제 claims=[]인 항목의 고정 reason은 해당 항목의 Schema const만 따른다."
        )
        if stage == "REVIEW":
            stage_text += (
                " 이전 답변은 제공되지 않는다. 원문으로 독립 재판정한다. 검토 대상으로 "
                "선정됐다는 사실은 오답·보류·고득점의 근거가 아니다."
            )
        return common + "\n\n" + _RUBRIC + "\n\n" + stage_text
    stage_text = (
        "현재 단계는 REDUCE. decisionCandidates의 범주·순위는 판단 보조이며 원문은 "
        "retrievedEvidence와 connectionBasis다. 원문 사건과 연결된 구체 업무, 결정하거나 "
        "보류할 판단을 overview에 설명한다. 업무 관계가 알려졌는데 규모·시점만 불명인 "
        "것을 관련 근거 부족으로 바꾸지 않는다. 같은 work나 같은 기업이 있어도 같은 "
        "프로젝트·사건이라는 뜻은 아니다. 각 finding의 주체·대상·단계·날짜는 그 finding의 "
        "원문 묶음 안에서 읽는다. 서로 다른 finding의 날짜·제품·시설·공정 정보를 이어 "
        "하나의 사건으로 만들지 않는다. 실제 공통 대상의 관계가 원문에 있을 때만 복수 "
        "finding을 연결하고, 공통 업무만 있으면 독립 사건으로 나란히 설명한다. "
        "basisClaimIds는 필요한 최소 원문만 중복 없이 쓴다. "
        "implications는 원문 사건→원문에 지원되는 중간 조건→업무 판단의 경로가 있을 때만 "
        "작성한다. assumption은 같은 대상의 성립 조건, falsifiedBy는 같은 해석을 바꾸는 "
        "관측 사건이다. 이 필드를 채우려고 새 승인 절차·조직·부품을 만들지 않는다. 경로를 "
        "특정할 수 없으면 implications를 비우고 알려진 사건은 overview에 남긴다. "
        "headline과 권고의 확대·축소·유지 방향은 원문의 조건과 일치해야 한다. 방향을 "
        "결정할 근거가 없으면 특정 방향의 실행이 필요하다고 단정하지 말고 확인할 변수와 "
        "그 결과에 따라 바뀌는 판단을 설명한다. watchItems는 "
        "원문의 실제 대상·변수·조건과 그 결과가 바꾸는 판단을 쓴다. 자료 부재는 반증이 아니다. "
        "priorityRank는 기존 중요도 순서이며 unavailable은 무관을 뜻하지 않는다. 모두 "
        "무관/미확인이면 종합 배열을 비운다. 원문 부재와 업무 관련성 미확인은 다르며 "
        "최종 빈 해석 안내는 서버가 판정한다. assessments는 작성하지 않는다."
    )
    return common + "\n\n" + stage_text
