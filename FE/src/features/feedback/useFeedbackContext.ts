import { useCallback, useEffect, useState } from 'react'
import { ApiError } from '../../api/client'
import { loadFeedbackContext, type FeedbackContext, type PersonalPolicy, type ReaderFeedback } from '../../api/feedback'
import { nextFeedbackPoll, pendingFeedback } from './feedbackState'

export function useFeedbackContext(token: string | null) {
  const [data, setData] = useState<FeedbackContext | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [busy, setBusy] = useState(!!token)
  const [paused, setPaused] = useState(false)
  const [revision, setRevision] = useState(0)
  const refresh = useCallback(() => { setBusy(true); setPaused(false); setRevision(value => value + 1) }, [])

  useEffect(() => {
    if (!token) return
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout> | undefined
    let attempt = 0
    async function load() {
      try {
        const result = await loadFeedbackContext(token!, controller.signal)
        if (controller.signal.aborted) return
        setData(result)
        setError(null)
        const delay = nextFeedbackPoll(result.feedback, attempt)
        setPaused(delay === false && pendingFeedback(result.feedback))
        if (delay !== false) { attempt += 1; timer = setTimeout(() => { void load() }, delay) }
      } catch (failure) {
        if (controller.signal.aborted) return
        setError(failure)
        setPaused(true)
        if (failure instanceof ApiError && (failure.status === 404 || failure.status === 400)) setData(null)
      } finally {
        if (!controller.signal.aborted) setBusy(false)
      }
    }
    void load()
    return () => { controller.abort(); clearTimeout(timer) }
  }, [token, revision])

  function recordFeedback(feedback: ReaderFeedback) {
    setData(current => current && ({ ...current, feedback: [feedback, ...current.feedback.filter(item => item.id !== feedback.id)] }))
    refresh()
  }
  function recordPolicy(policy: PersonalPolicy) {
    setData(current => current && ({ ...current, policies: current.policies.map(item => item.id === policy.id ? policy : item) }))
    refresh()
  }
  const unavailable = !token || (error instanceof ApiError && (error.status === 404 || error.status === 400))
  return { data, error, busy, paused, unavailable, refresh, recordFeedback, recordPolicy }
}
