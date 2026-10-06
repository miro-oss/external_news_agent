"""Source-bound stage instructions without fictional answer-copying anchors."""

from pathlib import Path

from app.schemas.analyze import Audience

_ROOT = Path(__file__).resolve().parents[1] / "prompts"
_BASE = (_ROOT / "report-insight.ko.v16.md").read_text(encoding="utf-8").strip()
_RUBRIC = (_ROOT / "report-importance.v6.md").read_text(encoding="utf-8").strip()
_REDUCE_RULES = (_ROOT / "report-insight-reduce.ko.v1.md").read_text(encoding="utf-8").strip()
ASSESSMENT_REASON_RULE = (
    "reason은 업무 연결·영향·시점의 근거나 한계를 1~2문장 180자 이내로 설명한다. "
    "영향·시점도 원문의 변경·준비·기한으로 뒷받침한다. "
    "코드·ID·기업명·수치 나열이나 재요약은 쓰지 않는다."
)

_ROLES = {
    "CHIP_MAKER": "칩 제조: 공정 인증·설계 IP/공정 적용·계측/공정 제어 검증 "
    "PROCESS_QUALIFICATION, "
    "생산 일정 PRODUCTION_SCHEDULE, "
    "수율/능력·품질/처리량 분석 YIELD_CAPACITY, 고객 요구·공급 약정 CUSTOMER_REQUIREMENTS, "
    "소재 확보 "
    "MATERIAL_SUPPLY. 메모리/HBM 제조사의 실제 고객 공급 계약도 직접 관계일 수 있다. "
    "공정·수율이 없다는 이유로 그 계약을 무관 처리하지 않는다. "
    "계약은 생산 증가·규격 승인·납품 완료를 뜻하지 않는다. 공정 인증·설계 적용은 "
    "고객 계약이 없어도 PROCESS_QUALIFICATION에서 판단한다. 생산능력·생산 배분은 "
    "YIELD_CAPACITY/PRODUCTION_SCHEDULE에서 판단한다. 기술 검토가 실제 채택·효과 달성을 "
    "뜻하지 않는다. 기판·패키징의 실제 공정 기술 적용도 업무 대상과 대조한다. "
    "주가·수출액·시장점유율의 변화는 생산 일정·생산능력의 변화와 "
    "구분한다. 소자·칩의 실험과 특성 분석은 실제 고객 요구 변경과 구분한다.",
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
    "운영이 아니다. 시장 수급·가격 전망만으로 특정 프로젝트 변경이나 준비 활동을 "
    "만들지 않는다. 전망의 연도·사업 발표일은 대응 기한이 아니다. "
    "직원·본사·사무공간의 물리 이전은 IT 시스템·네트워크 이전의 사실 근거가 아니다. "
    "업무용 소프트웨어·온라인 서비스의 실제 통합·배포·운영도 도입/운영 업무다. "
    "서비스 소개·이용 혜택만으로 실제 도입 사건을 만들지 않는다. "
    "시스템 변경이 원문에 있으면 직접 업무로 판단하고, 수반될 것이라는 가정만 있으면 "
    "그 구체적 연결 전제를 구분한다.",
}


def report_stage_instruction(audiences: list[Audience], stage: str) -> str:
    """Requested roles, active stage and source rules; schema defines the shape."""
    if not audiences or stage not in {"MAP", "REVIEW", "REDUCE"}:
        raise ValueError("A report stage needs requested audiences and a known stage")
    roles = "\n".join(f"{audience}: {_ROLES[audience]}" for audience in audiences)
    common = _BASE + "\n\n" + roles
    if stage in {"MAP", "REVIEW"}:
        stage_text = (
            f"현재 단계는 {stage}.\n"
            "1. 연결 sentence의 주체·대상·사건 단계를 읽는다. claim 요약이 강해도 원문의 "
            "실험·계획·전망 수준을 유지한다. "
            + ASSESSMENT_REASON_RULE
            + "\n2. 관점의 모든 업무로 connection을 판정한다. 원문에서 확인된 "
            "사실을 미확인 condition으로 반복하지 않는다.\n"
            "3. 업무 대상의 변경·준비 범위를 찾는다. 규모·성장률·기술 사양은 "
            "실제 제약이 아니며 수요·가격 전망은 프로젝트 변경·준비가 아니다. "
            "구체 계획의 자원·일정과 시제품의 준비 범위를 판단한다. "
            "범위를 모르면 관계는 유지하고 effect만 UNDETERMINED로 둔다.\n"
            "4. 축별 범주와 필수/null을 지킨다. reason은 선택한 "
            "claim·연결 sentence(모든 축 미확인이면 finding 원문)로 뒷받침한다. "
            "실제 claims=[]만 Schema const의 고정 reason을 쓴다."
        )
        if stage == "REVIEW":
            stage_text += " 검토 선정은 승격 근거가 아니며 원문으로 독립 재판정한다."
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
        "관측 사건이다. falsifiedBy가 실제로 발생했을 때 text의 예상 결과가 약해지는지 "
        "확인한다. 계획의 취소·연기가 발생하지 않았다는 관측은 계획 실현의 반증이 "
        "아니다. 부정 표현이 있어도 예상 효과의 미달과 취소의 부재는 방향이 다르다. "
        "이 필드를 채우려고 새 승인 절차·조직·부품을 만들지 않는다. 경로를 "
        "특정할 수 없으면 implications를 비우고 알려진 사건은 overview에 남긴다. "
        "headline과 권고의 확대·축소·유지 방향은 원문의 조건과 일치해야 한다. 방향을 "
        "결정할 근거가 없으면 특정 방향의 실행이 필요하다고 단정하지 말고 확인할 변수와 "
        "그 결과에 따라 바뀌는 판단을 설명한다. watchItems는 "
        "원문의 실제 대상·변수·조건과 그 결과가 바꾸는 판단을 쓴다. 자료 부재는 반증이 아니다. "
        "priorityRank는 기존 중요도 순서이며 unavailable은 무관을 뜻하지 않는다. 모두 "
        "무관/미확인이면 종합 배열을 비운다. 원문 부재와 업무 관련성 미확인은 다르며 "
        "최종 빈 해석 안내는 서버가 판정한다. assessments는 작성하지 않는다."
    )
    return common + "\n\n" + _REDUCE_RULES + "\n\n" + stage_text
