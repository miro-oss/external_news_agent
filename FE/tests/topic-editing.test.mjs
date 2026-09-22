import assert from 'node:assert/strict'
import test from 'node:test'
import { QueryClient } from '@tanstack/react-query'
import { ApiError } from '../src/api/client.ts'
import { topicEditOptions, saveTopicEditOptions } from '../src/api/topicEditing.ts'

const topic = { id: 31, name: '반도체', queryText: '반도체 공급망', requiredKeywords: [],
  optionalKeywords: ['첨단 패키징', '공급망'], excludedKeywords: ['채용'], batchSize: 100, intervalMinutes: 1440, active: false }
const detail = { ...topic, lastCollectedAt: null, sources: [{ id: 1, name: '검색', sourceKind: 'SEARCH', language: 'ko', robotsStatus: 'allowed', active: true }] }
const summary = { ...topic, linkedSourceCount: 1, lastCollectedAt: null, surgeKeywords: [], relatedKeywords: [] }
const page = content => ({ content, page: 0, size: 100, totalElements: content.length, totalPages: 1, hasNext: false })
const response = result => new Response(JSON.stringify({ isSuccess: true, code: 'COMMON200', message: '수정되었습니다.', result }))

test('opening fetches current conditions and source kinds with a cancellation signal', async context => {
  const controller = new AbortController()
  const requests = []
  context.mock.method(globalThis, 'fetch', async (url, init) => {
    requests.push({ url, method: init.method, signal: init.signal })
    return response(detail)
  })
  const options = topicEditOptions(topic.id)
  assert.equal(options.staleTime, 0)
  assert.deepEqual(await options.queryFn({ signal: controller.signal }), detail)
  assert.deepEqual(requests, [{ url: '/api/news/topics/31', method: undefined, signal: controller.signal }])
})

test('editing clears requested filters and preserves stopped state, schedule, sources and other topics', async context => {
  const client = new QueryClient()
  context.after(() => client.clear())
  const other = { ...summary, id: 29, name: 'AI' }
  for (const filter of ['all', false]) client.setQueryData(['topics', filter], page([summary, other]))
  client.setQueryData(topicEditOptions(topic.id).queryKey, detail)
  client.setQueryData(['topic-schedule', topic.id], detail)
  const combination = { topicId: 31, topicName: topic.name, sourceId: 1, sourceName: '검색', sourceKind: 'SEARCH',
    queryText: topic.queryText, batchSize: 100, intervalMinutes: 1440, active: false, lastCollectedAt: null, lastCollectedCount: null }
  const feedCombination = { ...combination, sourceId: 2, sourceName: 'RSS', sourceKind: 'FEED', queryText: null }
  client.setQueryData(['topic-sources'], { ...page([combination, feedCombination]), combinationCount: 2 })
  client.setQueryData(['topic-keyword-proposals', 'PENDING'], page([{ id: 1, topicId: 31 }]))
  const changes = { name: '공급망 뉴스', queryText: '공급망', excludedKeywords: [] }
  const requests = []
  context.mock.method(globalThis, 'fetch', async (url, init) => {
    requests.push({ url, method: init.method, body: JSON.parse(init.body), contentType: init.headers['Content-Type'] })
    return response({ ...topic, ...changes })
  })
  await client.getMutationCache().build(client, saveTopicEditOptions(client, topic.id)).execute({
    ...changes, active: true, intervalMinutes: 60, batchSize: 300, sourceIds: [],
  })
  assert.deepEqual(requests, [{ url: '/api/news/topics/31', method: 'PATCH', body: changes, contentType: 'application/json' }])
  for (const filter of ['all', false]) {
    assert.deepEqual(client.getQueryData(['topics', filter]), page([{ ...summary, ...changes }, other]))
    assert.equal(client.getQueryState(['topics', filter]).isInvalidated, true)
  }
  assert.deepEqual(client.getQueryData(topicEditOptions(topic.id).queryKey), { ...detail, ...changes })
  assert.deepEqual(client.getQueryData(['topic-schedule', topic.id]), { ...detail, ...changes })
  assert.deepEqual(client.getQueryData(['topic-sources']), {
    ...page([
      { ...combination, topicName: changes.name, queryText: changes.queryText },
      { ...feedCombination, topicName: changes.name },
    ]), combinationCount: 2,
  })
  assert.equal(client.getQueryState(['topic-keyword-proposals', 'PENDING']).isInvalidated, true)
})

test('late reads cannot restore the previous conditions after a successful edit', async context => {
  const client = new QueryClient()
  context.after(() => client.clear())
  const keys = [['topics', false], topicEditOptions(topic.id).queryKey, ['topic-schedule', topic.id]]
  const before = [page([summary]), detail, detail]
  const reads = keys.map(() => Promise.withResolvers())
  keys.forEach((key, index) => client.setQueryData(key, before[index]))
  const pending = keys.map((queryKey, index) => client.fetchQuery({ queryKey, queryFn: () => reads[index].promise }).catch(() => null))
  context.mock.method(globalThis, 'fetch', async () => response({ ...topic, optionalKeywords: ['AI 반도체'] }))
  await client.getMutationCache().build(client, saveTopicEditOptions(client, topic.id)).execute({ optionalKeywords: ['AI 반도체'] })
  reads.forEach((read, index) => read.resolve(before[index]))
  await Promise.all(pending)
  assert.deepEqual(client.getQueryData(keys[0]).content[0].optionalKeywords, ['AI 반도체'])
  for (const key of keys.slice(1)) assert.deepEqual(client.getQueryData(key).optionalKeywords, ['AI 반도체'])
})

test('a name-only save does not restore an older schedule or keywords from its response', async context => {
  const client = new QueryClient()
  context.after(() => client.clear())
  const current = { ...summary, intervalMinutes: 720, optionalKeywords: ['더 최근 조건'] }
  client.setQueryData(['topics', false], page([current]))
  context.mock.method(globalThis, 'fetch', async () => response({ ...topic, name: '수정한 이름' }))
  await client.getMutationCache().build(client, saveTopicEditOptions(client, topic.id)).execute({ name: '수정한 이름' })
  assert.deepEqual(client.getQueryData(['topics', false]), page([{ ...current, name: '수정한 이름' }]))
  assert.equal(client.getQueryData(topicEditOptions(topic.id).queryKey), undefined)
})

test('failed edits preserve cached conditions and expose the server message without retry', async context => {
  const client = new QueryClient()
  context.after(() => client.clear())
  client.setQueryData(['topics', false], page([summary]))
  client.setQueryData(topicEditOptions(topic.id).queryKey, detail)
  let attempts = 0
  context.mock.method(globalThis, 'fetch', async () => {
    attempts++
    return new Response(JSON.stringify({ isSuccess: false, code: 'TOPIC409', message: '이미 존재하는 주제명입니다.', result: {} }), { status: 409 })
  })
  await assert.rejects(client.getMutationCache().build(client, saveTopicEditOptions(client, topic.id)).execute({ name: '중복' }),
    error => error instanceof ApiError && error.code === 'TOPIC409' && error.message === '이미 존재하는 주제명입니다.')
  assert.equal(attempts, 1)
  assert.deepEqual(client.getQueryData(['topics', false]), page([summary]))
  assert.deepEqual(client.getQueryData(topicEditOptions(topic.id).queryKey), detail)
})
