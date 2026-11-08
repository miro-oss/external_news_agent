import { useEffect, useState } from 'react'
import type { ReportWatchItem } from '../../api/reportWatchItems'
import { createWatchItemPromotion, INITIAL_WATCH_PROMOTION } from './watchItemPromotion'

export function ReportWatchItemPromotion({ reportId, item, onRefresh }: {
  reportId: number
  item: ReportWatchItem
  onRefresh: () => Promise<unknown>
}) {
  const [state, setState] = useState(INITIAL_WATCH_PROMOTION)
  const [action] = useState(() => createWatchItemPromotion(reportId, item, {
    onChange: setState, onRefresh,
  }))
  useEffect(() => { action.setRefresh(onRefresh) }, [action, onRefresh])
  useEffect(() => { action.activate(); return () => action.dispose() }, [action])
  return <div className="report-event-feedback report-watch-promotion" aria-busy={state.pending}>
    <div className="report-item-actions">
      <button type="button" className="text-button report-watch-promote" disabled={state.pending || state.accepted || state.needsRefresh}
        aria-label={`${item.topic} 중요 이벤트로 올리기`} onClick={() => { void action.promote() }}>
        <span aria-hidden="true">↑ </span>{state.pending ? state.accepted ? '보고서 갱신 중…' : '올리는 중…'
          : state.accepted ? '중요 이벤트로 올림' : '중요 이벤트로 올리기'}
      </button>
      {(state.accepted || state.needsRefresh) && !state.pending && <button type="button" className="text-button"
        onClick={() => { void action.refresh() }}>보고서 다시 불러오기</button>}
    </div>
    {state.error && <div className="report-feedback-error" role="alert"><p>{state.error}</p>
      {state.needsRefresh && !state.accepted && <p>보고서 내용이 바뀌었을 수 있습니다. 새로 불러온 뒤 확인해 주세요.</p>}
    </div>}
    {state.accepted && !state.error && <p className="report-feedback-help" role="status">중요 이벤트에 반영했습니다.</p>}
  </div>
}
