import assert from 'node:assert/strict'
import test from 'node:test'
import { ApiError } from '../src/api/client.ts'
import { getAllNotificationPages } from '../src/api/notificationPages.ts'
import { notificationGroupRecipientsKey } from '../src/api/notificationGroupCache.ts'
import { notificationRecipientsKey } from '../src/api/notificationRecipientCache.ts'

test('group membership reads keep the group ID on every page and include inactive members', async context => {
  const requests = []
  const recipients = Array.from({ length: 201 }, (_, index) => ({ id: index + 1, name: `수신자 ${index + 1}`, active: index !== 200 }))
  context.mock.method(globalThis, 'fetch', async url => {
    requests.push(url)
    const page = Number(new URL(url, 'http://localhost').searchParams.get('page'))
    return new Response(JSON.stringify({ isSuccess: true, code: 'COMMON200', message: '조회되었습니다.', result: {
      content: recipients.slice(page * 100, (page + 1) * 100), page, size: 100, totalElements: 201, totalPages: 3, hasNext: page < 2,
    } }))
  })
  const result = await getAllNotificationPages('/recipients', { groupId: 17 })
  assert.deepEqual(requests, [0, 1, 2].map(page => `/api/notifications/recipients?groupId=17&page=${page}&size=100`))
  assert.deepEqual(result.content, recipients)
  assert.equal(result.hasNext, false)
  assert.equal(result.totalElements, 201)
  assert.deepEqual(notificationGroupRecipientsKey(17).slice(0, 2), notificationRecipientsKey)
  assert.notDeepEqual(notificationGroupRecipientsKey(17), notificationGroupRecipientsKey(18))
})

test('a later membership page failure rejects the entire read instead of exposing a partial member selection', async context => {
  let requestCount = 0
  context.mock.method(globalThis, 'fetch', async () => {
    requestCount += 1
    if (requestCount === 2) return new Response(JSON.stringify({
      isSuccess: false, code: 'COMMON500', message: '수신자를 조회하지 못했습니다.', result: {},
    }), { status: 500 })
    return new Response(JSON.stringify({ isSuccess: true, code: 'COMMON200', message: '조회되었습니다.', result: {
      content: [{ id: 1 }], page: 0, size: 100, totalElements: 101, totalPages: 2, hasNext: true,
    } }))
  })
  await assert.rejects(getAllNotificationPages('/recipients', { groupId: 17 }),
    error => error instanceof ApiError && error.code === 'COMMON500' && error.message === '수신자를 조회하지 못했습니다.')
  assert.equal(requestCount, 2)
})
