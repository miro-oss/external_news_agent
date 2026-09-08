import type { QueryClient } from '@tanstack/react-query'
import type { NotificationRecipient, PageResult } from './types'

export const notificationRecipientsKey = ['notifications', 'recipients'] as const

export async function cacheDeletedNotificationRecipient(client: QueryClient, recipientId: number) {
  // 삭제 전 시작된 조회가 늦게 도착해 수신자를 다시 표시하지 않도록 먼저 취소한다.
  await client.cancelQueries({ queryKey: notificationRecipientsKey })
  client.setQueryData<PageResult<NotificationRecipient>>(notificationRecipientsKey, current => current ? {
    ...current,
    content: current.content.map(recipient => recipient.id === recipientId
      ? { ...recipient, active: false, destinations: [], groupNames: [] }
      : recipient),
  } : current)
}
