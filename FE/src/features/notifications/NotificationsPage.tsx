import { type FormEvent, type ReactNode, useMemo, useState } from 'react'
import {
  type DeliveryLogFilters,
  useCreateNotificationGroup,
  useCreateNotificationRecipient,
  useDeleteNotificationGroup,
  useDeleteNotificationRecipient,
  useDeliveryLogs,
  useNotificationChannels,
  useNotificationGroups,
  useNotificationRecipients,
  useReports,
} from '../../api/queries'
import type { DeliveryStatus, GroupPerspective, NotificationChannelType } from '../../api/types'
import { Segmented, type SegmentedOption } from '../../components/Segmented'
import { Skeleton, SkeletonRegion } from '../../components/Skeleton'
import { formatMediumDate } from '../../lib/datetime'
import { MutationStatus } from '../settings/MutationStatus'
import { TelegramConnectionCard } from './TelegramConnectionCard'
import { RecipientEmailForm } from './RecipientEmailForm'
import { GroupRecipientPicker } from './GroupRecipientPicker'
import { DeliveryLogSkeleton, NotificationPanelSkeleton } from './NotificationSkeletons'
import './notifications-refinement.css'

const PERSPECTIVES: Array<{ value: GroupPerspective; label: string }> = [
  { value: 'EXECUTIVE', label: '경영진' },
  { value: 'PURCHASING', label: '구매' },
  { value: 'TECHNOLOGY', label: '기술' },
  { value: 'SALES', label: '영업' },
]

/* 값이 빈 문자열이면 "전체"다. 서버 필터를 지우는 것과 같아서 별도 값을 두지 않는다. */
const CHANNEL_FILTERS: ReadonlyArray<SegmentedOption<NotificationChannelType | ''>> = [
  { value: '', label: '전체' },
  { value: 'EMAIL', label: '메일' },
  { value: 'TELEGRAM', label: '텔레그램' },
]

const STATUS_FILTERS: ReadonlyArray<SegmentedOption<DeliveryStatus | ''>> = [
  { value: '', label: '전체' },
  { value: 'SENT', label: '성공' },
  { value: 'FAILED', label: '실패' },
  { value: 'SKIPPED', label: '건너뜀' },
]

export function NotificationsPage() {
  const channels = useNotificationChannels()
  const recipients = useNotificationRecipients()
  const groups = useNotificationGroups()
  const reports = useReports()
  const [logFilters, setLogFilters] = useState<DeliveryLogFilters>({ page: 0 })
  const logs = useDeliveryLogs(logFilters)
  const reportTitles = useMemo(
    () => new Map((reports.data?.content ?? []).map((report) => [report.id, report.title])),
    [reports.data],
  )
  /**
   * 발송 로그는 보낸 시점의 이름과 주소만 남기고 그룹은 남기지 않는다. 표에서는 지금 이 사람이
   * 어느 그룹에 있는지를 붙여 준다 — 보낸 당시의 소속이 아니므로 열 이름도 "소속 그룹"이다.
   */
  const recipientGroups = useMemo(
    () => new Map((recipients.data?.content ?? []).map((recipient) => [recipient.id, recipient.groupNames ?? []])),
    [recipients.data],
  )

  function changeLogFilter(key: Exclude<keyof DeliveryLogFilters, 'page'>, value: string) {
    setLogFilters((current) => ({ ...current, [key]: value, page: 0 }))
  }

  const pending = channels.isPending || recipients.isPending || groups.isPending
  const error = channels.error ?? recipients.error ?? groups.error
  const activeRecipients = (recipients.data?.content ?? []).filter(recipient => recipient.active)
  const activeGroups = (groups.data?.content ?? []).filter(group => group.active)

  return (
    <main className="notifications-page">
      <header className="page-header">
        <div>
          <h1>알림 관리</h1>
          <p className="muted">이메일과 텔레그램으로 알림을 받을 사람과 그룹을 관리합니다.</p>
        </div>
        {recipients.isPending || groups.isPending ? <SkeletonRegion label="수신자와 그룹 수를 불러오는 중입니다." className="summary-count">
          <Skeleton width="6.5rem" height="1.4rem" />
        </SkeletonRegion> : recipients.data && groups.data ? <div className="summary-count" aria-live="polite">
          <strong>{activeRecipients.length}</strong>
          <span>명 · 그룹 {activeGroups.length}개</span>
        </div> : null}
      </header>

      {error && <div className="state-panel error" role="alert">{error.message}</div>}

      {(pending || (recipients.data && (channels.data || groups.data))) && (
        <section className="notification-split">
          {channels.data && recipients.data ? <RecipientPanel channels={channels.data} recipients={activeRecipients} />
            : (channels.isPending || recipients.isPending) && <NotificationPanelSkeleton kind="recipient" />}
          {recipients.data && groups.data ? <GroupPanel recipients={activeRecipients} groups={activeGroups} />
            : (recipients.isPending || groups.isPending) && <NotificationPanelSkeleton kind="group" />}
        </section>
      )}

      <section className="notification-section">
        <div className="section-heading"><h2>발송 이력</h2><span>언제 누구에게 어떤 보고서를 보냈는지 확인합니다.</span></div>
        <div className="delivery-filter-bar">
          <label>보고서
            {reports.isPending ? <SkeletonRegion label="보고서 목록을 불러오는 중입니다."><Skeleton className="skeleton-control" /></SkeletonRegion> : <select value={logFilters.reportId ?? ''}
              onChange={(event) => changeLogFilter('reportId', event.target.value)}>
              <option value="">전체 보고서</option>
              {(reports.data?.content ?? []).map((report) => (
                <option value={report.id} key={report.id}>{report.title}</option>
              ))}
            </select>}
          </label>
          <div className="delivery-filter-group">
            <span className="filter-label" id="delivery-channel-label">전달 방식</span>
            <Segmented
              labelledBy="delivery-channel-label"
              value={logFilters.channelType ?? ''}
              options={CHANNEL_FILTERS}
              onSelect={(next) => changeLogFilter('channelType', next)}
            />
          </div>
          <div className="delivery-filter-group">
            <span className="filter-label" id="delivery-status-label">상태</span>
            <Segmented
              labelledBy="delivery-status-label"
              value={logFilters.status ?? ''}
              options={STATUS_FILTERS}
              onSelect={(next) => changeLogFilter('status', next)}
            />
          </div>
          {(logFilters.reportId || logFilters.channelType || logFilters.status) && (
            <button type="button" className="text-button" onClick={() => setLogFilters({ page: 0 })}>필터 초기화</button>
          )}
        </div>
        {logs.isPending && <DeliveryLogSkeleton />}
        {logs.isError && <div className="state-panel error" role="alert">{logs.error.message}</div>}
        {logs.data && (
          <>
            <DeliveryLogTable logs={logs.data} reportTitles={reportTitles} recipientGroups={recipientGroups} />
            <div className="pagination" aria-label="발송 이력 페이지 이동">
              <button type="button" className="secondary-button"
                disabled={(logFilters.page ?? 0) === 0 || logs.isFetching}
                onClick={() => setLogFilters((current) => ({ ...current, page: (current.page ?? 0) - 1 }))}>
                이전
              </button>
              <span>{(logFilters.page ?? 0) + 1} / {Math.max(logs.data.totalPages, 1)}</span>
              <button type="button" className="secondary-button"
                disabled={!logs.data.hasNext || logs.isFetching}
                onClick={() => setLogFilters((current) => ({ ...current, page: (current.page ?? 0) + 1 }))}>
                다음
              </button>
            </div>
          </>
        )}
      </section>
    </main>
  )
}

function RecipientPanel({ channels, recipients }: {
  channels: NonNullable<ReturnType<typeof useNotificationChannels>['data']>
  recipients: NonNullable<ReturnType<typeof useNotificationRecipients>['data']>['content']
}) {
  const create = useCreateNotificationRecipient()
  const remove = useDeleteNotificationRecipient()
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [settingsRecipientId, setSettingsRecipientId] = useState<number | null>(null)

  function submit(event: FormEvent) {
    event.preventDefault()
    const destinations = channels.flatMap((channel) => {
      const address = channel.channelType === 'EMAIL' ? email.trim() : ''
      return address ? [{ channelId: channel.id, address, use: true }] : []
    })
    create.mutate({ name: name.trim(), email: email.trim() || undefined, destinations }, {
      onSuccess: () => { setName(''); setEmail('') },
    })
  }

  return (
    <section className="notification-card-stack">
      <div className="section-heading notification-panel-heading"><h2>수신자</h2><span>{recipients.length}명</span></div>
      <form className="notification-form recipient-create-form" onSubmit={submit}>
        <div className="notification-form-heading"><strong>새 수신자 등록</strong><span>텔레그램은 등록 후 수신자의 설정에서 연결할 수 있습니다.</span></div>
        <div className="notification-form-grid">
          <label>이름<input required maxLength={100} value={name} onChange={(event) => { create.reset(); setName(event.target.value) }} placeholder="예: 홍길동" /></label>
          <label>메일 주소<input type="email" value={email} onChange={(event) => { create.reset(); setEmail(event.target.value) }} placeholder="user@example.com" /></label>
        </div>
        <button className="primary-button" disabled={create.isPending || !name.trim()}>{create.isPending ? '등록 중…' : '수신자 등록'}</button>
        <MutationStatus error={create.error} success={null} />
      </form>
      <SavedNotificationList title="등록된 수신자">
        <div className="compact-list">
          {recipients.length === 0 && <p className="notification-list-empty">등록된 수신자가 없습니다.</p>}
          {recipients.map((recipient) => (
            <article key={recipient.id} className="recipient-row">
              <div>
                <div className="recipient-name-row">
                  <strong>{recipient.name}</strong>
                  {(['EMAIL', 'TELEGRAM'] as const).filter((type) => recipient.active && recipient.destinations.some((destination) =>
                    destination.channelType === type && destination.use && destination.onboarded && destination.address?.trim()
                    && channels.some((channel) => channel.id === destination.channelId && channel.active),
                  )).map((type) => <span key={type} className={`recipient-channel-tag ${type.toLowerCase()}`}>{type === 'EMAIL' ? '이메일' : '텔레그램'}</span>)}
                </div>
                <span>{recipient.groupNames?.join(' · ') || '그룹 미지정'}</span>
              </div>
              <div className="recipient-actions">
                <button type="button" className="text-button" aria-label={`${recipient.name} 수신 설정`}
                  aria-expanded={settingsRecipientId === recipient.id} aria-controls={`recipient-settings-${recipient.id}`}
                  onClick={() => setSettingsRecipientId((current) => current === recipient.id ? null : recipient.id)}>설정</button>
                <button type="button" className="text-button danger" aria-label={`${recipient.name} 삭제`} disabled={remove.isPending} onClick={() => remove.mutate(recipient.id)}>삭제</button>
              </div>
              {settingsRecipientId === recipient.id && (
                <div className="recipient-settings" id={`recipient-settings-${recipient.id}`}>
                  <RecipientEmailForm recipient={recipient} emailChannelId={
                    (channels.find((channel) => channel.channelType === 'EMAIL' && channel.active)
                      ?? channels.find((channel) => channel.channelType === 'EMAIL'))?.id
                  } />
                  <TelegramConnectionCard recipientId={recipient.id} recipientName={recipient.name} />
                </div>
              )}
            </article>
          ))}
        </div>
      </SavedNotificationList>
      <MutationStatus error={remove.error} success={null} />
    </section>
  )
}

function GroupPanel({ recipients, groups }: {
  recipients: NonNullable<ReturnType<typeof useNotificationRecipients>['data']>['content']
  groups: NonNullable<ReturnType<typeof useNotificationGroups>['data']>['content']
}) {
  const create = useCreateNotificationGroup()
  const remove = useDeleteNotificationGroup()
  const [name, setName] = useState('')
  const [perspective, setPerspective] = useState<GroupPerspective>('EXECUTIVE')
  const [selected, setSelected] = useState<number[]>([])
  const selectedRecipients = selected.filter(id => recipients.some(recipient => recipient.id === id))

  function submit(event: FormEvent) {
    event.preventDefault()
    create.mutate({ name: name.trim(), perspective, recipientIds: selectedRecipients }, {
      onSuccess: () => { setName(''); setSelected([]) },
    })
  }

  return (
    <section className="notification-card-stack">
      <div className="section-heading notification-panel-heading"><h2>수신 그룹</h2><span>{groups.length}개</span></div>
      <form className="notification-form" onSubmit={submit}>
        <div className="notification-form-heading"><strong>새 그룹 등록</strong><span>같은 보고서를 받을 사람을 묶습니다.</span></div>
        <div className="notification-form-grid">
          <label>그룹명<input required maxLength={100} value={name} onChange={(event) => { create.reset(); setName(event.target.value) }} placeholder="예: 경영진 브리핑" /></label>
          <label>보고서 관점<select value={perspective} onChange={(event) => { create.reset(); setPerspective(event.target.value as GroupPerspective) }}>
            {PERSPECTIVES.map((item) => <option value={item.value} key={item.value}>{item.label}</option>)}
          </select></label>
        </div>
        <GroupRecipientPicker recipients={recipients} selected={selectedRecipients} disabled={create.isPending}
          onChange={ids => { create.reset(); setSelected(ids) }} />
        <button className="primary-button" disabled={create.isPending || !name.trim() || selectedRecipients.length === 0}>{create.isPending ? '등록 중…' : '그룹 등록'}</button>
        <MutationStatus error={create.error} success={null} />
      </form>
      <SavedNotificationList title="등록된 수신 그룹">
        <div className="compact-list group-list">
          {groups.length === 0 && <p className="notification-list-empty">등록된 수신 그룹이 없습니다.</p>}
          {groups.map((group) => <article key={group.id}>
            <div><strong>{group.name}</strong><span>{perspectiveLabel(group.perspective)} · {group.memberCount}명</span></div>
            <button type="button" className="text-button danger" aria-label={`${group.name} 삭제`} disabled={remove.isPending} onClick={() => remove.mutate(group.id)}>삭제</button>
          </article>)}
        </div>
      </SavedNotificationList>
      <MutationStatus error={remove.error} success={null} />
    </section>
  )
}

function SavedNotificationList({ title, children }: { title: string; children: ReactNode }) {
  return <details className="notification-saved-list">
    <summary>
      <span>{title}</span>
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="m6 9 6 6 6-6" /></svg>
    </summary>
    {children}
  </details>
}

function DeliveryLogTable({
  logs,
  reportTitles,
  recipientGroups,
}: {
  logs: NonNullable<ReturnType<typeof useDeliveryLogs>['data']>
  reportTitles: Map<number, string>
  recipientGroups: Map<number, string[]>
}) {
  return (
    <div className="delivery-log-shell">
      <div className="delivery-summary">
        <span className="sent"><strong>{logs.summary.sentCount}</strong> 성공</span>
        <span className="failed"><strong>{logs.summary.failedCount}</strong> 실패</span>
        <span className="skipped"><strong>{logs.summary.skippedCount}</strong> 건너뜀</span>
      </div>
      {logs.content.length === 0 ? <p className="empty-block">조건에 맞는 발송 이력이 없습니다.</p> : (
        <div className="table-scroll"><table className="delivery-table"><thead><tr>
          <th>보낸 시각</th><th>보고서</th><th>수신자</th><th>소속 그룹</th><th>전달 방식</th><th>결과</th>
        </tr></thead><tbody>{logs.content.map((log) => {
          const groupNames = recipientGroups.get(log.recipientId) ?? []
          return <tr key={log.id}>
            {/*
              여기만 요일까지 붙는 긴 형식이었다. 한 줄에 여섯 칸이 들어가는 표에서 "목요일"은
              자리를 가장 많이 먹으면서 정작 이력을 훑는 데는 쓰이지 않는다.
            */}
            <td className="delivery-time">{formatMediumDate(log.sentAt)}</td>
            <td className="delivery-report" title={reportTitles.get(log.reportId)}>
              {reportTitles.get(log.reportId) ?? `보고서 #${log.reportId}`}
            </td>
            <td className="delivery-recipient">
              <strong>{log.recipientName}</strong>
              {log.channelType === 'EMAIL' && <span title={log.address}>{log.address}</span>}
            </td>
            <td className="delivery-group" title={groupNames.join(' · ') || undefined}>
              {groupNames.length > 0 ? groupNames.join(' · ') : <span className="muted-cell">그룹 미지정</span>}
            </td>
            <td>{log.channelType === 'EMAIL' ? '메일' : '텔레그램'}</td>
            <td><div className="delivery-result"><span className={`delivery-status ${log.status.toLowerCase()}`}>{statusLabel(log.status)}</span>
              {log.errorMessage && <span>{log.errorMessage}</span>}
            </div></td>
          </tr>
        })}</tbody></table></div>
      )}
    </div>
  )
}

function perspectiveLabel(value: GroupPerspective | null) {
  return PERSPECTIVES.find((item) => item.value === value)?.label ?? '관점 없음'
}

function statusLabel(value: DeliveryStatus) {
  return { SENT: '성공', FAILED: '실패', SKIPPED: '건너뜀' }[value]
}
