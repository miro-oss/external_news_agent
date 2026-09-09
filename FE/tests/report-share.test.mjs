import assert from 'node:assert/strict'
import test from 'node:test'
import { QueryClient } from '@tanstack/react-query'
import { ApiError } from '../src/api/client.ts'
import { reportShareOptions } from '../src/api/reportShare.ts'
import { failedDeliveryBatchId, reportShareFeedback } from '../src/features/notifications/reportShareFeedback.ts'

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
