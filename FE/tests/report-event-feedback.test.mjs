import assert from 'node:assert/strict'
import test from 'node:test'
import { setImmediate } from 'node:timers/promises'
import { ApiError } from '../src/api/client.ts'
import { loadReportEventFeedback, submitReportEventFeedback } from '../src/api/reportEventFeedback.ts'
import { matchReportFeedbackEvent, prepareReportEventFeedback, reportEventDraftError } from '../src/features/reports/eventFeedbackState.ts'
import { nextFeedbackPoll } from '../src/features/feedback/feedbackState.ts'
import { startReportEventFeedbackPolling } from '../src/features/reports/reportEventFeedbackPolling.ts'

const event = { title: '여러 기업의 공급 계획', summaryKo: '두 기업이 서로 다른 공급 일정을 발표했습니다.', significance: '후속 확인이 필요합니다.', sourceFindingIds: [501, 502, 503] }
const target = { eventKey: 'a'.repeat(64), eventIndex: 0, title: event.title, summary: event.summaryKo, significance: event.significance, sourceFindingIds: [...event.sourceFindingIds] }
const draft = { eventKey: target.eventKey, category: 'WRONG_CLUSTER', comment: '서로 다른 기업의 계약이 한 사건으로 묶였습니다.' }
const saved = { ...draft, id: 1, status: 'PENDING', verdict: null, diagnosis: null, createdAt: '2026-09-29T15:00:00+09:00' }
const context = { reportId: 17, events: [target], feedback: [] }
const success = (result, status = 200) => new Response(JSON.stringify({ isSuccess: true, code: `COMMON${status}`, message: '성공입니다.', result }), { status })

test('event binding requires the report, position, exact text and every source finding, including repeated-article findings', () => {
  assert.equal(matchReportFeedbackEvent(17, event, 0, context), target)
  assert.equal(matchReportFeedbackEvent(18, event, 0, context), null)
  assert.equal(matchReportFeedbackEvent(17, event, 1, context), null)
  assert.equal(matchReportFeedbackEvent(17, event, 0, null), null)
  for (const change of [{ title: '다른 사건' }, { summary: '수정한 요약' }, { significance: null },
    { sourceFindingIds: [501] }, { sourceFindingIds: [503, 502, 501] }, { sourceFindingIds: [501, 502, 504] }, { eventKey: '' }]) {
    assert.equal(matchReportFeedbackEvent(17, event, 0, { ...context, events: [{ ...target, ...change }] }), null)
  }
  assert.equal(matchReportFeedbackEvent(17, event, 0, { ...context, events: [target, target] }), null)
  const repeated = { ...event, sourceFindingIds: [501, 501, 502] }
  assert.ok(matchReportFeedbackEvent(17, repeated, 0, { ...context, events: [{ ...target, sourceFindingIds: [501, 501, 502] }] }))
  assert.equal(matchReportFeedbackEvent(17, repeated, 0, { ...context, events: [{ ...target, sourceFindingIds: [501, 502] }] }), null)
})

test('report reads never issue a write and event submit carries only the server key and opinion, without a recipient or article target', async t => {
  const calls = []
  t.mock.method(globalThis, 'fetch', async (url, init) => { calls.push({ url, init }); return success(init.method === 'GET' ? context : saved) })
  const abort = new AbortController()
  assert.deepEqual(await loadReportEventFeedback(17, abort.signal), context)
  assert.equal(calls.length, 1)
  assert.equal(calls[0].init.method, 'GET')
  assert.equal(calls[0].init.body, undefined)
  const request = prepareReportEventFeedback(null, draft)
  await submitReportEventFeedback(17, request, abort.signal)
  for (const call of calls) {
    assert.equal(call.url, '/api/news/reports/17/event-feedback')
    assert.equal(call.init.cache, 'no-store')
    assert.equal(call.init.signal, abort.signal)
  }
  assert.equal(calls[1].init.method, 'POST')
  assert.deepEqual(JSON.parse(calls[1].init.body), request)
  assert.deepEqual(Object.keys(request).sort(), ['category', 'comment', 'eventKey', 'idempotencyKey'])
})

test('a lost submit response retains the same idempotency key and complete event target on explicit retry', async t => {
  const calls = []
  t.mock.method(globalThis, 'fetch', async (_url, init) => {
    calls.push(JSON.parse(init.body))
    if (calls.length === 1) throw new TypeError('lost response')
    return success(saved, 201)
  })
  let request = prepareReportEventFeedback(null, draft)
  await assert.rejects(submitReportEventFeedback(17, request), /lost response/)
  assert.equal(calls.length, 1, 'writes never retry automatically')
  request = prepareReportEventFeedback(request, { ...draft, comment: ` ${draft.comment} ` })
  assert.deepEqual(await submitReportEventFeedback(17, request), saved)
  assert.deepEqual(calls[0], calls[1])
  for (const change of [{ category: 'SUMMARY_ERROR' }, { comment: '고친 의견' }, { eventKey: 'b'.repeat(64) }]) {
    assert.notEqual(prepareReportEventFeedback(request, { ...draft, ...change }).idempotencyKey, request.idempotencyKey)
  }
})

test('stale events, missing reports and malformed HTTP successes remain errors for the inline recovery UI', async t => {
  for (const status of [400, 404, 409, 500]) {
    t.mock.method(globalThis, 'fetch', async () => new Response(JSON.stringify({ isSuccess: false,
      code: status === 404 ? 'REPORT404' : `COMMON${status}`, message: '서버가 정한 오류 메시지', result: {} }), { status }))
    await assert.rejects(submitReportEventFeedback(17, prepareReportEventFeedback(null, draft)), error =>
      error instanceof ApiError && error.status === status && error.message === '서버가 정한 오류 메시지')
  }
  t.mock.method(globalThis, 'fetch', async () => success(saved, 409))
  await assert.rejects(loadReportEventFeedback(17), error => error instanceof ApiError && error.status === 409)
})

test('abort signals cancel both report-level reads and in-flight submissions', async t => {
  t.mock.method(globalThis, 'fetch', (_url, init) => new Promise((_resolve, reject) => {
    init.signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true })
  }))
  for (const operation of [signal => loadReportEventFeedback(17, signal),
    signal => submitReportEventFeedback(17, prepareReportEventFeedback(null, draft), signal)]) {
    const abort = new AbortController()
    const pending = operation(abort.signal)
    abort.abort()
    await assert.rejects(pending, error => error.name === 'AbortError')
  }
})

test('local opinions validate comments and use bounded polling without changing personal policy fields', () => {
  assert.equal(reportEventDraftError(draft), null)
  for (const comment of ['', '  ', 'a'.repeat(2001)]) assert.ok(reportEventDraftError({ ...draft, comment }))
  assert.equal(reportEventDraftError({ ...draft, comment: 'a'.repeat(2000) }), null)
  assert.ok(reportEventDraftError({ ...draft, eventKey: 'article-501' }))
  assert.deepEqual(Array.from({ length: 8 }, (_, attempt) => nextFeedbackPoll([saved], attempt)), [2000, 4000, 8000, 15000, 30000, 30000, false, false])
  for (const status of ['COMPLETED', 'FAILED']) assert.equal(nextFeedbackPoll([{ ...saved, status }], 0), false)
})

test('one sequential report poll backs off to its bound, then offers a manual refresh without another automatic request', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const calls = [], states = [], errors = []
  t.mock.method(globalThis, 'fetch', async (_url, init) => { calls.push(init); return success({ ...context, feedback: [saved] }) })
  const stop = startReportEventFeedbackPolling(17, { onData: (_data, paused) => states.push(paused), onError: error => errors.push(error), onSettled() {} })
  t.after(stop)
  await setImmediate()
  assert.equal(calls.length, 1)
  for (const delay of [2000, 4000, 8000, 15000, 30000, 30000]) {
    const previous = calls.length
    t.mock.timers.tick(delay - 1)
    await setImmediate()
    assert.equal(calls.length, previous)
    t.mock.timers.tick(1)
    await setImmediate()
    assert.equal(calls.length, previous + 1)
  }
  assert.equal(states.at(-1), true)
  t.mock.timers.tick(300000)
  await setImmediate()
  assert.equal(calls.length, 7)
  assert.deepEqual(errors, [])
})

test('switching reports or unmounting cancels the scheduled poll and suppresses any late result from the previous report', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const calls = [], delivered = [], errors = []
  let resolveLate
  t.mock.method(globalThis, 'fetch', (_url, init) => {
    calls.push(init)
    return calls.length === 1 ? Promise.resolve(success({ ...context, feedback: [saved] }))
      : new Promise(resolve => { resolveLate = resolve })
  })
  const callbacks = { onData: data => delivered.push(data.reportId), onError: error => errors.push(error), onSettled() {} }
  const stopScheduled = startReportEventFeedbackPolling(17, callbacks)
  await setImmediate()
  stopScheduled()
  t.mock.timers.tick(300000)
  await setImmediate()
  assert.equal(calls.length, 1)
  assert.equal(calls[0].signal.aborted, true)
  const stopPending = startReportEventFeedbackPolling(18, callbacks)
  stopPending()
  assert.equal(calls[1].signal.aborted, true)
  resolveLate(success({ ...context, reportId: 18, feedback: [saved] }))
  await setImmediate()
  t.mock.timers.tick(300000)
  assert.deepEqual(delivered, [17])
  assert.deepEqual(errors, [])
  assert.equal(calls.length, 2)
})

test('terminal results, failed reads and mismatched report contexts stop polling without accepting another report’s feedback', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  for (const result of [{ ...context, feedback: [{ ...saved, status: 'COMPLETED' }] }, { ...context, reportId: 18 }, null]) {
    const states = [], errors = [], calls = []
    t.mock.method(globalThis, 'fetch', async (_url, init) => {
      calls.push(init)
      if (result === null) throw new TypeError('offline')
      return success(result)
    })
    const stop = startReportEventFeedbackPolling(17, { onData: data => states.push(data), onError: error => errors.push(error), onSettled() {} })
    await setImmediate()
    t.mock.timers.tick(300000)
    await setImmediate()
    assert.equal(calls.length, 1)
    assert.equal(states.length, result?.reportId === 17 ? 1 : 0)
    assert.equal(errors.length, result?.reportId === 17 ? 0 : 1)
    stop()
  }
})
