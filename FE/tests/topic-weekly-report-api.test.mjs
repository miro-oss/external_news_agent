import assert from 'node:assert/strict'
import test from 'node:test'
import { QueryClient } from '@tanstack/react-query'
import { ApiError } from '../src/api/client.ts'
import { createTopicWeeklyReportOptions } from '../src/api/topicWeeklyReport.ts'

const envelope = result => new Response(JSON.stringify({ isSuccess: true, code: 'COMMON200', message: '성공입니다.', result }))

test('creating a topic weekly report posts only the specified topic and Monday without retrying', async context => {
  const client = new QueryClient()
  context.after(() => client.clear())
  const calls = []
  context.mock.method(globalThis, 'fetch', async (url, init) => {
    calls.push({ url, method: init.method, body: JSON.parse(init.body) })
    return envelope({ reportId: 301, created: true, reportReady: true })
  })
  const result = await client.getMutationCache().build(client, createTopicWeeklyReportOptions())
    .execute({ topicId: 29, weekStartDate: '2026-09-21', active: true, regenerate: true })
  assert.deepEqual(result, { reportId: 301, created: true, reportReady: true })
  assert.deepEqual(calls, [{ url: '/api/news/reports/weekly', method: 'POST', body: { topicId: 29, weekStartDate: '2026-09-21' } }])
})

test('a duplicate completed or pending result preserves the original ID without submitting again', async context => {
  let requests = 0
  context.mock.method(globalThis, 'fetch', async () => {
    requests++
    return envelope({ reportId: 301, created: false, reportReady: requests === 1 })
  })
  for (const reportReady of [true, false]) {
    assert.deepEqual(await createTopicWeeklyReportOptions().mutationFn({ topicId: 29, weekStartDate: '2026-09-21' }),
      { reportId: 301, created: false, reportReady })
  }
  assert.equal(requests, 2)
})

test('unfinished collection and missing evidence errors retain the server message and are never retried', async context => {
  const client = new QueryClient()
  context.after(() => client.clear())
  const messages = ['선택한 주제의 수집·분석이 진행 중입니다. 완료 후 다시 시도해 주세요.',
    '선택한 주제와 기간에 보고서를 만들 수 있는 분석 자료가 없습니다.']
  let requests = 0
  context.mock.method(globalThis, 'fetch', async () => new Response(JSON.stringify({ isSuccess: false, code: 'REPORT409',
    message: messages[requests++], result: {} }), { status: 409 }))
  for (const message of messages) {
    await assert.rejects(client.getMutationCache().build(client, createTopicWeeklyReportOptions())
      .execute({ topicId: 29, weekStartDate: '2026-09-21' }),
    error => error instanceof ApiError && error.code === 'REPORT409' && error.status === 409 && error.message === message)
  }
  assert.equal(requests, 2)
})
