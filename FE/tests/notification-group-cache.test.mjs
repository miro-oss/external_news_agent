import assert from 'node:assert/strict'
import test from 'node:test'
import { QueryClient } from '@tanstack/react-query'
import { cacheDeletedNotificationGroup, notificationGroupsKey } from '../src/api/notificationGroupCache.ts'

function fixture() {
  return {
    content: [
      { id: 1, name: '삭제할 전략팀', active: true, perspective: 'EXECUTIVE', memberCount: 2, activeMemberCount: 2 },
      { id: 2, name: '유지할 기술팀', active: true, perspective: 'TECHNOLOGY', memberCount: 1, activeMemberCount: 1 },
    ],
    page: 0, size: 100, totalElements: 2, totalPages: 1, hasNext: false,
  }
}

test('successful group deletion immediately updates the list and count without deleting recipients or delivery history', async () => {
  const client = new QueryClient()
  const before = fixture()
  const recipientsKey = ['notifications', 'recipients']
  const logsKey = ['notifications', 'delivery-logs', {}]
  const recipients = { content: [{ id: 3, name: '유지할 수신자', active: true }] }
  const logs = { content: [{ id: 4, recipientId: 3, status: 'SENT' }] }
  client.setQueryData(notificationGroupsKey, before)
  client.setQueryData(recipientsKey, recipients)
  client.setQueryData(logsKey, logs)
  await cacheDeletedNotificationGroup(client, 1)
  const after = client.getQueryData(notificationGroupsKey)
  assert.deepEqual(after.content, [before.content[1]])
  assert.equal(after.content.filter(group => group.active).length, 1)
  assert.equal(after.totalElements, 1)
  assert.equal(before.content.length, 2)
  assert.deepEqual(client.getQueryData(recipientsKey), recipients)
  assert.deepEqual(client.getQueryData(logsKey), logs)
  client.clear()
})

test('a delayed older group list cannot restore a successfully deleted group', async () => {
  const client = new QueryClient()
  const stale = Promise.withResolvers()
  client.setQueryData(notificationGroupsKey, fixture())
  const oldRequest = client.fetchQuery({ queryKey: notificationGroupsKey, queryFn: () => stale.promise }).catch(() => null)
  await cacheDeletedNotificationGroup(client, 1)
  stale.resolve(fixture())
  await oldRequest
  assert.deepEqual(client.getQueryData(notificationGroupsKey).content.map(group => group.id), [2])
  assert.equal(client.getQueryData(notificationGroupsKey).totalElements, 1)
  client.clear()
})

test('deleting the final group leaves an empty list and does not double-count a repeated update', async () => {
  const client = new QueryClient()
  client.setQueryData(notificationGroupsKey, fixture())
  await cacheDeletedNotificationGroup(client, 1)
  await cacheDeletedNotificationGroup(client, 2)
  await cacheDeletedNotificationGroup(client, 2)
  const after = client.getQueryData(notificationGroupsKey)
  assert.deepEqual(after.content, [])
  assert.equal(after.totalElements, 0)
  assert.equal(after.totalPages, 0)
  client.clear()
})

test('a rejected group delete preserves the list and count', async () => {
  const client = new QueryClient()
  const before = fixture()
  client.setQueryData(notificationGroupsKey, before)
  const failure = new Error('fixture group deletion failed')
  const mutation = client.getMutationCache().build(client, {
    mutationFn: async () => { throw failure },
    onSuccess: (_, groupId) => cacheDeletedNotificationGroup(client, groupId),
  })
  await assert.rejects(mutation.execute(1), error => error === failure)
  assert.deepEqual(client.getQueryData(notificationGroupsKey), before)
  client.clear()
})
