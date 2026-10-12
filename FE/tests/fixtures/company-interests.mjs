export const companyCriterion = {
  id: 31, feedbackId: 10, sourceReportId: 17,
  eventTitle: '오픈AI, 수학 문제 300여 개의 새로운 풀이 공개',
  comment: 'AI가 수학문제를 얼마나 잘 풀었는지는 우리 회사의 관심사가 아님. 실제 제품 도입이나 사업 활용과 연결된 기술 소식을 보고 싶습니다.',
  diagnosis: '기사 내용의 사실 오류는 확인되지 않았습니다. 조직의 관심 범위에 대한 의견으로 검토했습니다.',
  topics: [{ id: 2, name: 'AI·LLM 기술 및 산업 동향' }], topicId: 2,
  instruction: '사업 활용이나 제품 도입과 연결되지 않는 순수 수학 문제 풀이 성과 중심의 기사는 보고서에서 제외한다.',
  reason: '사용자가 순수 수학 성과보다 회사의 제품·사업에 활용할 수 있는 AI 소식을 원한다고 명시했습니다.',
  status: 'DRAFT', version: 1, createdAt: '2026-10-12T10:00:00+09:00', updatedAt: '2026-10-12T10:00:00+09:00',
  activatedAt: null, lastApplication: null,
}
export const companyApplication = { reportId: 18, reportScope: 'DAILY', appliedAt: '2026-10-13T08:00:00+09:00', evaluatedCount: 12, suppressedCount: 2 }
export const companyCriteria = [companyCriterion,
  { ...companyCriterion, id: 32, feedbackId: 11, eventTitle: '일회성 AI 행사와 제품 도입 사례를 구분해 주세요', status: 'ACTIVE', version: 2,
    instruction: '단순 행사 일정 안내는 제외하되, 실제 고객의 도입 결과와 제품 출시 정보가 담긴 기사는 유지한다.', activatedAt: '2026-10-12T10:30:00+09:00' },
  { ...companyCriterion, id: 33, feedbackId: 12, eventTitle: '국내 제조업 적용 사례를 중심으로 확인하고 싶습니다', status: 'ACTIVE', version: 2,
    instruction: '생산성·품질 개선의 구체적인 도입 결과 없이 일반적인 AI 활용 가능성만 소개하는 기사는 제외한다.', activatedAt: '2026-10-12T10:30:00+09:00', lastApplication: companyApplication },
  { ...companyCriterion, id: 34, feedbackId: 13, eventTitle: '사업 연관성이 없는 유명인 소식은 제외해 주세요', status: 'ACTIVE', version: 2,
    activatedAt: '2026-10-12T10:30:00+09:00', lastApplication: { ...companyApplication, suppressedCount: 0 } },
  { ...companyCriterion, id: 35, feedbackId: 14, eventTitle: '이전에 사용한 관심 기준', status: 'REVOKED', version: 3 },
]
