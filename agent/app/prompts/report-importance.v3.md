# report-importance.v3

중요도는 관점의 구체 업무를 판단하는 우선순위다. 기업 유명세·산업 인기·기사 길이·감정·모델 자신감이 아니다. 내부 MAP의 connection/effect/timing은 각각 범주와 basis를 한 묶음으로 작성한다. 먼저 원문 인용과 역할 업무를 제시하고 범주를 고른다. 서버가 아래 대응으로 기존 공개 directness/impact/urgency 0~3 또는 null을 계산한다. 공개 가중치·등급 경계는 바꾸지 않는다.

| 축 | 범주 | 공개 값 | 필요한 원문 판단 |
|---|---|---|---|
| directness | DIRECT | 3 | 해당 관점의 구체 업무 자체에 연결 |
| directness | CONDITIONAL | 2 | 구체 업무 연결에 명시적인 미확인 중간 조건이 필요 |
| directness | BACKGROUND | 1 | 배경 정보이며 여러 미확인 연결 조건이 필요 |
| directness | UNRELATED | 0 | 원문상 해당 업무와 무관하다는 판정 |
| directness | UNDETERMINED | null | 업무 연결 자체를 판단할 근거 부족 |
| impact | CORE_CONSTRAINT | 3 | 명시된 핵심 생산·공급·운영·투자 대상의 실제 제약 |
| impact | PROJECT_CHANGE | 2 | 명시된 제품·공정·프로젝트의 일정·조건·자원 판단 변경 |
| impact | LIMITED_PREPARATION | 1 | 원문에 명시된 제한된 대상의 준비·확인 판단 |
| impact | NO_CHANGE | 0 | 연결되지만 판단 변경이 없다는 원문 근거 |
| impact | UNDETERMINED | null | 영향 범위·크기 미확인 |
| urgency | IMMEDIATE | 3 | 기준 시점의 즉시 조치·계속된 중단·임박한 실제 업무 마감 |
| urgency | SCHEDULED_PREPARATION | 2 | 준비 순서를 바꾸는 실제 일정·전환·확인 기회 |
| urgency | MONITOR | 1 | 임박한 마감 없이 후속 이행을 관찰할 사항 |
| urgency | NOT_URGENT | 0 | 기준 시점에 시급성이 없다는 원문 근거 |
| urgency | UNDETERMINED | null | 행동 시점·기한 미확인 |

- 0은 원문 근거가 있는 판정이고 null은 판단 불가다. 모든 판정 가능한 범주는 같은 finding의 claimId와 실제 원문 구절을 요구한다. 미확인 범주의 basis는 null이다. 연결되지 않은 문장·다른 finding·제목은 축 근거가 아니다.
- DIRECT라고 영향이나 시급성이 최대가 되지 않는다. 실제 계약은 사건 단계이며 공급 범위·이행 시점·실적 효과를 대신하지 않는다. 계약과 해당 관점 업무의 직접 연결만 확인되면 DIRECT로 두고, 영향 범위와 대응 기한은 독립적으로 UNDETERMINED로 남길 수 있다. "DIRECT이므로 CORE_CONSTRAINT/IMMEDIATE"로 세 축을 함께 올리지 않는다. 범위가 명시된 제한적 준비는 그 범위만 판단하고, 범위조차 없으면 영향은 미확인이다.
- 메모리/HBM 제조사의 확인된 고객 공급 계약은 CHIP_MAKER의 CUSTOMER_REQUIREMENTS에 직접 연결될 수 있다. 공정·생산량·수율 정보가 없다고 고객 대응 관계까지 미확인이나 무관으로 낮추지 않는다. 반대로 계약만으로 생산 확대·핵심 제약·즉시 대응은 확정하지 않는다. 해당 업무의 관계는 알려져도 범위와 시점이 없으면 directness=3, impact=null, urgency=null이며 계산 결과 unavailable과 관련 사건의 종합 설명이 함께 성립한다.
- UNRELATED/UNDETERMINED에서는 work=null, 업무 영향·시급성은 UNDETERMINED다. CONDITIONAL/BACKGROUND는 어떤 연결 조건이 미확인인지 condition으로 드러낸다. 알려진 사건 자체를 없다고 하지 않는다.
- 시간 기준은 전체 보고서의 reportReferenceDate다. 과거 기한·먼 미래 목표·발행일만으로 IMMEDIATE가 되지 않는다. 과거에 시작한 제약이 기준 시점에도 계속된다는 원문은 현재 제약으로 평가할 수 있다. 이행 결과가 없으면 기한과 실제 상태를 구분한다.
- 이전 리포트 기준선이 없으므로 novelty는 항상 null이다.
- 점수는 기존 계산을 유지한다. directness 또는 impact가 null인지 먼저 판정하며, 하나라도 null이면 unavailable이다. 따라서 무관 평가도 impact가 미확인이므로 unavailable이 되고, 무관 여부는 directness=0에서 구분한다. 두 축이 모두 판정 가능하면서 directness=0이면 low다. 나머지는 directness×0.4+impact×0.4+urgency×0.2이며 urgency=null이면 남은 가중치를 정규화한다. 2.25 이상 high, 1.25 이상 medium, 그 밖에는 low다. unavailable이어도 알려진 관련 사건의 종합은 설명할 수 있다.
- 단어 일치와 원문 인용의 존재만으로 의미가 검증되는 것은 아니다. 원문과 업무 연결을 독립 재검토하고, 높은 등급을 유지하는 것보다 주체·단계·범위의 일관성을 우선한다.
