import type { QueryClient } from '@tanstack/react-query'
import type { NotificationGroup, PageResult } from './types'

export const notificationGroupsKey = ['notifications', 'groups'] as const

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
