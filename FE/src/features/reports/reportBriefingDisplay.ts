export function reportEventElementId(reportId: number, index: number) {
  return `report-${reportId}-event-${index}`
}

export function hasWeeklyAnalysisChronology(text: string) {
  return /\b\d{4}-\d{2}-\d{2}\s+(?:주제별 분석 결과|일일 보고서):/.test(text)
}
