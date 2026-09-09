import { ApiError } from '../../api/client.ts'
import type { NotificationSendBatch } from '../../api/types'

export function failedDeliveryBatchId(error: unknown): string | null {
  if (!(error instanceof ApiError) || error.code !== 'DELIVERY502') return null
  const details = error.details
  if (!details || typeof details !== 'object' || !('deliveryBatchId' in details)) return null
  return typeof details.deliveryBatchId === 'string' && details.deliveryBatchId.trim()
    ? details.deliveryBatchId : null
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
