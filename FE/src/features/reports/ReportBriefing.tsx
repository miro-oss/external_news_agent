import type { ReportDetail } from '../../api/types'
import { prefersReducedMotion } from '../../lib/motion'
import { ReportCoverageSummary } from './ReportCoverage'
import { eventCoverage } from './reportCoverageDisplay'
import { ReportKeywordText } from './ReportKeywordText'
import { ReportIssueCompanies } from './ReportIssueCompanies'
import { reportEventElementId } from './reportBriefingDisplay'
import './report-briefing.css'

function sameSources(left: number[], right: number[]) {
  const unique = new Set(left)
  return unique.size === new Set(right).size && right.every(id => unique.has(id))
}

export function ReportBriefing({ report, terms }: { report: ReportDetail; terms: string[] }) {
  const briefing = report.briefing
  if (!briefing) return null
  const findings = report.findings && new Set(report.findings.map(finding => finding.id))
  const hasSources = (ids: number[]) => ids.length > 0 && (!findings || ids.every(id => findings.has(id)))
  const items = briefing.items.filter(item => hasSources(item.sourceFindingIds))
  const watchItems = briefing.watchItems.filter(item => hasSources(item.sourceFindingIds))
  function revealEvent(index: number) {
    const element = document.getElementById(reportEventElementId(report.id, index))
    if (!element) return
    let parent = element.parentElement
    while (parent) {
      if (parent instanceof HTMLDetailsElement) parent.open = true
      parent = parent.parentElement
    }
    element.focus({ preventScroll: true })
    element.scrollIntoView({ behavior: prefersReducedMotion() ? 'auto' : 'smooth', block: 'start' })
  }
  return <section className="card report-briefing" aria-label="핵심 브리핑">
    <header className="report-briefing-heading"><span className="report-briefing-kicker">먼저 읽기</span><h3>핵심 브리핑</h3>
      <p>이번 보고서의 주요 소식과 다음 확인점을 모았습니다.</p></header>
    {items.length > 0 ? <ol className="report-briefing-items">{items.map((item, index) => {
      const eventIndex = report.structuredContent?.importantEvents.findIndex(event => event.title === item.title
        && event.summaryKo === item.summary && sameSources(event.sourceFindingIds, item.sourceFindingIds)) ?? -1
      return <li key={index} className="report-briefing-item">
        <span className="report-briefing-number" aria-hidden="true">{String(index + 1).padStart(2, '0')}</span>
        <div className="report-briefing-item-content"><h4><ReportKeywordText text={item.title} terms={terms} /></h4>
          <ReportCoverageSummary coverage={eventCoverage(report.coverage, item.sourceFindingIds)} />
          <ReportIssueCompanies sourceFindingIds={item.sourceFindingIds} />
          <p><ReportKeywordText text={item.summary} terms={terms} /></p>
          {item.significance && <p className="report-briefing-significance"><span>살펴볼 이유</span><ReportKeywordText text={item.significance} terms={terms} /></p>}
          {eventIndex >= 0 && <button type="button" className="text-button report-briefing-source"
            aria-controls={reportEventElementId(report.id, eventIndex)} onClick={() => revealEvent(eventIndex)}>이슈와 근거 보기 →</button>}
        </div>
      </li>
    })}</ol> : <p className="muted">간단히 표시할 핵심 항목이 없습니다. 아래 상세에서 보고서를 확인해 주세요.</p>}
    {watchItems.length > 0 && <section className="report-briefing-watch" aria-label="브리핑 다음 확인점"><h4>다음 확인점</h4>
      <ul>{watchItems.map((item, index) => <li key={index}><strong><ReportKeywordText text={item.topic} terms={terms} /></strong>
        <p><ReportKeywordText text={item.reason} terms={terms} /></p></li>)}</ul></section>}
    {briefing.omittedItemCount > 0 && <p className="report-briefing-note">더 읽을 항목 {briefing.omittedItemCount}개는 조건과 맥락을 그대로 담아 아래 상세에 남겼습니다.</p>}
  </section>
}
