import assert from 'node:assert/strict'
import test from 'node:test'
import { recipientEmail, saveRecipientEmail } from '../src/lib/recipientEmail.ts'

function snapshot() {
  return {
    email: 'old@example.invalid',
    destinations: [
      { channelId: 1, channelType: 'EMAIL' as const, address: 'old@example.invalid', use: true, onboarded: true },
      { channelId: 2, channelType: 'TELEGRAM' as const, address: 'fixture-chat', use: false, onboarded: true },
    ],
  }
}

test('email edits preserve the latest Telegram destination and its disabled preference', async () => {
  const current = snapshot()
  const result = await saveRecipientEmail(' new@example.invalid ', 1, {
    loadRecipient: async () => current,
    replaceDestinations: async (destinations) => assert.deepEqual(destinations, [
      { channelId: 1, address: 'new@example.invalid', use: true },
      { channelId: 2, address: 'fixture-chat', use: false },
    ]),
    updateProfile: async (email) => assert.equal(email, 'new@example.invalid'),
  })
  assert.deepEqual(result, { email: 'new@example.invalid', profileSynced: true })
  assert.equal(current.destinations[0].address, 'old@example.invalid')
})

test('a rejected delivery address never changes the recipient profile', async () => {
  const duplicate = new Error('duplicate address')
  await assert.rejects(saveRecipientEmail('taken@example.invalid', 1, {
    loadRecipient: async () => snapshot(),
    replaceDestinations: async () => { throw duplicate },
    updateProfile: async () => assert.fail('profile must remain unchanged'),
  }), (error) => error === duplicate)
})

test('partial saves are explicit and retry only the unfinished profile update', async () => {
  const current = snapshot()
  let replacements = 0
  let failProfile = true
  const operations = {
    loadRecipient: async () => current,
    replaceDestinations: async () => { replacements++; current.destinations[0].address = 'new@example.invalid' },
    updateProfile: async (email: string) => {
      if (failProfile) throw new Error('profile unavailable')
      current.email = email
    },
  }
  assert.deepEqual(await saveRecipientEmail('new@example.invalid', 1, operations), { email: 'new@example.invalid', profileSynced: false })
  assert.equal(recipientEmail(current), 'new@example.invalid')
  failProfile = false
  assert.deepEqual(await saveRecipientEmail('new@example.invalid', 1, operations), { email: 'new@example.invalid', profileSynced: true })
  assert.equal(replacements, 1)
})

test('adding email preserves an unconfigured disabled destination', async () => {
  await saveRecipientEmail('new@example.invalid', 1, {
    loadRecipient: async () => ({ email: null, destinations: [
      { channelId: 2, channelType: 'TELEGRAM', address: null, use: false, onboarded: false },
    ] }),
    replaceDestinations: async (destinations) => assert.deepEqual(destinations, [
      { channelId: 2, address: null, use: false },
      { channelId: 1, address: 'new@example.invalid', use: true },
    ]),
    updateProfile: async () => {},
  })
})
