import { useState } from 'react'
import { usePreviewNotification } from '../../api/queries'
import { useAutoDeliveries, useRetryAutoDeliveries, useShareReport, type DeliveryTargets } from '../../api/notificationConnections'
import { DeliveryTargetPicker } from './DeliveryTargetPicker'
import { useDeliveryTargetAvailability } from './useDeliveryTargetAvailability'
import { MutationStatus } from '../settings/MutationStatus'
import { MessagePreviewSkeleton } from './NotificationSkeletons'
import './notifications-refinement.css'

const LABELS: Record<string, string> = { PENDING: '전달 대기', PROCESSING: '전달 중', SENT: '전달됨', FAILED: '전달 실패', SKIPPED: '전달 제외', UNKNOWN: '수신 확인 필요' }
export function ReportSharePanel({ reportId }: { reportId: number }) {
  const [targets, setTargets] = useState<DeliveryTargets>({ groupIds: [], recipientIds: [], channelIds: [] })
  const [requestKey, setRequestKey] = useState(() => `r${reportId}-${crypto.randomUUID()}`)
  const send = useShareReport(reportId)
  const preview = usePreviewNotification()
  const deliveries = useAutoDeliveries(reportId)
  const retry = useRetryAutoDeliveries(reportId)
  const availability = useDeliveryTargetAvailability(targets)
  function change(next: DeliveryTargets) { setTargets(next); setRequestKey(`r${reportId}-${crypto.randomUUID()}`); send.reset(); preview.reset() }
  const canSend = availability.valid && targets.channelIds.length > 0 && (targets.groupIds.length > 0 || targets.recipientIds.length > 0)
  return <section className="report-delivery-panel report-share-card" aria-label="보고서 공유">
    <div className="report-share-heading">
      <h3>다른 사람에게 공유</h3>
      <p className="muted">이 보고서의 핵심 요약을 추가로 전달할 수 있습니다.</p>
    </div>
    {!!deliveries.data?.length && <details className="auto-delivery-status"><summary>자동 전달 상태</summary>
      <ul>{deliveries.data.map((delivery) => <li key={delivery.id}>{delivery.recipientName} · {delivery.channelType === 'EMAIL' ? '이메일' : '텔레그램'} · {LABELS[delivery.status] ?? delivery.status}{delivery.message && <p className="muted">{delivery.message}</p>}</li>)}</ul>
      {deliveries.data.some((delivery) => delivery.status === 'FAILED') && <button className="secondary-button" type="button" disabled={retry.isPending} onClick={() => retry.mutate()}>실패한 대상만 다시 전달</button>}
      <MutationStatus error={retry.error} success={retry.data ? `${retry.data.queuedCount}명에게 다시 전달합니다.` : null} />
    </details>}
    <DeliveryTargetPicker value={targets} disabled={send.isPending} onChange={change} />
    <div className="report-share-actions">
      <button type="button" className="secondary-button" disabled={!availability.valid || !targets.channelIds.length || preview.isPending} onClick={() => preview.mutate({ reportId, channelId: targets.channelIds[0] })}>{preview.isPending ? '준비 중…' : '내용 미리보기'}</button>
      <button type="button" className="primary-button" disabled={!canSend || send.isPending || send.isSuccess} onClick={() => send.mutate({ ...targets, idempotencyKey: requestKey })}>{send.isPending ? '전달 중…' : send.isSuccess ? '전달 완료' : '요약 전달하기'}</button>
    </div>
    <MutationStatus error={send.error ?? preview.error ?? deliveries.error}
      success={send.data && !send.data.failedCount ? `${send.data.sentCount}명에게 전달했습니다.${send.data.skippedCount ? ` ${send.data.skippedCount}명은 연결 상태를 확인해 주세요.` : ''}` : null}
      warning={send.data?.failedCount ? `${send.data.sentCount}명 전달 · ${send.data.failedCount}명 실패. 발송 이력에서 확인해 주세요.` : null} />
    {preview.isPending && !preview.data && <MessagePreviewSkeleton />}
    {preview.data && <div className="report-message-preview"><strong>{preview.data.subject ?? '텔레그램 요약'}</strong>{preview.data.chunks.map((chunk) => <p key={chunk.seq}>{plainPreview(chunk.body)}</p>)}</div>}
  </section>
}
function plainPreview(html: string) {
  const document = new DOMParser().parseFromString(html.replace(/<\/(p|li|h[1-6]|ul)>/gi, '$&\n'), 'text/html')
  return document.body.textContent ?? ''
}
