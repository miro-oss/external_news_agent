import { ApiError } from './client.ts'
import type { ApiEnvelope } from './types'

export type FeedbackCategory = 'PREFERENCE' | 'TOPIC_MISMATCH' | 'SUMMARY_ERROR' | 'WRONG_CLUSTER' | 'OTHER'
export type FeedbackStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED'
export interface FeedbackItem {
  itemId: number
  topicId: number
  topicName: string
  title: string
  summary: string
  sources: Array<{ articleId: number; title: string; url: string }>
}
export interface ReaderFeedback {
  id: number
  itemId: number
  category: FeedbackCategory
  comment: string
  status: FeedbackStatus
  verdict: string | null
  diagnosis: string | null
  createdAt: string
}
export interface PersonalPolicy {
  id: number
  topicId: number
  topicName: string
  instruction: string
  version: number
  status: 'ACTIVE' | 'REVOKED'
  createdAt: string
}
export interface FeedbackContext {
  reportId: number
  reportTitle: string
  expiresAt: string
  items: FeedbackItem[]
  feedback: ReaderFeedback[]
  policies: PersonalPolicy[]
}
export interface FeedbackDraft {
  itemId: number
  category: FeedbackCategory
  comment: string
  allowPersonalization: boolean
}
export interface FeedbackRequest extends FeedbackDraft { idempotencyKey: string }

// Capability data must never enter the shared query/mutation cache or a request URL.
async function feedbackPost<T>(path: string, body: unknown, signal?: AbortSignal): Promise<T> {
  const response = await fetch(`/api/feedback${path}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
    credentials: 'omit', cache: 'no-store', redirect: 'error', referrerPolicy: 'no-referrer', signal,
  })
  let envelope: ApiEnvelope<T>
  try { envelope = await response.json() }
  catch { throw new ApiError('NETWORK', '서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.', response.status) }
  if (!response.ok || !envelope.isSuccess) {
    throw new ApiError(envelope.code, envelope.message, response.status)
  }
  return envelope.result
}

export function loadFeedbackContext(token: string, signal?: AbortSignal) {
  return feedbackPost<FeedbackContext>('/context', { token }, signal)
}
export function submitFeedback(token: string, request: FeedbackRequest, signal?: AbortSignal) {
  return feedbackPost<ReaderFeedback>('', { token, ...request }, signal)
}
export function revokePersonalPolicy(token: string, policy: Pick<PersonalPolicy, 'id' | 'version'>, signal?: AbortSignal) {
  return feedbackPost<PersonalPolicy>(`/policies/${policy.id}/revoke`, { token, version: policy.version }, signal)
}
