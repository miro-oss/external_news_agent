import { useCallback, useEffect, useState } from 'react'
import type { ReportEventFeedback, ReportEventFeedbackContext } from '../../api/reportEventFeedback'
import { startReportEventFeedbackPolling } from './reportEventFeedbackPolling'

/** One read/poll loop for the whole report. Rendering a card never submits feedback. */
export function useReportEventFeedback(reportId: number, enabled: boolean) {
  const [data, setData] = useState<ReportEventFeedbackContext | null>(null)
  const [failure, setFailure] = useState<{ reportId: number; error: unknown } | null>(null)
  const [busy, setBusy] = useState(enabled)
  const [paused, setPaused] = useState(false)
  const [revision, setRevision] = useState(0)
  const refresh = useCallback(() => { setBusy(true); setPaused(false); setRevision(value => value + 1) }, [])

  useEffect(() => {
    if (!enabled) return
    return startReportEventFeedbackPolling(reportId, {
      onData(result, pollingPaused) {
        setData(result)
        setFailure(null)
        setPaused(pollingPaused)
      },
      onError(error) {
        setFailure({ reportId, error })
        setPaused(true)
      },
      onSettled() { setBusy(false) },
    })
  }, [reportId, enabled, revision])

  function recordFeedback(feedback: ReportEventFeedback) {
    setData(current => current?.reportId === reportId
      ? { ...current, feedback: [feedback, ...current.feedback.filter(item => item.id !== feedback.id)] } : current)
    refresh()
  }
  return { data: data?.reportId === reportId ? data : null,
    error: failure?.reportId === reportId ? failure.error : null, busy, paused, refresh, recordFeedback }
}
