import { useEffect, useId, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import type { CollectionRunDelivery, DeliveryTargets } from '../../api/notificationConnections'
import { Segmented } from '../../components/Segmented'
import { Skeleton, SkeletonRegion } from '../../components/Skeleton'
import { useDeliveryTargetAvailability } from '../notifications/useDeliveryTargetAvailability'
import { useCreateNotificationGroup } from '../../api/queries'
import { CollectionDeliveryGroupForm } from './CollectionDeliveryGroupForm'
import '../notifications/notifications-refinement.css'
import './collection-delivery.css'

const EMPTY_DELIVERY: CollectionRunDelivery = {
  enabled: true, mode: 'ONCE', run: true, daily: false,
  groupIds: [], recipientIds: [], channelIds: [],
}
const MODE_OPTIONS = [
  { value: 'ONCE', label: '이번 수집만' },
  { value: 'TOPIC', label: '선택한 주제에 계속 적용' },
] as const
type TargetTab = 'groupIds' | 'recipientIds'
const TARGET_OPTIONS = [
  { value: 'groupIds', label: '수신 그룹' },
  { value: 'recipientIds', label: '개별 수신자' },
] as const
const SELECTION_LIMIT = 100

function deliverySummary(value?: CollectionRunDelivery) {
  if (!value) return '주제에 저장된 설정 사용'
  const scope = value.mode === 'ONCE' ? '이번 수집만' : '계속 적용'
  if (!value.enabled) return `${scope} · 보내지 않음`
  const targets = [value.groupIds.length ? `그룹 ${value.groupIds.length}개` : '', value.recipientIds.length ? `${value.recipientIds.length}명` : ''].filter(Boolean).join(', ')
  return `${scope} · ${targets}`
}

export function CollectionDeliveryPicker({ value, onChange, disabled }: {
  value?: CollectionRunDelivery
  onChange: (value: CollectionRunDelivery | undefined) => void
  disabled: boolean
}) {
  const [open, setOpen] = useState(false)
  const id = useId()
  return <>
    <button type="button" className="collection-delivery-trigger" disabled={disabled}
      aria-haspopup="dialog" aria-expanded={open} aria-controls={open ? id : undefined}
      onClick={() => setOpen(true)}>
      <span><strong>보고서 자동 전달</strong><small>{deliverySummary(value)}</small></span>
      <span className="collection-delivery-edit">설정
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="m9 5 7 7-7 7" /></svg>
      </span>
    </button>
    {open && createPortal(<DeliveryDialog id={id} value={value} onDismiss={() => setOpen(false)}
      onApply={next => { onChange(next); setOpen(false) }} />, document.body)}
  </>
}

function DeliveryDialog({ id, value, onDismiss, onApply }: {
  id: string
  value?: CollectionRunDelivery
  onDismiss: () => void
  onApply: (value: CollectionRunDelivery | undefined) => void
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  const [draft, setDraft] = useState(value ?? EMPTY_DELIVERY)
  const [tab, setTab] = useState<TargetTab>('groupIds')
  const [search, setSearch] = useState('')
  const [creatingGroup, setCreatingGroup] = useState(false)
  const createGroupButton = useRef<HTMLButtonElement>(null)
  const wasCreatingGroup = useRef(false)
  const createGroup = useCreateNotificationGroup()
  const availability = useDeliveryTargetAvailability(draft)
  const query = search.trim().toLocaleLowerCase()
  const groups = availability.groups.data?.content.filter(group => group.active) ?? []
  const recipients = availability.recipients.data?.content.filter(recipient => recipient.active) ?? []
  const channels = availability.channels.data?.filter(channel => channel.active) ?? []
  const matches = tab === 'groupIds'
    ? groups.filter(group => group.name.toLocaleLowerCase().includes(query))
    : recipients.filter(recipient => `${recipient.name} ${recipient.email ?? ''}`.toLocaleLowerCase().includes(query))
  const allSelected = matches.length > 0 && matches.every(target => draft[tab].includes(target.id))
  const hasTargets = draft.groupIds.length + draft.recipientIds.length > 0
  const oversizedSelections = [
    { count: draft.groupIds.length, label: '수신 그룹은 최대 100개' },
    { count: draft.recipientIds.length, label: '수신자는 최대 100명' },
    { count: draft.channelIds.length, label: '전달 방식은 최대 100개' },
  ].filter(selection => selection.count > SELECTION_LIMIT)
  const selectionLimitMessage = oversizedSelections.length
    ? `${oversizedSelections.map(selection => selection.label).join(', ')}까지 선택할 수 있어요.` : null
  const canApply = !selectionLimitMessage && (!draft.enabled || (availability.valid && hasTargets && draft.channelIds.length > 0 && (draft.run || draft.daily)))

  function toggle(key: keyof DeliveryTargets, targetId: number) {
    setDraft(current => ({ ...current, [key]: current[key].includes(targetId)
      ? current[key].filter(selected => selected !== targetId) : [...current[key], targetId] }))
  }

  function toggleAll() {
    const ids = new Set(matches.map(target => target.id))
    setDraft(current => ({ ...current, [tab]: allSelected
      ? current[tab].filter(targetId => !ids.has(targetId)) : [...new Set([...current[tab], ...ids])] }))
  }

  function dismiss() {
    if (createGroup.isPending) return
    if (creatingGroup) { setCreatingGroup(false); createGroup.reset() }
    else onDismiss()
  }

  useEffect(() => {
    if (wasCreatingGroup.current && !creatingGroup) createGroupButton.current?.focus()
    wasCreatingGroup.current = creatingGroup
  }, [creatingGroup])

  useEffect(() => {
    const element = dialog.current
    const previousFocus = document.activeElement
    element?.showModal()
    document.body.classList.add('modal-open')
    return () => {
      element?.close()
      document.body.classList.remove('modal-open')
      if (previousFocus instanceof HTMLElement && previousFocus.isConnected) previousFocus.focus()
    }
  }, [])

  return <dialog ref={dialog} id={id} className="recipient-selection-dialog collection-delivery-dialog"
    data-enabled={draft.enabled}
    aria-labelledby={`${id}-title`} onCancel={event => { event.preventDefault(); dismiss() }}
    onClick={event => {
      if (event.target !== event.currentTarget) return
      const bounds = event.currentTarget.getBoundingClientRect()
      if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) dismiss()
    }}>
    <header className="recipient-selection-heading">
      <div><h2 id={`${id}-title`}>{creatingGroup ? '새 수신 그룹' : '보고서 자동 전달'}</h2><p>{creatingGroup ? '같은 보고서를 받을 사람들을 묶어 주세요.' : '보고서가 완성되면 핵심 요약을 보내드려요.'}</p></div>
      <button type="button" className="text-button recipient-selection-close" disabled={createGroup.isPending}
        aria-label={creatingGroup ? '그룹 만들기 닫기' : '자동 전달 설정 닫기'} onClick={dismiss}>×</button>
    </header>
    {creatingGroup ? <CollectionDeliveryGroupForm recipients={recipients} create={createGroup} onCancel={dismiss}
      onCreated={group => {
        setDraft(current => ({ ...current, groupIds: [...new Set([...current.groupIds, group.id])] }))
        setTab('groupIds'); setSearch(''); setCreatingGroup(false)
      }} /> : <>
    <div className="collection-delivery-body">
      <div className="collection-delivery-enabled">
        <span id={`${id}-enabled`}>완성된 보고서 보내기</span>
        <button type="button" className="collection-delivery-switch" role="switch" aria-checked={draft.enabled}
          aria-labelledby={`${id}-enabled`} onClick={() => setDraft(current => ({ ...current, enabled: !current.enabled }))}><span /></button>
      </div>
      <div className="collection-delivery-scope">
        <Segmented label="전달 설정 적용 범위" value={draft.mode} options={MODE_OPTIONS}
          onSelect={mode => setDraft(current => ({ ...current, mode }))} />
        <p>{draft.mode === 'ONCE'
          ? '이번 수집에만 적용해요. 주제에 저장된 설정은 유지됩니다.'
          : '수집을 시작하면 선택한 주제의 정기 수집에도 적용해요.'}</p>
      </div>
      {draft.enabled && <>
        <div className="collection-delivery-options-row">
        <fieldset className="collection-delivery-options"><legend>보낼 보고서</legend>
          <div className="collection-delivery-checks">
            <label><input type="checkbox" checked={draft.run} onChange={event => setDraft(current => ({ ...current, run: event.target.checked }))} />수집별 보고서</label>
            <label><input type="checkbox" checked={draft.daily} onChange={event => setDraft(current => ({ ...current, daily: event.target.checked }))} />일일 통합 보고서</label>
          </div>
        </fieldset>
          <fieldset className="collection-delivery-options"><legend>전달 방식</legend>
            <div className="collection-delivery-checks">
              {availability.pending && <Skeleton width="7rem" height="1.25rem" />}
              {channels.map(channel => <label key={channel.id}><input type="checkbox" checked={draft.channelIds.includes(channel.id)} onChange={() => toggle('channelIds', channel.id)} />{channel.channelType === 'EMAIL' ? '이메일' : '텔레그램'}</label>)}
              {!availability.pending && !availability.error && !channels.length && <p className="collection-delivery-note">알림 관리에서 전달 채널을 켜 주세요.</p>}
            </div>
          </fieldset>
        </div>
        {draft.daily && <p className="collection-delivery-note">일일 통합에는 같은 날 수집한 다른 주제도 함께 담깁니다.</p>}
        {availability.pending ? <SkeletonRegion label="전달 대상을 불러오는 중" contentClassName="collection-delivery-loading">
          <Skeleton width="7rem" /><Skeleton height="2.75rem" /><Skeleton height="12rem" />
        </SkeletonRegion> : availability.error ? <p className="field-error" role="alert">전달 대상을 불러오지 못했습니다. 잠시 후 다시 열어 주세요.</p> : <>
          <section className="collection-delivery-targets" aria-label="전달 대상 선택">
            <div className="collection-delivery-target-tabs">
              <Segmented label="전달 대상 유형" value={tab} options={TARGET_OPTIONS} onSelect={next => { setTab(next); setSearch('') }} />
              <button ref={createGroupButton} type="button" className="text-button collection-delivery-create-group" onClick={() => { createGroup.reset(); setCreatingGroup(true) }}>+ 새 그룹</button>
            </div>
            <input type="search" aria-label="전달 대상 검색" placeholder={tab === 'groupIds' ? '그룹 이름으로 검색' : '이름 또는 이메일로 검색'}
              value={search} onChange={event => setSearch(event.target.value)} />
            <div className="recipient-selection-meta">
              <span>{query ? '검색 결과' : '전체'} {matches.length}{tab === 'groupIds' ? '개' : '명'} · {draft[tab].length}{tab === 'groupIds' ? '개' : '명'} 선택</span>
              <button type="button" className="text-button" disabled={!matches.length} onClick={toggleAll}>{allSelected ? '선택 해제' : query ? '검색 결과 전체 선택' : '전체 선택'}</button>
            </div>
            <div className="member-choice-list collection-delivery-list" role="group" aria-label={tab === 'groupIds' ? '선택 가능한 수신 그룹' : '선택 가능한 수신자'}>
              {matches.map(target => <label key={target.id}><input type="checkbox" checked={draft[tab].includes(target.id)} onChange={() => toggle(tab, target.id)} />
                <span><strong>{target.name}</strong></span>{'activeMemberCount' in target && <small>{target.activeMemberCount}명</small>}
              </label>)}
              {!matches.length && <p className="recipient-selection-empty">{query ? '검색 결과가 없습니다.' : tab === 'groupIds' ? '등록된 수신 그룹이 없습니다.' : '등록된 수신자가 없습니다.'}</p>}
            </div>
          </section>
          {availability.unavailable.length > 0 && <div className="collection-delivery-unavailable" role="status">
            <p>사용할 수 없는 대상을 선택에서 빼 주세요.</p>
            {availability.unavailable.map(target => <button key={`${target.key}-${target.id}`} type="button" className="text-button" onClick={() => toggle(target.key, target.id)}>{target.label} 해제</button>)}
          </div>}
        </>}
      </>}
    </div>
    <footer className="recipient-selection-actions collection-delivery-actions">
      {selectionLimitMessage && <div className="collection-delivery-limit" role="alert">
        <p id={`${id}-selection-limit`}>{selectionLimitMessage}</p>
        {!draft.enabled && <button type="button" className="text-button"
          onClick={() => setDraft(current => ({ ...current, groupIds: [], recipientIds: [], channelIds: [] }))}>선택 비우기</button>}
      </div>}
      <button type="button" className="text-button" onClick={() => onApply(undefined)}>기존 설정 사용</button>
      <button type="button" className="secondary-button" onClick={onDismiss}>취소</button>
      <button type="button" className="primary-button" disabled={!canApply}
        aria-describedby={selectionLimitMessage ? `${id}-selection-limit` : undefined} onClick={() => onApply(draft)}>선택 완료</button>
    </footer>
    </>}
  </dialog>
}
