import { useNotificationChannels, useNotificationGroups, useNotificationRecipients } from '../../api/queries'
import type { DeliveryTargets } from '../../api/notificationConnections'
import { unavailableDeliveryTargets } from './deliveryTargetAvailability'

export function useDeliveryTargetAvailability(value: DeliveryTargets) {
  const groups = useNotificationGroups()
  const recipients = useNotificationRecipients()
  const channels = useNotificationChannels()
  const pending = groups.isPending || recipients.isPending || channels.isPending
  const error = groups.error ?? recipients.error ?? channels.error
  const unavailable = pending || error ? [] : unavailableDeliveryTargets(value, {
    groupIds: groups.data?.content ?? [],
    recipientIds: recipients.data?.content ?? [],
    channelIds: channels.data ?? [],
  })
  return { groups, recipients, channels, pending, error, unavailable, valid: !pending && !error && unavailable.length === 0 }
}
