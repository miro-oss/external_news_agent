# report-importance.v6

DIRECT: 원문 사건·조건이 해당 관점 업무 자체다. CONDITIONAL: 원문 사건을 해당 업무로 연결하는 구체적인 미확인 중간 전제가 필요하다. BACKGROUND: 여러 전제가 필요한 배경이다. UNRELATED: 원문상 무관한 업무다. UNDETERMINED: 업무 연결을 판단할 근거 부족이다. 자료 부족은 중간 전제가 아니다. 관계 판단이 불가하면 UNRELATED가 아니라 UNDETERMINED다.

확인된 고객 공급 계약·조달 조건·공정 인증·설계 적용은 이행 결과가 미정이어도 해당 업무에 직접 연결될 수 있다. 업무 관계, 영향 범위, 행동 시점은 별개로 판단한다.

impact: CORE_CONSTRAINT=핵심 대상의 실제 제약, PROJECT_CHANGE=특정 프로젝트 조건·일정·자원 변경, LIMITED_PREPARATION=명시된 제한 대상 준비, NO_CHANGE=명시된 변경 없음, UNDETERMINED=범위 판단 불가. 원문에 영향 대상과 변경 범위가 확인되면 정량 금액·비율 없이도 정성적으로 판정한다. 알려진 제한 대상의 변화와 규모 자체를 모르는 경우를 구분한다. 알려진 업무 관계만으로 영향 범주를 채우거나 미확인을 NO_CHANGE로 바꾸지 않는다.

urgency: IMMEDIATE=현재 즉시 적용·계속된 중단·실제 임박 마감, SCHEDULED_PREPARATION=준비 순서를 바꾸는 실제 일정, MONITOR=후속 이행 관찰, NOT_URGENT=명시된 시급하지 않음, UNDETERMINED=행동 시점 불명.

각 known 범주는 해당 판단을 뒷받침하는 원문 basis가 필요하며 미확인 basis는 null이다. UNRELATED/UNDETERMINED는 work/condition=null이고 영향·시점도 미확인이다. 조건부 관계는 실제 사건→구체 업무의 전제를 condition에 쓴다. novelty=null.

점수는 서버가 계산한다. UNRELATED의 직접 관련성 0은 영향·시점 미확인이어도 낮음이다. 그 밖에 직접 관련성 또는 영향 미확인은 판단 보류다. 가중치와 등급 경계값은 유지한다. 낮은 점수나 보류 비율을 줄이려고 원문이 지원하지 않는 범주를 선택하지 않는다.
