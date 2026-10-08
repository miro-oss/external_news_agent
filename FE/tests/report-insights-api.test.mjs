import assert from 'node:assert/strict'
import test from 'node:test'
import { environmentManager, focusManager, MutationObserver, QueryClient, QueryObserver } from '@tanstack/react-query'
import { ApiError } from '../src/api/client.ts'
import { generateReportInsightOptions, isReportInsightAbsent, isReportInsightPreparing, reportInsightKey, reportInsightOptions, reportInsightSnapshotKey, selectReportInsight } from '../src/api/reportInsights.ts'
import { reportInsightFixture } from '../scripts/report-insight-fixtures.mjs'
import { readFile } from 'node:fs/promises'
const report = JSON.parse(await readFile(new URL('./fixtures/refactor-report.json', import.meta.url), 'utf8')).report
const snapshot = reportInsightSnapshotKey(report)
const envelope = result => new Response(JSON.stringify({ isSuccess: true, code: 'COMMON200', message: '성공입니다.', result }))
const fail = (code, message, status) => new Response(JSON.stringify({ isSuccess: false, code, message, result: {} }), { status })
const preparingMessage = '동일한 리포트 관점 인사이트 생성 요청이 진행 중입니다. 잠시 후 다시 확인해주세요.'
function clientFor(context) { const client = new QueryClient(); context.after(() => client.clear()); return client }
const settle = () => new Promise(resolve => setImmediate(resolve))
function pollingClock(context) {
  const server = environmentManager.isServer()
  environmentManager.setIsServer(() => false)
  context.after(() => { environmentManager.setIsServer(() => server); focusManager.setFocused(undefined) })
  context.mock.timers.enable({ apis: ['setInterval', 'setTimeout', 'Date'], now: 1_000_000 })
  return async milliseconds => { context.mock.timers.tick(milliseconds); await settle() }
}

test('report/audience/snapshot are independent, and stored lookup sends only one audience without generation', async context => {
  const client = clientFor(context), calls = []
  context.mock.method(globalThis, 'fetch', async (url, init) => {
    calls.push({ url, method: init.method ?? 'GET' })
    const audience = new URL(url, 'https://example.invalid').searchParams.get('audience')
    return envelope(reportInsightFixture(report, audience))
  })
  for (const audience of ['CHIP_MAKER', 'EQUIPMENT_MAKER']) {
    const result = await client.fetchQuery(reportInsightOptions(report.id, audience, snapshot))
    assert.equal(selectReportInsight(result, report.id, audience).audience, audience)
  }
  assert.deepEqual(calls, ['CHIP_MAKER', 'EQUIPMENT_MAKER'].map(audience => ({
    url: `/api/news/reports/${report.id}/insights?audience=${audience}`, method: 'GET' })))
  assert.notDeepEqual(reportInsightKey(report.id, 'CHIP_MAKER', snapshot), reportInsightKey(report.id + 1, 'CHIP_MAKER', snapshot))
  const changed = structuredClone(report); changed.findings[0].keyPoints[0].text += ' 새 근거'
  assert.notEqual(reportInsightSnapshotKey(changed), snapshot)
  assert.equal(client.getQueryData(reportInsightKey(report.id, 'CHIP_MAKER', reportInsightSnapshotKey(changed))), undefined)
})

test('an explicit generation posts a singleton and writes only its original report/audience/snapshot, refreshing usage', async context => {
  const client = clientFor(context), calls = [], invalidations = []
  context.mock.method(client, 'invalidateQueries', options => { invalidations.push(options); return Promise.resolve() })
  context.mock.method(globalThis, 'fetch', async (url, init) => {
    calls.push({ url, method: init.method, body: JSON.parse(init.body) })
    return envelope({ ...reportInsightFixture(report, 'EQUIPMENT_MAKER'), cached: false })
  })
  const result = await client.getMutationCache().build(client, generateReportInsightOptions(client))
    .execute({ reportId: report.id, audience: 'EQUIPMENT_MAKER', snapshot })
  assert.deepEqual(calls, [{ url: `/api/news/reports/${report.id}/insights`, method: 'POST', body: { audiences: ['EQUIPMENT_MAKER'] } }])
  assert.deepEqual(client.getQueryData(reportInsightKey(report.id, 'EQUIPMENT_MAKER', snapshot)), result)
  for (const key of [reportInsightKey(report.id, 'CHIP_MAKER', snapshot), reportInsightKey(report.id + 1, 'EQUIPMENT_MAKER', snapshot),
    reportInsightKey(report.id, 'EQUIPMENT_MAKER', 'new-snapshot')]) assert.equal(client.getQueryData(key), undefined)
  assert.deepEqual(invalidations, [{ queryKey: ['usage', 'llm'] }])
})

test('absence is only COMMON404/404; real lookup and generation errors preserve server details with no retry', async context => {
  const client = clientFor(context)
  assert.equal(isReportInsightAbsent(new ApiError('COMMON404', 'missing', 404)), true)
  for (const error of [new ApiError('REPORT404', 'missing', 404), new ApiError('COMMON404', 'missing', 500), new TypeError('network')])
    assert.equal(isReportInsightAbsent(error), false)
  for (const [code, message, status] of [['COMMON404', '저장된 리포트 관점 인사이트가 없습니다.', 404],
    ['COMMON409', '리포트 관점 인사이트 기능이 현재 비활성화되어 있습니다.', 409],
    ['QUOTA429', '인사이트 크레딧을 모두 사용했습니다.', 429], ['COMMON500', '리포트 관점 인사이트 생성에 실패했습니다.', 500]]) {
    let calls = 0
    context.mock.method(globalThis, 'fetch', async () => { calls++; return fail(code, message, status) })
    await assert.rejects(client.fetchQuery(reportInsightOptions(report.id, 'CHIP_MAKER', snapshot)),
      error => error instanceof ApiError && error.message === message && error.code === code)
    assert.equal(calls, 1)
    calls = 0
    await assert.rejects(client.getMutationCache().build(client, generateReportInsightOptions(client))
      .execute({ reportId: report.id, audience: 'CHIP_MAKER', snapshot }),
      error => error instanceof ApiError && error.message === message)
    assert.equal(calls, 1)
  }
})

test('only the exact COMMON409 automatic-preparation response is polled', () => {
  assert.equal(isReportInsightPreparing(new ApiError('COMMON409', preparingMessage, 409)), true)
  for (const error of [new ApiError('COMMON409', preparingMessage, 500), new ApiError('COMMON404', preparingMessage, 409),
    new ApiError('COMMON409', '리포트 관점 인사이트 기능이 현재 비활성화되어 있습니다.', 409),
    new ApiError('COMMON409', '이 리포트는 인사이트에 사용할 검증된 근거가 없습니다.', 409), new Error(preparingMessage)])
    assert.equal(isReportInsightPreparing(error), false)
})

test('automatic preparation polls stored GETs until ready without generation or usage invalidation', async context => {
  const advance = pollingClock(context)
  const client = clientFor(context), calls = [], states = [], invalidations = []
  context.mock.method(client, 'invalidateQueries', options => { invalidations.push(options); return Promise.resolve() })
  context.mock.method(globalThis, 'fetch', async (url, init) => {
    calls.push({ url, method: init.method ?? 'GET' })
    return calls.length < 3 ? fail('COMMON409', preparingMessage, 409) : envelope(reportInsightFixture(report))
  })
  const observer = new QueryObserver(client, reportInsightOptions(report.id, 'CHIP_MAKER', snapshot))
  context.after(observer.subscribe(state => states.push(state)))
  await settle()
  await advance(10_000)
  await advance(10_000)
  assert.equal(observer.getCurrentResult().data.insights[0].audience, 'CHIP_MAKER')
  assert.ok(states.some(state => isReportInsightPreparing(state.error)))
  assert.equal(observer.getCurrentResult().isSuccess, true)
  await advance(60_000)
  assert.equal(calls.length, 3)
  assert.ok(calls.every(call => call.method === 'GET' && call.url.endsWith('?audience=CHIP_MAKER')))
  assert.deepEqual(invalidations, [])
})

test('a terminal missing result after preparation stops polling and remains eligible for explicit retry', async context => {
  const advance = pollingClock(context)
  const client = clientFor(context), calls = []
  context.mock.method(globalThis, 'fetch', async (_url, init) => {
    calls.push(init.method ?? 'GET')
    return calls.length === 1 ? fail('COMMON409', preparingMessage, 409)
      : fail('COMMON404', '저장된 리포트 관점 인사이트가 없습니다.', 404)
  })
  const observer = new QueryObserver(client, reportInsightOptions(report.id, 'CHIP_MAKER', snapshot))
  context.after(observer.subscribe(() => {}))
  await settle()
  await advance(10_000)
  assert.ok(isReportInsightAbsent(observer.getCurrentResult().error))
  await advance(60_000)
  assert.deepEqual(calls, ['GET', 'GET'])
})

test('a queue longer than fifteen minutes continues at a slower cadence and shows the eventual result automatically', async context => {
  const advance = pollingClock(context)
  const client = clientFor(context), calls = []
  let ready = false
  context.mock.method(globalThis, 'fetch', async (_url, init) => {
    calls.push(init.method ?? 'GET')
    return ready ? envelope(reportInsightFixture(report)) : fail('COMMON409', preparingMessage, 409)
  })
  const observer = new QueryObserver(client, reportInsightOptions(report.id, 'CHIP_MAKER', snapshot))
  context.after(observer.subscribe(() => {}))
  await settle()
  for (let poll = 0; poll < 90; poll++) await advance(10_000)
  assert.equal(calls.length, 91)
  assert.ok(isReportInsightPreparing(observer.getCurrentResult().error))
  await advance(29_999)
  assert.equal(calls.length, 91)
  await advance(1)
  assert.equal(calls.length, 92)
  ready = true
  await advance(30_000)
  assert.equal(observer.getCurrentResult().data.insights[0].audience, 'CHIP_MAKER')
  assert.equal(observer.getCurrentResult().isSuccess, true)
  await advance(60_000)
  assert.equal(calls.length, 93)
  assert.ok(calls.every(method => method === 'GET'))
})

test('leaving a report cancels an in-flight lookup and all future automatic polls', async context => {
  const advance = pollingClock(context)
  const client = clientFor(context)
  let calls = 0, signal
  context.mock.method(globalThis, 'fetch', async (_url, init) => {
    calls++
    signal = init.signal
    return calls === 1 ? fail('COMMON409', preparingMessage, 409)
      : new Promise((_resolve, reject) => signal.addEventListener('abort', () => reject(signal.reason), { once: true }))
  })
  const observer = new QueryObserver(client, reportInsightOptions(report.id, 'CHIP_MAKER', snapshot))
  const stop = observer.subscribe(() => {})
  context.after(stop)
  await settle()
  await advance(10_000)
  assert.equal(calls, 2)
  stop()
  assert.equal(signal.aborted, true)
  await advance(60_000)
  assert.equal(calls, 2)
})

test('switching perspectives while preparing cancels the old poll and reads only the selected stored result', async context => {
  const advance = pollingClock(context)
  const client = clientFor(context), calls = []
  context.mock.method(globalThis, 'fetch', async (url, init) => {
    const audience = new URL(url, 'https://example.invalid').searchParams.get('audience')
    calls.push({ audience, method: init.method ?? 'GET' })
    if (audience === 'CHIP_MAKER') {
      return fail('COMMON409', preparingMessage, 409)
    }
    return envelope(reportInsightFixture(report, audience))
  })
  const observer = new QueryObserver(client, reportInsightOptions(report.id, 'CHIP_MAKER', snapshot))
  context.after(observer.subscribe(() => {}))
  await settle()
  observer.setOptions(reportInsightOptions(report.id, 'IT_INFRA', snapshot))
  assert.equal(observer.getCurrentResult().data, undefined)
  await settle()
  await advance(60_000)
  assert.equal(observer.getCurrentResult().data.insights[0].audience, 'IT_INFRA')
  assert.equal(calls.filter(call => call.audience === 'CHIP_MAKER').length, 1)
  assert.ok(calls.every(call => call.method === 'GET'))
})

test('background preparation pauses polling and checks promptly on return without refetching terminal errors', async context => {
  const advance = pollingClock(context)
  const client = clientFor(context)
  client.mount()
  context.after(() => client.unmount())
  let calls = 0, ready = false
  context.mock.method(globalThis, 'fetch', async () => {
    calls++
    return ready ? fail('COMMON500', '조회 실패', 500) : fail('COMMON409', preparingMessage, 409)
  })
  const observer = new QueryObserver(client, reportInsightOptions(report.id, 'CHIP_MAKER', snapshot))
  context.after(observer.subscribe(() => {}))
  await settle()
  focusManager.setFocused(false)
  await advance(60_000)
  assert.equal(calls, 1)
  ready = true
  focusManager.setFocused(true)
  await settle()
  assert.equal(calls, 2)
  assert.equal(observer.getCurrentResult().error.code, 'COMMON500')
  await advance(60_000)
  focusManager.setFocused(false)
  focusManager.setFocused(true)
  await settle()
  assert.equal(calls, 2)
})

test('an explicit retry racing an automatic job refreshes stored lookup without repeating its POST', async context => {
  const client = clientFor(context), calls = [], invalidations = []
  context.mock.method(client, 'invalidateQueries', options => { invalidations.push(options); return Promise.resolve() })
  context.mock.method(globalThis, 'fetch', async (_url, init) => {
    calls.push(init.method)
    return fail('COMMON409', preparingMessage, 409)
  })
  await assert.rejects(client.getMutationCache().build(client, generateReportInsightOptions(client))
    .execute({ reportId: report.id, audience: 'CHIP_MAKER', snapshot }), isReportInsightPreparing)
  assert.deepEqual(calls, ['POST'])
  assert.deepEqual(invalidations, [{ queryKey: reportInsightKey(report.id, 'CHIP_MAKER', snapshot), exact: true }, { queryKey: ['usage', 'llm'] }])
})

test('responses for another report or missing requested perspective are rejected without populating the requested cache', async context => {
  const client = clientFor(context)
  for (const wrong of [reportInsightFixture({ ...report, id: report.id + 1 }), reportInsightFixture(report, 'IT_INFRA')]) {
    context.mock.method(globalThis, 'fetch', async () => envelope(wrong))
    await assert.rejects(client.fetchQuery(reportInsightOptions(report.id, 'CHIP_MAKER', snapshot)),
      error => error instanceof ApiError && error.code === 'CONTRACT')
    assert.equal(client.getQueryData(reportInsightKey(report.id, 'CHIP_MAKER', snapshot)), undefined)
  }
})

test('changing a perspective performs GET only and does not copy previous audience data', async context => {
  const client = clientFor(context), calls = []
  context.mock.method(globalThis, 'fetch', async (url, init) => {
    calls.push({ url, method: init.method ?? 'GET' })
    const audience = new URL(url, 'https://example.invalid').searchParams.get('audience')
    return envelope(reportInsightFixture(report, audience))
  })
  await client.fetchQuery(reportInsightOptions(report.id, 'CHIP_MAKER', snapshot))
  const observer = new QueryObserver(client, reportInsightOptions(report.id, 'CHIP_MAKER', snapshot))
  const stop = observer.subscribe(() => {}); context.after(stop)
  observer.setOptions(reportInsightOptions(report.id, 'IT_INFRA', snapshot))
  assert.equal(observer.getCurrentResult().data, undefined)
  await client.fetchQuery(reportInsightOptions(report.id, 'IT_INFRA', snapshot))
  assert.equal(observer.getCurrentResult().data.insights[0].audience, 'IT_INFRA')
  assert.ok(calls.every(call => call.method === 'GET'))
})

test('a slow stored-result read cannot overwrite a newly generated result', async context => {
  const client = clientFor(context)
  let releaseRead, markReadStarted
  const readStarted = new Promise(resolve => { markReadStarted = resolve })
  const prior = reportInsightFixture(report)
  prior.insights[0].headline = '이전 저장 결과'
  const generated = { ...reportInsightFixture(report), cached: false }
  generated.insights[0].headline = '명시적으로 새로 생성한 결과'
  context.mock.method(globalThis, 'fetch', async (_url, init) => {
    if (init.method === 'POST') return envelope(generated)
    markReadStarted()
    return new Promise(resolve => { releaseRead = () => resolve(envelope(prior)) })
  })
  const read = client.fetchQuery(reportInsightOptions(report.id, 'CHIP_MAKER', snapshot)).catch(() => null)
  await readStarted
  await client.getMutationCache().build(client, generateReportInsightOptions(client))
    .execute({ reportId: report.id, audience: 'CHIP_MAKER', snapshot })
  releaseRead()
  await read
  assert.equal(client.getQueryData(reportInsightKey(report.id, 'CHIP_MAKER', snapshot)).insights[0].headline, generated.insights[0].headline)
})

test('four manual generations share two FIFO slots across panel navigation and retain their original caches', async context => {
  const client = clientFor(context), calls = []
  let active = 0, peak = 0
  context.mock.method(globalThis, 'fetch', (url, init) => new Promise(resolve => {
    const audience = JSON.parse(init.body).audiences[0]
    active++
    peak = Math.max(peak, active)
    calls.push({ url, audience, finish() {
      active--
      resolve(envelope({ ...reportInsightFixture(report, audience), cached: false }))
    } })
  }))
  const audiences = ['CHIP_MAKER', 'EQUIPMENT_MAKER', 'IT_INFRA', 'MARKET_INVESTOR']
  const observers = audiences.map(() => new MutationObserver(client, generateReportInsightOptions(client)))
  const unsubscribe = observers.map(observer => observer.subscribe(() => {}))
  context.after(() => unsubscribe.forEach(stop => stop()))
  const pending = observers.map((observer, index) => observer.mutate({ reportId: report.id, audience: audiences[index], snapshot }))
  await settle()
  assert.deepEqual(calls.map(call => call.audience), audiences.slice(0, 2))
  assert.ok(observers.every(observer => observer.getCurrentResult().isPending))
  // Switching panels removes observers but must preserve both queued and running work.
  unsubscribe[0]()
  unsubscribe[2]()
  calls[1].finish()
  await settle()
  assert.deepEqual(calls.map(call => call.audience), audiences.slice(0, 3))
  assert.equal(active, 2)
  calls[0].finish()
  await settle()
  assert.deepEqual(calls.map(call => call.audience), audiences)
  calls[2].finish()
  calls[3].finish()
  const results = await Promise.all(pending)
  assert.equal(peak, 2)
  assert.equal(active, 0)
  assert.ok(calls.every(call => call.url === `/api/news/reports/${report.id}/insights`))
  audiences.forEach((audience, index) => {
    assert.deepEqual(client.getQueryData(reportInsightKey(report.id, audience, snapshot)), results[index])
    assert.equal(client.getQueryData(reportInsightKey(report.id, audience, 'new-snapshot')), undefined)
    assert.equal(client.getQueryData(reportInsightKey(report.id + 1, audience, snapshot)), undefined)
  })
})

test('failed generation and invalid responses release slots without retrying or affecting unrelated cached results', async context => {
  const client = clientFor(context), calls = []
  const saved = reportInsightFixture(report)
  client.setQueryData(reportInsightKey(report.id, 'CHIP_MAKER', 'prior-snapshot'), saved)
  context.mock.method(globalThis, 'fetch', (_url, init) => new Promise(resolve => {
    calls.push({ audience: JSON.parse(init.body).audiences[0], resolve })
  }))
  const audiences = ['CHIP_MAKER', 'EQUIPMENT_MAKER', 'IT_INFRA', 'MARKET_INVESTOR']
  const pending = audiences.map(audience => client.getMutationCache().build(client, generateReportInsightOptions(client))
    .execute({ reportId: report.id, audience, snapshot }))
  const settled = Promise.allSettled(pending)
  await settle()
  assert.equal(calls.length, 2)
  calls[0].resolve(fail('COMMON500', '리포트 관점 인사이트 생성에 실패했습니다.', 500))
  calls[1].resolve(envelope(reportInsightFixture({ ...report, id: report.id + 1 }, 'EQUIPMENT_MAKER')))
  await settle()
  assert.deepEqual(calls.map(call => call.audience), audiences)
  calls[2].resolve(envelope(reportInsightFixture(report, 'IT_INFRA')))
  calls[3].resolve(envelope(reportInsightFixture(report, 'MARKET_INVESTOR')))
  const results = await settled
  assert.deepEqual(results.map(result => result.status), ['rejected', 'rejected', 'fulfilled', 'fulfilled'])
  assert.equal(results[0].reason.code, 'COMMON500')
  assert.equal(results[1].reason.code, 'CONTRACT')
  assert.equal(calls.length, 4)
  assert.deepEqual(client.getQueryData(reportInsightKey(report.id, 'CHIP_MAKER', 'prior-snapshot')), saved)
  for (const audience of audiences.slice(0, 2)) assert.equal(client.getQueryData(reportInsightKey(report.id, audience, snapshot)), undefined)
})

test('admission is shared across reports but separate QueryClients do not block each other', async context => {
  const first = clientFor(context), second = clientFor(context), calls = []
  context.mock.method(globalThis, 'fetch', (url, init) => new Promise(resolve => {
    const reportId = Number(url.match(/reports\/(\d+)\/insights/)[1])
    const audience = JSON.parse(init.body).audiences[0]
    calls.push({ reportId, finish: () => resolve(envelope(reportInsightFixture({ ...report, id: reportId }, audience))) })
  }))
  const start = (client, id) => client.getMutationCache().build(client, generateReportInsightOptions(client))
    .execute({ reportId: id, audience: 'CHIP_MAKER', snapshot })
  const pending = [start(first, 101), start(first, 102), start(first, 103), start(second, 201)]
  await settle()
  assert.deepEqual(calls.map(call => call.reportId), [101, 102, 201])
  calls[0].finish()
  await settle()
  assert.deepEqual(calls.map(call => call.reportId), [101, 102, 201, 103])
  calls.slice(1).forEach(call => call.finish())
  await Promise.all(pending)
})
