import assert from 'node:assert/strict'
import test from 'node:test'
import { QueryClient } from '@tanstack/react-query'
import { cacheDeletedNotificationRecipient, notificationRecipientsKey } from '../src/api/notificationRecipientCache.ts'

function fixture() {
  return {
    content: [
      { id: 1, name: '삭제 대상', email: 'first@example.invalid', active: true, groupNames: ['전략팀'], destinations: [{ channelId: 1, address: 'first@example.invalid' }] },
      { id: 2, name: '유지 대상', email: 'second@example.invalid', active: true, groupNames: ['기술팀'], destinations: [{ channelId: 1, address: 'second@example.invalid' }] },
    ],
    page: 0, size: 100, totalElements: 2, totalPages: 1, hasNext: false,
  }
}

test('successful deletion immediately hides only that recipient while retaining the historical profile', async () => {
  const client = new QueryClient()
  const before = fixture()
  client.setQueryData(notificationRecipientsKey, before)
  await cacheDeletedNotificationRecipient(client, 1)
  const after = client.getQueryData(notificationRecipientsKey)
  assert.deepEqual(after.content.filter(recipient => recipient.active).map(recipient => recipient.id), [2])
  assert.deepEqual(after.content[0], { ...before.content[0], active: false, groupNames: [], destinations: [] })
  assert.deepEqual(after.content[1], before.content[1])
  assert.equal(after.totalElements, 2)
  assert.equal(before.content[0].active, true)
  client.clear()
})

test('an older list response cannot restore a successfully deleted recipient', async () => {
  const client = new QueryClient()
  const stale = Promise.withResolvers()
  client.setQueryData(notificationRecipientsKey, fixture())
  const oldRequest = client.fetchQuery({ queryKey: notificationRecipientsKey, queryFn: () => stale.promise }).catch(() => null)
  await cacheDeletedNotificationRecipient(client, 1)
  stale.resolve(fixture())
  await oldRequest
  assert.equal(client.getQueryData(notificationRecipientsKey).content[0].active, false)
  assert.deepEqual(client.getQueryData(notificationRecipientsKey).content[0].destinations, [])
  client.clear()
})

test('a rejected delete retains the current recipient list', async () => {
  const client = new QueryClient()
  const before = fixture()
  client.setQueryData(notificationRecipientsKey, before)
  const failure = new Error('fixture deletion failed')
  const mutation = client.getMutationCache().build(client, {
    mutationFn: async () => { throw failure },
    onSuccess: (_, recipientId) => cacheDeletedNotificationRecipient(client, recipientId),
  })
  await assert.rejects(mutation.execute(1), error => error === failure)
  assert.deepEqual(client.getQueryData(notificationRecipientsKey), before)
  client.clear()
})
