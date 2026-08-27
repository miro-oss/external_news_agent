import { type FormEvent, useState } from 'react'
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
  useUpdateNotificationChannel,
} from '../../api/queries'
import type { DeliveryStatus, GroupPerspective, NotificationChannelType } from '../../api/types'
import { formatFullDate } from '../../lib/datetime'
import { MutationStatus } from '../settings/MutationStatus'

const PERSPECTIVES: Array<{ value: GroupPerspective; label: string }> = [
  { value: 'EXECUTIVE', label: '경영진' },
  { value: 'PURCHASING', label: '구매' },
  { value: 'TECHNOLOGY', label: '기술' },
  { value: 'SALES', label: '영업' },
]

export function NotificationsPage() {
  const channels = useNotificationChannels()
  const recipients = useNotificationRecipients()
  const groups = useNotificationGroups()
  const [logFilters, setLogFilters] = useState<DeliveryLogFilters>({ page: 0 })
  const logs = useDeliveryLogs(logFilters)

  function changeLogFilter(key: Exclude<keyof DeliveryLogFilters, 'page'>, value: string) {
    setLogFilters((current) => ({ ...current, [key]: value, page: 0 }))
  }

  const pending = channels.isPending || recipients.isPending || groups.isPending
  const error = channels.error ?? recipients.error ?? groups.error

  return (
    <main className="notifications-page">
      <header className="page-header">
        <div>
          <h1>알림 관리</h1>
          <p className="muted">보고서를 받을 사람과 채널을 준비하고, 실제 전달 결과까지 추적합니다.</p>
        </div>
        <div className="summary-count" aria-live="polite">
          <strong>{recipients.data?.totalElements ?? 0}</strong>
          <span>등록 수신자</span>
        </div>
      </header>

      {pending && <div className="state-panel" aria-busy="true">알림 설정을 불러오는 중입니다.</div>}
      {error && <div className="state-panel error" role="alert">{error.message}</div>}

      {channels.data && (
        <section className="notification-section">
          <div className="section-heading"><h2>전달 채널</h2><span>비밀값은 화면에 표시하지 않습니다.</span></div>
          <div className="channel-grid">
            {channels.data.map((channel) => <ChannelCard channel={channel} key={channel.id} />)}
          </div>
        </section>
      )}

      {channels.data && recipients.data && (
        <section className="notification-split">
          <RecipientPanel channels={channels.data} recipients={recipients.data.content} />
          <GroupPanel recipients={recipients.data.content} groups={groups.data?.content ?? []} />
        </section>
      )}

      <section className="notification-section">
        <div className="section-heading"><h2>발송 이력</h2><span>외부 메시지 식별자로 문제를 추적할 수 있습니다.</span></div>
        <div className="delivery-filter-bar">
          <label>보고서 ID
            <input type="number" min="1" value={logFilters.reportId ?? ''}
              onChange={(event) => changeLogFilter('reportId', event.target.value)} />
          </label>
          <label>실행 ID
            <input type="number" min="1" value={logFilters.runId ?? ''}
              onChange={(event) => changeLogFilter('runId', event.target.value)} />
          </label>
          <label>발송 배치 ID
            <input value={logFilters.deliveryBatchId ?? ''}
              onChange={(event) => changeLogFilter('deliveryBatchId', event.target.value)} />
          </label>
          <label>수신자 ID
            <input type="number" min="1" value={logFilters.recipientId ?? ''}
              onChange={(event) => changeLogFilter('recipientId', event.target.value)} />
          </label>
          <label>채널
            <select value={logFilters.channelType ?? ''}
              onChange={(event) => changeLogFilter('channelType', event.target.value as NotificationChannelType | '')}>
              <option value="">전체</option><option value="EMAIL">메일</option><option value="TELEGRAM">텔레그램</option>
            </select>
          </label>
          <label>상태
            <select value={logFilters.status ?? ''}
              onChange={(event) => changeLogFilter('status', event.target.value as DeliveryStatus | '')}>
              <option value="">전체</option><option value="SENT">성공</option><option value="FAILED">실패</option><option value="SKIPPED">건너뜀</option>
            </select>
          </label>
          <label>시작 시각
            <input type="datetime-local" value={logFilters.from ?? ''}
              onChange={(event) => changeLogFilter('from', event.target.value)} />
          </label>
          <label>종료 시각
            <input type="datetime-local" value={logFilters.to ?? ''}
              onChange={(event) => changeLogFilter('to', event.target.value)} />
          </label>
        </div>
        {logs.isPending && <div className="state-panel" aria-busy="true">발송 이력을 불러오는 중입니다.</div>}
        {logs.isError && <div className="state-panel error" role="alert">{logs.error.message}</div>}
        {logs.data && (
          <>
            <DeliveryLogTable logs={logs.data} />
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

function ChannelCard({ channel }: { channel: NonNullable<ReturnType<typeof useNotificationChannels>['data']>[number] }) {
  const update = useUpdateNotificationChannel()
  return (
    <article className="channel-card">
      <div className="channel-card-title">
        <span className={`channel-mark ${channel.channelType.toLowerCase()}`}>{channel.channelType === 'EMAIL' ? '✉' : '↗'}</span>
        <div><strong>{channel.name}</strong><span>{channel.channelType === 'EMAIL' ? 'run 단위 HTML 보고서' : '짧은 이벤트 속보'}</span></div>
      </div>
      <dl>
        <div><dt>연결 설정</dt><dd>{channel.tokenConfigured ? '준비됨' : '환경변수 필요'}</dd></div>
        <div><dt>메시지 길이</dt><dd>{channel.maxLength.toLocaleString()}자</dd></div>
      </dl>
      <button
        type="button"
        className={channel.active ? 'secondary-button' : 'primary-button'}
        disabled={update.isPending}
        onClick={() => update.mutate({ channelId: channel.id, body: { active: !channel.active } })}
      >{channel.active ? '채널 끄기' : '채널 켜기'}</button>
      <MutationStatus error={update.error} success={update.isSuccess ? '채널 상태를 바꿨습니다.' : null} />
    </article>
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
  const [telegram, setTelegram] = useState('')

  function submit(event: FormEvent) {
    event.preventDefault()
    const destinations = channels.flatMap((channel) => {
      const address = channel.channelType === 'EMAIL' ? email.trim() : telegram.trim()
      return address ? [{ channelId: channel.id, address, use: true }] : []
    })
    create.mutate({ name: name.trim(), email: email.trim() || undefined, destinations }, {
      onSuccess: () => { setName(''); setEmail(''); setTelegram('') },
    })
  }

  return (
    <section className="notification-card-stack">
      <div className="section-heading"><h2>수신자</h2><span>{recipients.length}명</span></div>
      <form className="notification-form" onSubmit={submit}>
        <label>이름<input required maxLength={100} value={name} onChange={(event) => setName(event.target.value)} /></label>
        <label>메일 주소<input type="email" value={email} onChange={(event) => setEmail(event.target.value)} placeholder="user@example.com" /></label>
        <label>텔레그램 chat_id<input value={telegram} onChange={(event) => setTelegram(event.target.value)} placeholder="123456789" /></label>
        <button className="primary-button" disabled={create.isPending || !name.trim()}>{create.isPending ? '등록 중…' : '수신자 등록'}</button>
        <MutationStatus error={create.error} success={create.isSuccess ? '수신자를 등록했습니다.' : null} />
      </form>
      <div className="compact-list">
        {recipients.length === 0 && <p className="muted">등록된 수신자가 없습니다.</p>}
        {recipients.map((recipient) => (
          <article key={recipient.id}>
            <div><strong>{recipient.name}</strong><span>{recipient.groupNames?.join(' · ') || '그룹 미지정'}</span></div>
            <div className="destination-badges">
              {recipient.destinations.map((destination) => (
                <span key={destination.channelId} data-ready={destination.onboarded}>
                  {destination.channelType === 'EMAIL' ? '메일' : destination.onboarded ? '텔레그램 준비됨' : '텔레그램 /start 필요'}
                </span>
              ))}
            </div>
            <button type="button" className="text-button danger" onClick={() => remove.mutate(recipient.id)}>삭제</button>
          </article>
        ))}
      </div>
      <MutationStatus error={remove.error} success={remove.isSuccess ? '수신자를 비활성화했습니다.' : null} />
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

  function submit(event: FormEvent) {
    event.preventDefault()
    create.mutate({ name: name.trim(), perspective, recipientIds: selected }, {
      onSuccess: () => { setName(''); setSelected([]) },
    })
  }

  return (
    <section className="notification-card-stack">
      <div className="section-heading"><h2>수신 그룹</h2><span>{groups.length}개</span></div>
      <form className="notification-form" onSubmit={submit}>
        <label>그룹명<input required maxLength={100} value={name} onChange={(event) => setName(event.target.value)} /></label>
        <label>관점<select value={perspective} onChange={(event) => setPerspective(event.target.value as GroupPerspective)}>
          {PERSPECTIVES.map((item) => <option value={item.value} key={item.value}>{item.label}</option>)}
        </select></label>
        <fieldset className="member-picker"><legend>멤버</legend>
          {recipients.map((recipient) => <label key={recipient.id}>
            <input type="checkbox" checked={selected.includes(recipient.id)} onChange={() => setSelected((current) => current.includes(recipient.id) ? current.filter((id) => id !== recipient.id) : [...current, recipient.id])} />
            {recipient.name}
          </label>)}
          {recipients.length === 0 && <span className="muted">먼저 수신자를 등록해 주세요.</span>}
        </fieldset>
        <button className="primary-button" disabled={create.isPending || !name.trim()}>{create.isPending ? '등록 중…' : '그룹 등록'}</button>
        <MutationStatus error={create.error} success={create.isSuccess ? '수신 그룹을 등록했습니다.' : null} />
      </form>
      <div className="compact-list group-list">
        {groups.length === 0 && <p className="muted">등록된 수신 그룹이 없습니다.</p>}
        {groups.map((group) => <article key={group.id}>
          <div><strong>{group.name}</strong><span>{perspectiveLabel(group.perspective)} · 활성 {group.activeMemberCount}/{group.memberCount}명</span></div>
          <button type="button" className="text-button danger" onClick={() => remove.mutate(group.id)}>삭제</button>
        </article>)}
      </div>
      <MutationStatus error={remove.error} success={remove.isSuccess ? '수신 그룹을 삭제했습니다.' : null} />
    </section>
  )
}

function DeliveryLogTable({ logs }: { logs: NonNullable<ReturnType<typeof useDeliveryLogs>['data']> }) {
  return (
    <div className="delivery-log-shell">
      <div className="delivery-summary">
        <span><strong>{logs.summary.sentCount}</strong> 성공</span>
        <span><strong>{logs.summary.failedCount}</strong> 실패</span>
        <span><strong>{logs.summary.skippedCount}</strong> 건너뜀</span>
      </div>
      {logs.content.length === 0 ? <div className="state-panel">조건에 맞는 발송 이력이 없습니다.</div> : (
        <div className="table-scroll"><table className="delivery-table"><thead><tr>
          <th>발송 시각</th><th>수신자</th><th>채널</th><th>상태</th><th>외부 식별자</th>
        </tr></thead><tbody>{logs.content.map((log) => <tr key={log.id}>
          <td>{formatFullDate(log.sentAt)}</td><td>{log.recipientName}</td>
          <td>{log.channelType === 'EMAIL' ? '메일' : '텔레그램'}</td>
          <td><span className={`delivery-status ${log.status.toLowerCase()}`}>{statusLabel(log.status)}</span></td>
          <td className="external-id">{log.externalMessageId ?? log.errorMessage ?? '-'}</td>
        </tr>)}</tbody></table></div>
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
