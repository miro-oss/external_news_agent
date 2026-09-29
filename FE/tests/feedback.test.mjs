import assert from 'node:assert/strict'
import test from 'node:test'
import { ApiError } from '../src/api/client.ts'
import { loadFeedbackContext, submitFeedback, revokePersonalPolicy } from '../src/api/feedback.ts'
import {
  feedbackTokenFromHash, isFeedbackHash, feedbackDraftError, prepareFeedbackRequest,
  nextFeedbackPoll, safeFeedbackSource,
} from '../src/features/feedback/feedbackState.ts'

const token = 'synthetic-reader-capability'
const draft = { itemId: 701, category: 'PREFERENCE', comment: '실제 공급 계약 소식을 더 보고 싶어요.', allowPersonalization: false }
const feedback = { ...draft, id: 601, status: 'PENDING', verdict: null, diagnosis: null, createdAt: '2026-09-29T09:00:00+09:00' }
const success = result => new Response(JSON.stringify({ isSuccess: true, code: 'COMMON200', message: '성공입니다.', result }))

test('only the feedback hash grants a capability; missing, repeated, oversized and whitespace tokens fail closed', () => {
  assert.equal(feedbackTokenFromHash(`#/feedback?token=${token}`), token)
  assert.equal(feedbackTokenFromHash(`#feedback?token=${token}`), token)
  for (const hash of ['#/feedback', '#/feedback?token=', '#/feedback?token=a&token=b', '#/feedback?token=a%20b',
    `#/feedback?token=${'a'.repeat(513)}`, `#/reports?token=${token}`, `?token=${token}#/feedback`]) {
    assert.equal(feedbackTokenFromHash(hash), null)
  }
  assert.equal(isFeedbackHash('#/feedback'), true, 'missing credentials stay on the isolated recovery screen')
  assert.equal(isFeedbackHash('#/reports'), false)
})

test('all feedback operations send the capability in JSON only and bypass ambient credentials/cache/referrers', async context => {
  const calls = []
  context.mock.method(globalThis, 'fetch', async (url, init) => { calls.push({ url, init }); return success(feedback) })
  await loadFeedbackContext(token)
  await submitFeedback(token, { ...draft, idempotencyKey: 'stable-key' })
  await revokePersonalPolicy(token, { id: 501, version: 7 })
  assert.deepEqual(calls.map(call => call.url), ['/api/feedback/context', '/api/feedback', '/api/feedback/policies/501/revoke'])
  for (const { url, init } of calls) {
    assert.equal(init.method, 'POST')
    assert.equal(init.cache, 'no-store')
    assert.equal(init.credentials, 'omit')
    assert.equal(init.referrerPolicy, 'no-referrer')
    assert.equal(init.redirect, 'error')
    assert.equal(JSON.parse(init.body).token, token)
    assert.equal(url.includes(token), false)
    assert.equal(JSON.stringify(init.headers).includes(token), false)
  }
  assert.deepEqual(JSON.parse(calls[2].init.body), { token, version: 7 })
})

test('a lost response is retried explicitly with the same content and idempotency key', async context => {
  const calls = []
  context.mock.method(globalThis, 'fetch', async (url, init) => {
    calls.push(JSON.parse(init.body))
    if (calls.length === 1) throw new TypeError('connection lost')
    return success(feedback)
  })
  let request = prepareFeedbackRequest(null, draft)
  await assert.rejects(submitFeedback(token, request), /connection lost/)
  assert.equal(calls.length, 1, 'writes must not automatically retry')
  request = prepareFeedbackRequest(request, { ...draft, comment: ` ${draft.comment} ` })
  assert.equal((await submitFeedback(token, request)).id, feedback.id)
  assert.deepEqual(calls[0], calls[1])
  const original = request.idempotencyKey
  for (const change of [{ ...draft, itemId: 702 }, { ...draft, comment: '다른 의견' },
    { ...draft, category: 'SUMMARY_ERROR' }, { ...draft, allowPersonalization: true }]) {
    assert.notEqual(prepareFeedbackRequest(request, change).idempotencyKey, original)
  }
})

test('personalization is opt-in and cannot be carried into a fact-error category', () => {
  assert.equal(prepareFeedbackRequest(null, draft).allowPersonalization, false)
  assert.equal(feedbackDraftError({ ...draft, category: 'SUMMARY_ERROR', allowPersonalization: true }), '개인 기준 적용은 관심에 대한 의견에서만 선택할 수 있습니다.')
  assert.equal(prepareFeedbackRequest(null, { ...draft, category: 'SUMMARY_ERROR', allowPersonalization: true }).allowPersonalization, false)
  for (const itemId of [0, -1, NaN, 1.5]) assert.ok(feedbackDraftError({ ...draft, itemId }))
  for (const comment of ['', '  ', 'a'.repeat(2001)]) assert.ok(feedbackDraftError({ ...draft, comment }))
  assert.equal(feedbackDraftError({ ...draft, comment: 'a'.repeat(2000) }), null)
})

test('status polling backs off, stops after the bound, and stops immediately at a terminal outcome', () => {
  const delays = Array.from({ length: 8 }, (_, attempt) => nextFeedbackPoll([feedback], attempt))
  assert.deepEqual(delays, [2000, 4000, 8000, 15000, 30000, 30000, false, false])
  assert.equal(nextFeedbackPoll([{ ...feedback, status: 'PROCESSING' }], 0), 2000)
  for (const status of ['COMPLETED', 'FAILED']) assert.equal(nextFeedbackPoll([{ ...feedback, status }], 0), false)
  assert.equal(nextFeedbackPoll([], 0), false)
})

test('invalid capability errors remain generic; HTTP failures never become success even with a malformed success envelope', async context => {
  context.mock.method(globalThis, 'fetch', async () => new Response(JSON.stringify({
    isSuccess: false, code: 'COMMON404', message: '요청한 데이터를 찾을 수 없습니다.', result: null,
  }), { status: 404 }))
  await assert.rejects(loadFeedbackContext(token), error => error instanceof ApiError && error.status === 404
    && error.message === '요청한 데이터를 찾을 수 없습니다.' && !error.message.includes(token))
  context.mock.method(globalThis, 'fetch', async () => new Response(JSON.stringify({
    isSuccess: true, code: 'COMMON409', message: '요청이 현재 상태와 충돌합니다.', result: null,
  }), { status: 409 }))
  await assert.rejects(revokePersonalPolicy(token, { id: 501, version: 1 }), error => error instanceof ApiError && error.status === 409)
})

test('source links exclude executable, relative and credential-bearing URLs', () => {
  for (const value of ['javascript:alert(1)', 'data:text/html,hello', '/api/feedback', 'https://name:secret@example.invalid/']) {
    assert.equal(safeFeedbackSource(value), null)
  }
  assert.equal(safeFeedbackSource('https://example.invalid/news/1'), 'https://example.invalid/news/1')
})
