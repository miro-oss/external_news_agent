import assert from 'node:assert/strict'
import test from 'node:test'
import { QueryClient } from '@tanstack/react-query'
import { ApiError } from '../src/api/client.ts'
import { runDeliverySettingsOptions, saveRunDeliverySettingsOptions } from '../src/api/runDeliverySettings.ts'
import { toDeliveryPolicy } from '../src/features/settings/deliverySettings.ts'

const policy = { enabled: true, run: true, daily: false, groupIds: [1], recipientIds: [], channelIds: [2] }
const saved = { ...policy, runId: 42, editable: true, reportId: null, reportReady: false, source: 'RUN', topicPolicies: [] }
const response = (result, status = 200) => new Response(JSON.stringify({ isSuccess: true, code: 'COMMON200', message: '성공입니다.', result }), { status })

test('inherited topic policies remain separate and never become a combined run selection', async context => {
  const inherited = { ...saved, source: 'TOPIC', enabled: false, groupIds: [], channelIds: [],
    topicPolicies: [{ ...policy, topicId: 1, topicName: '반도체' },
      { ...policy, channelIds: [1], groupIds: [2], topicId: 2, topicName: 'AI' }] }
  const calls = []
  context.mock.method(globalThis, 'fetch', async (url, init) => { calls.push({ url, method: init.method }); return response(inherited) })
  const result = await runDeliverySettingsOptions(42).queryFn()
  assert.deepEqual(result, inherited)
  assert.deepEqual(toDeliveryPolicy(result), { ...policy, enabled: false, groupIds: [], channelIds: [] })
  assert.deepEqual(calls, [{ url: '/api/notifications/runs/42/delivery-settings', method: undefined }])
})

test('saving a run sends only policy fields and refreshes the independently saved run state', async context => {
  const client = new QueryClient()
  context.after(() => client.clear())
  const requests = []
  context.mock.method(globalThis, 'fetch', async (url, init) => { requests.push({ url, method: init.method, body: JSON.parse(init.body) }); return response(saved) })
  const editor = { ...saved, mode: 'ONCE' }
  await client.getMutationCache().build(client, saveRunDeliverySettingsOptions(client, 42)).execute(toDeliveryPolicy(editor))
  assert.deepEqual(requests, [{ url: '/api/notifications/runs/42/delivery-settings', method: 'PUT', body: policy }])
  assert.deepEqual(client.getQueryData(runDeliverySettingsOptions(42).queryKey), saved)
})

test('a completion conflict fetches the finished state without retrying a save or sending a report', async context => {
  const client = new QueryClient()
  context.after(() => client.clear())
  const completed = { ...saved, editable: false, reportId: 17, reportReady: true }
  const requests = []
  context.mock.method(globalThis, 'fetch', async (url, init) => {
    requests.push({ url, method: init.method ?? 'GET' })
    return requests.length === 1 ? new Response(JSON.stringify({ isSuccess: false, code: 'COMMON409',
      message: '보고서가 완성되었거나 수집이 종료되어 알림 설정을 변경할 수 없습니다.', result: {} }), { status: 409 }) : response(completed)
  })
  await assert.rejects(client.getMutationCache().build(client, saveRunDeliverySettingsOptions(client, 42)).execute(policy),
    error => error instanceof ApiError && error.status === 409)
  assert.deepEqual(requests, [
    { url: '/api/notifications/runs/42/delivery-settings', method: 'PUT' },
    { url: '/api/notifications/runs/42/delivery-settings', method: 'GET' },
  ])
  assert.deepEqual(client.getQueryData(runDeliverySettingsOptions(42).queryKey), completed)
})

test('turning off preserves stale selections and a failed save leaves the last saved state intact', async context => {
  const client = new QueryClient()
  context.after(() => client.clear())
  client.setQueryData(runDeliverySettingsOptions(42).queryKey, saved)
  const disabled = toDeliveryPolicy({ ...saved, enabled: false, recipientIds: [99], groupIds: [98], channelIds: [97] })
  const requests = []
  context.mock.method(globalThis, 'fetch', async (url, init) => {
    requests.push({ url, body: JSON.parse(init.body) })
    return new Response(JSON.stringify({ isSuccess: false, code: 'COMMON500', message: '서버 내부 오류입니다.', result: {} }), { status: 500 })
  })
  await assert.rejects(client.getMutationCache().build(client, saveRunDeliverySettingsOptions(client, 42)).execute(disabled))
  assert.deepEqual(requests, [{ url: '/api/notifications/runs/42/delivery-settings', body: disabled }])
  assert.deepEqual(client.getQueryData(runDeliverySettingsOptions(42).queryKey), saved)
})
