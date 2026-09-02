회사 민감도 판정 기준:

- 회사는 제조 현장 데이터를 분석해 산업 AI 해결책을 설계·구축·운영한다. 감시 범위는 반도체를 중심으로 디스플레이, 이차전지, 자동차 전장 등 반도체와 관련된 제조 산업까지다.
- classification.sensitivity는 customerMove, dealSignal, competitorThreat, industryShift 네 축을 모두 포함한다.
- customerMove: 고객사·잠재고객 제조사의 팹 증설, 라인 투자, 감산, 조직 개편, DX 조직 신설 같은 움직임을 본다.
- dealSignal: OEE, SPC, 설비 예지보전, 품질·공정 최적화, 자율제조, 스마트팩토리, 제조 데이터 플랫폼 도입·입찰·예산 집행처럼 실제 사업과 직접 연결되는 신호를 본다.
- competitorThreat: 동종 산업 AI 업체의 수주, 고객사의 내재화, 기존 서비스를 대체할 기술 등 경쟁·대체 위협을 본다.
- industryShift: 수출 규제, 보조금, 공급망 충격처럼 제조사의 투자 심리와 사업 기반을 바꾸는 사건을 본다.
- 각 축 score는 0(신호 없음), 1(약함), 2(중간), 3(강함) 중 하나다. 입력 자료만으로 판정할 수 없으면 score를 null로 둔다.
- score가 null이면 evidenceSentenceIds는 빈 배열이어야 한다. 0~3점이면 점수를 직접 뒷받침하는 source-sentences를 하나 이상 1-based evidenceSentenceIds로 연결한다.
- dealSignal은 기사에 도입·입찰·예산·계약·수주·구매 등 직접 사업 근거가 있을 때만 판정한다. 외부 조달 데이터가 있다고 가정하지 말고 근거가 없으면 null로 둔다.
- 네 축을 모두 null로 만들 수 없다. 다만 기사에 없는 신호를 만들어 점수를 채워서는 안 된다.
- 총점과 high/medium/low 단계는 서버가 가용 축의 가중치를 재정규화해 계산하므로 출력하지 않는다.
- 회사의 사업·구축 사례는 판정 기준일 뿐 기사에 없는 사실을 보충하는 외부 근거로 사용하지 않는다.
