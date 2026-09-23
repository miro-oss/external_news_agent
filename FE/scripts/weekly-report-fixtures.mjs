// Synthetic weekly snapshot with five missing days to exercise coverage and source navigation.
export function weeklyReportFixture(base) {
  return {
    ...structuredClone(base), id: 217, runId: null, reportScope: 'WEEKLY',
    reportDate: '2026-09-07', reportEndDate: '2026-09-13',
    sourceRunIds: [42, 43, 44], sourceReportCount: 2,
    sourceReportIds: [116, 117], sourceReportDates: ['2026-09-07', '2026-09-08'],
    missingReportDates: ['2026-09-09', '2026-09-10', '2026-09-11', '2026-09-12', '2026-09-13'],
    title: '2026-09-07 ~ 2026-09-13 주간 통합 뉴스 보고서',
    generatedAt: '2026-09-14T01:00:00+09:00', collectionStartedAt: null,
    findingCount: base.findings.length, highSensitivityCount: base.findings.length, deliveryStatus: 'NOT_SENT',
    structuredContent: {
      ...structuredClone(base.structuredContent),
      executiveSummary: ['이번 주 일일 보고서에서 HBM 공급 확대와 첨단 패키징 투자 소식을 묶었습니다.'],
      sourceNotes: ['일일 통합 보고서 2개를 바탕으로 작성했습니다. 일일 보고서가 없는 날짜는 집계에서 제외했습니다.'],
    },
  }
}
