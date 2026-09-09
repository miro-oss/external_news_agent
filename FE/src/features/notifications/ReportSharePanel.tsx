import { useState } from 'react'
import { usePreviewNotification } from '../../api/queries'
import { useAutoDeliveries, useRetryAutoDeliveries, useShareReport, type DeliveryTargets } from '../../api/notificationConnections'
import { DeliveryTargetPicker } from './DeliveryTargetPicker'
import { useDeliveryTargetAvailability } from './useDeliveryTargetAvailability'
import { MutationStatus } from '../settings/MutationStatus'
import { MessagePreviewSkeleton } from './NotificationSkeletons'
import { failedDeliveryBatchId, reportShareFeedback } from './reportShareFeedback'
import { ReportShareDeliveryLogs } from './ReportShareDeliveryLogs'
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
  const feedback = reportShareFeedback(send.data)
  const failureBatchId = failedDeliveryBatchId(send.error)
  const resultBatchId = failureBatchId ?? (send.data && (send.data.failedCount || send.data.skippedCount) ? send.data.deliveryBatchId : null)
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
    {deliveries.isSuccess && deliveries.data.length === 0 && <p className="muted">이 보고서에 예약된 자동 전달이 없습니다. 다음 수집에서 자동으로 받으려면 수집 설정의 ‘보고서 자동 전달’에서 대상과 전달 방식을 선택해 주세요.</p>}
    <DeliveryTargetPicker value={targets} disabled={send.isPending} onChange={change} />
    <div className="report-share-actions">
      <button type="button" className="secondary-button" disabled={!availability.valid || !targets.channelIds.length || preview.isPending} onClick={() => preview.mutate({ reportId, channelId: targets.channelIds[0] })}>{preview.isPending ? '준비 중…' : '내용 미리보기'}</button>
      <button type="button" className="primary-button" disabled={!canSend || send.isPending || send.isSuccess || !!failureBatchId} onClick={() => send.mutate({ ...targets, idempotencyKey: requestKey })}>{send.isPending ? '전달 중…' : failureBatchId ? '전달 실패' : send.error ? '같은 요청 다시 확인' : feedback.label}</button>
    </div>
    <MutationStatus error={send.error ?? preview.error ?? deliveries.error}
      success={feedback.success} warning={feedback.warning} />
    {resultBatchId && <ReportShareDeliveryLogs key={resultBatchId} reportId={reportId} deliveryBatchId={resultBatchId} />}
    {preview.isPending && !preview.data && <MessagePreviewSkeleton />}
    {preview.data && <div className="report-message-preview"><strong>{preview.data.subject ?? '텔레그램 요약'}</strong>{preview.data.chunks.map((chunk) => <p key={chunk.seq}>{plainPreview(chunk.body)}</p>)}</div>}
  </section>
}
function plainPreview(html: string) {
  const document = new DOMParser().parseFromString(html.replace(/<\/(p|li|h[1-6]|ul)>/gi, '$&\n'), 'text/html')
  return document.body.textContent ?? ''
}
