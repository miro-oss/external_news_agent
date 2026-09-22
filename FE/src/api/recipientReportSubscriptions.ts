import type { QueryClient } from '@tanstack/react-query'
import { notificationGet, notificationPut } from './client.ts'
import type { NotificationChannelType, ReportScope } from './types'

export type RecipientReportSubscription = {
  topicId: number
  topicName: string
  enabled: boolean
  configuredScopes: ReportScope[]
  excludedScopes: ReportScope[]
  channelTypes: NotificationChannelType[]
  direct: boolean
  groupNames: string[]
}
export type RecipientReportSubscriptions = { recipientId: number; topics: RecipientReportSubscription[] }
export const reportSubscriptionsKey = ['notifications', 'report-subscriptions'] as const

export function recipientReportSubscriptionsOptions(recipientId: number) {
  return {
    queryKey: [...reportSubscriptionsKey, recipientId],
    queryFn: ({ signal }: { signal?: AbortSignal } = {}) => notificationGet<RecipientReportSubscriptions>(
      `/recipients/${recipientId}/report-subscriptions`, undefined, signal),
    staleTime: 0,
  }
}

export function saveRecipientReportSubscriptionOptions(client: QueryClient, recipientId: number, topicId: number) {
  const queryKey = recipientReportSubscriptionsOptions(recipientId).queryKey
  return {
    mutationKey: ['recipient-report-subscription-save', recipientId, topicId],
    mutationFn: (excludedScopes: ReportScope[]) => notificationPut<RecipientReportSubscription>(
      `/recipients/${recipientId}/report-subscriptions/${topicId}`, { excludedScopes }),
    retry: false,
    onSuccess: async (topic: RecipientReportSubscription) => {
      // A GET started before the save must never bring back an old exclusion list.
      await client.cancelQueries({ queryKey, exact: true })
      client.setQueryData<RecipientReportSubscriptions>(queryKey, current => current
        ? { ...current, topics: current.topics.map(row => row.topicId === topicId ? topic : row) } : undefined)
    },
  }
}
