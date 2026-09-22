import type { QueryClient } from '@tanstack/react-query'
import type { NotificationGroup, NotificationGroupMembers, NotificationRecipient, PageResult } from './types'
import { notificationRecipientsKey } from './notificationRecipientCache.ts'

export const notificationGroupsKey = ['notifications', 'groups'] as const
export const notificationGroupRecipientsKey = (groupId: number) =>
  [...notificationRecipientsKey, 'group', groupId] as const

async function cancelGroupReads(client: QueryClient) {
  await Promise.all([
    client.cancelQueries({ queryKey: notificationGroupsKey }),
    client.cancelQueries({ queryKey: notificationRecipientsKey }),
  ])
}

export async function cacheUpdatedNotificationGroup(client: QueryClient, updated: NotificationGroup) {
  await cancelGroupReads(client)
  client.setQueryData<PageResult<NotificationGroup>>(notificationGroupsKey, current => current && {
    ...current,
    // 이름만 저장했으므로, 별도로 저장된 최신 구성원 수를 이전 응답으로 되돌리지 않는다.
    content: current.content.map(group => group.id === updated.id ? { ...group, name: updated.name } : group),
  })
}

export async function cacheReplacedNotificationGroupMembers(client: QueryClient, updated: NotificationGroupMembers) {
  await cancelGroupReads(client)
  client.setQueryData<PageResult<NotificationGroup>>(notificationGroupsKey, current => current && {
    ...current,
    content: current.content.map(group => group.id === updated.groupId ? {
      ...group,
      members: updated.members,
      memberCount: updated.memberCount,
      activeMemberCount: updated.activeMemberCount,
    } : group),
  })

  const recipientKey = notificationGroupRecipientsKey(updated.groupId)
  const previous = client.getQueryData<PageResult<NotificationRecipient>>(recipientKey)
  const allRecipients = client.getQueryData<PageResult<NotificationRecipient>>(notificationRecipientsKey)
  const knownRecipients = new Map([
    ...(previous?.content ?? []),
    ...(allRecipients?.content ?? []),
  ].map(recipient => [recipient.id, recipient]))
  const content: NotificationRecipient[] = []
  for (const member of updated.members) {
    const recipient = knownRecipients.get(member.recipientId)
    if (!recipient) {
      // 구성원 응답은 상세 연락처를 포함하지 않는다. 불완전한 이전 선택을 재사용하지 않고
      // 활성 편집 화면을 로딩 상태로 돌린 뒤 전체 상세를 다시 읽는다.
      void client.resetQueries({ queryKey: recipientKey, exact: true })
      return
    }
    content.push({ ...recipient, name: member.name, active: member.active })
  }
  const size = previous?.size ?? 100
  client.setQueryData<PageResult<NotificationRecipient>>(recipientKey, {
    content,
    page: 0,
    size,
    totalElements: updated.memberCount,
    totalPages: Math.ceil(updated.memberCount / size),
    hasNext: false,
  })
}

export async function cacheDeletedNotificationGroup(client: QueryClient, groupId: number) {
  await client.cancelQueries({ queryKey: notificationGroupsKey })
  client.setQueryData<PageResult<NotificationGroup>>(notificationGroupsKey, current => {
    if (!current || !current.content.some(group => group.id === groupId)) return current
    const content = current.content.filter(group => group.id !== groupId)
    const totalElements = Math.max(0, current.totalElements - 1)
    return {
      ...current,
      content,
      totalElements,
      totalPages: Math.ceil(totalElements / current.size),
    }
  })
}
