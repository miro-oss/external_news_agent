import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { notificationDelete, notificationGet, notificationPost, notificationPut } from './client'
import type { NotificationSendBatch } from './types'

export type DeliveryPolicy = {
  enabled: boolean
  run: boolean
  daily: boolean
  groupIds: number[]
  recipientIds: number[]
  channelIds: number[]
}
export type TelegramConnection = { status: 'CONNECTED' | 'WAITING' | 'EXPIRED' | 'DISCONNECTED'; expiresAt: string | null }
export type TelegramLink = { url: string; expiresAt: string }
export type AutoDelivery = { id: number; recipientName: string; channelType: string; status: string; attempts: number; message: string | null }
export type DeliveryTargets = { groupIds: number[]; recipientIds: number[]; channelIds: number[] }

export function useTelegramConnection(recipientId: number) {
  return useQuery({ queryKey: ['telegram-connection', recipientId],
    queryFn: () => notificationGet<TelegramConnection>(`/recipients/${recipientId}/telegram`),
    refetchOnWindowFocus: true,
    refetchInterval: (query) => query.state.data?.status === 'WAITING' ? 3000 : false,
  })
}
export function useTelegramLink(recipientId: number) {
  const client = useQueryClient()
  return useMutation({ mutationFn: () => notificationPost<TelegramLink>(`/recipients/${recipientId}/telegram/link`, {}),
    onSuccess: () => client.invalidateQueries({ queryKey: ['telegram-connection', recipientId] }),
  })
}
export function useDisconnectTelegram(recipientId: number) {
  const client = useQueryClient()
  return useMutation({ mutationFn: () => notificationDelete<TelegramConnection>(`/recipients/${recipientId}/telegram`),
    onSuccess: () => { void client.invalidateQueries({ queryKey: ['telegram-connection', recipientId] }); void client.invalidateQueries({ queryKey: ['notifications'] }) },
  })
}
export function useDeliveryPolicy(topicId: number) {
  return useQuery({ queryKey: ['delivery-policy', topicId], queryFn: () => notificationGet<DeliveryPolicy>(`/topics/${topicId}/delivery-policy`) })
}
export function useSaveDeliveryPolicy(topicId: number) {
  const client = useQueryClient()
  return useMutation({ mutationFn: (policy: DeliveryPolicy) => notificationPut<DeliveryPolicy>(`/topics/${topicId}/delivery-policy`, policy),
    onSuccess: (policy) => client.setQueryData(['delivery-policy', topicId], policy),
  })
}
export function useEmailReadiness() {
  return useQuery({ queryKey: ['notifications', 'email-readiness'],
    queryFn: () => notificationGet<{ mode: 'LOCAL_CAPTURE' | 'SMTP' | 'UNCONFIGURED'; configured: boolean; message: string }>('/email-readiness'),
  })
}
export function useShareReport(reportId: number) {
  const client = useQueryClient()
  return useMutation({ mutationFn: (body: DeliveryTargets & { idempotencyKey: string }) => notificationPost<NotificationSendBatch>(`/reports/${reportId}/send`, body),
    onSuccess: () => { void client.invalidateQueries({ queryKey: ['notifications'] }); void client.invalidateQueries({ queryKey: ['reports'] }) },
  })
}
export function useAutoDeliveries(reportId: number) {
  return useQuery({ queryKey: ['auto-deliveries', reportId], queryFn: () => notificationGet<AutoDelivery[]>(`/reports/${reportId}/auto-deliveries`),
    refetchInterval: (query) => query.state.data?.some((item) => ['PENDING', 'PROCESSING'].includes(item.status)) ? 5000 : false,
  })
}
export function useRetryAutoDeliveries(reportId: number) {
  const client = useQueryClient()
  return useMutation({ mutationFn: () => notificationPost<{ queuedCount: number }>(`/reports/${reportId}/auto-deliveries/retry`, {}),
    onSuccess: () => client.invalidateQueries({ queryKey: ['auto-deliveries', reportId] }),
  })
}
