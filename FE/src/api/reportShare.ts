import type { QueryClient } from '@tanstack/react-query'
import { notificationPost } from './client.ts'
import type { DeliveryTargets } from './notificationConnections'
import type { NotificationSendBatch } from './types'

export function reportShareOptions(client: QueryClient, reportId: number) {
  return {
    mutationFn: (body: DeliveryTargets & { idempotencyKey: string }) =>
      notificationPost<NotificationSendBatch>(`/reports/${reportId}/send`, body),
    // 응답이 유실되었을 때 자동으로 새 발송을 만들지 않는다.
    retry: false,
    onSettled: async () => {
      // DELIVERY502에도 배치와 개별 실패 이력이 저장된다.
      await Promise.all([
        client.invalidateQueries({ queryKey: ['notifications'] }),
        client.invalidateQueries({ queryKey: ['reports'] }),
      ])
    },
  }
}
