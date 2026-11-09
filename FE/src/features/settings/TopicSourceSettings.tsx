import { useEffect, useId, useRef, useState, type RefObject } from 'react'
import { createPortal } from 'react-dom'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../../api/client'
import type { TopicSummary } from '../../api/types'
import { saveTopicSourcesOptions, topicSourceEditOptions, type TopicSourceEditData } from '../../api/topicSources'
import { TransientStatus } from '../../components/TransientStatus'
import { TopicSourceSelector } from './TopicSourceSelector'
import '../notifications/notifications-refinement.css'
import './topic-edit.css'

export function TopicSourceSettings({ topic }: { topic: TopicSummary }) {
  const client = useQueryClient()
  const save = useMutation(saveTopicSourcesOptions(client, topic.id))
  const [editing, setEditing] = useState<TopicSourceEditData | null>(null)
  const [loading, setLoading] = useState(false)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [success, setSuccess] = useState<string | null>(null)
  const trigger = useRef<HTMLButtonElement>(null)
  const id = useId()

  async function open() {
    if (loading) return
    setLoading(true)
    setLoadError(null)
    setSuccess(null)
    save.reset()
    try {
      setEditing(await client.fetchQuery(topicSourceEditOptions(topic.id)))
    } catch (error) {
      setLoadError(error instanceof ApiError ? error.message : '연결된 소스와 소스 목록을 불러오지 못했습니다. 다시 시도해 주세요.')
    } finally {
      setLoading(false)
    }
  }

  return <div className="saved-topic-sources">
    <button ref={trigger} type="button" className="ghost-button topic-management-action" disabled={loading}
      aria-label={`${topic.name} 소스 편집`} aria-haspopup="dialog" aria-expanded={editing !== null}
      aria-controls={editing ? id : undefined} onClick={() => void open()}>
      {loading ? '불러오는 중…' : '소스 편집'}
    </button>
    <TransientStatus as="small" message={success} />
    {loadError && <small className="field-error" role="alert">{loadError}</small>}
    {editing && createPortal(<TopicSourceDialog id={id} initial={editing} pending={save.isPending}
      returnFocusRef={trigger} onDraftChange={save.reset}
      error={save.error ? save.error instanceof ApiError ? save.error.message : '소스 연결을 저장하지 못했습니다. 다시 시도해 주세요.' : null}
      onDismiss={() => { if (!save.isPending) setEditing(null) }}
      onSave={sourceIds => save.mutate(sourceIds, {
        onSuccess: result => { setEditing(null); setSuccess(`${result.combinationCount}개 소스를 연결했습니다.`) },
      })} />, document.body)}
  </div>
}

export function TopicSourceDialog({ id, initial, pending = false, error, onDismiss, onSave, onDraftChange, returnFocusRef }: {
  id: string
  initial: TopicSourceEditData
  pending?: boolean
  error?: string | null
  onDismiss: () => void
  onSave: (sourceIds: number[]) => void
  onDraftChange?: () => void
  returnFocusRef?: RefObject<HTMLButtonElement | null>
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  const [selected, setSelected] = useState(() => initial.topic.sources.map(source => source.id))
  const [open, setOpen] = useState(true)
  const original = initial.topic.sources.map(source => source.id)
  const changed = selected.length !== original.length || selected.some(id => !original.includes(id))
  const canSave = !pending && changed

  useEffect(() => {
    const element = dialog.current
    const previousFocus = document.activeElement
    const trigger = returnFocusRef?.current
    element?.showModal()
    element?.querySelector<HTMLInputElement>('input[type="search"]')?.focus()
    document.body.classList.add('modal-open')
    return () => {
      element?.close()
      document.body.classList.remove('modal-open')
      if (trigger?.isConnected) trigger.focus()
      else if (previousFocus instanceof HTMLElement && previousFocus.isConnected) previousFocus.focus()
    }
  }, [returnFocusRef])

  function dismiss() { if (!pending) onDismiss() }

  return <dialog ref={dialog} id={id} className="recipient-selection-dialog topic-edit-dialog topic-source-dialog"
    aria-labelledby={`${id}-title`} aria-describedby={`${id}-description`} aria-busy={pending}
    onCancel={event => { event.preventDefault(); dismiss() }}
    onMouseDown={event => {
      if (event.button !== 0 || event.target !== event.currentTarget) return
      const bounds = event.currentTarget.getBoundingClientRect()
      if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) {
        event.preventDefault(); dismiss()
      }
    }}>
    <header className="recipient-selection-heading">
      <div><h2 id={`${id}-title`}>소스 편집</h2><p id={`${id}-description`}>{initial.topic.name}</p></div>
      <button type="button" className="text-button recipient-selection-close" aria-label="소스 편집 닫기"
        disabled={pending} onClick={dismiss}>×</button>
    </header>
    <form className="topic-edit-form" onSubmit={event => { event.preventDefault(); if (canSave) onSave(selected) }}>
      <div className="topic-edit-body">
        <p className="hint">저장하면 이 주제의 연결 목록이 선택한 소스로 바뀝니다. 검색어와 기사 조건은 그대로 유지됩니다.</p>
        <TopicSourceSelector sources={initial.sources} selected={selected} disabled={pending} open={open}
          onToggle={setOpen} allowEmpty preserveInactive
          onChange={ids => { setSelected(ids); onDraftChange?.() }} />
        <p className="hint" aria-live="polite">현재 {original.length}개 → 저장 후 {selected.length}개 연결</p>
      </div>
      <footer className="recipient-selection-actions">
        {error && <p className="error topic-edit-save-error" role="alert">{error}</p>}
        <button type="button" className="secondary-button" disabled={pending} onClick={dismiss}>취소</button>
        <button type="submit" className="primary-button" disabled={!canSave}>{pending ? '저장 중…' : '소스 저장'}</button>
      </footer>
    </form>
  </dialog>
}
