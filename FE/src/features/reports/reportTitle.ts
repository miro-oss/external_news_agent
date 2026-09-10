import type { ReportSummary } from '../../api/types'

type DisplayReport = Pick<ReportSummary, 'reportScope' | 'title' | 'generatedAt'>
  & Partial<Pick<ReportSummary, 'reportDate'>>

const titleDate = new Intl.DateTimeFormat('ko-KR', { month: 'short', day: 'numeric' })

function removeReportSuffix(title: string) {
  return title.replace(/(?:^|\s+)(?:리포트|보고서)$/u, '').trim()
}

function reportSubject(title: string) {
  let subject = title.replace(/\s+/gu, ' ').trim()
  // Older reports put their generated timestamp after the report label without a separator.
  subject = subject.replace(/((?:^|\s+)(?:리포트|보고서))\s+\d{4}-\d{2}-\d{2}(?:[ T]\d{2}:\d{2}(?::\d{2})?)?$/u, '$1')
  subject = removeReportSuffix(subject)
  // The part before the generated timestamp is the full saved collection name.
  const collectionTitle = subject.match(/^(.*?)\s*[·|]\s*\d{4}-\d{2}-\d{2}(?:[ T]\d{2}:\d{2}(?::\d{2})?)?$/u)
  if (collectionTitle) return collectionTitle[1].trim() || '뉴스 리포트'
  subject = subject.replace(/(?:^|\s*[·|]\s*)\d{4}-\d{2}-\d{2}(?:[ T]\d{2}:\d{2}(?::\d{2})?)?$/u, '').trim()
  subject = removeReportSuffix(subject)

  // Legacy execution titles append "뉴스 보고서" after the collection name.
  const executionName = subject.match(/^((?:MEASURE\d*|LIVE)-.+-20\d{6}(?:-\d{2})?) 뉴스$/u)
    ?? subject.match(/^(발표-.+-(?:20\d{6}|\d{4})(?:-\d{2})?) 뉴스$/u)
  subject = executionName?.[1]?.trim() || subject
  return !subject || subject === '뉴스' ? '뉴스 리포트' : subject
}

export function reportDisplayTitle(report: DisplayReport) {
  if (report.reportScope === 'DAILY') {
    return report.reportDate ? `${report.reportDate} 일일 통합 뉴스 보고서` : report.title
  }

  const subject = reportSubject(report.title)
  const generatedAt = new Date(report.generatedAt)
  return Number.isNaN(generatedAt.getTime()) ? subject : `${subject} · ${titleDate.format(generatedAt)}`
}
