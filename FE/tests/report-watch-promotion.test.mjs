import assert from 'node:assert/strict'
import test from 'node:test'
import { ApiError } from '../src/api/client.ts'
import { promoteReportWatchItem } from '../src/api/reportWatchItems.ts'
import { createWatchItemPromotion } from '../src/features/reports/watchItemPromotion.ts'

const item = { topic: 'HBM 양산 일정', reason: '공식 공급 계약을 확인할 필요가 있습니다.', sourceFindingIds: [503, 501, 503] }
const success = () => new Response(JSON.stringify({ isSuccess: true, code: 'COMMON200', message: '성공입니다.', result: { reportId: 17 } }))
const failure = status => new Response(JSON.stringify({ isSuccess: false, code: status === 404 ? 'REPORT404' : `COMMON${status}`,
  message: `서버 오류 ${status}`, result: {} }), { status })

test('promotion posts the exact saved watch item and all evidence, without using an event opinion', async t => {
  const calls = []
  const abort = new AbortController()
  t.mock.method(globalThis, 'fetch', async (url, init) => { calls.push({ url, init }); return success() })
  assert.deepEqual(await promoteReportWatchItem(17, { ...item, eventKey: 'unrelated', comment: 'not an opinion' }, abort.signal), { reportId: 17 })
  assert.equal(calls.length, 1)
  assert.equal(calls[0].url, '/api/news/reports/17/watch-items/promote')
  assert.equal(calls[0].init.method, 'POST')
  assert.equal(calls[0].init.cache, 'no-store')
  assert.equal(calls[0].init.signal, abort.signal)
  assert.deepEqual(JSON.parse(calls[0].init.body), item)
})

test('API errors preserve server messages and reject malformed responses', async t => {
  for (const status of [400, 404, 409, 500]) {
    t.mock.method(globalThis, 'fetch', async () => failure(status))
    await assert.rejects(promoteReportWatchItem(17, item), error => error instanceof ApiError
      && error.status === status && error.message === `서버 오류 ${status}`)
  }
  t.mock.method(globalThis, 'fetch', async () => new Response('<html>proxy unavailable</html>', { status: 502 }))
  await assert.rejects(promoteReportWatchItem(17, item), error => error instanceof ApiError && error.code === 'NETWORK')
})

test('rapid clicks submit once, then refresh the whole report before finishing', async t => {
  const response = Promise.withResolvers(), refresh = Promise.withResolvers()
  const states = [], calls = []
  t.mock.method(globalThis, 'fetch', async () => { calls.push('submit'); return response.promise })
  const action = createWatchItemPromotion(17, item, { onChange: state => states.push(state),
    onRefresh: () => { calls.push('refresh'); return refresh.promise } })
  const pending = action.promote()
  await action.promote()
  assert.deepEqual(calls, ['submit'])
  assert.equal(states.at(-1).pending, true)
  response.resolve(success())
  await new Promise(resolve => setImmediate(resolve))
  assert.deepEqual(calls, ['submit', 'refresh'])
  assert.equal(states.at(-1).accepted, true)
  assert.equal(states.at(-1).pending, true)
  refresh.resolve()
  await pending
  assert.deepEqual(states.at(-1), { pending: false, accepted: true, needsRefresh: false, error: '' })
  await action.promote()
  assert.deepEqual(calls, ['submit', 'refresh'])
})

test('refresh failure keeps the accepted promotion and retry only reloads the report', async t => {
  let submissions = 0, refreshes = 0
  const states = []
  t.mock.method(globalThis, 'fetch', async () => { submissions++; return success() })
  const action = createWatchItemPromotion(17, item, { onChange: state => states.push(state),
    async onRefresh() { if (++refreshes === 1) throw new Error('offline') } })
  await action.promote()
  assert.equal(states.at(-1).accepted, true)
  assert.match(states.at(-1).error, /올렸지만 보고서를 새로 불러오지 못했습니다/)
  await action.promote()
  await action.refresh()
  assert.equal(submissions, 1)
  assert.equal(refreshes, 2)
  assert.equal(states.at(-1).error, '')
})

test('network errors allow explicit retry with the same full target and never retry automatically', async t => {
  const bodies = [], states = []
  let refreshes = 0
  t.mock.method(globalThis, 'fetch', async (_url, init) => {
    bodies.push(JSON.parse(init.body))
    if (bodies.length === 1) throw new TypeError('lost response')
    return success()
  })
  const action = createWatchItemPromotion(17, item, { onChange: state => states.push(state), async onRefresh() { refreshes++ } })
  await action.promote()
  assert.equal(bodies.length, 1)
  assert.equal(refreshes, 0)
  assert.equal(states.at(-1).accepted, false)
  assert.match(states.at(-1).error, /다시 시도/)
  await action.promote()
  assert.deepEqual(bodies, [item, item])
  assert.equal(refreshes, 1)
})

test('stale or missing targets require a report refresh instead of another submission', async t => {
  for (const status of [404, 409]) {
    const states = []
    let submissions = 0, refreshes = 0
    t.mock.method(globalThis, 'fetch', async () => { submissions++; return failure(status) })
    const action = createWatchItemPromotion(17, item, { onChange: state => states.push(state), async onRefresh() { refreshes++ } })
    await action.promote()
    assert.equal(states.at(-1).needsRefresh, true)
    assert.equal(states.at(-1).error, `서버 오류 ${status}`)
    await action.promote()
    assert.equal(submissions, 1)
    await action.refresh()
    assert.equal(refreshes, 1)
    assert.equal(states.at(-1).needsRefresh, false)
    assert.equal(states.at(-1).accepted, false)
    await action.promote()
    assert.equal(submissions, 2, 'a refreshed surviving card can be submitted again')
  }
})

test('failed refresh keeps the conflict lock until a successful reload and does not accept the mutation', async t => {
  const states = []
  let submissions = 0, refreshes = 0
  t.mock.method(globalThis, 'fetch', async () => { submissions++; return failure(409) })
  const action = createWatchItemPromotion(17, item, { onChange: state => states.push(state),
    async onRefresh() { if (++refreshes === 1) throw new TypeError('offline') } })
  await action.promote()
  await action.refresh()
  assert.equal(states.at(-1).needsRefresh, true)
  assert.equal(states.at(-1).accepted, false)
  await action.promote()
  assert.equal(submissions, 1)
  await action.refresh()
  assert.equal(states.at(-1).needsRefresh, false)
  assert.equal(states.at(-1).accepted, false)
  assert.equal(states.at(-1).error, '')
})

test('switching reports aborts the old request and ignores a late success', async t => {
  const response = Promise.withResolvers(), states = []
  let signal, refreshes = 0
  t.mock.method(globalThis, 'fetch', async (_url, init) => { signal = init.signal; return response.promise })
  const action = createWatchItemPromotion(17, item, { onChange: state => states.push(state), async onRefresh() { refreshes++ } })
  const pending = action.promote()
  action.dispose()
  assert.equal(signal.aborted, true)
  const before = states.length
  response.resolve(success())
  await pending
  await action.promote()
  assert.equal(refreshes, 0)
  assert.equal(states.length, before)
})
