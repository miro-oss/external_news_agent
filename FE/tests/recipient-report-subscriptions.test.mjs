import assert from 'node:assert/strict'
import test from 'node:test'
import { QueryClient } from '@tanstack/react-query'
import { recipientReportSubscriptionsOptions, saveRecipientReportSubscriptionOptions } from '../src/api/recipientReportSubscriptions.ts'

const row = { topicId: 31, topicName: '반도체', enabled: true, configuredScopes: ['RUN', 'DAILY', 'WEEKLY'],
  excludedScopes: [], channelTypes: ['EMAIL'], direct: false, groupNames: ['기술'] }
const response = result => new Response(JSON.stringify({ isSuccess: true, code: 'COMMON200', message: '성공입니다.', result }))

test('recipient subscription reads keep recipient IDs, original target attribution and abort signals', async context => {
  const signal = new AbortController().signal
  const calls = []
  context.mock.method(globalThis, 'fetch', async (url, init) => { calls.push({ url, signal: init.signal }); return response({ recipientId: 2, topics: [row] }) })
  const result = await recipientReportSubscriptionsOptions(2).queryFn({ signal })
  assert.deepEqual(result, { recipientId: 2, topics: [row] })
  assert.deepEqual(calls, [{ url: '/api/notifications/recipients/2/report-subscriptions', signal }])
})

test('saving exclusions sends only personal report choices and retains fully disabled rows and other recipients', async context => {
  const client = new QueryClient()
  context.after(() => client.clear())
  const otherRow = { ...row, topicId: 32, topicName: 'AI' }
  client.setQueryData(recipientReportSubscriptionsOptions(1).queryKey, { recipientId: 1, topics: [row, otherRow] })
  client.setQueryData(recipientReportSubscriptionsOptions(2).queryKey, { recipientId: 2, topics: [row] })
  const updated = { ...row, excludedScopes: ['RUN', 'DAILY', 'WEEKLY'] }
  const calls = []
  context.mock.method(globalThis, 'fetch', async (url, init) => { calls.push({ url, method: init.method, body: JSON.parse(init.body) }); return response(updated) })
  await client.getMutationCache().build(client, saveRecipientReportSubscriptionOptions(client, 1, 31)).execute(updated.excludedScopes)
  assert.deepEqual(calls, [{ url: '/api/notifications/recipients/1/report-subscriptions/31', method: 'PUT', body: { excludedScopes: updated.excludedScopes } }])
  assert.deepEqual(client.getQueryData(recipientReportSubscriptionsOptions(1).queryKey), { recipientId: 1, topics: [updated, otherRow] })
  assert.deepEqual(client.getQueryData(recipientReportSubscriptionsOptions(2).queryKey), { recipientId: 2, topics: [row] })
})

test('a read started before save cannot restore a removed subscription and other recipient reads stay active', async context => {
  const client = new QueryClient()
  context.after(() => client.clear())
  const key = recipientReportSubscriptionsOptions(1).queryKey
  client.setQueryData(key, { recipientId: 1, topics: [row] })
  const stale = Promise.withResolvers()
  const other = Promise.withResolvers()
  const oldRequest = client.fetchQuery({ queryKey: key, queryFn: () => stale.promise }).catch(() => null)
  const otherRequest = client.fetchQuery({ queryKey: recipientReportSubscriptionsOptions(2).queryKey, queryFn: () => other.promise })
  const updated = { ...row, excludedScopes: ['WEEKLY'] }
  context.mock.method(globalThis, 'fetch', async () => response(updated))
  await client.getMutationCache().build(client, saveRecipientReportSubscriptionOptions(client, 1, 31)).execute(['WEEKLY'])
  stale.resolve({ recipientId: 1, topics: [row] })
  other.resolve({ recipientId: 2, topics: [row] })
  await Promise.all([oldRequest, otherRequest])
  assert.deepEqual(client.getQueryData(key).topics[0].excludedScopes, ['WEEKLY'])
  assert.deepEqual(client.getQueryData(recipientReportSubscriptionsOptions(2).queryKey).topics, [row])
})

test('save failures retain saved settings, expose the error and can be retried without an optimistic unsubscribe', async context => {
  const client = new QueryClient()
  context.after(() => client.clear())
  const key = recipientReportSubscriptionsOptions(1).queryKey
  const saved = { recipientId: 1, topics: [row] }
  client.setQueryData(key, saved)
  let failure = true
  context.mock.method(globalThis, 'fetch', async () => failure
    ? new Response(JSON.stringify({ isSuccess: false, code: 'COMMON500', message: '저장 실패', result: {} }), { status: 500 })
    : response({ ...row, excludedScopes: ['DAILY'] }))
  const mutation = client.getMutationCache().build(client, saveRecipientReportSubscriptionOptions(client, 1, 31))
  await assert.rejects(mutation.execute(['DAILY']), /저장 실패/)
  assert.deepEqual(client.getQueryData(key), saved)
  failure = false
  await mutation.execute(['DAILY'])
  assert.deepEqual(client.getQueryData(key).topics[0].excludedScopes, ['DAILY'])
})

test('pending saves retain saved state and do not fabricate a missing full recipient list', async context => {
  const client = new QueryClient()
  context.after(() => client.clear())
  const completion = Promise.withResolvers()
  context.mock.method(globalThis, 'fetch', async () => { await completion.promise; return response({ ...row, excludedScopes: ['WEEKLY'] }) })
  const mutation = client.getMutationCache().build(client, saveRecipientReportSubscriptionOptions(client, 1, 31))
  const request = mutation.execute(['WEEKLY'])
  assert.equal(mutation.state.status, 'pending')
  assert.equal(client.getQueryData(recipientReportSubscriptionsOptions(1).queryKey), undefined)
  completion.resolve()
  await request
  assert.equal(client.getQueryData(recipientReportSubscriptionsOptions(1).queryKey), undefined)
})
