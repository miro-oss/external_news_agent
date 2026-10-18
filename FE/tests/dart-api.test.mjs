import assert from 'node:assert/strict'
import test from 'node:test'
import { environmentManager, focusManager, QueryClient, QueryObserver } from '@tanstack/react-query'
import { ApiError } from '../src/api/client.ts'
import { DART_POLL_WINDOW, dartCompanySearchOptions, dartPollInterval, reportDartCompanies, reportDartKey, reportDartOptions, retryReportDartOptions } from '../src/api/dart.ts'

const company = { corpCode: '00126380', corpName: '삼성전자', stockCode: '005930' }
const envelope = result => new Response(JSON.stringify({ isSuccess: true, code: 'COMMON200', message: '성공입니다.', result }))
const report = status => ({ reportId: 17, companies: [{ company, status, message: status, data: null }] })
const settle = () => new Promise(resolve => setImmediate(resolve))
function clientFor(context) { const client = new QueryClient(); context.after(() => client.clear()); return client }

test('company search trims and encodes input, keeps codes as strings, and forwards cancellation', async context => {
  const options = dartCompanySearchOptions(' 삼성 & SDI ', 2)
  const controller = new AbortController()
  const calls = []
  const result = { status: 'READY', message: '공시 회사를 검색했습니다.', content: [company], page: 2, size: 20, totalElements: 41, totalPages: 3, hasNext: false }
  context.mock.method(globalThis, 'fetch', async (url, init) => { calls.push({ url, signal: init.signal }); return envelope(result) })
  assert.deepEqual(options.queryKey, ['dart-companies', '삼성 & SDI', 2])
  assert.deepEqual(await options.queryFn({ signal: controller.signal }), result)
  const url = new URL(calls[0].url, 'http://localhost')
  assert.equal(url.pathname, '/api/news/dart/companies')
  assert.equal(url.searchParams.get('query'), '삼성 & SDI')
  assert.equal(url.searchParams.get('page'), '2')
  assert.equal(url.searchParams.get('size'), '20')
  assert.equal(calls[0].signal, controller.signal)
  for (const query of ['', ' ', '삼', 'a'.repeat(101)]) assert.equal(dartCompanySearchOptions(query).enabled, false)
  assert.equal(dartCompanySearchOptions('삼성').enabled, true)
})

test('late search responses cannot replace a newer query and changing queries clears previous results', async context => {
  const client = clientFor(context)
  const oldSearch = Promise.withResolvers()
  context.mock.method(globalThis, 'fetch', async url => {
    const query = new URL(url, 'http://localhost').searchParams.get('query')
    if (query === '삼성') return oldSearch.promise
    return envelope({ status: 'READY', content: [{ ...company, corpCode: '00164779', corpName: 'SK하이닉스' }] })
  })
  const oldOptions = dartCompanySearchOptions('삼성')
  const newOptions = dartCompanySearchOptions('하이닉스')
  const oldRead = client.fetchQuery(oldOptions)
  const observer = new QueryObserver(client, newOptions)
  assert.equal(observer.getCurrentResult().data, undefined)
  const remove = observer.subscribe(() => {})
  context.after(remove)
  await client.fetchQuery(newOptions)
  oldSearch.resolve(envelope({ status: 'READY', content: [company] }))
  await oldRead
  assert.equal(observer.getCurrentResult().data.content[0].corpName, 'SK하이닉스')
  assert.equal(client.getQueryData(oldOptions.queryKey).content[0].corpName, '삼성전자')
})

test('legacy RUN snapshots retain stable company deduplication and null support', () => {
  const another = { ...company, corpCode: '00164779', corpName: 'SK하이닉스' }
  const collectionContexts = [{ topics: [{ dartCompany: company }, {}, { dartCompany: null }] }, { topics: [{ dartCompany: company }, { dartCompany: another }] }]
  assert.deepEqual(reportDartCompanies({ reportScope: 'RUN', collectionContexts }), [company, another])
  for (const reportScope of ['DAILY', 'WEEKLY']) assert.deepEqual(reportDartCompanies({ reportScope, collectionContexts }), [])
  assert.deepEqual(reportDartCompanies({ reportScope: 'RUN' }), [])
  assert.deepEqual(reportDartCompanies({ reportScope: 'RUN', collectionContexts: [{ topics: [{}] }] }), [])
})

test('RUN and topic weekly reports use explicit company selections, including an authoritative empty selection', () => {
  const historical = { ...company, corpCode: '00164779', corpName: 'SK하이닉스' }
  const collectionContexts = [{ topics: [{ topicId: 29, dartCompany: historical }] }]
  for (const reportScope of ['RUN', 'WEEKLY']) {
    const report = { reportScope, topicId: reportScope === 'WEEKLY' ? 29 : null, collectionContexts }
    assert.deepEqual(reportDartCompanies({ ...report, dartCompanies: [company] }), [company])
    assert.deepEqual(reportDartCompanies({ ...report, dartCompanies: [] }), [])
  }
  assert.deepEqual(reportDartCompanies({ reportScope: 'WEEKLY', topicId: 29, collectionContexts }), [])
})

test('aggregate reports cannot opt into DART through companies or source collection snapshots', () => {
  for (const reportScope of ['DAILY', 'WEEKLY']) {
    assert.deepEqual(reportDartCompanies({ reportScope, topicId: null, dartCompanies: [company],
      collectionContexts: [{ topics: [{ dartCompany: company }] }] }), [])
  }
})

test('polls only pending data for a bounded window; completion, disabled, errors, or no companies stop polling', () => {
  assert.equal(dartPollInterval(report('PENDING'), 100, 99), 15_000)
  assert.equal(dartPollInterval(report('PENDING'), 100, 100), false)
  for (const status of ['READY', 'PARTIAL', 'DISABLED', 'FAILED']) assert.equal(dartPollInterval(report(status), 100, 0), false)
  assert.equal(dartPollInterval({ reportId: 17, companies: [] }, 100, 0), false)
  assert.equal(dartPollInterval(undefined, 100, 0), false)
  assert.equal(reportDartOptions(17, Infinity).refetchInterval({ state: { status: 'error', data: report('PENDING') } }), false)
})

test('background observation polls GET to completion without triggering POST', async context => {
  const server = environmentManager.isServer()
  environmentManager.setIsServer(() => false)
  context.after(() => { environmentManager.setIsServer(() => server); focusManager.setFocused(undefined) })
  context.mock.timers.enable({ apis: ['setInterval', 'setTimeout', 'Date'], now: 1_000_000 })
  const client = clientFor(context), calls = []
  let status = 'PENDING'
  context.mock.method(globalThis, 'fetch', async (url, init) => { calls.push({ url, method: init.method ?? 'GET' }); return envelope(report(status)) })
  const observer = new QueryObserver(client, reportDartOptions(17, Date.now() + DART_POLL_WINDOW))
  const remove = observer.subscribe(() => {})
  context.after(remove)
  await settle()
  assert.equal(calls.length, 1)
  context.mock.timers.tick(15_000); await settle()
  assert.equal(calls.length, 2)
  status = 'READY'
  context.mock.timers.tick(15_000); await settle()
  assert.equal(calls.length, 3)
  context.mock.timers.tick(60_000); await settle()
  assert.equal(calls.length, 3)
  assert.ok(calls.every(call => call.method === 'GET' && call.url === '/api/news/reports/17/dart'))
})

test('explicit retry has no request body, cancels old reads, and updates only the original report', async context => {
  const client = clientFor(context), oldRead = Promise.withResolvers(), calls = []
  client.setQueryData(reportDartKey(17), report('FAILED'))
  client.setQueryData(reportDartKey(18), { ...report('READY'), reportId: 18 })
  context.mock.method(globalThis, 'fetch', async (url, init) => {
    calls.push({ url, method: init.method, body: init.body })
    return init.method === 'POST' ? envelope(report('PENDING')) : oldRead.promise
  })
  const pendingRead = client.fetchQuery({ ...reportDartOptions(17, Infinity), staleTime: 0 }).catch(() => null)
  await client.getMutationCache().build(client, retryReportDartOptions(client, 17)).execute()
  oldRead.resolve(envelope(report('FAILED')))
  await pendingRead
  assert.deepEqual(calls[1], { url: '/api/news/reports/17/dart/retry', method: 'POST', body: undefined })
  assert.equal(client.getQueryData(reportDartKey(17)).companies[0].status, 'PENDING')
  assert.equal(client.getQueryData(reportDartKey(18)).companies[0].status, 'READY')
})

test('errors retain specified messages with no automatic retry', async context => {
  const client = clientFor(context)
  let attempts = 0
  context.mock.method(globalThis, 'fetch', async () => {
    attempts++
    return new Response(JSON.stringify({ isSuccess: false, code: 'REPORT404', message: '보고서를 찾을 수 없습니다.', result: {} }), { status: 404 })
  })
  await assert.rejects(client.fetchQuery(reportDartOptions(17, Infinity)), error => error instanceof ApiError && error.code === 'REPORT404')
  assert.equal(attempts, 1)
})
