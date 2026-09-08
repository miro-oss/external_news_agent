import type { NotificationRecipient } from '../api/types'

type EmailRecipient = Pick<NotificationRecipient, 'email' | 'destinations'>
type DestinationInput = { channelId: number; address: string | null; use: boolean }

export function recipientEmail(recipient: EmailRecipient): string {
  const emailDestinations = recipient.destinations.filter((destination) => destination.channelType === 'EMAIL')
  return emailDestinations.find((destination) => destination.use && destination.address)?.address
    || emailDestinations.find((destination) => destination.address)?.address || recipient.email || ''
}

export async function saveRecipientEmail(email: string, emailChannelId: number, operations: {
  loadRecipient: () => Promise<EmailRecipient>
  replaceDestinations: (destinations: DestinationInput[]) => Promise<unknown>
  updateProfile: (email: string) => Promise<unknown>
}): Promise<{ email: string; profileSynced: boolean }> {
  const address = email.trim()
  const current = await operations.loadRecipient()
  const existingEmail = current.destinations.filter((destination) => destination.channelType === 'EMAIL')
  if (!existingEmail.length || existingEmail.some((destination) => destination.address !== address)) {
    const destinations = current.destinations.map((destination) => ({
      channelId: destination.channelId,
      address: destination.channelType === 'EMAIL' ? address : destination.address,
      use: destination.use,
    }))
    if (!existingEmail.length) destinations.push({ channelId: emailChannelId, address, use: true })
    await operations.replaceDestinations(destinations)
  }

  if (current.email !== address) {
    try {
      await operations.updateProfile(address)
    } catch {
      // Delivery addresses and profile fields have separate API transactions.
      return { email: address, profileSynced: false }
    }
  }
  return { email: address, profileSynced: true }
}
