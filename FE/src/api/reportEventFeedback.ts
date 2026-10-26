import { ApiError } from './client.ts'
import type { FeedbackCategory, FeedbackStatus } from './feedback'
import type { ApiEnvelope } from './types'

export interface ReportFeedbackEvent {
  eventKey: string
  eventIndex: number
  title: string
  summary: string
  significance: string | null
  sourceFindingIds: number[]
}
export interface ReportEventFeedback {
  id: number
  eventKey: string
  category: FeedbackCategory
  comment: string
  status: FeedbackStatus
  verdict: 'PREFERENCE' | 'CONFIRMED_ERROR' | 'NOT_CONFIRMED' | 'INSUFFICIENT_EVIDENCE' | null
  diagnosis: string | null
  createdAt: string
}
export interface ReportEventFeedbackContext {
  reportId: number
  events: ReportFeedbackEvent[]
  feedback: ReportEventFeedback[]
}
export interface ReportEventFeedbackDraft { eventKey: string; category: FeedbackCategory; comment: string }
export interface ReportEventFeedbackRequest extends ReportEventFeedbackDraft { idempotencyKey: string }

async function request<T>(reportId: number, init: RequestInit): Promise<T> {
  const response = await fetch(`/api/news/reports/${reportId}/event-feedback`, {
    ...init, cache: 'no-store', headers: { 'Content-Type': 'application/json' },
  })
  let envelope: ApiEnvelope<T>
  try { envelope = await response.json() }
  catch { throw new ApiError('NETWORK', '서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.', response.status) }
  if (!response.ok || !envelope.isSuccess) throw new ApiError(envelope.code, envelope.message, response.status)
  return envelope.result
}
export function loadReportEventFeedback(reportId: number, signal?: AbortSignal) {
  return request<ReportEventFeedbackContext>(reportId, { method: 'GET', signal })
}
export function submitReportEventFeedback(reportId: number, body: ReportEventFeedbackRequest, signal?: AbortSignal) {
  return request<ReportEventFeedback>(reportId, { method: 'POST', body: JSON.stringify(body), signal })
}
