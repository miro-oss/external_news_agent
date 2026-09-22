import assert from 'node:assert/strict'
import test from 'node:test'
import { QueryClient } from '@tanstack/react-query'
import { ApiError } from '../src/api/client.ts'
import { reportShareOptions } from '../src/api/reportShare.ts'
import { canRetryFailedReportShare, failedDeliveryBatchId, reportShareFeedback, reportShareRequestKey } from '../src/features/notifications/reportShareFeedback.ts'

test('all-failed responses expose the existing batch for failure lookup', () => {
  assert.equal(failedDeliveryBatchId(new ApiError('DELIVERY502', '발송에 모두 실패했습니다.', 502, {
    deliveryBatchId: 'failed-batch', targetCount: 2, failedCount: 2,
  })), 'failed-batch')
  for (const details of [null, {}, { deliveryBatchId: 1 }, { deliveryBatchId: '' }, { deliveryBatchId: '  ' }]) {
    assert.equal(failedDeliveryBatchId(new ApiError('DELIVERY502', 'failure', 502, details)), null)
  }
  assert.equal(failedDeliveryBatchId(new ApiError('NETWORK', 'failure', 502)), null)
  assert.equal(failedDeliveryBatchId(new Error('fetch failed')), null)
})

test('an idempotent replay of an all-failed batch never says delivery completed', () => {
  const result = reportShareFeedback({ sentCount: 0, failedCount: 2, skippedCount: 0 })
  assert.equal(result.label, '전달 실패')
  assert.equal(result.success, null)
  assert.match(result.warning, /0명 전달 · 2명 실패/)
})

test('partial and excluded deliveries retain their individual outcomes', () => {
  const partial = reportShareFeedback({ sentCount: 1, failedCount: 1, skippedCount: 1 })
  assert.equal(partial.label, '일부 전달 완료')
  assert.equal(partial.success, null)
  assert.match(partial.warning, /1명 전달 · 1명 실패 · 1명 제외/)
  const skipped = reportShareFeedback({ sentCount: 0, failedCount: 0, skippedCount: 2 })
  assert.equal(skipped.label, '전달 제외')
  assert.equal(skipped.success, null)
  assert.deepEqual(reportShareFeedback({ sentCount: 2, failedCount: 0, skippedCount: 0 }), {
    label: '전달 완료', success: '2명에게 전달했습니다.', warning: null,
  })
})

test('a failed send refreshes saved delivery logs and preserves the key on explicit replay', async (context) => {
  const client = new QueryClient()
  context.after(() => client.clear())
  const logsKey = ['notifications', 'delivery-logs', { reportId: '17', deliveryBatchId: 'failed-batch' }]
  const reportKey = ['reports', 17]
  client.setQueryData(logsKey, { content: [] })
  client.setQueryData(reportKey, { id: 17 })
  const requests = []
  context.mock.method(globalThis, 'fetch', async (url, init) => {
    requests.push({ url, body: JSON.parse(init.body) })
    return new Response(JSON.stringify(requests.length === 1 ? {
      isSuccess: false, code: 'DELIVERY502', message: '발송에 모두 실패했습니다.',
      result: { deliveryBatchId: 'failed-batch', targetCount: 2, failedCount: 2 },
    } : {
      isSuccess: true, code: 'COMMON200', message: '발송을 완료했습니다.',
      result: { deliveryBatchId: 'failed-batch', targetCount: 2, sentCount: 0, failedCount: 2, skippedCount: 0 },
    }), { status: requests.length === 1 ? 502 : 200 })
  })
  const options = reportShareOptions(client, 17)
  const mutation = client.getMutationCache().build(client, options)
  const request = { groupIds: [], recipientIds: [1], channelIds: [1], idempotencyKey: 'stable-key' }
  await assert.rejects(mutation.execute(request), (error) => failedDeliveryBatchId(error) === 'failed-batch')
  assert.equal(requests.length, 1, 'failed sends must not be retried automatically')
  assert.equal(client.getQueryState(logsKey).isInvalidated, true)
  assert.equal(client.getQueryState(reportKey).isInvalidated, true)
  const replay = await client.getMutationCache().build(client, options).execute(request)
  assert.deepEqual(requests, [
    { url: '/api/notifications/reports/17/send', body: request },
    { url: '/api/notifications/reports/17/send', body: request },
  ])
  assert.equal(reportShareFeedback(replay).label, '전달 실패')
})

test('an uncertain network failure is not automatically resent', async (context) => {
  const client = new QueryClient()
  context.after(() => client.clear())
  const failure = new TypeError('fetch failed')
  const fetchMock = context.mock.method(globalThis, 'fetch', async () => { throw failure })
  const mutation = client.getMutationCache().build(client, reportShareOptions(client, 17))
  await assert.rejects(mutation.execute({ groupIds: [], recipientIds: [1], channelIds: [1], idempotencyKey: 'stable-key' }), error => error === failure)
  assert.equal(fetchMock.mock.callCount(), 1)
})

test('a new delivery request is available only for a confirmed all-failed batch', () => {
  const details = { deliveryBatchId: 'failed-batch', targetCount: 2, failedCount: 2 }
  const failure = value => new ApiError('DELIVERY502', '발송에 모두 실패했습니다.', 502, value)
  assert.equal(canRetryFailedReportShare(undefined, failure(details)), true)
  const replay = { ...details, sentCount: 0, skippedCount: 0 }
  assert.equal(canRetryFailedReportShare(replay, null), true)

  for (const invalid of [
    {}, { ...details, deliveryBatchId: '' }, { ...details, deliveryBatchId: 1 },
    { ...details, targetCount: 0, failedCount: 0 }, { ...details, targetCount: -1, failedCount: -1 },
    { ...details, targetCount: 1.5, failedCount: 1.5 }, { ...details, targetCount: '2' },
    { ...details, failedCount: 1 }, { ...details, sentCount: 1 }, { ...details, skippedCount: 1 },
  ]) {
    assert.equal(canRetryFailedReportShare(undefined, failure(invalid)), false)
  }
  for (const batch of [
    details, { ...replay, deliveryBatchId: '' }, { ...replay, deliveryBatchId: 1 },
    { ...replay, sentCount: 1, failedCount: 1 },
    { ...replay, skippedCount: 1, failedCount: 1 },
    { ...replay, sentCount: 2, failedCount: 0 },
    { ...replay, skippedCount: 2, failedCount: 0 },
  ]) {
    assert.equal(canRetryFailedReportShare(batch, null), false)
    assert.equal(reportShareRequestKey(17, 'existing-key', batch, null), 'existing-key')
  }
  for (const error of [
    new TypeError('fetch failed'), new ApiError('NETWORK', '서버에 연결하지 못했습니다.', 502),
    new ApiError('COMMON409', '동일한 발송 요청이 진행 중입니다.', 409, details),
  ]) {
    assert.equal(canRetryFailedReportShare(undefined, error), false)
    assert.equal(canRetryFailedReportShare(replay, error), false, 'an uncertain current attempt overrides older data')
    assert.equal(reportShareRequestKey(17, 'existing-key', replay, error), 'existing-key')
  }
})

test('an explicit new attempt rotates the key once and reuses it after a lost response', async (context) => {
  const client = new QueryClient()
  context.after(() => client.clear())
  const requests = []
  const networkFailure = new TypeError('fetch failed')
  context.mock.method(globalThis, 'fetch', async (url, init) => {
    requests.push({ url, body: JSON.parse(init.body) })
    if (requests.length === 2) throw networkFailure
    return new Response(JSON.stringify(requests.length === 1 ? {
      isSuccess: false, code: 'DELIVERY502', message: '발송에 모두 실패했습니다.',
      result: { deliveryBatchId: 'failed-batch', targetCount: 1, failedCount: 1 },
    } : {
      isSuccess: true, code: 'COMMON200', message: '발송을 완료했습니다.',
      result: { deliveryBatchId: 'new-batch', targetCount: 1, sentCount: 1, failedCount: 0, skippedCount: 0 },
    }), { status: requests.length === 1 ? 502 : 200 })
  })
  const mutation = client.getMutationCache().build(client, reportShareOptions(client, 17))
  const targets = { groupIds: [], recipientIds: [1], channelIds: [1] }
  let requestKey = 'original-key'
  const share = () => {
    requestKey = reportShareRequestKey(17, requestKey, mutation.state.data, mutation.state.error)
    return mutation.execute({ ...targets, idempotencyKey: requestKey })
  }

  await assert.rejects(share(), error => error instanceof ApiError && error.code === 'DELIVERY502')
  assert.equal(requests.length, 1, 'a terminal failure waits for the user to start another request')
  await assert.rejects(share(), error => error === networkFailure)
  assert.notEqual(requestKey, 'original-key')
  assert.match(requestKey, /^r17-/)
  const newKey = requestKey
  assert.equal(requests.length, 2, 'a lost response is not automatically resent')
  const replay = await share()
  assert.equal(replay.sentCount, 1)
  assert.equal(requestKey, newKey)
  assert.deepEqual(requests.map(request => request.body.idempotencyKey), ['original-key', newKey, newKey])
  assert.ok(requests.every(request => request.url === '/api/notifications/reports/17/send'))
  assert.ok(requests.every(request => request.body.recipientIds[0] === 1 && request.body.channelIds[0] === 1))
})
