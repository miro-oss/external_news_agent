import assert from 'node:assert/strict'
import test from 'node:test'
import { QueryClient } from '@tanstack/react-query'
import { ApiError } from '../src/api/client.ts'
import { topicScheduleOptions, saveTopicScheduleOptions } from '../src/api/topicSchedule.ts'

const topic = { id: 31, name: '반도체', queryText: '반도체 공급망', requiredKeywords: ['반도체'],
  optionalKeywords: ['공급망'], excludedKeywords: ['채용'], batchSize: 100, intervalMinutes: 1440, active: false }
const summary = { ...topic, linkedSourceCount: 2, lastCollectedAt: null,
  surgeKeywords: [{ keyword: 'HBM4', deltaIssueCount: 3 }], relatedKeywords: [] }
const page = content => ({ content, page: 0, size: 100, totalElements: content.length, totalPages: 1, hasNext: false })
const response = result => new Response(JSON.stringify({ isSuccess: true, code: 'COMMON200', message: '수정되었습니다.', result }))

test('opening the editor fetches the latest topic schedule and passes its cancellation signal', async context => {
  const controller = new AbortController()
  const calls = []
  context.mock.method(globalThis, 'fetch', async (url, init) => {
    calls.push({ url, method: init.method, signal: init.signal })
    return response({ ...topic, intervalMinutes: 720 })
  })
  const latest = await topicScheduleOptions(31).queryFn({ signal: controller.signal })
  assert.equal(latest.intervalMinutes, 720)
  assert.equal(latest.active, false)
  assert.deepEqual(calls, [{ url: '/api/news/topics/31', method: undefined, signal: controller.signal }])
})

test('schedule save sends only intervalMinutes and updates cached rows without losing topic data', async context => {
  const client = new QueryClient()
  context.after(() => client.clear())
  const other = { ...summary, id: 29, name: 'AI' }
  for (const filter of ['all', false]) client.setQueryData(['topics', filter], page([summary, other]))
  client.setQueryData(['topic-sources'], { ...page([{ topicId: 31, sourceId: 1, active: false, intervalMinutes: 1440 },
    { topicId: 31, sourceId: 2, active: false, intervalMinutes: 1440 }, { topicId: 29, sourceId: 1, intervalMinutes: 1440 }]), combinationCount: 3 })
  const detail = { ...topic, sources: [{ id: 1 }], lastCollectedAt: null }
  client.setQueryData(topicScheduleOptions(31).queryKey, detail)
  const requests = []
  context.mock.method(globalThis, 'fetch', async (url, init) => {
    requests.push({ url, method: init.method, body: JSON.parse(init.body) })
    return response({ ...topic, intervalMinutes: 720 })
  })
  await client.getMutationCache().build(client, saveTopicScheduleOptions(client, 31))
    .execute({ intervalMinutes: 720, active: true, requiredKeywords: [] })
  assert.deepEqual(requests, [{ url: '/api/news/topics/31', method: 'PATCH', body: { intervalMinutes: 720 } }])
  for (const filter of ['all', false]) {
    assert.deepEqual(client.getQueryData(['topics', filter]), page([{ ...summary, intervalMinutes: 720 }, other]))
    assert.equal(client.getQueryState(['topics', filter]).isInvalidated, true)
  }
  assert.deepEqual(client.getQueryData(topicScheduleOptions(31).queryKey), { ...detail, intervalMinutes: 720 })
  const combinations = client.getQueryData(['topic-sources'])
  assert.deepEqual(combinations.content.map(row => row.intervalMinutes), [720, 720, 1440])
  assert.equal(combinations.content[0].active, false)
  assert.equal(combinations.combinationCount, 3)
})

test('a delayed older query cannot overwrite a saved schedule', async context => {
  const client = new QueryClient()
  context.after(() => client.clear())
  client.setQueryData(['topics', false], page([summary]))
  client.setQueryData(topicScheduleOptions(31).queryKey, topic)
  const listRead = Promise.withResolvers()
  const detailRead = Promise.withResolvers()
  const pending = [
    client.fetchQuery({ queryKey: ['topics', false], queryFn: () => listRead.promise }).catch(() => null),
    client.fetchQuery({ queryKey: topicScheduleOptions(31).queryKey, queryFn: () => detailRead.promise }).catch(() => null),
  ]
  context.mock.method(globalThis, 'fetch', async () => response({ ...topic, intervalMinutes: 60 }))
  await client.getMutationCache().build(client, saveTopicScheduleOptions(client, 31)).execute({ intervalMinutes: 60 })
  listRead.resolve(page([summary]))
  detailRead.resolve(topic)
  await Promise.all(pending)
  assert.equal(client.getQueryData(['topics', false]).content[0].intervalMinutes, 60)
  assert.equal(client.getQueryData(topicScheduleOptions(31).queryKey).intervalMinutes, 60)
})

test('failed saves preserve the previous schedule and expose the API error without retrying', async context => {
  const client = new QueryClient()
  context.after(() => client.clear())
  client.setQueryData(['topics', false], page([summary]))
  client.setQueryData(topicScheduleOptions(31).queryKey, topic)
  let attempts = 0
  context.mock.method(globalThis, 'fetch', async () => {
    attempts++
    return new Response(JSON.stringify({ isSuccess: false, code: 'TOPIC404',
      message: '수집 주제를 찾을 수 없습니다.', result: {} }), { status: 404 })
  })
  await assert.rejects(client.getMutationCache().build(client, saveTopicScheduleOptions(client, 31)).execute({ intervalMinutes: 720 }),
    error => error instanceof ApiError && error.code === 'TOPIC404' && error.message === '수집 주제를 찾을 수 없습니다.')
  assert.equal(attempts, 1)
  assert.deepEqual(client.getQueryData(['topics', false]), page([summary]))
  assert.deepEqual(client.getQueryData(topicScheduleOptions(31).queryKey), topic)
})
