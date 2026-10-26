import type { QueryClient } from '@tanstack/react-query'
import { ApiError, notificationGet, notificationPut } from './client.ts'
import type { DeliveryPolicy } from './notificationConnections'

export type RunDeliveryPolicy = Omit<DeliveryPolicy, 'weekly'>
export type RunDeliverySettings = RunDeliveryPolicy & {
  runId: number
  editable: boolean
  reportId: number | null
  reportReady: boolean
  source: 'RUN' | 'TOPIC'
  topicPolicies: Array<DeliveryPolicy & { topicId: number; topicName: string }>
}

export function runDeliverySettingsOptions(runId: number) {
  return {
    queryKey: ['run-delivery-settings', runId],
    queryFn: () => notificationGet<RunDeliverySettings>(`/runs/${runId}/delivery-settings`),
    staleTime: 0,
  }
}

export function saveRunDeliverySettingsOptions(client: QueryClient, runId: number) {
  return {
    mutationFn: (policy: RunDeliveryPolicy) => notificationPut<RunDeliverySettings>(`/runs/${runId}/delivery-settings`, {
      enabled: policy.enabled, run: policy.run, daily: policy.daily,
      groupIds: policy.groupIds, recipientIds: policy.recipientIds, channelIds: policy.channelIds,
    }),
    retry: false,
    onSuccess: (settings: RunDeliverySettings) => client.setQueryData(runDeliverySettingsOptions(runId).queryKey, settings),
    onError: async (error: Error) => {
      if (error instanceof ApiError && error.status === 409) {
        // Completion won the race. Refresh state for the report link; never turn this into a send.
        await client.fetchQuery(runDeliverySettingsOptions(runId)).catch(() => undefined)
      }
    },
  }
}
