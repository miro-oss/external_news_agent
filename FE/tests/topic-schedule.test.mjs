import assert from 'node:assert/strict'
import test from 'node:test'
import { QueryClient } from '@tanstack/react-query'
import { ApiError } from '../src/api/client.ts'
import { topicScheduleOptions, saveTopicScheduleOptions } from '../src/api/topicSchedule.ts'

const topic = { id: 31, name: '반도체', queryText: '반도체 공급망', requiredKeywords: ['반도체'],
  optionalKeywords: ['공급망'], excludedKeywords: ['채용'], batchSize: 100, intervalMinutes: 1440, active: false }
const summary = { ...topic, linkedSourceCount: 2, lastCollectedAt: null,
  surgeKeywords: [{ keyword: 'HBM4', articleCount: 5, previousArticleCount: 2, deltaArticleCount: 3, zScore: 1.5, burst: false }],
  relatedKeywords: [{ keyword: '패키징', articleCount: 4, sharePercent: 40 }] }
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

test('weekly schedule save sends only intervalMinutes and updates cached rows without losing topic data', async context => {
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
    return response({ ...topic, intervalMinutes: 10080 })
  })
  await client.getMutationCache().build(client, saveTopicScheduleOptions(client, 31))
    .execute({ intervalMinutes: 10080, active: true, requiredKeywords: [] })
  assert.deepEqual(requests, [{ url: '/api/news/topics/31', method: 'PATCH', body: { intervalMinutes: 10080 } }])
  for (const filter of ['all', false]) {
    assert.deepEqual(client.getQueryData(['topics', filter]), page([{ ...summary, intervalMinutes: 10080 }, other]))
    assert.equal(client.getQueryState(['topics', filter]).isInvalidated, true)
  }
  assert.deepEqual(client.getQueryData(topicScheduleOptions(31).queryKey), { ...detail, intervalMinutes: 10080 })
  const combinations = client.getQueryData(['topic-sources'])
  assert.deepEqual(combinations.content.map(row => row.intervalMinutes), [10080, 10080, 1440])
  assert.equal(combinations.content[0].active, false)
  assert.equal(combinations.combinationCount, 3)
})

for (const active of [true, false]) test(`manual-only schedule survives saving and reloading, then restores a recurring interval without changing active=${active}`, async context => {
  const client = new QueryClient()
  context.after(() => client.clear())
  const original = { ...summary, active }
  const other = { ...summary, id: 29, name: 'AI', active }
  const detail = { ...topic, active, sources: [{ id: 1, name: '검색' }], lastCollectedAt: null }
  const combination = { topicId: topic.id, sourceId: 1, active, intervalMinutes: 1440, lastCollectedCount: 12 }
  const otherCombination = { ...combination, topicId: other.id }
  for (const filter of ['all', active]) client.setQueryData(['topics', filter], page([original, other]))
  client.setQueryData(topicScheduleOptions(topic.id).queryKey, detail)
  client.setQueryData(['topic-sources'], { ...page([combination, otherCombination]), combinationCount: 2 })
  const requests = []
  let stored = { ...detail }
  context.mock.method(globalThis, 'fetch', async (url, init) => {
    const body = init.body ? JSON.parse(init.body) : undefined
    requests.push({ url, method: init.method, body })
    if (init.method === 'PATCH') {
      stored = { ...stored, intervalMinutes: body.intervalMinutes }
      return response({ ...topic, active, intervalMinutes: stored.intervalMinutes })
    }
    return response(stored)
  })
  for (const intervalMinutes of [0, 720]) {
    await client.getMutationCache().build(client, saveTopicScheduleOptions(client, topic.id))
      .execute({ intervalMinutes, active: !active, requiredKeywords: [] })
    for (const filter of ['all', active]) {
      assert.deepEqual(client.getQueryData(['topics', filter]), page([{ ...original, intervalMinutes }, other]))
      assert.equal(client.getQueryState(['topics', filter]).isInvalidated, true)
    }
    assert.deepEqual(client.getQueryData(topicScheduleOptions(topic.id).queryKey), { ...detail, intervalMinutes })
    assert.deepEqual(client.getQueryData(['topic-sources']), {
      ...page([{ ...combination, intervalMinutes }, otherCombination]), combinationCount: 2,
    })
    const reloaded = await client.fetchQuery(topicScheduleOptions(topic.id))
    assert.deepEqual(reloaded, { ...detail, intervalMinutes })
  }
  assert.deepEqual(requests, [
    { url: '/api/news/topics/31', method: 'PATCH', body: { intervalMinutes: 0 } },
    { url: '/api/news/topics/31', method: undefined, body: undefined },
    { url: '/api/news/topics/31', method: 'PATCH', body: { intervalMinutes: 720 } },
    { url: '/api/news/topics/31', method: undefined, body: undefined },
  ])
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
