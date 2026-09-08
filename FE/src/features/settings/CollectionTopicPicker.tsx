import { useEffect, useId, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import '../notifications/notifications-refinement.css'

type CollectionTopic = { id: number; name: string }
type Props = {
  topics: CollectionTopic[]
  selected: number[]
  onChange: (ids: number[]) => void
  disabled?: boolean
}

export function CollectionTopicPicker({ topics, selected, onChange, disabled = false }: Props) {
  const [open, setOpen] = useState(false)
  const id = useId()
  const chosen = topics.filter(topic => selected.includes(topic.id))

  return <div className="group-recipient-picker collection-topic-picker">
    <span id={`${id}-label`}>수집할 주제</span>
    <button type="button" className="group-recipient-trigger" disabled={disabled}
      aria-labelledby={`${id}-label ${id}-value`} aria-haspopup="dialog" aria-expanded={open}
      aria-controls={open ? `${id}-dialog` : undefined} onClick={() => setOpen(true)}>
      <span id={`${id}-value`} className={chosen.length ? 'group-recipient-value' : 'group-recipient-placeholder'}>
        {chosen.length ? chosen.slice(0, 2).map(topic => topic.name).join(', ') + (chosen.length > 2 ? ' 외' : '') : '주제를 선택해 주세요'}
      </span>
      {chosen.length > 0 && <span className="group-recipient-count">{chosen.length}개</span>}
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="m9 5 7 7-7 7" /></svg>
    </button>
    {open && createPortal(<TopicSelectionDialog id={`${id}-dialog`} topics={topics} selected={selected}
      onDismiss={() => setOpen(false)} onApply={ids => { onChange(ids); setOpen(false) }} />, document.body)}
  </div>
}

function TopicSelectionDialog({ id, topics, selected, onDismiss, onApply }: {
  id: string
  topics: CollectionTopic[]
  selected: number[]
  onDismiss: () => void
  onApply: (ids: number[]) => void
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  const searchInput = useRef<HTMLInputElement>(null)
  const [search, setSearch] = useState('')
  const [draft, setDraft] = useState(selected)
  const query = search.trim().toLocaleLowerCase()
  const matches = topics.filter(topic => topic.name.toLocaleLowerCase().includes(query))
  const available = new Set(topics.map(topic => topic.id))
  const validDraft = draft.filter(topicId => available.has(topicId))
  const allMatchesSelected = matches.length > 0 && matches.every(topic => validDraft.includes(topic.id))

  function toggleAllMatches() {
    const matchingIds = new Set(matches.map(topic => topic.id))
    setDraft(current => allMatchesSelected
      ? current.filter(topicId => !matchingIds.has(topicId))
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
      <div><h2 id={`${id}-title`}>수집할 주제 선택</h2><p>이번에 함께 수집할 주제를 골라 주세요.</p></div>
      <button type="button" className="text-button recipient-selection-close" aria-label="주제 선택 닫기" onClick={onDismiss}>×</button>
    </header>
    <div className="recipient-selection-search">
      <input ref={searchInput} type="search" aria-label="주제 검색" placeholder="주제 이름으로 검색" value={search} onChange={event => setSearch(event.target.value)} />
      <div className="recipient-selection-meta">
        <div><span>{query ? `검색 결과 ${matches.length}개` : `전체 ${topics.length}개`}</span>
          <button type="button" className="text-button" disabled={!matches.length} onClick={toggleAllMatches}>
            {query ? (allMatchesSelected ? '검색 결과 선택 해제' : '검색 결과 전체 선택') : (allMatchesSelected ? '전체 선택 해제' : '전체 선택')}
          </button>
        </div>
        <span aria-live="polite">{validDraft.length}개 선택</span>
      </div>
    </div>
    <div className="member-choice-list recipient-selection-list" role="group" aria-label="선택 가능한 주제">
      {matches.map(topic => <label key={topic.id}>
        <input type="checkbox" checked={validDraft.includes(topic.id)} onChange={() => setDraft(current => current.includes(topic.id) ? current.filter(value => value !== topic.id) : [...current, topic.id])} />
        <span><strong>{topic.name}</strong></span>
      </label>)}
      {!matches.length && <p className="recipient-selection-empty">{topics.length ? '검색 결과가 없습니다.' : '수집할 주제를 먼저 등록해 주세요.'}</p>}
    </div>
    <footer className="recipient-selection-actions">
      <button type="button" className="secondary-button" onClick={onDismiss}>취소</button>
      <button type="button" className="primary-button" onClick={() => onApply(validDraft)}>선택 완료{validDraft.length > 0 ? ` · ${validDraft.length}개` : ''}</button>
    </footer>
  </dialog>
}
