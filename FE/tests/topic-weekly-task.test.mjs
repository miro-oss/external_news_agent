import assert from 'node:assert/strict'
import test from 'node:test'
import { QueryClient, QueryObserver } from '@tanstack/react-query'
import { clearTopicWeeklyReportTask, observeTopicWeeklyReportTask, releaseTopicWeeklyReportTask,
  startTopicWeeklyReportTask, topicWeeklyReportTaskKey } from '../src/features/reports/topicWeeklyReportTask.ts'

const request = { topicId: 29, weekStartDate: '2026-09-21' }
const success = result => new Response(JSON.stringify({ isSuccess: true, code: 'COMMON200', message: '성공입니다.', result }))
const tick = () => new Promise(resolve => setImmediate(resolve))
function setup(context) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } })
  const stop = observeTopicWeeklyReportTask(client)
  context.after(() => { stop(); client.clear() })
  return { client, task: () => client.getQueryData(topicWeeklyReportTaskKey) }
}

test('closing observers preserves a single POST, submitted draft, and completion from a fresh weekly list', async context => {
  const { client, task } = setup(context)
  let finish
  const calls = []
  context.mock.method(globalThis, 'fetch', async (url, init) => {
    calls.push({ url, body: JSON.parse(init.body) })
    await new Promise(resolve => { finish = resolve })
    return success({ reportId: 301, created: true, reportReady: true })
  })
  const dialogObserver = new QueryObserver(client, { queryKey: topicWeeklyReportTaskKey, enabled: false })
  const closeDialog = dialogObserver.subscribe(() => {})
  client.setQueryData(['reports', 'list', 'WEEKLY'], { content: [{ id: 301 }] })
  assert.equal(startTopicWeeklyReportTask(client, request, 'HBM 시장'), true)
  assert.equal(startTopicWeeklyReportTask(client, { ...request, topicId: 30 }, '다른 주제'), false)
  clearTopicWeeklyReportTask(client)
  releaseTopicWeeklyReportTask(client)
  assert.equal(task().status, 'creating', 'neither ordinary dismissal nor waiting release cancels an in-flight POST')
  closeDialog()
  await tick()
  assert.equal(calls.length, 1)
  finish()
  await tick()
  assert.equal(task().status, 'waiting', 'a stale list from before the POST is insufficient')
  assert.deepEqual(task().request, request)
  assert.equal(task().topicName, 'HBM 시장')
  clearTopicWeeklyReportTask(client)
  assert.equal(task().status, 'waiting', 'changing or closing a dialog cannot discard active list tracking')
  await client.fetchQuery({ queryKey: ['reports', 'list', 'RUN'], queryFn: async () => ({ content: [{ id: 301 }] }) })
  assert.equal(task().status, 'waiting', 'another report scope cannot complete this task')
  await client.fetchQuery({ queryKey: ['reports', 'list', 'WEEKLY'], queryFn: async () => ({ content: [] }) })
  assert.equal(task().status, 'waiting')
  await client.fetchQuery({ queryKey: ['reports', 'list', 'WEEKLY'], queryFn: async () => ({ content: [{ id: 301 }] }) })
  assert.equal(task().status, 'ready')
  assert.deepEqual(task().result, { reportId: 301, created: true, reportReady: true })
  assert.deepEqual(calls, [{ url: '/api/news/reports/weekly', body: request }])
  clearTopicWeeklyReportTask(client)
  assert.equal(task(), null)
})

test('a concurrent pending result does not become ready from cache writes or failed list reads', async context => {
  const { client, task } = setup(context)
  let requests = 0
  context.mock.method(globalThis, 'fetch', async () => {
    requests++
    return success({ reportId: 301, created: false, reportReady: false })
  })
  startTopicWeeklyReportTask(client, request, 'HBM 시장')
  await tick()
  assert.equal(task().status, 'waiting')
  client.setQueryData(['reports', 'list', 'WEEKLY'], { content: [{ id: 301 }] })
  assert.equal(task().status, 'waiting', 'manual cache writes are not a completion check')
  await assert.rejects(client.fetchQuery({ queryKey: ['reports', 'list', 'WEEKLY'], queryFn: async () => { throw new Error('offline') } }))
  assert.equal(task().status, 'waiting')
  assert.equal(startTopicWeeklyReportTask(client, request, 'HBM 시장'), false)
  assert.equal(requests, 1)
  await client.fetchQuery({ queryKey: ['reports', 'list', 'WEEKLY'], queryFn: async () => ({ content: [{ id: 301 }] }) })
  assert.deepEqual(task().result, { reportId: 301, created: false, reportReady: true })
  assert.equal(task().status, 'ready')
})

test('a failed request remains recoverable after closing and never automatically repeats the POST', async context => {
  const { client, task } = setup(context)
  let requests = 0
  context.mock.method(globalThis, 'fetch', async () => {
    requests++
    return new Response(JSON.stringify({ isSuccess: false, code: 'REPORT409',
      message: '선택한 주제의 수집·분석이 진행 중입니다. 완료 후 다시 시도해 주세요.', result: {} }), { status: 409 })
  })
  startTopicWeeklyReportTask(client, request, 'HBM 시장')
  await tick()
  assert.equal(requests, 1)
  assert.equal(task().status, 'error')
  assert.deepEqual(task().request, request)
  assert.equal(task().topicName, 'HBM 시장')
  assert.match(task().error, /수집·분석이 진행 중/)
  assert.equal(client.getMutationCache().getAll()[0].options.retry, false)
  assert.equal(startTopicWeeklyReportTask(client, request, 'HBM 시장'), true, 'only an explicit new submission retries')
  await tick()
  assert.equal(requests, 2)
  clearTopicWeeklyReportTask(client)
  assert.equal(task(), null)
})

test('explicitly releasing list tracking frees the slot without cancelling or reposting the old generation', async context => {
  const { client, task } = setup(context)
  let requests = 0
  context.mock.method(globalThis, 'fetch', async () => success({ reportId: 300 + ++requests, created: false, reportReady: false }))
  startTopicWeeklyReportTask(client, request, 'HBM 시장')
  await tick()
  releaseTopicWeeklyReportTask(client)
  assert.equal(task(), null)
  assert.equal(requests, 1)
  assert.equal(startTopicWeeklyReportTask(client, { topicId: 30, weekStartDate: '2026-09-14' }, '장비 시장'), true)
  await tick()
  await client.fetchQuery({ queryKey: ['reports', 'list', 'WEEKLY'], queryFn: async () => ({ content: [{ id: 301 }] }) })
  assert.equal(task().status, 'waiting', 'the released report cannot complete another report task')
  assert.equal(task().request.topicId, 30)
  await client.fetchQuery({ queryKey: ['reports', 'list', 'WEEKLY'], queryFn: async () => ({ content: [{ id: 302 }] }) })
  assert.equal(task().status, 'ready')
  assert.equal(requests, 2)
})
