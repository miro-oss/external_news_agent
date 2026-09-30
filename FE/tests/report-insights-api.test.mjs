import assert from 'node:assert/strict'
import test from 'node:test'
import { QueryClient, QueryObserver } from '@tanstack/react-query'
import { ApiError } from '../src/api/client.ts'
import { generateReportInsightOptions, isReportInsightAbsent, reportInsightKey, reportInsightOptions, reportInsightSnapshotKey, selectReportInsight } from '../src/api/reportInsights.ts'
import { reportInsightFixture } from '../scripts/report-insight-fixtures.mjs'
import { readFile } from 'node:fs/promises'
const report = JSON.parse(await readFile(new URL('./fixtures/refactor-report.json', import.meta.url), 'utf8')).report
const snapshot = reportInsightSnapshotKey(report)
const envelope = result => new Response(JSON.stringify({ isSuccess: true, code: 'COMMON200', message: '성공입니다.', result }))
const fail = (code, message, status) => new Response(JSON.stringify({ isSuccess: false, code, message, result: {} }), { status })
function clientFor(context) { const client = new QueryClient(); context.after(() => client.clear()); return client }

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

test('absence is only COMMON404/404; lookup and generation errors preserve server details with no retry', async context => {
  const client = clientFor(context)
  assert.equal(isReportInsightAbsent(new ApiError('COMMON404', 'missing', 404)), true)
  for (const error of [new ApiError('REPORT404', 'missing', 404), new ApiError('COMMON404', 'missing', 500), new TypeError('network')])
    assert.equal(isReportInsightAbsent(error), false)
  for (const [code, message, status] of [['COMMON404', '저장된 리포트 관점 인사이트가 없습니다.', 404],
    ['COMMON409', '동일한 리포트 관점 인사이트 생성 요청이 진행 중입니다. 잠시 후 다시 확인해주세요.', 409],
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
