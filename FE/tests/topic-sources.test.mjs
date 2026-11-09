import assert from 'node:assert/strict'
import test from 'node:test'
import { QueryClient } from '@tanstack/react-query'
import { ApiError } from '../src/api/client.ts'
import { availableTopicSources, saveTopicSourcesOptions, topicSourceEditOptions } from '../src/api/topicSources.ts'
import { recommendedTopicSourceIds } from '../src/features/settings/topicPresets.ts'

const source = (id, urlTemplate, extra = {}) => ({ id, name: `소스 ${id}`, sourceKind: 'FEED', active: true,
  urlTemplate, country: null, language: 'en', crawlPolicy: null, robotsStatus: 'allowed', robotsCheckedAt: null, reliabilityScore: null, ...extra })
const feeds = [
  source(7103, 'https://openai.com/news/rss.xml'), source(7104, 'https://huggingface.co/blog/feed.xml'),
  source(7105, 'https://research.google/blog/rss/'), source(7106, 'https://www.microsoft.com/en-us/research/feed/'),
  source(7107, 'https://simonwillison.net/tags/llms.atom'), source(1, 'https://news.example.com/rss'),
]
const topic = { id: 4955, name: '기존 AI', queryText: '인공지능', requiredKeywords: ['인공지능'], optionalKeywords: [],
  excludedKeywords: [], intervalMinutes: 1440, batchSize: 100, active: true,
  sources: [{ id: 1, name: '기존 뉴스', sourceKind: 'FEED', active: true, language: 'ko', robotsStatus: 'allowed' },
    { id: 2, name: '중지된 소스', sourceKind: 'FEED', active: false, language: 'ko', robotsStatus: 'allowed' }] }
const page = (content, hasNext = false) => ({ content, page: 0, size: 100, totalElements: content.length, totalPages: hasNext ? 2 : 1, hasNext })
const response = result => new Response(JSON.stringify({ isSuccess: true, code: 'COMMON200', message: '연결되었습니다.', result }))

test('opening reads all source pages and existing links without writing; discarding the draft leaves links unchanged', async context => {
  const requests = []
  context.mock.method(globalThis, 'fetch', async (url, init) => {
    requests.push({ url, method: init.method, signal: init.signal })
    return response(url.includes('/topics/') ? topic : url.includes('page=0') ? page(feeds.slice(0, 2), true) : page(feeds.slice(2)))
  })
  const signal = new AbortController().signal
  const editing = await topicSourceEditOptions(topic.id).queryFn({ signal })
  const draft = recommendedTopicSourceIds(editing.sources, editing.topic.sources.map(source => source.id))
  assert.deepEqual(draft, [7103, 7104, 7105, 7106, 7107, 2])
  assert.deepEqual(editing.topic.sources.map(source => source.id), [1, 2])
  assert.equal(editing.sources.find(source => source.id === 2).active, false)
  assert.equal(editing.sources.length, 7)
  assert.ok(requests.every(request => request.method === undefined && request.signal === signal))
  assert.deepEqual(requests.map(request => request.url).sort(), [
    '/api/news/topics/4955', '/api/news/sources?active=true&page=0&size=100', '/api/news/sources?active=true&page=1&size=100',
  ].sort())
})

test('technical recommendations keep inactive links until explicitly deselected', () => {
  const sources = availableTopicSources(topic, feeds)
  assert.deepEqual(recommendedTopicSourceIds(sources, [1, 2]), [7103, 7104, 7105, 7106, 7107, 2])
  assert.deepEqual(recommendedTopicSourceIds(sources, [1]), [7103, 7104, 7105, 7106, 7107])
})

test('source save sends exactly selected IDs in one PUT and updates connection counts without changing topic conditions', async context => {
  const client = new QueryClient()
  context.after(() => client.clear())
  client.setQueryData(['topics', true], page([{ ...topic, linkedSourceCount: 2 }]))
  client.setQueryData(['topic-edit', topic.id], topic)
  client.setQueryData(['topic-sources'], page([{ topicId: topic.id, sourceId: 1 }]))
  const requests = []
  const sourceIds = [7103, 7104, 7105, 7106, 7107, 2]
  context.mock.method(globalThis, 'fetch', async (url, init) => {
    requests.push({ url, method: init.method, body: JSON.parse(init.body) })
    return response({ topicId: topic.id, sources: [], addedCount: 5, removedCount: 1, combinationCount: 6 })
  })
  const saved = await client.getMutationCache().build(client, saveTopicSourcesOptions(client, topic.id)).execute(sourceIds)
  assert.equal(saved.combinationCount, 6)
  assert.deepEqual(requests, [{ url: '/api/news/topics/4955/sources', method: 'PUT', body: { sourceIds } }])
  assert.deepEqual(client.getQueryData(['topics', true]).content[0], { ...topic, linkedSourceCount: 6 })
  assert.equal(client.getQueryState(['topic-edit', topic.id]).isInvalidated, true)
  assert.equal(client.getQueryState(['topic-sources']).isInvalidated, true)
})

test('explicitly saving an empty selection unlinks all sources without a fallback', async context => {
  const client = new QueryClient()
  context.after(() => client.clear())
  context.mock.method(globalThis, 'fetch', async (url, init) => {
    assert.equal(url, '/api/news/topics/4955/sources')
    assert.deepEqual(JSON.parse(init.body), { sourceIds: [] })
    return response({ topicId: topic.id, sources: [], addedCount: 0, removedCount: 2, combinationCount: 0 })
  })
  await client.getMutationCache().build(client, saveTopicSourcesOptions(client, topic.id)).execute([])
})

test('a source-list failure prevents opening an incomplete editable selection and never writes', async context => {
  const requests = []
  context.mock.method(globalThis, 'fetch', async (url, init) => {
    requests.push(init.method)
    if (url.includes('/topics/')) return response(topic)
    return new Response(JSON.stringify({ isSuccess: false, code: 'SOURCE502', message: '소스 목록 조회 실패', result: {} }), { status: 502 })
  })
  await assert.rejects(topicSourceEditOptions(topic.id).queryFn({ signal: new AbortController().signal }),
    error => error instanceof ApiError && error.message === '소스 목록 조회 실패')
  assert.ok(requests.every(method => method === undefined))
})

test('failed source saves preserve cached links and expose the server message without automatic retry', async context => {
  const client = new QueryClient()
  context.after(() => client.clear())
  const editing = { topic, sources: feeds }
  client.setQueryData(topicSourceEditOptions(topic.id).queryKey, editing)
  let attempts = 0
  context.mock.method(globalThis, 'fetch', async () => {
    attempts++
    return new Response(JSON.stringify({ isSuccess: false, code: 'SOURCE404', message: '수집 소스를 찾을 수 없습니다.', result: { notFoundSourceIds: [99] } }), { status: 404 })
  })
  await assert.rejects(client.getMutationCache().build(client, saveTopicSourcesOptions(client, topic.id)).execute([99]),
    error => error instanceof ApiError && error.code === 'SOURCE404' && error.message === '수집 소스를 찾을 수 없습니다.')
  assert.equal(attempts, 1)
  assert.deepEqual(client.getQueryData(topicSourceEditOptions(topic.id).queryKey), editing)
})
