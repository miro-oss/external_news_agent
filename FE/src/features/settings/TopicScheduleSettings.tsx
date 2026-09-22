import { useEffect, useId, useRef, useState, type RefObject } from 'react'
import { createPortal } from 'react-dom'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { TransientStatus } from '../../components/TransientStatus'
import { ApiError } from '../../api/client'
import type { Topic, TopicSummary } from '../../api/types'
import { saveTopicScheduleOptions, topicScheduleOptions } from '../../api/topicSchedule'
import { COLLECTION_INTERVALS, formatCollectionInterval, isCollectionInterval } from './collectionIntervals'
import '../notifications/notifications-refinement.css'
import './topic-schedule.css'

function formatCollectedAt(value: string | null) {
  if (!value) return '—'
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return value
  return parsed.toLocaleString('ko-KR', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  })
}

export function TopicScheduleSettings({ topic }: { topic: TopicSummary }) {
  const client = useQueryClient()
  const save = useMutation(saveTopicScheduleOptions(client, topic.id))
  const [editing, setEditing] = useState<Topic | null>(null)
  const [loading, setLoading] = useState(false)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [success, setSuccess] = useState(false)
  const trigger = useRef<HTMLButtonElement>(null)
  const id = useId()

  async function open() {
    if (loading) return
    setLoading(true)
    setLoadError(null)
    setSuccess(false)
    save.reset()
    try {
      setEditing(await client.fetchQuery({ ...topicScheduleOptions(topic.id), staleTime: 0 }))
    } catch (error) {
      setLoadError(error instanceof ApiError ? error.message : '수집 일정을 불러오지 못했습니다. 다시 시도해 주세요.')
    } finally {
      setLoading(false)
    }
  }

  return <div className="saved-topic-schedule">
    <span>{formatCollectionInterval(topic.intervalMinutes)}</span>
    <small title="마지막 수집">{formatCollectedAt(topic.lastCollectedAt)}</small>
    <TransientStatus as="small" message={success ? '저장했습니다.' : null} />
    {loadError && <small className="field-error" role="alert">{loadError}</small>}
    <button ref={trigger} type="button" className="ghost-button topic-management-action" disabled={loading}
      aria-label={`${topic.name} 수집 일정 설정`} aria-haspopup="dialog" aria-expanded={editing !== null}
      aria-controls={editing ? id : undefined} onClick={() => void open()}>
      {loading ? '불러오는 중…' : loadError ? '다시 불러오기' : '일정 설정'}
    </button>
    {editing && createPortal(<TopicScheduleDialog id={id} initial={editing} pending={save.isPending}
      returnFocusRef={trigger} onDraftChange={save.reset}
      error={save.error ? save.error instanceof ApiError ? save.error.message : '수집 일정을 저장하지 못했습니다. 다시 시도해 주세요.' : null}
      onDismiss={() => { if (!save.isPending) setEditing(null) }}
      onSave={intervalMinutes => save.mutate({ intervalMinutes }, {
        onSuccess: () => { setEditing(null); setSuccess(true) },
      })} />, document.body)}
  </div>
}

export function TopicScheduleDialog({ id, initial, pending = false, error, onDismiss, onSave, onDraftChange, returnFocusRef }: {
  id: string
  initial: Topic
  pending?: boolean
  error?: string | null
  onDismiss: () => void
  onSave: (intervalMinutes: number) => void
  onDraftChange?: () => void
  returnFocusRef?: RefObject<HTMLButtonElement | null>
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  const select = useRef<HTMLSelectElement>(null)
  const [interval, setInterval] = useState(String(initial.intervalMinutes))
  const supported = isCollectionInterval(interval)
  const canSave = !pending && supported && Number(interval) !== initial.intervalMinutes

  useEffect(() => {
    const element = dialog.current
    const previousFocus = document.activeElement
    const trigger = returnFocusRef?.current
    element?.showModal()
    select.current?.focus()
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

  return <dialog ref={dialog} id={id} className="recipient-selection-dialog topic-schedule-dialog"
    aria-labelledby={`${id}-title`} aria-describedby={`${id}-description`} aria-busy={pending}
    onCancel={event => { event.preventDefault(); dismiss() }}
    onClick={event => {
      if (event.target !== event.currentTarget) return
      const bounds = event.currentTarget.getBoundingClientRect()
      if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) dismiss()
    }}>
    <header className="recipient-selection-heading">
      <div><h2 id={`${id}-title`}>수집 일정 설정</h2><p id={`${id}-description`}>{initial.name}</p></div>
      <button type="button" className="text-button recipient-selection-close" aria-label="수집 일정 설정 닫기"
        disabled={pending} onClick={dismiss}>×</button>
    </header>
    <form className="topic-schedule-form" onSubmit={event => { event.preventDefault(); if (canSave) onSave(Number(interval)) }}>
      <div className="topic-schedule-body">
        <div className="field">
          <label htmlFor={`${id}-interval`}>수집 주기</label>
          <select ref={select} id={`${id}-interval`} value={interval} disabled={pending}
            aria-describedby={`${id}-hint${!supported ? ` ${id}-unsupported` : ''}`}
            onChange={event => { setInterval(event.target.value); onDraftChange?.() }}>
            {!isCollectionInterval(String(initial.intervalMinutes)) && <option value={String(initial.intervalMinutes)} disabled>
              현재 · {formatCollectionInterval(initial.intervalMinutes)}
            </option>}
            {COLLECTION_INTERVALS.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}
          </select>
          <p id={`${id}-hint`} className="hint">새 기사를 확인하는 주기입니다. 변경한 주기는 앞으로 시작하는 정기 수집에 적용됩니다.</p>
          {!supported && <p id={`${id}-unsupported`} className="hint">일정을 변경하려면 1시간, 12시간, 24시간 중에서 선택해 주세요.</p>}
          {!initial.active && <p className="hint">현재 수집이 중지된 주제입니다. 수집을 재개하면 저장한 주기로 수집합니다.</p>}
        </div>
        {error && <p className="error" role="alert">{error}</p>}
      </div>
      <footer className="recipient-selection-actions">
        <button type="button" className="secondary-button" disabled={pending} onClick={dismiss}>취소</button>
        <button type="submit" className="primary-button" disabled={!canSave}>{pending ? '저장 중…' : '저장'}</button>
      </footer>
    </form>
  </dialog>
}
