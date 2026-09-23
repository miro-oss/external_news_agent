import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react'
import { useIsMutating } from '@tanstack/react-query'
import { ApiError } from '../../api/client'
import { useRecipientReportSubscriptions, useSaveRecipientSettings } from '../../api/notificationConnections'
import { topicReportSelected, type RecipientReportSubscriptions, type RecipientSettingsUpdate } from '../../api/recipientReportSubscriptions'
import type { NotificationRecipient } from '../../api/types'
import { Skeleton, SkeletonRegion } from '../../components/Skeleton'
import { TransientStatus } from '../../components/TransientStatus'
import { RecipientEmailForm } from './RecipientEmailForm'
import { TelegramConnectionCard } from './TelegramConnectionCard'

const TABS = [
  { value: 'reports', label: '보고서 알림' }, { value: 'channels', label: '이메일·텔레그램' },
] as const
type SettingsTab = typeof TABS[number]['value']

export function RecipientSettingsDialog({ recipient, emailChannelId, onDismiss }: {
  recipient: NotificationRecipient; emailChannelId?: number; onDismiss: () => void
}) {
  const id = useId()
  const dialog = useRef<HTMLDialogElement>(null)
  const tabButtons = useRef<Array<HTMLButtonElement | null>>([])
  const [tab, setTab] = useState<SettingsTab>('reports')
  const subscriptions = useRecipientReportSubscriptions(recipient.id)
  // Keep every in-dialog editor mounted while saving, including destination changes.
  const busy = useIsMutating() > 0

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

  function dismiss() { if (!busy) onDismiss() }
  function selectWithKeyboard(event: KeyboardEvent<HTMLButtonElement>, index: number) {
    if (busy) return
    const next = event.key === 'Home' ? 0 : event.key === 'End' ? TABS.length - 1
      : event.key === 'ArrowRight' ? (index + 1) % TABS.length
      : event.key === 'ArrowLeft' ? (index + TABS.length - 1) % TABS.length : null
    if (next === null) return
    event.preventDefault()
    setTab(TABS[next].value)
    tabButtons.current[next]?.focus()
  }

  return <dialog ref={dialog} className="recipient-selection-dialog recipient-settings-dialog" id={`recipient-settings-${recipient.id}`}
    aria-labelledby={`${id}-title`} aria-busy={busy}
    onCancel={event => { event.preventDefault(); dismiss() }}
    onClick={event => {
      if (event.target !== event.currentTarget) return
      const bounds = event.currentTarget.getBoundingClientRect()
      if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) dismiss()
    }}>
    <header className="recipient-selection-heading">
      <div><h2 id={`${id}-title`}>{recipient.name} 수신 설정</h2><p>받을 보고서와 전달 방식을 확인하고 변경하세요.</p></div>
      <button type="button" className="text-button recipient-selection-close" disabled={busy} aria-label="수신 설정 닫기" onClick={dismiss}>×</button>
    </header>
    <div className="recipient-settings-tabs" role="tablist" aria-label="수신 설정">
      {TABS.map((item, index) => <button key={item.value} type="button" role="tab" id={`${id}-${item.value}-tab`}
        aria-controls={`${id}-${item.value}-panel`} aria-selected={tab === item.value} tabIndex={tab === item.value ? 0 : -1}
        disabled={busy} ref={element => { tabButtons.current[index] = element }}
        onKeyDown={event => selectWithKeyboard(event, index)} onClick={() => setTab(item.value)}>{item.label}</button>)}
    </div>
    <div id={`${id}-reports-panel`} role="tabpanel" aria-labelledby={`${id}-reports-tab`} hidden={tab !== 'reports'} className="recipient-subscriptions-panel">
      {subscriptions.isPending && <SkeletonRegion label="주제 보고서 알림을 불러오는 중입니다." contentClassName="recipient-subscriptions-loading">
        <Skeleton height="9rem" /><Skeleton height="9rem" />
      </SkeletonRegion>}
      {subscriptions.isError && <div className="recipient-subscriptions-error" role="alert"><p>주제 보고서 알림을 불러오지 못했습니다.</p>
        <button type="button" className="secondary-button" disabled={subscriptions.isFetching} onClick={() => void subscriptions.refetch()}>다시 불러오기</button></div>}
      {subscriptions.data && <RecipientReportSettings settings={subscriptions.data} unavailable={subscriptions.isError} />}
    </div>
    <div id={`${id}-channels-panel`} role="tabpanel" aria-labelledby={`${id}-channels-tab`} hidden={tab !== 'channels'} className="recipient-connections-panel">
      {tab === 'channels' && <><RecipientEmailForm recipient={recipient} emailChannelId={emailChannelId} />
        <TelegramConnectionCard recipientId={recipient.id} recipientName={recipient.name} /></>}
    </div>
    <footer className="recipient-selection-actions"><button type="button" className="secondary-button" disabled={busy} onClick={dismiss}>닫기</button></footer>
  </dialog>
}

export function RecipientReportSettings({ settings, unavailable = false }: {
  settings: RecipientReportSubscriptions; unavailable?: boolean
}) {
  const save = useSaveRecipientSettings(settings.recipientId)
  const [draft, setDraft] = useState<RecipientSettingsUpdate>({})
  const [saved, setSaved] = useState(false)
  const aggregates = settings.aggregates
  const changes: RecipientSettingsUpdate = {
    ...(draft.daily !== undefined && draft.daily !== aggregates?.daily ? { daily: draft.daily } : {}),
    ...(draft.weekly !== undefined && draft.weekly !== aggregates?.weekly ? { weekly: draft.weekly } : {}),
  }
  const topics = (draft.topics ?? []).filter(choice => {
    const topic = settings.topics.find(row => row.topicId === choice.topicId)
    return topic && choice.subscribed !== topicReportSelected(topic)
  })
  if (topics.length > 0) changes.topics = topics
  const changed = Object.keys(changes).length > 0
  const locked = save.isPending || unavailable
  function change(next: RecipientSettingsUpdate) {
    if (locked) return
    setDraft(current => ({ ...current, ...next }))
    setSaved(false)
    save.reset()
  }
  if (!aggregates) return <p role="alert" className="field-error">새 수신 설정을 불러오려면 서버 업데이트가 필요합니다. 잠시 후 다시 열어 주세요.</p>
  return <div className="recipient-simple-settings" aria-busy={save.isPending}>
    <fieldset disabled={locked} className="recipient-aggregate-choices"><legend>통합 보고서</legend>
      <label><input type="checkbox" checked={draft.daily ?? aggregates.daily} onChange={event => change({ daily: event.target.checked })} />일일 통합 받기</label>
      <label><input type="checkbox" checked={draft.weekly ?? aggregates.weekly} onChange={event => change({ weekly: event.target.checked })} />주간 통합 받기</label>
    </fieldset>
    <p className="recipient-settings-intro">통합 보고서는 아래 주제 선택과 별도로 받습니다.</p>
    {aggregates.channelTypes.length === 0 && <p className="recipient-subscription-note">이메일·텔레그램 탭에서 전달 방식을 연결해 주세요. 선택한 설정은 유지됩니다.</p>}
    <fieldset disabled={locked} className="recipient-simple-topics"><legend>주제 보고서</legend>
      <p className="recipient-settings-intro">받고 싶은 주제만 체크해 주세요.</p>
      {settings.topics.length === 0 ? <p className="recipient-subscriptions-empty">등록된 주제가 없습니다.</p>
        : <ul>{settings.topics.map(topic => {
          const selected = topicReportSelected(topic)
          const checked = draft.topics?.find(choice => choice.topicId === topic.topicId)?.subscribed ?? selected
          const status = !selected ? '받지 않음' : !topic.enabled ? '주제 알림 중지'
            : topic.channelTypes.length === 0 ? '연결 필요' : '받는 중'
          return <li key={topic.topicId}><label><input type="checkbox" checked={checked}
            onChange={event => change({ topics: [...(draft.topics ?? []).filter(choice => choice.topicId !== topic.topicId),
              { topicId: topic.topicId, subscribed: event.target.checked }] })} /><span>{topic.topicName}</span></label>
            <small>{status}</small></li>
        })}</ul>}
    </fieldset>
    <div className="recipient-settings-save"><span>{changed ? '저장하지 않은 변경 사항' : <TransientStatus as="small" message={saved ? '저장했습니다.' : null} />}</span>
      <button type="button" className="primary-button" disabled={locked || !changed}
        onClick={() => save.mutate(changes, { onSuccess: () => { setDraft({}); setSaved(true) } })}>{save.isPending ? '저장 중…' : '저장'}</button></div>
    {save.error && <p className="field-error" role="alert">{save.error instanceof ApiError ? save.error.message : '알림 설정을 저장하지 못했습니다. 다시 시도해 주세요.'}</p>}
  </div>
}
