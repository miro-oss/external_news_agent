import { useContext } from 'react'
import type { ReportDartCompany } from '../../api/dart'
import { prefersReducedMotion } from '../../lib/motion'
import { issueDartCompanies, ReportDartContext, reportDartPanelId } from './reportDartNavigation'
import './report-dart.css'

export function ReportIssueCompanies({ sourceFindingIds }: { sourceFindingIds: number[] }) {
  const navigation = useContext(ReportDartContext)
  if (!navigation) return null
  return <ReportIssueCompanyButtons entries={navigation.entries} reportId={navigation.reportId}
    sourceFindingIds={sourceFindingIds} onSelect={navigation.select} />
}

export function ReportIssueCompanyButtons({ entries, reportId, sourceFindingIds, onSelect }: {
  entries: ReportDartCompany[]
  reportId: number
  sourceFindingIds: number[]
  onSelect: (corpCode: string, findingIds: number[], origin: HTMLElement) => void
}) {
  const companies = issueDartCompanies(entries, sourceFindingIds)
  if (!companies.length) return null
  return <div className="report-issue-companies" aria-label="이슈에서 확인한 기업">
    {companies.map(entry => <button type="button" key={entry.company.corpCode} className="report-issue-company"
      aria-controls={reportDartPanelId(reportId)}
      aria-label={`${entry.company.corpName} 실적과 공시 보기`}
      onClick={event => {
        onSelect(entry.company.corpCode, sourceFindingIds, event.currentTarget)
        requestAnimationFrame(() => {
          const panel = document.getElementById(reportDartPanelId(reportId))
          panel?.focus({ preventScroll: true })
          panel?.scrollIntoView({ behavior: prefersReducedMotion() ? 'auto' : 'smooth', block: 'start' })
        })
      }}><span>{entry.company.corpName}</span><small>실적 · 공시 ↗</small></button>)}
  </div>
}
