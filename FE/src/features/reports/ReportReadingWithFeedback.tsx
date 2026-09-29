import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { ApiError } from '../../api/client'
import { submitReportEventFeedback, type ReportEventFeedback, type ReportEventFeedbackRequest, type ReportFeedbackEvent } from '../../api/reportEventFeedback'
import type { FeedbackCategory } from '../../api/feedback'
import type { ReportDetail } from '../../api/types'
import { FEEDBACK_CATEGORIES, FEEDBACK_STATUS_LABELS } from '../feedback/feedbackState'
import { formatFullDate } from '../../lib/datetime'
import { ReportReadingContent } from './ReportReadingContent'
import { matchReportFeedbackEvent, prepareReportEventFeedback, reportEventDraftError } from './eventFeedbackState'
import { useReportEventFeedback } from './useReportEventFeedback'
import './reportEventFeedback.css'

export function ReportReadingWithFeedback({ report, onEvidenceSelect, beforeOtherAnalysis, onRefreshReport }: {
  report: ReportDetail
  onEvidenceSelect: (articleId: number, runId: number, sentences: number[]) => void
  beforeOtherAnalysis?: ReactNode
  onRefreshReport: () => Promise<unknown>
}) {
  const hasEvents = !!report.structuredContent?.importantEvents.length
  const { data, error, busy, paused, refresh, recordFeedback } = useReportEventFeedback(report.id, hasEvents)
  const [refreshingReport, setRefreshingReport] = useState(false)
  const [refreshError, setRefreshError] = useState('')
  const mounted = useRef(true)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  async function refreshReport() {
    setRefreshingReport(true)
    setRefreshError('')
    try { await onRefreshReport(); if (mounted.current) refresh() }
    catch { if (mounted.current) setRefreshError('보고서를 새로고침하지 못했습니다. 다시 시도해 주세요.') }
    finally { if (mounted.current) setRefreshingReport(false) }
  }
  const mismatch = !!data && (report.structuredContent?.importantEvents ?? [])
    .some((event, index) => !matchReportFeedbackEvent(report.id, event, index, data))
  const refreshButton = <button type="button" className="text-button" disabled={refreshingReport || busy}
    onClick={() => { void refreshReport() }}>{refreshingReport ? '새로고침 중…' : '보고서 새로고침'}</button>
  const notice = hasEvents && <>
    {!data && !error && <p className="report-feedback-notice" role="status">의견 작성 기능을 불러오고 있어요.</p>}
    {error != null && <div className="report-feedback-notice" role="alert">
      <span>의견을 불러오지 못했습니다. 보고서 내용은 계속 확인할 수 있습니다.</span>
      <button type="button" className="text-button" disabled={busy} onClick={refresh}>{busy ? '확인 중…' : '의견 다시 불러오기'}</button>
    </div>}
    {mismatch && <div className="report-feedback-notice"><span>보고서 내용이 바뀌었습니다. 새로고침 후 이 이벤트에 의견을 남겨 주세요.</span>{refreshButton}</div>}
    {!error && paused && <div className="report-feedback-notice"><span>검토가 이어지고 있습니다. 잠시 뒤 결과를 다시 확인해 주세요.</span>
      <button type="button" className="text-button" disabled={busy} onClick={refresh}>검토 결과 확인</button></div>}
    {refreshError && <p className="report-feedback-notice" role="alert">{refreshError}</p>}
  </>
  return <ReportReadingContent report={report} onEvidenceSelect={onEvidenceSelect} beforeOtherAnalysis={beforeOtherAnalysis}
    feedbackNotice={notice} eventFeedback={(event, index) => {
      const target = matchReportFeedbackEvent(report.id, event, index, data)
      if (!target) return null
      const feedback = data?.feedback.find(item => item.eventKey === target.eventKey)
      return <ReportEventFeedbackCard key={target.eventKey} reportId={report.id} target={target} feedback={feedback}
        disabled={error != null || refreshingReport || mismatch} onFeedback={recordFeedback} onRefreshReport={refreshReport} />
    }} />
}

export function ReportEventFeedbackCard({ reportId, target, feedback, disabled = false, onFeedback, onRefreshReport }: {
  reportId: number; target: ReportFeedbackEvent; feedback?: ReportEventFeedback; disabled?: boolean
  onFeedback: (feedback: ReportEventFeedback) => void; onRefreshReport: () => Promise<void>
}) {
  return <div className="report-event-feedback">
    {feedback ? <ReportEventFeedbackResult feedback={feedback} />
      : <details className="report-feedback-disclosure">
        <summary>의견 남기기<span aria-hidden="true"> +</span></summary>
        <ReportEventFeedbackComposer reportId={reportId} target={target} disabled={disabled}
          onFeedback={onFeedback} onRefreshReport={onRefreshReport} />
      </details>}
  </div>
}

export function ReportEventFeedbackComposer({ reportId, target, disabled = false, onFeedback, onRefreshReport }: {
  reportId: number; target: ReportFeedbackEvent; disabled?: boolean
  onFeedback: (feedback: ReportEventFeedback) => void; onRefreshReport: () => Promise<void>
}) {
  const id = useId()
  const [category, setCategory] = useState<FeedbackCategory>('SUMMARY_ERROR')
  const [comment, setComment] = useState('')
  const [error, setError] = useState('')
  const [pending, setPending] = useState(false)
  const [retry, setRetry] = useState(false)
  const [conflict, setConflict] = useState(false)
  const request = useRef<ReportEventFeedbackRequest | null>(null)
  const controller = useRef<AbortController | null>(null)
  useEffect(() => () => controller.current?.abort(), [])
  function changed() { setError(''); setRetry(false) }
  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (controller.current || disabled || conflict) return
    const draft = { eventKey: target.eventKey, category, comment }
    const validation = reportEventDraftError(draft)
    if (validation) { setError(validation); return }
    request.current = prepareReportEventFeedback(request.current, draft)
    const active = new AbortController()
    controller.current = active
    setPending(true)
    setError('')
    try {
      const result = await submitReportEventFeedback(reportId, request.current, active.signal)
      if (active.signal.aborted) return
      onFeedback(result)
    } catch (failure) {
      if (active.signal.aborted) return
      setError(failure instanceof ApiError ? failure.message : '의견 접수 결과를 확인하지 못했습니다. 다시 확인해 주세요.')
      setConflict(failure instanceof ApiError && (failure.status === 409 || failure.status === 404))
      setRetry(!(failure instanceof ApiError && (failure.status === 409 || failure.status === 404 || failure.status === 400)))
    } finally {
      if (!active.signal.aborted) { controller.current = null; setPending(false) }
    }
  }
  return <form className="report-feedback-form" onSubmit={event => { void submit(event) }} aria-busy={pending} aria-label={`${target.title} 의견`}>
    <fieldset disabled={disabled || pending || conflict}>
      <legend>어떤 의견인가요?</legend>
      <div className="report-feedback-categories">
        {FEEDBACK_CATEGORIES.map(option => <label key={option.value} className={category === option.value ? 'is-selected' : ''}>
          <input type="radio" name={`${id}-category`} value={option.value} checked={category === option.value}
            onChange={() => { setCategory(option.value); changed() }} aria-describedby={`${id}-category-help`} />{option.label}
        </label>)}
      </div>
      <p className="report-feedback-help" id={`${id}-category-help`}>{FEEDBACK_CATEGORIES.find(option => option.value === category)?.description}</p>
      <label className="report-feedback-comment" htmlFor={`${id}-comment`}>의견
        <textarea id={`${id}-comment`} rows={3} value={comment} required maxLength={2000}
          aria-describedby={`${id}-count ${id}-scope`} placeholder="이 이벤트에서 어떤 내용이 달랐는지 알려 주세요."
          onChange={event => { setComment(event.target.value); changed() }} />
      </label>
      <div className="report-feedback-help report-feedback-count" id={`${id}-count`}><span>1~2,000자</span><span>{comment.length.toLocaleString()} / 2,000</span></div>
    </fieldset>
    <p className="report-feedback-help" id={`${id}-scope`}>보고서 검토용 의견입니다. 개인 알림 기준에는 적용하지 않습니다.</p>
    {error && <div className="report-feedback-error" role="alert"><p>{error}</p>
      {conflict ? <><p>이미 의견이 접수되었거나 보고서 내용이 바뀌었을 수 있습니다. 새로고침 후 확인해 주세요.</p>
        <button type="button" className="text-button" disabled={disabled} onClick={() => { void onRefreshReport() }}>보고서 새로고침</button></>
        : retry && <p>같은 내용으로 다시 확인하면 중복 접수하지 않습니다.</p>}
    </div>}
    {!conflict && <button type="submit" disabled={disabled || pending} className="primary-button report-feedback-submit">
      {pending ? '접수 확인 중…' : retry ? '접수 결과 다시 확인' : '의견 보내기'}
    </button>}
  </form>
}

const VERDICT_LABELS = { PREFERENCE: '관심에 대한 의견', CONFIRMED_ERROR: '오류 확인', NOT_CONFIRMED: '오류 확인 안 됨', INSUFFICIENT_EVIDENCE: '근거 부족' }
export function ReportEventFeedbackResult({ feedback }: { feedback: ReportEventFeedback }) {
  return <div className="report-feedback-result" role="status">
    <div className="report-feedback-result-meta"><strong className={`report-feedback-status is-${feedback.status.toLowerCase()}`}>{FEEDBACK_STATUS_LABELS[feedback.status]}</strong>
      <time dateTime={feedback.createdAt}>{formatFullDate(feedback.createdAt)}</time></div>
    <p className="report-feedback-help">{FEEDBACK_CATEGORIES.find(option => option.value === feedback.category)?.label}</p>
    <p className="report-feedback-saved-comment">{feedback.comment}</p>
    {feedback.diagnosis && <div className="report-feedback-diagnosis"><strong>{feedback.verdict ? VERDICT_LABELS[feedback.verdict] : '검토 설명'}</strong><p>{feedback.diagnosis}</p></div>}
    {feedback.status === 'COMPLETED' && !feedback.diagnosis && <p>검토를 마쳤습니다. 추가 설명이 제공되지 않았습니다.</p>}
    {feedback.status === 'FAILED' && <p>검토를 완료하지 못했습니다. 남긴 의견은 보관됩니다.</p>}
    {(feedback.status === 'PENDING' || feedback.status === 'PROCESSING') && <p className="report-feedback-help">이 이벤트의 내용과 함께 묶인 근거를 확인하고 있습니다.</p>}
  </div>
}
