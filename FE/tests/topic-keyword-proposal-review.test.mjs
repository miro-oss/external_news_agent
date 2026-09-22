import assert from 'node:assert/strict'
import test from 'node:test'
import { QueryClient, QueryObserver } from '@tanstack/react-query'
import { ApiError } from '../src/api/client.ts'
import { approveTopicKeywordProposalOptions, rejectTopicKeywordProposalOptions } from '../src/api/topicKeywordProposalReview.ts'

const originalKeywords = { requiredKeywords: [], optionalKeywords: ['공급망'], excludedKeywords: ['채용'] }
const updatedKeywords = { ...originalKeywords, optionalKeywords: ['공급망', 'HBM'] }
const proposal = {
  id: 8, topicId: 31, topicName: '반도체', collectionRunId: 100, status: 'PENDING', summary: '키워드 제안',
  currentKeywords: originalKeywords, changes: [{ bucket: 'OPTIONAL', action: 'ADD', keyword: 'HBM', reason: '관련 키워드' }],
  selectedChangeIndexes: null, reviewedAt: null, createdAt: '2026-09-22T09:00:00',
}
const approved = { ...proposal, status: 'APPROVED', currentKeywords: updatedKeywords, selectedChangeIndexes: [0], reviewedAt: '2026-09-23T09:00:00' }
const topic = { id: 31, name: '반도체', ...originalKeywords, active: true, intervalMinutes: 1440, sources: [{ id: 1 }] }
const page = content => ({ content, page: 0, size: 100, totalElements: content.length, totalPages: Math.ceil(content.length / 100), hasNext: false })
const key = status => ['topic-keyword-proposals', status]
const response = result => new Response(JSON.stringify({ isSuccess: true, code: 'COMMON200', message: '처리되었습니다.', result }))
function fixture(context) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  context.after(() => client.clear())
  client.setQueryData(key('PENDING'), page([proposal]))
  client.setQueryData(key('APPROVED'), page([]))
  client.setQueryData(key('REJECTED'), page([]))
  client.setQueryData(key('ALL'), page([proposal]))
  client.setQueryData(['topics', true], page([topic]))
  client.setQueryData(['topic-edit', 31], topic)
  client.setQueryData(['topic-schedule', 31], topic)
  return client
}
const approve = client => client.getMutationCache().build(client, approveTopicKeywordProposalOptions(client))
const reject = client => client.getMutationCache().build(client, rejectTopicKeywordProposalOptions(client))

test('approval moves every cached status list, updates counts and current topic keywords, preserving unrelated topics', async context => {
  const client = fixture(context)
  const otherTopic = { ...proposal, id: 15, topicId: 29, createdAt: '2026-09-23T09:00:00' }
  const older = { ...proposal, id: 5, status: 'APPROVED', createdAt: '2026-09-21T09:00:00' }
  const newer = { ...otherTopic, status: 'APPROVED' }
  client.setQueryData(key('PENDING'), page([otherTopic, proposal]))
  client.setQueryData(key('APPROVED'), page([newer, older]))
  client.setQueryData(key('REJECTED'), page([{ ...older, id: 4, status: 'REJECTED' }]))
  const requests = []
  context.mock.method(globalThis, 'fetch', async (url, init) => {
    requests.push({ url, method: init.method, body: JSON.parse(init.body) })
    return response(approved)
  })
  await approve(client).execute({ proposalId: 8, selectedChangeIndexes: [0] })
  assert.deepEqual(requests, [{ url: '/api/news/topics/keyword-proposals/8/approve', method: 'POST', body: { selectedChangeIndexes: [0] } }])
  assert.deepEqual(client.getQueryData(key('PENDING')), page([otherTopic]))
  assert.deepEqual(client.getQueryData(key('APPROVED')), page([newer, approved, { ...older, currentKeywords: updatedKeywords }]))
  assert.deepEqual(client.getQueryData(key('ALL')), page([approved]))
  assert.deepEqual(client.getQueryData(key('REJECTED')).content[0].currentKeywords, updatedKeywords)
  assert.deepEqual(client.getQueryData(['topics', true]), page([{ ...topic, ...updatedKeywords }]))
  for (const queryKey of [['topic-edit', 31], ['topic-schedule', 31]]) {
    assert.deepEqual(client.getQueryData(queryKey), { ...topic, ...updatedKeywords })
    assert.equal(client.getQueryState(queryKey).isInvalidated, true)
  }
  assert.deepEqual(proposal.currentKeywords, originalKeywords)
})

test('repeat approvals do not duplicate a record or inflate filter counts', async context => {
  const client = fixture(context)
  context.mock.method(globalThis, 'fetch', async () => response(approved))
  for (let attempt = 0; attempt < 2; attempt++) await approve(client).execute({ proposalId: 8, selectedChangeIndexes: [0] })
  assert.deepEqual(client.getQueryData(key('PENDING')), page([]))
  assert.deepEqual(client.getQueryData(key('APPROVED')), page([approved]))
  assert.deepEqual(client.getQueryData(key('ALL')), page([approved]))
})

test('rejecting approved changes moves history and restores the keywords returned by the server', async context => {
  const client = fixture(context)
  const rejected = { ...approved, status: 'REJECTED', currentKeywords: originalKeywords }
  const requests = []
  context.mock.method(globalThis, 'fetch', async (url, init) => {
    requests.push({ url, method: init.method, body: JSON.parse(init.body) })
    return response(url.endsWith('/approve') ? approved : rejected)
  })
  await approve(client).execute({ proposalId: 8, selectedChangeIndexes: [0] })
  await reject(client).execute(8)
  assert.deepEqual(requests[1], { url: '/api/news/topics/keyword-proposals/8/reject', method: 'POST', body: {} })
  assert.deepEqual(client.getQueryData(key('APPROVED')), page([]))
  assert.deepEqual(client.getQueryData(key('REJECTED')), page([rejected]))
  assert.deepEqual(client.getQueryData(['topics', true]), page([topic]))
  assert.deepEqual(client.getQueryData(['topic-edit', 31]), topic)
})

test('late proposal and topic reads cannot overwrite a successful review', async context => {
  const client = fixture(context)
  const keys = [key('PENDING'), key('APPROVED'), ['topics', true], ['topic-edit', 31], ['topic-schedule', 31]]
  const before = keys.map(queryKey => client.getQueryData(queryKey))
  const reads = keys.map(() => Promise.withResolvers())
  const pending = keys.map((queryKey, index) => client.fetchQuery({ queryKey, queryFn: () => reads[index].promise }).catch(() => null))
  context.mock.method(globalThis, 'fetch', async () => response(approved))
  await approve(client).execute({ proposalId: 8, selectedChangeIndexes: [0] })
  reads.forEach((read, index) => read.resolve(before[index]))
  await Promise.all(pending)
  assert.deepEqual(client.getQueryData(key('PENDING')), page([]))
  assert.deepEqual(client.getQueryData(key('APPROVED')), page([approved]))
  assert.deepEqual(client.getQueryData(['topics', true]).content[0].optionalKeywords, updatedKeywords.optionalKeywords)
  assert.deepEqual(client.getQueryData(['topic-edit', 31]).optionalKeywords, updatedKeywords.optionalKeywords)
  assert.deepEqual(client.getQueryData(['topic-schedule', 31]).optionalKeywords, updatedKeywords.optionalKeywords)
})

test('successful approval remains reflected when the subsequent active list refresh fails', async context => {
  const client = fixture(context)
  const failure = new Error('목록을 다시 불러오지 못했습니다.')
  let listRequests = 0
  const observer = new QueryObserver(client, { queryKey: key('PENDING'), staleTime: Infinity, queryFn: async () => {
    listRequests++
    throw failure
  } })
  const unsubscribe = observer.subscribe(() => {})
  context.after(unsubscribe)
  context.mock.method(globalThis, 'fetch', async () => response(approved))
  await approve(client).execute({ proposalId: 8, selectedChangeIndexes: [0] })
  assert.equal(listRequests, 1)
  assert.deepEqual(client.getQueryData(key('PENDING')), page([]))
  assert.equal(client.getQueryState(key('PENDING')).error, failure)
  assert.deepEqual(client.getQueryData(key('APPROVED')), page([approved]))
})

test('TOPIC409 refreshes proposals and topic detail without retrying or changing the review status', async context => {
  const client = fixture(context)
  const newestKeywords = { ...originalKeywords, optionalKeywords: ['수정한 조건'] }
  const freshProposal = { ...proposal, currentKeywords: newestKeywords }
  const keys = [key('PENDING'), ['topics', true], ['topic-edit', 31], ['topic-schedule', 31]]
  const freshValues = [page([freshProposal]), page([{ ...topic, ...newestKeywords }]), { ...topic, ...newestKeywords }, { ...topic, ...newestKeywords }]
  const requests = []
  keys.forEach((queryKey, index) => {
    const observer = new QueryObserver(client, { queryKey, staleTime: Infinity, queryFn: async () => {
      requests.push(queryKey)
      return freshValues[index]
    } })
    context.after(observer.subscribe(() => {}))
  })
  const message = '제안 생성 후 주제 키워드가 변경되었습니다. 새 제안을 기다려 주세요.'
  let mutationRequests = 0
  context.mock.method(globalThis, 'fetch', async () => {
    mutationRequests++
    return new Response(JSON.stringify({ isSuccess: false, code: 'TOPIC409', message, result: {} }), { status: 409 })
  })
  await assert.rejects(approve(client).execute({ proposalId: 8, selectedChangeIndexes: [0] }),
    error => error instanceof ApiError && error.code === 'TOPIC409' && error.message === message)
  assert.equal(mutationRequests, 1)
  assert.equal(requests.length, 4)
  keys.forEach((queryKey, index) => assert.deepEqual(client.getQueryData(queryKey), freshValues[index]))
  assert.deepEqual(client.getQueryData(key('APPROVED')), page([]))
  assert.deepEqual(client.getQueryData(key('REJECTED')), page([]))
})

test('other mutation failures retain the existing lists and do not initiate review or refresh requests', async context => {
  const client = fixture(context)
  context.mock.method(globalThis, 'fetch', async () => new Response(JSON.stringify({ isSuccess: false, code: 'COMMON500', message: '처리에 실패했습니다.' }), { status: 500 }))
  await assert.rejects(reject(client).execute(8), error => error instanceof ApiError && error.code === 'COMMON500')
  assert.deepEqual(client.getQueryData(key('PENDING')), page([proposal]))
  assert.equal(client.getQueryState(key('PENDING')).isInvalidated, false)
  assert.deepEqual(client.getQueryData(['topic-edit', 31]), topic)
})
