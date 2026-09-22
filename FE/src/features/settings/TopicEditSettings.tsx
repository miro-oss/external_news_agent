import { useEffect, useId, useRef, useState, type RefObject } from 'react'
import { createPortal } from 'react-dom'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../../api/client'
import type { TopicDetail, TopicSummary } from '../../api/types'
import { saveTopicEditOptions, topicEditOptions, type TopicEditRequest } from '../../api/topicEditing'
import { baseTopicKeywords } from './topicKeywordInput'
import { buildTopicEditRequest, topicEditDraft, type TopicEditDraft } from './topicEditDraft'
import '../notifications/notifications-refinement.css'
import './topic-edit.css'

export function TopicEditSettings({ topic }: { topic: TopicSummary }) {
  const client = useQueryClient()
  const save = useMutation(saveTopicEditOptions(client, topic.id))
  const [editing, setEditing] = useState<TopicDetail | null>(null)
  const [loading, setLoading] = useState(false)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [success, setSuccess] = useState(false)
  const trigger = useRef<HTMLButtonElement>(null)
  const id = useId()

  useEffect(() => {
    if (!success) return
    const timer = window.setTimeout(() => setSuccess(false), 3000)
    return () => window.clearTimeout(timer)
  }, [success])

  async function open() {
    if (loading) return
    setLoading(true)
    setLoadError(null)
    setSuccess(false)
    save.reset()
    try {
      setEditing(await client.fetchQuery({ ...topicEditOptions(topic.id), staleTime: 0 }))
    } catch (error) {
      setLoadError(error instanceof ApiError ? error.message : '수집 설정을 불러오지 못했습니다. 다시 시도해 주세요.')
    } finally {
      setLoading(false)
    }
  }

  return <div className="saved-topic-edit">
    <button ref={trigger} type="button" className="ghost-button topic-management-action" disabled={loading}
      aria-label={`${topic.name} 수집 편집`} aria-haspopup="dialog" aria-expanded={editing !== null}
      aria-controls={editing ? id : undefined} onClick={() => void open()}>
      {loading ? '불러오는 중…' : '수집 편집'}
    </button>
    {success && <small role="status">저장했습니다.</small>}
    {loadError && <small className="field-error" role="alert">{loadError}</small>}
    {editing && createPortal(<TopicEditDialog id={id} initial={editing} pending={save.isPending}
      returnFocusRef={trigger} onDraftChange={save.reset}
      error={save.error ? save.error instanceof ApiError ? save.error.message : '수집 설정을 저장하지 못했습니다. 다시 시도해 주세요.' : null}
      onDismiss={() => { if (!save.isPending) setEditing(null) }}
      onSave={changes => save.mutate(changes, {
        onSuccess: () => { setEditing(null); setSuccess(true) },
      })} />, document.body)}
  </div>
}

export function TopicEditDialog({ id, initial, pending = false, error, onDismiss, onSave, onDraftChange, returnFocusRef }: {
  id: string
  initial: TopicDetail
  pending?: boolean
  error?: string | null
  onDismiss: () => void
  onSave: (changes: TopicEditRequest) => void
  onDraftChange?: () => void
  returnFocusRef?: RefObject<HTMLButtonElement | null>
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  const nameInput = useRef<HTMLInputElement>(null)
  const [draft, setDraft] = useState(() => topicEditDraft(initial))
  const [nameTouched, setNameTouched] = useState(false)
  const [queryTouched, setQueryTouched] = useState(false)
  const requiresQuery = initial.sources.some(source => source.sourceKind === 'SEARCH')
  const nameMissing = draft.name.trim().length === 0
  const queryMissing = requiresQuery && baseTopicKeywords(draft.queryText).length === 0
  const changes = buildTopicEditRequest(initial, draft)
  const canSave = !pending && !nameMissing && !queryMissing && Object.keys(changes).length > 0

  useEffect(() => {
    const element = dialog.current
    const previousFocus = document.activeElement
    const trigger = returnFocusRef?.current
    element?.showModal()
    nameInput.current?.focus()
    document.body.classList.add('modal-open')
    return () => {
      element?.close()
      document.body.classList.remove('modal-open')
      if (trigger?.isConnected) trigger.focus()
      else if (previousFocus instanceof HTMLElement && previousFocus.isConnected) previousFocus.focus()
    }
  }, [returnFocusRef])

  function update(field: keyof TopicEditDraft, value: string) {
    setDraft(current => ({ ...current, [field]: value }))
    onDraftChange?.()
  }

  function dismiss() {
    if (!pending) onDismiss()
  }

  return <dialog ref={dialog} id={id} className="recipient-selection-dialog topic-edit-dialog"
    aria-labelledby={`${id}-title`} aria-describedby={`${id}-description`} aria-busy={pending}
    onCancel={event => { event.preventDefault(); dismiss() }}
    onClick={event => {
      if (event.target !== event.currentTarget) return
      const bounds = event.currentTarget.getBoundingClientRect()
      if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) dismiss()
    }}>
    <header className="recipient-selection-heading">
      <div><h2 id={`${id}-title`}>수집 편집</h2><p id={`${id}-description`}>{initial.name}</p></div>
      <button type="button" className="text-button recipient-selection-close" aria-label="수집 편집 닫기"
        disabled={pending} onClick={dismiss}>×</button>
    </header>
    <form className="topic-edit-form" onSubmit={event => { event.preventDefault(); if (canSave) onSave(changes) }}>
      <div className="topic-edit-body">
        <div className="field">
          <label htmlFor={`${id}-name`}>주제명</label>
          <input ref={nameInput} id={`${id}-name`} value={draft.name} maxLength={200} required disabled={pending}
            onChange={event => update('name', event.target.value)} onBlur={() => setNameTouched(true)}
            aria-invalid={nameTouched && nameMissing || undefined}
            aria-describedby={nameTouched && nameMissing ? `${id}-name-error` : undefined} />
          {nameTouched && nameMissing && <p id={`${id}-name-error`} className="error">주제명을 입력해 주세요.</p>}
        </div>
        <div className="field">
          <label htmlFor={`${id}-query`}>검색 키워드</label>
          <input id={`${id}-query`} value={draft.queryText} maxLength={500} required={requiresQuery} disabled={pending}
            onChange={event => update('queryText', event.target.value)} onBlur={() => setQueryTouched(true)}
            aria-invalid={queryTouched && queryMissing || undefined}
            aria-describedby={`${id}-query-hint${queryTouched && queryMissing ? ` ${id}-query-error` : ''}`} />
          <p id={`${id}-query-hint`} className="hint">공백이나 쉼표로 구분합니다. 기사 조건은 아래에서 따로 수정할 수 있습니다.</p>
          {queryTouched && queryMissing && <p id={`${id}-query-error`} className="error">검색 키워드를 입력해 주세요.</p>}
        </div>
        <fieldset className="topic-edit-conditions" disabled={pending} aria-describedby={`${id}-conditions-hint`}>
          <legend>상세 기사 조건</legend>
          <p id={`${id}-conditions-hint`} className="hint">각 조건은 쉼표로 구분합니다. 비워 두면 해당 조건을 사용하지 않습니다.</p>
          <div className="field">
            <label htmlFor={`${id}-required`}>모두 포함</label>
            <textarea id={`${id}-required`} value={draft.requiredKeywords} rows={3} disabled={pending}
              placeholder="HBM, 반도체" onChange={event => update('requiredKeywords', event.target.value)} />
          </div>
          <div className="field">
            <label htmlFor={`${id}-optional`}>하나 이상 포함</label>
            <textarea id={`${id}-optional`} value={draft.optionalKeywords} rows={3} disabled={pending}
              placeholder="SK하이닉스, 삼성전자, 고대역폭 메모리" onChange={event => update('optionalKeywords', event.target.value)} />
          </div>
          <div className="field">
            <label htmlFor={`${id}-excluded`}>제외</label>
            <textarea id={`${id}-excluded`} value={draft.excludedKeywords} rows={3} disabled={pending}
              placeholder="광고, 채용" onChange={event => update('excludedKeywords', event.target.value)}
              aria-describedby={`${id}-excluded-hint`} />
            <p id={`${id}-excluded-hint`} className="hint">제외 키워드가 들어간 기사는 모으지 않습니다.</p>
          </div>
        </fieldset>
        <p className="hint topic-edit-application-hint">변경한 설정은 다음 수집부터 적용됩니다.</p>
        {!initial.active && <p className="hint">현재 수집이 중지된 주제입니다. 수집을 재개하면 변경한 설정으로 수집합니다.</p>}
      </div>
      <footer className="recipient-selection-actions">
        {error && <p className="error topic-edit-save-error" role="alert">{error}</p>}
        <button type="button" className="secondary-button" disabled={pending} onClick={dismiss}>취소</button>
        <button type="submit" className="primary-button" disabled={!canSave}>{pending ? '저장 중…' : '저장'}</button>
      </footer>
    </form>
  </dialog>
}
