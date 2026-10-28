import type { ReportCoverage, ReportCoverageItem } from '../../api/types'

function sourceKey(ids: number[]) {
  return [...new Set(ids)].sort((left, right) => left - right).join(',')
}

export function eventCoverage(coverage: ReportCoverage | null | undefined, sourceFindingIds: number[]) {
  const key = sourceKey(sourceFindingIds)
  return coverage?.events.find(item => sourceKey(item.sourceFindingIds) === key)
}

export function findingCoverage(coverage: ReportCoverage | null | undefined, findingId: number) {
  return coverage?.findings.find(item => item.findingId === findingId)
}

export function coverageAvailable(coverage: ReportCoverageItem | null | undefined): coverage is ReportCoverageItem {
  return coverage?.status === 'AVAILABLE'
    && [coverage.articleCount, coverage.publisherCount, coverage.unknownPublisherArticleCount]
      .every(count => count !== null && Number.isSafeInteger(count) && count >= 0)
}

export function coverageLabel(coverage: ReportCoverageItem | null | undefined) {
  if (!coverageAvailable(coverage)) return '집계 자료 없음'
  const unknown = coverage.unknownPublisherArticleCount ?? 0
  return `관련 기사 ${coverage.articleCount}건 · ${coverage.publisherCount}개 매체${unknown > 0 ? ` · 매체 미확인 ${unknown}건` : ''}`
}

const articleDate = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit' })

export function coverageArticleDate(value: string | null) {
  if (!value || !Number.isFinite(Date.parse(value))) return '발행일 미확인'
  return articleDate.format(new Date(value))
}
