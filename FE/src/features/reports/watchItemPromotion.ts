import { ApiError } from '../../api/client.ts'
import { promoteReportWatchItem, type ReportWatchItem } from '../../api/reportWatchItems.ts'

export interface WatchItemPromotionState {
  pending: boolean
  accepted: boolean
  needsRefresh: boolean
  error: string
}

export const INITIAL_WATCH_PROMOTION: WatchItemPromotionState = {
  pending: false, accepted: false, needsRefresh: false, error: '',
}

/** A request belongs to one saved watch item, including when its card disappears during refresh. */
export function createWatchItemPromotion(reportId: number, item: ReportWatchItem, callbacks: {
  onChange: (state: WatchItemPromotionState) => void
  onRefresh: () => Promise<unknown>
}) {
  let state = INITIAL_WATCH_PROMOTION
  let request: AbortController | null = null
  let active = true
  let refreshReport = callbacks.onRefresh
  function update(values: Partial<WatchItemPromotionState>) {
    state = { ...state, ...values }
    if (active) callbacks.onChange(state)
  }
  async function run(refreshOnly: boolean) {
    if (!active || request || (!refreshOnly && (state.accepted || state.needsRefresh))) return
    const current = new AbortController()
    request = current
    update({ pending: true, error: '' })
    try {
      if (!refreshOnly) {
        await promoteReportWatchItem(reportId, item, current.signal)
        if (current.signal.aborted) return
        update({ accepted: true })
      }
      await refreshReport()
    } catch (failure) {
      if (current.signal.aborted) return
      update({
        error: state.accepted ? '중요 이벤트로 올렸지만 보고서를 새로 불러오지 못했습니다. 다시 불러와 주세요.'
          : failure instanceof ApiError ? failure.message : '중요 이벤트로 올리지 못했습니다. 다시 시도해 주세요.',
        needsRefresh: state.needsRefresh || (failure instanceof ApiError && (failure.status === 409 || failure.status === 404)),
      })
    } finally {
      if (!current.signal.aborted) { request = null; update({ pending: false }) }
    }
  }
  return {
    promote: () => run(false),
    refresh: () => run(true),
    setRefresh(callback: () => Promise<unknown>) { refreshReport = callback },
    activate() { active = true },
    dispose() { active = false; request?.abort(); request = null },
  }
}
