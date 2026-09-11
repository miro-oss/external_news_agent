import type { ReportSummary } from '../../api/types'

export type ReportPeriod = 'ALL' | 'TODAY' | '7' | '30' | 'CUSTOM'

export interface ReportFilters {
  search: string
  topicId: number | null
  period: ReportPeriod
  from: string
  to: string
}

export const DEFAULT_REPORT_FILTERS: ReportFilters = {
  search: '', topicId: null, period: 'ALL', from: '', to: '',
}

const kstCalendar = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit',
})

function kstDate(value: Date) {
  if (Number.isNaN(value.getTime())) return null
  const parts = kstCalendar.formatToParts(value)
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find(item => item.type === type)?.value
  return `${part('year')}-${part('month')}-${part('day')}`
}

function validCalendarDate(value: string) {
  if (!/^\d{4}-\d{2}-\d{2}$/u.test(value)) return false
  const date = new Date(`${value}T00:00:00Z`)
  return !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === value
}

export function reportFilterDate(report: ReportSummary): string | null {
  if (report.reportScope === 'DAILY') {
    return report.reportDate && validCalendarDate(report.reportDate) ? report.reportDate : null
  }
  return report.collectionStartedAt ? kstDate(new Date(report.collectionStartedAt)) : null
}

export function reportTopicOptions(reports: readonly ReportSummary[]): Array<{ id: number; label: string }> {
  const names = new Map<number, Set<string>>()
  for (const report of reports) {
    for (const context of report.collectionContexts ?? []) {
      for (const topic of context.topics) {
        const savedNames = names.get(topic.topicId) ?? new Set<string>()
        if (topic.topicName.trim()) savedNames.add(topic.topicName.trim())
        names.set(topic.topicId, savedNames)
      }
    }
  }
  return [...names].map(([id, savedNames]) => ({
    id, label: [...savedNames].join(' / ') || `주제 #${id}`,
  })).sort((left, right) => left.label.localeCompare(right.label, 'ko') || left.id - right.id)
}

export function reportDateRangeError(filters: ReportFilters): string | null {
  if (filters.period !== 'CUSTOM') return null
  if ((filters.from && !validCalendarDate(filters.from)) || (filters.to && !validCalendarDate(filters.to))) {
    return '올바른 날짜를 입력해 주세요.'
  }
  return filters.from && filters.to && filters.from > filters.to
    ? '시작일은 종료일보다 늦을 수 없습니다.'
    : null
}

export function hasReportFilters(filters: ReportFilters) {
  return !!filters.search.trim() || filters.topicId !== null || filters.period !== 'ALL'
}

export function filterReports(reports: readonly ReportSummary[], filters: ReportFilters, now = new Date()): ReportSummary[] {
  if (reportDateRangeError(filters)) return []
  const search = filters.search.trim().toLocaleLowerCase('ko-KR')
  const today = kstDate(now)
  let from = filters.period === 'CUSTOM' ? filters.from : ''
  let to = filters.period === 'CUSTOM' ? filters.to : ''
  if (filters.period !== 'ALL' && filters.period !== 'CUSTOM' && today) {
    to = today
    const days = filters.period === 'TODAY' ? 1 : Number(filters.period)
    const start = new Date(`${today}T00:00:00Z`)
    start.setUTCDate(start.getUTCDate() - days + 1)
    from = start.toISOString().slice(0, 10)
  }

  return reports.filter(report => {
    const topics = (report.collectionContexts ?? []).flatMap(context => context.topics)
    if (filters.topicId !== null && !topics.some(topic => topic.topicId === filters.topicId)) return false
    if (search && ![
      report.title,
      ...topics.flatMap(topic => [
        topic.topicName, topic.queryText ?? '',
        ...topic.requiredKeywords, ...topic.optionalKeywords, ...topic.excludedKeywords,
      ]),
    ].some(value => value.toLocaleLowerCase('ko-KR').includes(search))) return false
    if (filters.period !== 'ALL') {
      const date = reportFilterDate(report)
      if (!date || (from && date < from) || (to && date > to)) return false
    }
    return true
  })
}
