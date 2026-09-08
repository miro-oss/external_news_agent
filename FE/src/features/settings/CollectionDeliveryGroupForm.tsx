import { useEffect, useId, useRef, useState } from 'react'
import type { useCreateNotificationGroup } from '../../api/queries'
import type { NotificationGroup, NotificationRecipient } from '../../api/types'
import { ApiError } from '../../api/client'

export function CollectionDeliveryGroupForm({ recipients, create, onCancel, onCreated }: {
  recipients: NotificationRecipient[]
  create: ReturnType<typeof useCreateNotificationGroup>
  onCancel: () => void
  onCreated: (group: NotificationGroup) => void
}) {
  const id = useId()
  const nameInput = useRef<HTMLInputElement>(null)
  const [name, setName] = useState('')
  const [search, setSearch] = useState('')
  const [selected, setSelected] = useState<number[]>([])
  const available = new Set(recipients.map(recipient => recipient.id))
  const validSelected = selected.filter(recipientId => available.has(recipientId))
  const query = search.trim().toLocaleLowerCase()
  const matches = recipients.filter(recipient => `${recipient.name} ${recipient.email ?? ''}`.toLocaleLowerCase().includes(query))
  const allSelected = matches.length > 0 && matches.every(recipient => validSelected.includes(recipient.id))

  useEffect(() => { nameInput.current?.focus() }, [])

  function toggleAll() {
    create.reset()
    const ids = new Set(matches.map(recipient => recipient.id))
    setSelected(current => allSelected ? current.filter(recipientId => !ids.has(recipientId)) : [...new Set([...current, ...ids])])
  }

  return <>
    <form id={id} className="collection-delivery-body collection-delivery-group-form" aria-label="수신 그룹 만들기"
      onSubmit={event => {
        event.preventDefault()
        if (create.isPending || !name.trim() || !validSelected.length) return
        create.mutate({ name: name.trim(), recipientIds: validSelected }, { onSuccess: onCreated })
      }}>
      <label className="collection-delivery-group-name">그룹명
        <input ref={nameInput} required maxLength={100} value={name} disabled={create.isPending} placeholder="예: 반도체 리서치팀"
          onChange={event => { create.reset(); setName(event.target.value) }} />
      </label>
      <div className="recipient-selection-search">
        <input type="search" aria-label="그룹에 넣을 수신자 검색" placeholder="이름 또는 이메일로 검색" value={search} disabled={create.isPending}
          onChange={event => setSearch(event.target.value)} />
        <div className="recipient-selection-meta">
          <span>{query ? `검색 결과 ${matches.length}명` : `전체 ${recipients.length}명`} · {validSelected.length}명 선택</span>
          <button type="button" className="text-button" disabled={create.isPending || !matches.length} onClick={toggleAll}>
            {allSelected ? '선택 해제' : query ? '검색 결과 전체 선택' : '전체 선택'}
          </button>
        </div>
      </div>
      <div className="member-choice-list collection-delivery-list collection-delivery-group-members" role="group" aria-label="그룹에 넣을 수신자">
        {matches.map(recipient => <label key={recipient.id}><input type="checkbox" checked={validSelected.includes(recipient.id)} disabled={create.isPending}
          onChange={() => { create.reset(); setSelected(current => current.includes(recipient.id) ? current.filter(value => value !== recipient.id) : [...current, recipient.id]) }} />
          <span><strong>{recipient.name}</strong></span>
        </label>)}
        {!matches.length && <p className="recipient-selection-empty">{query ? '검색 결과가 없습니다.' : '알림 관리에서 수신자를 먼저 등록해 주세요.'}</p>}
      </div>
      <p className="collection-delivery-note">만든 그룹은 알림 관리에 저장되고, 전달 대상으로 선택됩니다.</p>
      {create.error && <p className="field-error" role="alert">{create.error instanceof ApiError ? create.error.message : '그룹을 만들지 못했습니다. 다시 시도해 주세요.'}</p>}
    </form>
    <footer className="recipient-selection-actions">
      <button type="button" className="secondary-button" disabled={create.isPending} onClick={onCancel}>뒤로</button>
      <button type="submit" form={id} className="primary-button" disabled={create.isPending || !name.trim() || !validSelected.length}>
        {create.isPending ? '만드는 중…' : '그룹 만들고 선택'}
      </button>
    </footer>
  </>
}
