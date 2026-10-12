import assert from 'node:assert/strict'
import test from 'node:test'
import { ApiError } from '../src/api/client.ts'
import { loadCompanyInterests, updateCompanyInterest } from '../src/api/companyInterests.ts'
import { companyInterestDraftError, companyInterestHash, companyInterestIdFromHash, companyInterestStatus, reconcileCompanyInterests } from '../src/features/interests/companyInterestState.ts'
import { companyCriterion, companyApplication } from './fixtures/company-interests.mjs'

const success = result => new Response(JSON.stringify({ isSuccess: true, code: 'COMMON200', message: '성공입니다.', result }))
const update = { version: 1, instruction: companyCriterion.instruction, topicId: 2, status: 'ACTIVE' }

test('company criteria reads and writes match the separate API contract without personal policy fields', async t => {
  const calls = []
  const active = { ...companyCriterion, ...update, version: 2, activatedAt: '2026-10-12T12:00:00+09:00' }
  t.mock.method(globalThis, 'fetch', async (url, init) => {
    calls.push({ url, init })
    return success(init.method === 'GET' ? { criteria: [companyCriterion] } : active)
  })
  const controller = new AbortController()
  assert.deepEqual(await loadCompanyInterests(controller.signal), { criteria: [companyCriterion] })
  assert.deepEqual(await updateCompanyInterest(31, update, controller.signal), active)
  assert.equal(calls[0].url, '/api/news/company-interests')
  assert.equal(calls[0].init.method, 'GET')
  assert.equal(calls[0].init.body, undefined)
  assert.equal(calls[1].url, '/api/news/company-interests/31')
  assert.equal(calls[1].init.method, 'PATCH')
  assert.deepEqual(JSON.parse(calls[1].init.body), update)
  for (const { init } of calls) {
    assert.equal(init.cache, 'no-store')
    assert.equal(init.signal, controller.signal)
    assert.equal(init.headers['Content-Type'], 'application/json')
  }
})

test('stale versions, a lost response and malformed responses never silently activate or retry a criterion', async t => {
  let calls = 0
  t.mock.method(globalThis, 'fetch', async () => {
    calls++
    return new Response(JSON.stringify({ isSuccess: false, code: 'COMMON409', message: '상태 충돌이 발생했습니다.', result: {} }), { status: 409 })
  })
  await assert.rejects(updateCompanyInterest(31, update), error => error instanceof ApiError && error.status === 409 && error.message === '상태 충돌이 발생했습니다.')
  assert.equal(calls, 1)
  t.mock.method(globalThis, 'fetch', async () => { calls++; throw new TypeError('lost response') })
  await assert.rejects(updateCompanyInterest(31, update), /lost response/)
  assert.equal(calls, 2)
  t.mock.method(globalThis, 'fetch', async () => new Response('<html>Bad gateway</html>', { status: 502 }))
  await assert.rejects(loadCompanyInterests(), error => error instanceof ApiError && error.code === 'NETWORK')
  t.mock.method(globalThis, 'fetch', async () => new Response(JSON.stringify({ isSuccess: true, code: 'COMMON200', message: '성공입니다.', result: companyCriterion }), { status: 409 }))
  await assert.rejects(updateCompanyInterest(31, update), error => error instanceof ApiError && error.status === 409)
})

test('aborting a navigation or criterion mutation cancels its own request', async t => {
  t.mock.method(globalThis, 'fetch', async (_url, init) => new Promise((_resolve, reject) => {
    init.signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true })
  }))
  for (const operation of [signal => loadCompanyInterests(signal), signal => updateCompanyInterest(31, update, signal)]) {
    const controller = new AbortController()
    const pending = operation(controller.signal)
    controller.abort()
    await assert.rejects(pending, error => error.name === 'AbortError')
  }
})

test('a saved draft, activation, completed evaluation and later edited version are distinct lifecycle states', () => {
  assert.equal(companyInterestStatus(companyCriterion), '기준 확인 대기')
  const active = { ...companyCriterion, status: 'ACTIVE', version: 2, activatedAt: '2026-10-12T12:00:00+09:00' }
  assert.equal(companyInterestStatus(active), '다음 보고서 반영 대기')
  assert.equal(companyInterestStatus({ ...active, lastApplication: companyApplication }), '실제 반영 완료')
  assert.equal(companyInterestStatus({ ...active, lastApplication: { ...companyApplication, suppressedCount: 0 } }), '실제 반영 완료')
  assert.equal(companyInterestStatus({ ...active, version: 3, lastApplication: null }), '다음 보고서 반영 대기')
  assert.equal(companyInterestStatus({ ...active, status: 'REVOKED' }), '사용 중지')
})

test('activation needs a bounded instruction and one original event topic, while draft edits can defer the topic choice', () => {
  assert.equal(companyInterestDraftError(companyCriterion, update), null)
  assert.equal(companyInterestDraftError(companyCriterion, { ...update, topicId: null, status: 'DRAFT' }), null)
  for (const instruction of ['', '  ', 'a'.repeat(501)]) assert.ok(companyInterestDraftError(companyCriterion, { ...update, instruction }))
  assert.equal(companyInterestDraftError(companyCriterion, { ...update, instruction: ` ${'a'.repeat(500)} ` }), null)
  for (const topicId of [null, 999]) assert.ok(companyInterestDraftError(companyCriterion, { ...update, topicId }))
  assert.ok(companyInterestDraftError(companyCriterion, { ...update, topicId: 999, status: 'DRAFT' }))
})

test('criterion links select only an exact safe positive identifier', () => {
  assert.equal(companyInterestHash(31), '#/company-interests?criterionId=31')
  assert.equal(companyInterestIdFromHash(companyInterestHash(31)), 31)
  assert.equal(companyInterestHash(), '#/company-interests')
  for (const value of ['', '0', '-1', '1.5', '31abc', '9007199254740992']) assert.equal(companyInterestIdFromHash(`#/company-interests?criterionId=${value}`), null)
})

test('a delayed list response cannot restore a draft after activation or restore an old application after editing', () => {
  const active = { ...companyCriterion, status: 'ACTIVE', version: 2 }
  assert.deepEqual(reconcileCompanyInterests([companyCriterion], [active]), [active])
  const priorApplication = { ...active, lastApplication: companyApplication }
  const edited = { ...active, instruction: '변경한 회사 기준', version: 3, lastApplication: null }
  assert.deepEqual(reconcileCompanyInterests([priorApplication], [edited]), [edited])
  const newer = { ...edited, status: 'REVOKED', version: 4 }
  assert.deepEqual(reconcileCompanyInterests([newer], [edited]), [newer])
})
