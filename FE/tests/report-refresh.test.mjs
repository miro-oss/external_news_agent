import assert from 'node:assert/strict'
import test from 'node:test'
import { QueryClient, QueryObserver } from '@tanstack/react-query'
import { refreshReportReading } from '../src/features/reports/reportRefresh.ts'

const key = ['reports', 17, 'changes']
const ready = { status: 'READY', items: [{ title: '오류가 확인된 이슈' }] }
const unavailable = { status: 'UNAVAILABLE', items: [] }

function observe(t, client, queryFn) {
  const observer = new QueryObserver(client, { queryKey: key, queryFn, staleTime: Infinity, retry: false })
  t.after(observer.subscribe(() => {}))
  return observer
}

test('manual DAILY refresh awaits the report then replaces only its active comparison with the current visibility result', async t => {
  const client = new QueryClient()
  t.after(() => client.clear())
  client.setQueryData(key, ready)
  const otherKey = ['reports', 18, 'changes']
  client.setQueryData(otherKey, ready)
  client.setQueryData(['reports', 18], { id: 18, title: '다른 보고서' })
  client.setQueryData(['reports', 17, 'event-feedback'], { feedback: [] })
  const listKey = ['reports', 'list', 'DAILY']
  client.setQueryData(listKey, { content: [{ id: 17, findingCount: 1, highCount: 1 }] })
  const calls = []
  const list = new QueryObserver(client, { queryKey: listKey, staleTime: Infinity, retry: false,
    queryFn: async () => { calls.push('list'); return { content: [{ id: 17, findingCount: 0, highCount: 0 }] } } })
  t.after(list.subscribe(() => {}))
  const observer = observe(t, client, async () => { calls.push('comparison'); return unavailable })
  const reportRead = Promise.withResolvers()
  const refreshing = refreshReportReading(client, { id: 17, reportScope: 'DAILY' }, () => {
    calls.push('report'); return reportRead.promise
  })
  assert.deepEqual(calls, ['report'])
  reportRead.resolve({ data: { id: 17 } })
  assert.deepEqual(await refreshing, { data: { id: 17 } })
  assert.deepEqual(calls, ['report', 'list', 'comparison'])
  assert.deepEqual(observer.getCurrentResult().data, unavailable)
  assert.deepEqual(list.getCurrentResult().data, { content: [{ id: 17, findingCount: 0, highCount: 0 }] })
  assert.deepEqual(client.getQueryData(otherKey), ready)
  assert.equal(client.getQueryState(otherKey).isInvalidated, false)
  assert.equal(client.getQueryState(['reports', 18]).isInvalidated, false)
  assert.equal(client.getQueryState(['reports', 17, 'event-feedback']).isInvalidated, false)
})

test('an older comparison read cannot restore a rejected issue after manual refresh', async t => {
  const client = new QueryClient()
  t.after(() => client.clear())
  client.setQueryData(key, ready)
  const delayed = Promise.withResolvers()
  const oldRead = client.fetchQuery({ queryKey: key, queryFn: () => delayed.promise }).catch(() => null)
  const observer = observe(t, client, async () => unavailable)
  await refreshReportReading(client, { id: 17, reportScope: 'DAILY' }, async () => ({ data: { id: 17 } }))
  delayed.resolve(ready)
  await oldRead
  assert.deepEqual(observer.getCurrentResult().data, unavailable)
})

test('a comparison failure stays visible as a comparison error while the successful report refresh resolves', async t => {
  const client = new QueryClient()
  t.after(() => client.clear())
  client.setQueryData(key, ready)
  const failure = new Error('비교 결과를 불러오지 못했습니다.')
  const observer = observe(t, client, async () => { throw failure })
  await refreshReportReading(client, { id: 17, reportScope: 'DAILY' }, async () => ({ data: { id: 17 } }))
  assert.equal(observer.getCurrentResult().isError, true)
  assert.equal(observer.getCurrentResult().error, failure)
})

test('RUN and WEEKLY refreshes never fetch or invalidate daily comparisons', async t => {
  const client = new QueryClient()
  t.after(() => client.clear())
  client.setQueryData(key, ready)
  let comparisonReads = 0
  observe(t, client, async () => { comparisonReads++; return unavailable })
  for (const reportScope of ['RUN', 'WEEKLY']) {
    assert.equal(await refreshReportReading(client, { id: 17, reportScope }, async () => reportScope), reportScope)
  }
  assert.equal(comparisonReads, 0)
  assert.equal(client.getQueryState(key).isInvalidated, false)
  assert.deepEqual(client.getQueryData(key), ready)
})

test('failed main report refresh keeps its error and does not silently replace the comparison', async t => {
  const client = new QueryClient()
  t.after(() => client.clear())
  client.setQueryData(key, ready)
  let comparisonReads = 0
  observe(t, client, async () => { comparisonReads++; return unavailable })
  const failure = new Error('보고서를 새로고침하지 못했습니다.')
  await assert.rejects(refreshReportReading(client, { id: 17, reportScope: 'DAILY' }, async () => { throw failure }),
    error => error === failure)
  assert.equal(comparisonReads, 0)
  assert.deepEqual(client.getQueryData(key), ready)
})
