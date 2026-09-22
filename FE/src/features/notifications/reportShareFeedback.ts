import { ApiError } from '../../api/client.ts'
import type { NotificationSendBatch } from '../../api/types'

export function failedDeliveryBatchId(error: unknown): string | null {
  if (!(error instanceof ApiError) || error.code !== 'DELIVERY502') return null
  const details = error.details
  if (!details || typeof details !== 'object' || !('deliveryBatchId' in details)) return null
  return typeof details.deliveryBatchId === 'string' && details.deliveryBatchId.trim()
    ? details.deliveryBatchId : null
}

export function canRetryFailedReportShare(batch: NotificationSendBatch | undefined, error: unknown): boolean {
  if (error) {
    if (!failedDeliveryBatchId(error) || !(error instanceof ApiError)) return false
    return hasAllFailedCounts(error.details)
  }
  return typeof batch?.deliveryBatchId === 'string' && !!batch.deliveryBatchId.trim() && hasAllFailedCounts(batch)
    && batch.sentCount === 0 && batch.skippedCount === 0
}

function hasAllFailedCounts(value: unknown): boolean {
  if (!value || typeof value !== 'object' || !('targetCount' in value) || !('failedCount' in value)) return false
  return typeof value.targetCount === 'number' && Number.isSafeInteger(value.targetCount) && value.targetCount > 0
    && value.failedCount === value.targetCount
    && (!('sentCount' in value) || value.sentCount === 0)
    && (!('skippedCount' in value) || value.skippedCount === 0)
}

/** 전체 실패 응답 후 사용자가 새 발송을 선택할 때만 키를 바꾸고, 결과 확인에는 기존 키를 쓴다. */
export function reportShareRequestKey(reportId: number, currentKey: string,
  batch: NotificationSendBatch | undefined, error: unknown): string {
  return canRetryFailedReportShare(batch, error) ? `r${reportId}-${crypto.randomUUID()}` : currentKey
}

export function reportShareFeedback(batch: NotificationSendBatch | undefined) {
  if (!batch) return { label: '요약 전달하기', success: null, warning: null }
  const { sentCount, failedCount, skippedCount } = batch
  if (failedCount || skippedCount || !sentCount) {
    return {
      label: sentCount ? '일부 전달 완료' : failedCount ? '전달 실패' : '전달 제외',
      success: null,
      warning: `${sentCount}명 전달 · ${failedCount}명 실패 · ${skippedCount}명 제외. 아래에서 수신자별 결과를 확인해 주세요.`,
    }
  }
  return { label: '전달 완료', success: `${sentCount}명에게 전달했습니다.`, warning: null }
}
