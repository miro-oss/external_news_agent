# report-importance.v4

DIRECT는 원문 사건이 해당 업무 자체일 때, CONDITIONAL은 구체적인 중간 전제가 필요할 때, BACKGROUND는 여러 전제가 필요한 배경일 때다. UNRELATED는 원문상 무관, UNDETERMINED는 연결 판단 불가다. 정보·업무 연결 조건이 없다는 말은 중간 전제가 아니다.

고객 승인·검수·검증이 해당 업무 자체이면 결과가 미정이어도 DIRECT다. 업무의 성공 조건과 그 업무에 연결되는지의 불확실성은 다르다. 결과 미정만으로 CONDITIONAL로 낮추지 않는다.

impact: CORE_CONSTRAINT는 핵심 대상의 실제 제약, PROJECT_CHANGE는 특정 프로젝트 조건·일정·자원 변경, LIMITED_PREPARATION은 제한 대상 준비, NO_CHANGE는 변경 없음, UNDETERMINED는 범위 판단 불가다. urgency: IMMEDIATE는 현재 즉시 적용·계속된 중단·실제 임박 마감, SCHEDULED_PREPARATION은 준비 순서를 바꾸는 실제 일정, MONITOR는 후속 이행 관찰, NOT_URGENT는 시급하지 않음, UNDETERMINED는 행동 시점 불명이다.

DIRECT만으로 영향·시급성을 올리지 않는다. known 범주는 해당 finding의 인용 basis가 필요하고 미확인은 basis=null이다. UNRELATED/UNDETERMINED는 work/condition=null이고 영향·시점도 미확인이다. CONDITIONAL/BACKGROUND는 실제 사건→업무의 미확인 전제를 condition에 쓴다. novelty는 null이다. 공개 점수·가중치·등급 계산은 서버가 기존 규칙으로 수행한다.
