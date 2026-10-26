export function feedbackFixture() {
  return {
    reportId: 281,
    reportTitle: '반도체 산업 · 9월 28일 뉴스 리포트',
    expiresAt: '2026-10-28T09:00:00+09:00',
    items: [
      { itemId: 701, topicId: 31, topicName: '반도체 공급망', title: '첨단 패키징 생산능력 확대, 공급망 투자 이어져',
        summary: '주요 반도체 기업이 첨단 패키징 생산능력을 늘리고 있습니다. 이번 보고서는 새 공장 투자와 공급 계약에 관한 보도를 함께 살펴봤습니다.',
        sources: [{ articleId: 801, title: '첨단 패키징 시설 투자 계획 발표', url: 'https://example.invalid/news/801' },
          { articleId: 802, title: '주요 장비 기업, 공급 계약 체결', url: 'https://example.invalid/news/802' }] },
      { itemId: 702, topicId: 29, topicName: 'HBM 시장 동향', title: 'HBM 수요 확대 전망과 차세대 제품 일정',
        summary: '시장 전망과 제품 출시 일정을 다룬 소식입니다. 기업 발표와 전망의 근거를 구분해 정리했습니다.',
        sources: [{ articleId: 803, title: '차세대 메모리 제품 개발 일정 공개', url: 'https://example.invalid/news/803' }] },
      { itemId: 703, topicId: 31, topicName: '반도체 공급망', title: '반도체 장비 기업, 신규 생산설비 공급 계약 발표',
        summary: '반도체 장비 기업이 신규 생산설비 공급 계약을 발표했습니다. 공개한 납기와 적용 공정을 중심으로 정리했습니다.',
        sources: [{ articleId: 804, title: '신규 생산설비 공급 계약 발표', url: 'https://example.invalid/news/804' }] },
    ],
    feedback: [
      { id: 601, itemId: 701, category: 'SUMMARY_ERROR', comment: '투자 계획을 확정된 계약처럼 읽을 수 있어요. 발표문에 나온 시점을 확인해 주세요.',
        status: 'COMPLETED', verdict: 'SUPPORTED', diagnosis: '원문은 향후 투자 계획을 설명하고 있습니다. 이미 집행한 투자로 단정하지 않도록 시점을 구분할 필요가 있다는 의견을 확인했습니다. 공통 분석 기준을 자동으로 변경하지는 않습니다.', createdAt: '2026-09-28T13:35:00+09:00' },
      { id: 602, itemId: 702, category: 'PREFERENCE', comment: '단기 시장 전망보다 실제 생산·공급 계약 소식이 업무에 더 도움이 됩니다.',
        status: 'COMPLETED', verdict: 'PREFERENCE', diagnosis: '뉴스의 사실 여부에 대한 오류 제보가 아닌 개인 관심 의견으로 확인했습니다. 동의하신 범위에서 반도체 공급망 소식을 읽을 때 참고할 관심 기준을 만들었습니다.', createdAt: '2026-09-28T14:10:00+09:00' },
    ],
    policies: [{ id: 501, topicId: 31, topicName: '반도체 공급망', instruction: '단기 시장 전망보다 생산능력 확대와 실제 공급 계약에 관한 소식을 우선 참고합니다.', version: 1, status: 'ACTIVE', createdAt: '2026-09-28T14:12:00+09:00' }],
  }
}
