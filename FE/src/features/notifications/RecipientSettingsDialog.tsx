import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react'
import { useIsMutating } from '@tanstack/react-query'
import { ApiError } from '../../api/client'
import { useRecipientReportSubscriptions, useSaveRecipientReportSubscription } from '../../api/notificationConnections'
import type { RecipientReportSubscription } from '../../api/recipientReportSubscriptions'
import type { NotificationRecipient, ReportScope } from '../../api/types'
import { Skeleton, SkeletonRegion } from '../../components/Skeleton'
import { TransientStatus } from '../../components/TransientStatus'
import { RecipientEmailForm } from './RecipientEmailForm'
import { TelegramConnectionCard } from './TelegramConnectionCard'

const SCOPES: ReadonlyArray<{ value: ReportScope; label: string }> = [
  { value: 'RUN', label: '수집별' }, { value: 'DAILY', label: '일일 통합' }, { value: 'WEEKLY', label: '주간 통합' },
]
const TABS = [
  { value: 'reports', label: '주제 보고서 알림' }, { value: 'channels', label: '이메일·텔레그램' },
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
      <p className="recipient-settings-intro">체크한 보고서를 받습니다. 해제하면 이 수신자의 해당 주제 알림만 중지됩니다.</p>
      {subscriptions.isPending && <SkeletonRegion label="주제 보고서 알림을 불러오는 중입니다." contentClassName="recipient-subscriptions-loading">
        <Skeleton height="9rem" /><Skeleton height="9rem" />
      </SkeletonRegion>}
      {subscriptions.isError && <div className="recipient-subscriptions-error" role="alert"><p>주제 보고서 알림을 불러오지 못했습니다.</p>
        <button type="button" className="secondary-button" disabled={subscriptions.isFetching} onClick={() => void subscriptions.refetch()}>다시 불러오기</button></div>}
      {subscriptions.data && <>
        {subscriptions.data.topics.length === 0 ? <div className="recipient-subscriptions-empty"><strong>등록된 주제 보고서 알림이 없습니다.</strong>
          <p>수집 설정의 보고서 알림에서 이 수신자나 소속 그룹을 선택하면 여기에 표시됩니다.</p></div>
          : <div className="recipient-subscriptions-list">{subscriptions.data.topics.map(topic => <RecipientSubscriptionRow key={`${recipient.id}-${topic.topicId}`}
            recipientId={recipient.id} topic={topic} unavailable={subscriptions.isError} />)}</div>}
        {subscriptions.data.topics.length > 0 && <p className="recipient-subscriptions-footnote">일일·주간 통합은 여러 주제를 담습니다. 다른 포함 주제에서 같은 보고서를 받도록 설정했다면 한 번 전달될 수 있습니다. 변경한 설정은 앞으로 보낼 알림에 적용됩니다.</p>}
      </>}
    </div>
    <div id={`${id}-channels-panel`} role="tabpanel" aria-labelledby={`${id}-channels-tab`} hidden={tab !== 'channels'} className="recipient-connections-panel">
      {tab === 'channels' && <><RecipientEmailForm recipient={recipient} emailChannelId={emailChannelId} />
        <TelegramConnectionCard recipientId={recipient.id} recipientName={recipient.name} /></>}
    </div>
    <footer className="recipient-selection-actions"><button type="button" className="secondary-button" disabled={busy} onClick={dismiss}>닫기</button></footer>
  </dialog>
}

export function RecipientSubscriptionRow({ recipientId, topic, unavailable = false }: {
  recipientId: number; topic: RecipientReportSubscription; unavailable?: boolean
}) {
  const save = useSaveRecipientReportSubscription(recipientId, topic.topicId)
  const [draft, setDraft] = useState<ReportScope[] | null>(null)
  const [saved, setSaved] = useState(false)
  const excluded = draft ?? topic.excludedScopes
  const changed = SCOPES.some(scope => excluded.includes(scope.value) !== topic.excludedScopes.includes(scope.value))
  const receiving = SCOPES.filter(scope => topic.configuredScopes.includes(scope.value) && !topic.excludedScopes.includes(scope.value))
  const status = !topic.enabled ? '주제 알림 중지' : topic.channelTypes.length === 0 ? '수신 가능한 전달 방식 없음'
    : receiving.length === 0 ? '받지 않음' : `${receiving.map(scope => scope.label).join(' · ')} 받는 중`
  const sources = [topic.direct ? '직접 등록' : '', ...topic.groupNames.map(name => `${name} 그룹`)].filter(Boolean)
  const locked = save.isPending || unavailable

  function change(scope: ReportScope, checked: boolean) {
    if (locked) return
    setDraft(checked ? excluded.filter(value => value !== scope) : [...new Set([...excluded, scope])])
    setSaved(false)
    save.reset()
  }
  return <article className="recipient-subscription-row" aria-label={`${topic.topicName} 보고서 알림`} aria-busy={save.isPending}>
    <header><h3>{topic.topicName}</h3><span className="recipient-subscription-state" data-receiving={topic.enabled && topic.channelTypes.length > 0 && receiving.length > 0}>{status}</span></header>
    <p className="recipient-subscription-sources">{sources.length ? sources.join(' · ') : '현재 주제의 수신 대상이 아닙니다.'}</p>
    <p className="recipient-subscription-channels">전달 방식: {topic.channelTypes.length
      ? topic.channelTypes.map(type => type === 'EMAIL' ? '이메일' : '텔레그램').join(' · ') : '연결된 전달 방식 없음'}</p>
    <fieldset disabled={locked} className="recipient-subscription-scopes"><legend>받을 보고서</legend>
      {SCOPES.map(scope => <label key={scope.value} data-configured={topic.configuredScopes.includes(scope.value)}>
        <input type="checkbox" checked={topic.configuredScopes.includes(scope.value) && !excluded.includes(scope.value)}
          disabled={!topic.configuredScopes.includes(scope.value)} onChange={event => change(scope.value, event.target.checked)} />
        <span>{scope.label}{!topic.configuredScopes.includes(scope.value) && <small>주제 설정 없음</small>}</span>
      </label>)}
    </fieldset>
    {!topic.enabled && <p className="recipient-subscription-note">주제 알림이 꺼져 있어 전달되지 않습니다. 개인 선택은 유지됩니다.</p>}
    <div className="recipient-subscription-actions">
      {excluded.length > 0 && <button type="button" className="text-button" disabled={locked}
        onClick={() => { setDraft([]); setSaved(false); save.reset() }}>개인 해제 초기화</button>}
      <span>{changed ? '저장하지 않은 변경 사항' : <TransientStatus as="small" message={saved ? '저장했습니다.' : null} />}</span>
      <button type="button" className="secondary-button" aria-label={`${topic.topicName} 알림 저장`} disabled={locked || !changed}
        onClick={() => save.mutate(excluded, { onSuccess: () => { setDraft(null); setSaved(true) } })}>{save.isPending ? '저장 중…' : '저장'}</button>
    </div>
    {save.error && <p className="field-error" role="alert">{save.error instanceof ApiError ? save.error.message : '알림 설정을 저장하지 못했습니다. 다시 시도해 주세요.'}</p>}
  </article>
}
