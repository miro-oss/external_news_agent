import assert from 'node:assert/strict'
import test from 'node:test'
import { QueryClient } from '@tanstack/react-query'
import { reportChangesOptions } from '../src/api/reportChanges.ts'
import { groupReportChanges, reportDateGap, safeReportEvidenceUrl } from '../src/features/reports/reportChangesDisplay.ts'
import type { ReportChanges, ReportChangesStatus } from '../src/api/types.ts'

const result = (status: ReportChangesStatus): ReportChanges => ({ reportId: 117, reportDate: '2026-09-08',
  baseReportId: 116, baseReportDate: '2026-09-07', status, message: '상태 안내', scopeChanged: false, notes: [], items: [] })

test('comparison GET preserves its saved evidence without fetching articles', async context => {
  const client = new QueryClient()
  context.after(() => client.clear())
  const saved = { ...result('READY'), notes: ['당시 저장 자료'] }
  const calls: string[] = []
  context.mock.method(globalThis, 'fetch', async (input: string | URL | Request, init?: RequestInit) => {
    calls.push(String(input))
    assert.ok(init?.signal)
    return new Response(JSON.stringify({ isSuccess: true, code: 'COMMON200', message: '성공입니다.', result: saved }))
  })
  assert.deepEqual(await client.fetchQuery(reportChangesOptions(117)), saved)
  assert.deepEqual(calls, ['/api/news/reports/117/changes'])
})

test('only pending and running success results poll; terminal, failed, and long-running queries stop', context => {
  const client = new QueryClient()
  context.after(() => client.clear())
  const options = reportChangesOptions(117)
  const query = client.getQueryCache().build(client, options)
  const interval = options.refetchInterval
  assert.equal(typeof interval, 'function')
  if (typeof interval !== 'function') throw new Error('Expected status-dependent interval')
  for (const status of ['PENDING', 'RUNNING', 'READY', 'FAILED', 'NO_BASELINE', 'UNAVAILABLE', 'NOT_APPLICABLE'] as const) {
    query.setData(result(status))
    assert.equal(interval(query), ['PENDING', 'RUNNING'].includes(status) ? 5_000 : false, status)
  }
  query.setData(result('PENDING'))
  query.setState({ status: 'error' })
  assert.equal(interval(query), false, 'stale pending data must not continue polling after a request error')
  query.setState({ status: 'success', dataUpdateCount: 60 })
  assert.equal(interval(query), false, 'a stuck job eventually stops automatic polling')
  assert.equal(options.refetchIntervalInBackground, false)
})

test('a removed report surfaces REPORT404 without retrying', async context => {
  const client = new QueryClient()
  context.after(() => client.clear())
  let requests = 0
  context.mock.method(globalThis, 'fetch', async () => {
    requests++
    return new Response(JSON.stringify({ isSuccess: false, code: 'REPORT404', message: '보고서를 찾을 수 없습니다.', result: {} }), { status: 404 })
  })
  await assert.rejects(client.fetchQuery(reportChangesOptions(117)), { code: 'REPORT404', message: '보고서를 찾을 수 없습니다.' })
  assert.equal(requests, 1)
})

test('leaving a comparison cancels its pending network request', async context => {
  const client = new QueryClient()
  context.after(() => client.clear())
  let activeSignal: AbortSignal | null = null
  context.mock.method(globalThis, 'fetch', (_input: string | URL | Request, init?: RequestInit) => new Promise<Response>((_resolve, reject) => {
    activeSignal = init?.signal ?? null
    activeSignal?.addEventListener('abort', () => reject(activeSignal?.reason), { once: true })
  }))
  const pending = client.fetchQuery(reportChangesOptions(117))
  const rejection = assert.rejects(pending)
  await client.cancelQueries({ queryKey: ['reports', 117, 'changes'] })
  await rejection
  assert.equal((activeSignal as AbortSignal | null)?.aborted, true)
})

test('unchanged items stay counted but are separate from visible updates and uncertainty', () => {
  const item = { id: '1', title: '이슈', summary: '요약', previous: null,
    current: { issueId: 1, topicId: 1, title: '이슈', summary: '요약', claims: [] } }
  const groups = groupReportChanges([
    { ...item, type: 'UNCHANGED' }, { ...item, id: '2', type: 'UNDETERMINED' }, { ...item, id: '3', type: 'UPDATED' },
  ])
  assert.deepEqual(groups.visible.map(entry => entry.type), ['UNDETERMINED', 'UPDATED'])
  assert.equal(groups.unchanged.length, 1)
  assert.equal(groups.counts.UNCHANGED, 1)
  assert.equal(groups.counts.NEWLY_INCLUDED, 0)
})

test('calendar comparison is timezone independent and rejects invalid or unordered dates', () => {
  assert.equal(reportDateGap('2026-09-07', '2026-09-08'), 1)
  assert.equal(reportDateGap('2026-09-05', '2026-09-08'), 3)
  assert.equal(reportDateGap('2026-12-31', '2027-01-01'), 1)
  for (const date of [null, '', 'yesterday', '2026-02-30', '2026-09-08', '2026-09-09']) {
    assert.equal(reportDateGap(date, '2026-09-08'), null)
  }
})

test('only absolute HTTP(S) source links without embedded credentials are clickable', () => {
  for (const url of ['https://example.invalid/source', 'http://example.invalid/source']) assert.equal(safeReportEvidenceUrl(url), url)
  for (const url of ['javascript:alert(1)', 'data:text/html,bad', 'file:///secret', '//example.invalid', '/relative', '', 'https://user:password@example.invalid']) {
    assert.equal(safeReportEvidenceUrl(url), null)
  }
})
