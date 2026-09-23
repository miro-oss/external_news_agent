import type { QueryClient } from '@tanstack/react-query'
import { ApiError, notificationGet, notificationPut } from './client.ts'
import type { NotificationChannelType, ReportScope } from './types'

export type RecipientReportSubscription = {
  topicId: number
  topicName: string
  enabled: boolean
  configuredScopes: ReportScope[]
  includedScopes: ReportScope[]
  excludedScopes: ReportScope[]
  channelTypes: NotificationChannelType[]
  direct: boolean
  groupNames: string[]
}
export type RecipientReportSubscriptions = { recipientId: number; topics: RecipientReportSubscription[] }
export type RecipientReportChoices = Pick<RecipientReportSubscription, 'includedScopes' | 'excludedScopes'>
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
    mutationFn: async (choices: RecipientReportChoices) => {
      const topic = await notificationPut<RecipientReportSubscription>(
        `/recipients/${recipientId}/report-subscriptions/${topicId}`, choices)
      if (choices.includedScopes.length > 0 && !Array.isArray(topic.includedScopes))
        throw new ApiError('SERVER_UPDATE_REQUIRED', '추가 수신 설정을 저장하려면 서버 업데이트가 필요합니다. 잠시 후 다시 시도해 주세요.')
      return topic
    },
    retry: false,
    onSuccess: async (topic: RecipientReportSubscription) => {
      // A GET started before the save must never bring back old personal choices.
      await client.cancelQueries({ queryKey, exact: true })
      client.setQueryData<RecipientReportSubscriptions>(queryKey, current => current
        ? { ...current, topics: current.topics.map(row => row.topicId === topicId ? topic : row) } : undefined)
    },
  }
}
