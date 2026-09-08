import { useEffect, useId, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import type { NotificationRecipient } from '../../api/types'

type Props = {
  recipients: NotificationRecipient[]
  selected: number[]
  onChange: (ids: number[]) => void
  disabled?: boolean
}

export function GroupRecipientPicker({ recipients, selected, onChange, disabled = false }: Props) {
  const [open, setOpen] = useState(false)
  const id = useId()
  const chosen = recipients.filter(recipient => selected.includes(recipient.id))
  return <div className="group-recipient-picker">
    <span id={`${id}-label`}>수신자 선택</span>
    <button type="button" className="group-recipient-trigger" disabled={disabled}
      aria-labelledby={`${id}-label ${id}-value`} aria-haspopup="dialog" aria-expanded={open}
      aria-controls={open ? `${id}-dialog` : undefined} onClick={() => setOpen(true)}>
      <span id={`${id}-value`} className={chosen.length ? 'group-recipient-value' : 'group-recipient-placeholder'}>
        {chosen.length ? chosen.slice(0, 2).map(recipient => recipient.name).join(', ') + (chosen.length > 2 ? ' 외' : '') : '수신자를 선택해 주세요'}
      </span>
      {chosen.length > 0 && <span className="group-recipient-count">{chosen.length}명</span>}
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="m9 5 7 7-7 7" /></svg>
    </button>
    {open && createPortal(<RecipientSelectionDialog id={`${id}-dialog`} recipients={recipients} selected={selected}
      onDismiss={() => setOpen(false)} onApply={ids => { onChange(ids); setOpen(false) }} />, document.body)}
  </div>
}

function RecipientSelectionDialog({ id, recipients, selected, onDismiss, onApply }: {
  id: string
  recipients: NotificationRecipient[]
  selected: number[]
  onDismiss: () => void
  onApply: (ids: number[]) => void
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  const searchInput = useRef<HTMLInputElement>(null)
  const [search, setSearch] = useState('')
  const [draft, setDraft] = useState(selected)
  const query = search.trim().toLocaleLowerCase()
  const matches = recipients.filter(recipient => `${recipient.name} ${recipient.email ?? ''}`.toLocaleLowerCase().includes(query))
  const available = new Set(recipients.map(recipient => recipient.id))
  const validDraft = draft.filter(recipientId => available.has(recipientId))
  const allMatchesSelected = matches.length > 0 && matches.every(recipient => validDraft.includes(recipient.id))

  function toggleAllMatches() {
    const matchingIds = new Set(matches.map(recipient => recipient.id))
    setDraft(current => allMatchesSelected
      ? current.filter(recipientId => !matchingIds.has(recipientId))
      : [...new Set([...current, ...matchingIds])])
  }

  useEffect(() => {
    const element = dialog.current
    const previousFocus = document.activeElement
    element?.showModal()
    searchInput.current?.focus()
    document.body.classList.add('modal-open')
    return () => {
      element?.close()
      document.body.classList.remove('modal-open')
      if (previousFocus instanceof HTMLElement && previousFocus.isConnected) previousFocus.focus()
    }
  }, [])

  return <dialog ref={dialog} id={id} className="recipient-selection-dialog" aria-labelledby={`${id}-title`}
    onCancel={event => { event.preventDefault(); onDismiss() }}
    onClick={event => {
      if (event.target !== event.currentTarget) return
      const bounds = event.currentTarget.getBoundingClientRect()
      if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) onDismiss()
    }}>
    <header className="recipient-selection-heading">
      <div><h2 id={`${id}-title`}>수신자 선택</h2><p>그룹에 함께할 사람을 골라 주세요.</p></div>
      <button type="button" className="text-button recipient-selection-close" aria-label="수신자 선택 닫기" onClick={onDismiss}>×</button>
    </header>
    <div className="recipient-selection-search">
      <input ref={searchInput} type="search" aria-label="수신자 검색" placeholder="이름이나 이메일로 검색" value={search} onChange={event => setSearch(event.target.value)} />
      <div className="recipient-selection-meta">
        <div><span>{query ? `검색 결과 ${matches.length}명` : `전체 ${recipients.length}명`}</span>
          <button type="button" className="text-button" disabled={!matches.length} onClick={toggleAllMatches}>
            {query ? (allMatchesSelected ? '검색 결과 선택 해제' : '검색 결과 전체 선택') : (allMatchesSelected ? '전체 선택 해제' : '전체 선택')}
          </button>
        </div>
        <span aria-live="polite">{validDraft.length}명 선택</span>
      </div>
    </div>
    <div className="member-choice-list recipient-selection-list" role="group" aria-label="선택 가능한 수신자">
      {matches.map(recipient => <label key={recipient.id}>
        <input type="checkbox" checked={validDraft.includes(recipient.id)} onChange={() => setDraft(current => current.includes(recipient.id) ? current.filter(value => value !== recipient.id) : [...current, recipient.id])} />
        <span><strong>{recipient.name}</strong></span>
      </label>)}
      {!matches.length && <p className="recipient-selection-empty">{recipients.length ? '검색 결과가 없습니다.' : '수신자를 먼저 등록해 주세요.'}</p>}
    </div>
    <footer className="recipient-selection-actions">
      <button type="button" className="secondary-button" onClick={onDismiss}>취소</button>
      <button type="button" className="primary-button" onClick={() => onApply(validDraft)}>선택 완료{validDraft.length > 0 ? ` · ${validDraft.length}명` : ''}</button>
    </footer>
  </dialog>
}
