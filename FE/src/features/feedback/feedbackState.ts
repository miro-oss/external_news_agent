import type { FeedbackCategory, FeedbackDraft, FeedbackRequest, ReaderFeedback } from '../../api/feedback'

export const FEEDBACK_CATEGORIES: Array<{ value: FeedbackCategory; label: string; description: string }> = [
  { value: 'PREFERENCE', label: '내 관심과 맞지 않아요', description: '내용이 틀린 것은 아니지만, 내 업무나 관심에 덜 맞아요.' },
  { value: 'TOPIC_MISMATCH', label: '수집 주제와 달라요', description: '이 소식이 선택한 수집 주제와 관련이 없어요.' },
  { value: 'SUMMARY_ERROR', label: '요약을 확인해 주세요', description: '원문과 다른 사실이나 빠진 맥락이 있어요.' },
  { value: 'WRONG_CLUSTER', label: '다른 소식이 묶였어요', description: '함께 묶인 기사들이 서로 다른 사건을 다뤄요.' },
  { value: 'OTHER', label: '다른 의견이 있어요', description: '위 항목에 해당하지 않는 의견을 남겨요.' },
]
export const FEEDBACK_STATUS_LABELS: Record<ReaderFeedback['status'], string> = {
  PENDING: '접수 완료', PROCESSING: '검토 중', COMPLETED: '검토 완료', FAILED: '검토하지 못함',
}
export const FEEDBACK_POLL_DELAYS = [2000, 4000, 8000, 15000, 30000, 30000] as const

export function isFeedbackHash(hash: string) {
  return hash.replace(/^#\/?/, '').split('?')[0] === 'feedback'
}
export function feedbackTokenFromHash(hash: string): string | null {
  if (!isFeedbackHash(hash)) return null
  const query = new URLSearchParams(hash.slice(hash.indexOf('?') + 1))
  const values = query.getAll('token')
  const token = values[0]
  return values.length === 1 && token.length > 0 && token.length <= 512 && !/\s/.test(token) ? token : null
}
export function pendingFeedback(feedback: Array<Pick<ReaderFeedback, 'status'>>) {
  return feedback.some(item => item.status === 'PENDING' || item.status === 'PROCESSING')
}
export function nextFeedbackPoll(feedback: Array<Pick<ReaderFeedback, 'status'>>, attempt: number): number | false {
  return pendingFeedback(feedback) && attempt >= 0 && attempt < FEEDBACK_POLL_DELAYS.length
    ? FEEDBACK_POLL_DELAYS[attempt] : false
}
export function feedbackDraftError(draft: FeedbackDraft) {
  if (!Number.isSafeInteger(draft.itemId) || draft.itemId <= 0) return '의견을 남길 소식을 선택해 주세요.'
  if (!draft.comment.trim() || draft.comment.trim().length > 2000) return '의견을 1~2,000자로 입력해 주세요.'
  if (draft.category !== 'PREFERENCE' && draft.allowPersonalization) return '개인 기준 적용은 관심에 대한 의견에서만 선택할 수 있습니다.'
  return null
}
export function prepareFeedbackRequest(previous: FeedbackRequest | null, draft: FeedbackDraft): FeedbackRequest {
  const normalized = { ...draft, comment: draft.comment.trim(), allowPersonalization: draft.category === 'PREFERENCE' && draft.allowPersonalization }
  if (previous && previous.itemId === normalized.itemId && previous.category === normalized.category
    && previous.comment === normalized.comment && previous.allowPersonalization === normalized.allowPersonalization) return previous
  return { ...normalized, idempotencyKey: `feedback-${crypto.randomUUID()}` }
}
export function safeFeedbackSource(value: string): string | null {
  try {
    const url = new URL(value)
    return ['https:', 'http:'].includes(url.protocol) && !url.username && !url.password ? url.href : null
  } catch { return null }
}
