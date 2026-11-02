import type { CompanyInterestCriterion } from '../../api/companyInterests'
import { ReportEventFeedbackResult } from './ReportReadingWithFeedback'
import { useReportEventFeedback } from './useReportEventFeedback'

/** Submitted issues leave the report; their saved opinions and diagnoses remain readable here. */
export function ReportFeedbackHistory({ reportId, criteria = [] }: {
  reportId: number; criteria?: CompanyInterestCriterion[]
}) {
  const { data, error, busy, paused, refresh } = useReportEventFeedback(reportId, true)
  return <section className="card report-feedback-history" aria-label="보고서 의견 이력">
    <div className="section-heading"><h2>보고서 #{reportId} 의견·검토 이력</h2>
      <button type="button" className="text-button" disabled={busy} onClick={refresh}>{busy ? '확인 중…' : '이력 새로고침'}</button></div>
    <p className="muted">의견을 남긴 이슈는 보고서에서 제외됩니다. 접수한 의견과 검토 결과는 이곳에 보관됩니다.</p>
    <a href={`#/reports?reportId=${reportId}`}>보고서로 돌아가기 →</a>
    {error != null && <p role="alert">의견 이력을 불러오지 못했습니다. 다시 시도해 주세요.</p>}
    {!data && busy && <p role="status">의견 이력을 불러오고 있습니다.</p>}
    {data?.feedback.length === 0 && <p className="muted">접수된 의견이 없습니다.</p>}
    {data?.feedback.map(feedback => <article key={feedback.id} className="report-event-feedback">
      <ReportEventFeedbackResult feedback={feedback} criterion={criteria.find(item => item.feedbackId === feedback.id)} />
    </article>)}
    {paused && !error && <p className="muted">검토가 이어지고 있습니다. 잠시 뒤 이력을 새로고침해 주세요.</p>}
  </section>
}
