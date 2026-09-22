import assert from 'node:assert/strict'
import test from 'node:test'
import { QueryClient, QueryObserver } from '@tanstack/react-query'
import {
  cacheDeletedNotificationGroup,
  cacheReplacedNotificationGroupMembers,
  cacheUpdatedNotificationGroup,
  notificationGroupRecipientsKey,
  notificationGroupsKey,
} from '../src/api/notificationGroupCache.ts'
import { notificationRecipientsKey } from '../src/api/notificationRecipientCache.ts'

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

test('renaming immediately updates only that group name and preserves a separately saved membership count', async context => {
  const client = new QueryClient()
  context.after(() => client.clear())
  const before = fixture()
  client.setQueryData(notificationGroupsKey, before)
  await cacheReplacedNotificationGroupMembers(client, {
    groupId: 1, members: [], memberCount: 0, activeMemberCount: 0, addedCount: 0, removedCount: 2,
  })
  await cacheUpdatedNotificationGroup(client, { ...before.content[0], name: '새 전략팀' })
  assert.deepEqual(client.getQueryData(notificationGroupsKey), {
    ...before,
    content: [{ ...before.content[0], name: '새 전략팀', members: [], memberCount: 0, activeMemberCount: 0 }, before.content[1]],
  })
  assert.equal(before.content[0].name, '삭제할 전략팀')
})

test('replacing members preserves the group name and uses the server active and total member counts', async context => {
  const client = new QueryClient()
  context.after(() => client.clear())
  const before = fixture()
  const members = [{ recipientId: 4, name: '활성 수신자', active: true }, { recipientId: 5, name: '비활성 수신자', active: false }]
  client.setQueryData(notificationGroupsKey, before)
  await cacheUpdatedNotificationGroup(client, { ...before.content[0], name: '이름 수정 완료' })
  await cacheReplacedNotificationGroupMembers(client, {
    groupId: 1, members, memberCount: 2, activeMemberCount: 1, addedCount: 2, removedCount: 2,
  })
  assert.deepEqual(client.getQueryData(notificationGroupsKey), {
    ...before,
    content: [{ ...before.content[0], name: '이름 수정 완료', members, activeMemberCount: 1 }, before.content[1]],
  })
})

for (const operation of ['rename', 'members']) {
  test(`a successful ${operation} cancels stale group and all recipient reads including group-filtered queries`, async context => {
    const client = new QueryClient()
    context.after(() => client.clear())
    const before = fixture()
    const keys = [notificationGroupsKey, notificationRecipientsKey, notificationGroupRecipientsKey(1), notificationGroupRecipientsKey(2)]
    const current = [before, { content: [{ id: 4, name: '현재 이름' }] }, { content: [{ id: 4 }] }, { content: [] }]
    const reads = keys.map(() => Promise.withResolvers())
    keys.forEach((key, index) => client.setQueryData(key, current[index]))
    const pending = keys.map((queryKey, index) => client.fetchQuery({ queryKey, queryFn: () => reads[index].promise }).catch(() => null))
    if (operation === 'rename') {
      await cacheUpdatedNotificationGroup(client, { ...before.content[0], name: '새 이름' })
    } else {
      await cacheReplacedNotificationGroupMembers(client, {
        groupId: 1, members: [], memberCount: 0, activeMemberCount: 0, addedCount: 0, removedCount: 2,
      })
    }
    reads[0].resolve(before)
    reads.slice(1).forEach(read => read.resolve({ content: [{ id: 999, name: '늦게 도착한 이전 데이터' }] }))
    await Promise.all(pending)
    const saved = client.getQueryData(notificationGroupsKey).content[0]
    assert.equal(operation === 'rename' ? saved.name : saved.memberCount, operation === 'rename' ? '새 이름' : 0)
    keys.slice(1).forEach((key, index) => {
      if (operation === 'members' && index === 1) assert.deepEqual(client.getQueryData(key).content, [])
      else assert.deepEqual(client.getQueryData(key), current[index + 1])
    })
  })
}

test('updates never create incomplete lists or restore a group already removed from the list', async context => {
  const client = new QueryClient()
  context.after(() => client.clear())
  const group = fixture().content[0]
  const members = { groupId: 1, members: [], memberCount: 0, activeMemberCount: 0, addedCount: 0, removedCount: 2 }
  await cacheUpdatedNotificationGroup(client, group)
  await cacheReplacedNotificationGroupMembers(client, members)
  assert.equal(client.getQueryData(notificationGroupsKey), undefined)
  client.setQueryData(notificationGroupsKey, fixture())
  await cacheDeletedNotificationGroup(client, 1)
  await cacheUpdatedNotificationGroup(client, group)
  await cacheReplacedNotificationGroupMembers(client, members)
  assert.deepEqual(client.getQueryData(notificationGroupsKey).content.map(item => item.id), [2])
  assert.equal(client.getQueryData(notificationGroupsKey).totalElements, 1)
})

test('reopening immediately after saving uses the saved member IDs even while an earlier read is delayed', async context => {
  const client = new QueryClient()
  context.after(() => client.clear())
  const page = content => ({ content, page: 0, size: 100, totalElements: content.length, totalPages: 1, hasNext: false })
  const before = { id: 1, name: '이전 구성원', active: true, email: 'old@example.invalid', destinations: [] }
  const added = { id: 2, name: '캐시 이름', active: true, email: 'new@example.invalid', destinations: [{ channelId: 1 }] }
  const recipientKey = notificationGroupRecipientsKey(1)
  client.setQueryData(notificationRecipientsKey, page([before, added]))
  client.setQueryData(recipientKey, page([before]))
  const stale = Promise.withResolvers()
  const pending = client.fetchQuery({ queryKey: recipientKey, queryFn: () => stale.promise }).catch(() => null)
  await cacheReplacedNotificationGroupMembers(client, {
    groupId: 1, members: [{ recipientId: 2, name: '서버 이름', active: false }], memberCount: 1, activeMemberCount: 0, addedCount: 1, removedCount: 1,
  })
  stale.resolve(page([before]))
  await pending
  assert.deepEqual(client.getQueryData(recipientKey), page([{ ...added, name: '서버 이름', active: false }]))
  // 편집 화면이 다시 구독하는 즉시 선택할 데이터도 저장된 구성원이다.
  const observer = new QueryObserver(client, { queryKey: recipientKey, enabled: false })
  const unsubscribe = observer.subscribe(() => {})
  context.after(unsubscribe)
  assert.deepEqual(observer.getCurrentResult().data.content.map(recipient => recipient.id), [2])
})

test('missing member details clear stale selections and refetch an active membership observer', async context => {
  const client = new QueryClient()
  context.after(() => client.clear())
  const recipientKey = notificationGroupRecipientsKey(1)
  const before = { content: [{ id: 1, name: '이전 구성원', active: true }], page: 0, size: 100, totalElements: 1, totalPages: 1, hasNext: false }
  client.setQueryData(recipientKey, before)
  const reads = []
  const observer = new QueryObserver(client, {
    queryKey: recipientKey,
    queryFn: () => {
      const read = Promise.withResolvers()
      reads.push(read)
      return read.promise
    },
  })
  const unsubscribe = observer.subscribe(() => {})
  context.after(unsubscribe)
  await cacheReplacedNotificationGroupMembers(client, {
    groupId: 1, members: [{ recipientId: 99, name: '다른 화면에서 추가된 구성원', active: true }], memberCount: 1, activeMemberCount: 1, addedCount: 1, removedCount: 1,
  })
  assert.equal(observer.getCurrentResult().isPending, true)
  assert.equal(observer.getCurrentResult().isFetching, true)
  assert.equal(observer.getCurrentResult().data, undefined)
  assert.equal(reads.length, 2)
  reads[0].resolve(before)
  const after = { ...before, content: [{ id: 99, name: '다른 화면에서 추가된 구성원', active: true }] }
  reads[1].resolve(after)
  await client.getQueryCache().find({ queryKey: recipientKey }).promise
  assert.deepEqual(observer.getCurrentResult().data, after)
})
