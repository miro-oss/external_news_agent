import { loadReportEventFeedback, type ReportEventFeedbackContext } from '../../api/reportEventFeedback.ts'
import { nextFeedbackPoll, pendingFeedback } from '../feedback/feedbackState.ts'

/** A single sequential loop; its disposer cancels the request and every future poll. */
export function startReportEventFeedbackPolling(reportId: number, callbacks: {
  onData: (data: ReportEventFeedbackContext, paused: boolean) => void
  onError: (error: unknown) => void
  onSettled: () => void
}) {
  const controller = new AbortController()
  let timer: ReturnType<typeof setTimeout> | undefined
  let attempt = 0
  async function load() {
    try {
      const result = await loadReportEventFeedback(reportId, controller.signal)
      if (controller.signal.aborted) return
      if (result.reportId !== reportId) throw new Error('보고서와 의견 정보가 일치하지 않습니다. 다시 불러와 주세요.')
      const delay = nextFeedbackPoll(result.feedback, attempt)
      callbacks.onData(result, delay === false && pendingFeedback(result.feedback))
      if (delay !== false) { attempt += 1; timer = setTimeout(() => { void load() }, delay) }
    } catch (error) {
      if (!controller.signal.aborted) callbacks.onError(error)
    } finally {
      if (!controller.signal.aborted) callbacks.onSettled()
    }
  }
  void load()
  return () => { controller.abort(); clearTimeout(timer) }
}
