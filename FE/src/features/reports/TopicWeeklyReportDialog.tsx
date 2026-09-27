import { useEffect, useRef, useState, type RefObject } from 'react'
import { lastCompletedReportWeek, reportWeekError, reportWeekStart, shiftReportDate } from './reportWeek'
import './topic-weekly-report.css'

export interface WeeklyReportTopicOption { id: number; name: string; active: boolean }

interface Props {
  id: string
  topics: readonly WeeklyReportTopicOption[]
  topicsLoading?: boolean
  topicsError?: string | null
  initialTopicId?: number | null
  now?: Date
  pending?: boolean
  waiting?: boolean
  error?: string | null
  onRetryTopics: () => void
  onDismiss: () => void
  onCreate: (topicId: number, weekStart: string) => void
  onDraftChange?: () => void
  returnFocusRef?: RefObject<HTMLButtonElement | null>
}

export function TopicWeeklyReportDialog({ id, topics, topicsLoading = false, topicsError,
  initialTopicId, now = new Date(), pending = false, waiting = false, error, onRetryTopics, onDismiss, onCreate,
  onDraftChange, returnFocusRef }: Props) {
  const dialog = useRef<HTMLDialogElement>(null)
  const topicSelect = useRef<HTMLSelectElement>(null)
  const closeButton = useRef<HTMLButtonElement>(null)
  const [topicId, setTopicId] = useState(initialTopicId ?? null)
  const [weekStart, setWeekStart] = useState(() => lastCompletedReportWeek(now))
  const lastWeek = lastCompletedReportWeek(now)
  const weekError = reportWeekError(weekStart, now)
  const weekEnd = shiftReportDate(weekStart, 6)
  const selectedTopic = topics.find(topic => topic.id === topicId)
  const busy = pending || waiting
  const canCreate = !busy && !topicsLoading && !topicsError && !!selectedTopic && !weekError

  useEffect(() => {
    const element = dialog.current
    const previousFocus = document.activeElement
    const trigger = returnFocusRef?.current
    element?.showModal()
    if (topicSelect.current && !topicSelect.current.disabled) topicSelect.current.focus()
    else closeButton.current?.focus()
    document.body.classList.add('modal-open')
    return () => {
      element?.close()
      document.body.classList.remove('modal-open')
      if (trigger?.isConnected) trigger.focus()
      else if (previousFocus instanceof HTMLElement && previousFocus.isConnected) previousFocus.focus()
    }
  }, [returnFocusRef])

  function dismiss() {
    if (!pending) onDismiss()
  }

  function changeWeek(value: string) {
    setWeekStart(reportWeekStart(value))
    onDraftChange?.()
  }

  return <dialog ref={dialog} id={id} className="topic-weekly-dialog" aria-labelledby={`${id}-title`}
    aria-describedby={`${id}-description`} aria-busy={busy || topicsLoading}
    onCancel={event => { event.preventDefault(); dismiss() }}
    onClick={event => {
      if (event.target !== event.currentTarget) return
      const bounds = event.currentTarget.getBoundingClientRect()
      if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) dismiss()
    }}>
    <header className="topic-weekly-heading">
      <div><h2 id={`${id}-title`}>주제별 주간 보고서 만들기</h2>
        <p id={`${id}-description`}>한 주제의 소식을 월요일부터 일요일까지 모아 정리합니다.</p></div>
      <button ref={closeButton} type="button" className="text-button topic-weekly-close" disabled={pending}
        aria-label="주제별 주간 보고서 만들기 닫기" onClick={dismiss}>×</button>
    </header>
    <form className="topic-weekly-form" onSubmit={event => {
      event.preventDefault()
      if (canCreate && selectedTopic) onCreate(selectedTopic.id, weekStart)
    }}>
      <div className="topic-weekly-body">
        <div className="field">
          <label htmlFor={`${id}-topic`}>수집 주제</label>
          <select ref={topicSelect} id={`${id}-topic`} value={selectedTopic?.id ?? ''} required
            disabled={busy || topicsLoading || !!topicsError || topics.length === 0}
            aria-describedby={`${id}-topic-hint`}
            onChange={event => { setTopicId(event.target.value ? Number(event.target.value) : null); onDraftChange?.() }}>
            <option value="">{topicsLoading ? '주제를 불러오는 중…' : '주제를 선택해 주세요'}</option>
            {topics.map(topic => <option key={topic.id} value={topic.id}>{topic.name}{topic.active ? '' : ' (수집 중지)'}</option>)}
          </select>
          <p id={`${id}-topic-hint`} className="hint">수집을 중지한 주제도 이전에 모은 기사로 보고서를 만들 수 있습니다.</p>
          {topicsLoading && <p className="hint" role="status">전체 수집 주제를 불러오는 중입니다.</p>}
          {topicsError && <div className="topic-weekly-topic-error" role="alert"><p className="error">{topicsError}</p>
            <button type="button" className="text-button" disabled={pending} onClick={onRetryTopics}>주제 다시 불러오기</button></div>}
          {!topicsLoading && !topicsError && topics.length === 0 && <p className="hint" role="status">등록된 수집 주제가 없습니다. 수집 설정에서 주제를 등록해 주세요.</p>}
        </div>
        <div className="field">
          <label htmlFor={`${id}-week`}>주차 선택</label>
          <div className="topic-weekly-date-control">
            <button type="button" className="secondary-button" aria-label="이전 주 선택" disabled={busy || !weekStart}
              onClick={() => changeWeek(shiftReportDate(weekStart, -7))}>‹</button>
            <input id={`${id}-week`} type="date" value={weekStart} max={shiftReportDate(lastWeek, 6)} required
              disabled={busy} aria-invalid={!!weekError} aria-describedby={`${id}-week-hint${weekError ? ` ${id}-week-error` : ''}`}
              onChange={event => changeWeek(event.target.value)} />
            <button type="button" className="secondary-button" aria-label="다음 주 선택" disabled={busy || !weekStart || weekStart >= lastWeek}
              onClick={() => changeWeek(shiftReportDate(weekStart, 7))}>›</button>
          </div>
          <p id={`${id}-week-hint`} className="hint">날짜를 고르면 해당 주의 월~일이 선택됩니다. 한국 시간 기준으로 종료된 주만 만들 수 있습니다.</p>
          {weekError && <p id={`${id}-week-error`} className="error" role="alert">{weekError}</p>}
        </div>
        {!weekError && <div className="topic-weekly-selection" aria-live="polite">
          <span>{selectedTopic?.name ?? '선택할 주제의 주간 보고서'}</span>
          <strong><time dateTime={weekStart}>{weekStart}</time><span> ~ </span><time dateTime={weekEnd}>{weekEnd}</time></strong>
          <small>월요일 ~ 일요일 · 한국 시간</small>
        </div>}
        {!busy && <p className="hint topic-weekly-reuse-hint">같은 주제·주차의 보고서가 있으면 기존 보고서를 엽니다.</p>}
        {busy && <p className="topic-weekly-progress" role="status">{waiting
          ? '보고서가 준비되면 자동으로 엽니다. 이 창을 닫아도 생성은 계속됩니다.'
          : '주간 보고서를 만들고 있습니다. 잠시 기다려 주세요.'}</p>}
      </div>
      <footer className="topic-weekly-actions">
        {error && <p className="error topic-weekly-save-error" role="alert">{error}</p>}
        <button type="button" className="secondary-button" disabled={pending} onClick={dismiss}>{waiting ? '닫기' : '취소'}</button>
        <button type="submit" className="primary-button" disabled={!canCreate}>{busy ? '만드는 중…' : '보고서 만들기'}</button>
      </footer>
    </form>
  </dialog>
}
