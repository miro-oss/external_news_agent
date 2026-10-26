import { useCallback, useEffect, useState } from 'react'
import { loadCompanyInterests, type CompanyInterestCriterion } from '../../api/companyInterests'
import { reconcileCompanyInterests } from './companyInterestState'

/** One abortable read for a management page or report, never one request per event. */
export function useCompanyInterests(enabled = true, contextKey = '') {
  const [result, setResult] = useState<{ key: string; criteria: CompanyInterestCriterion[] } | null>(null)
  const [failure, setFailure] = useState<{ key: string; error: unknown } | null>(null)
  const [loadingKey, setLoadingKey] = useState<string | null>(null)
  const [revision, setRevision] = useState(0)
  const key = `${contextKey}:${revision}`
  const refresh = useCallback(() => setRevision(value => value + 1), [])

  useEffect(() => {
    if (!enabled) return
    const controller = new AbortController()
    // Deferring the request state keeps it associated with this exact read, including a manual refresh.
    void Promise.resolve().then(async () => {
      if (controller.signal.aborted) return
      setLoadingKey(key)
      try {
        const data = await loadCompanyInterests(controller.signal)
        if (controller.signal.aborted) return
        setResult(current => ({ key, criteria: reconcileCompanyInterests(data.criteria, current?.criteria ?? []) }))
        setFailure(null)
      } catch (error) {
        if (!controller.signal.aborted) setFailure({ key, error })
      } finally {
        if (!controller.signal.aborted) setLoadingKey(null)
      }
    })
    return () => controller.abort()
  }, [enabled, key])

  const record = useCallback((criterion: CompanyInterestCriterion) => {
    setResult(current => current ? { ...current, criteria: current.criteria.map(item => item.id === criterion.id ? criterion : item) } : current)
  }, [])
  const settled = result?.key === key || failure?.key === key
  return {
    criteria: enabled ? result?.criteria ?? null : null,
    error: enabled && failure?.key === key ? failure.error : null,
    busy: enabled && (!settled || loadingKey === key),
    refresh, record,
  }
}
