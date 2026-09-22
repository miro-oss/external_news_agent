import type { DeliveryPolicy } from '../../api/notificationConnections'

/** Explicitly copy the API payload: editor scope and response metadata are not request fields. */
export function toDeliveryPolicy(value: Omit<DeliveryPolicy, 'weekly'> & { weekly?: boolean }): DeliveryPolicy {
  return { enabled: value.enabled, run: value.run, daily: value.daily, weekly: value.weekly ?? false,
    groupIds: value.groupIds, recipientIds: value.recipientIds, channelIds: value.channelIds }
}

export function deliveryPolicySummary(value: Omit<DeliveryPolicy, 'weekly'> & { weekly?: boolean }) {
  if (!value.enabled) return '꺼짐'
  const reports = [value.run ? '수집별' : '', value.daily ? '일일 통합' : '', value.weekly ? '주간 통합' : ''].filter(Boolean).join(' · ')
  const targets = [value.groupIds.length ? `그룹 ${value.groupIds.length}개` : '', value.recipientIds.length ? `${value.recipientIds.length}명` : ''].filter(Boolean).join(', ')
  return `${reports} · ${targets}`
}
