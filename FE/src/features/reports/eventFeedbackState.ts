import type { ReportContent } from '../../api/types'
import type { ReportEventFeedbackContext, ReportEventFeedbackDraft, ReportEventFeedbackRequest } from '../../api/reportEventFeedback'

/** A displayed event is the complete saved evidence group, never its first article. */
export function matchReportFeedbackEvent(reportId: number, event: ReportContent['importantEvents'][number], index: number,
  context: ReportEventFeedbackContext | null) {
  if (context?.reportId !== reportId) return null
  const candidates = context.events.filter(item => item.eventIndex === index)
  if (candidates.length !== 1) return null
  const candidate = candidates[0]
  return /^[a-f0-9]{64}$/.test(candidate.eventKey) && candidate.title === event.title && candidate.summary === event.summaryKo
    && candidate.significance === event.significance && candidate.sourceFindingIds.length === event.sourceFindingIds.length
    && candidate.sourceFindingIds.every((id, position) => id === event.sourceFindingIds[position]) ? candidate : null
}
export function reportEventDraftError(draft: ReportEventFeedbackDraft) {
  if (!/^[a-f0-9]{64}$/.test(draft.eventKey)) return '보고서를 새로고침한 뒤 다시 확인해 주세요.'
  if (!draft.comment.trim() || draft.comment.trim().length > 2000) return '의견을 1~2,000자로 입력해 주세요.'
  return null
}
export function prepareReportEventFeedback(previous: ReportEventFeedbackRequest | null, draft: ReportEventFeedbackDraft): ReportEventFeedbackRequest {
  const normalized = { ...draft, comment: draft.comment.trim() }
  if (previous && previous.eventKey === normalized.eventKey && previous.category === normalized.category
    && previous.comment === normalized.comment) return previous
  return { ...normalized, idempotencyKey: `event-feedback-${crypto.randomUUID()}` }
}
