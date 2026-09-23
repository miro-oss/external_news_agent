import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { after, before, test } from 'node:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createServer } from 'vite'
import react from '@vitejs/plugin-react'

let server, emptyEnvDir, Dialog, Settings
before(async () => {
  emptyEnvDir = await mkdtemp(join(tmpdir(), 'recipient-settings-test-'))
  server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false,
    envDir: emptyEnvDir, cacheDir: join(emptyEnvDir, 'vite-cache'), plugins: [react()],
    optimizeDeps: { noDiscovery: true, include: [] },
    server: { middlewareMode: true, watch: null, ws: false }, logLevel: 'error' })
  const module = await server.ssrLoadModule('/src/features/notifications/RecipientSettingsDialog.tsx')
  Dialog = module.RecipientSettingsDialog
  Settings = module.RecipientReportSettings
})
after(async () => { await server?.close(); if (emptyEnvDir) await rm(emptyEnvDir, { recursive: true, force: true, maxRetries: 3 }) })

const recipient = { id: 1, name: '김수신', active: true, email: 'reader@example.invalid', destinations: [], groupNames: ['전략팀'] }
const topic = { topicId: 31, topicName: '반도체', enabled: true, configuredScopes: ['RUN', 'DAILY', 'WEEKLY'],
  includedScopes: [], excludedScopes: ['DAILY'], channelTypes: ['EMAIL'], direct: true, groupNames: ['전략팀'] }
const settings = { recipientId: 1, aggregates: { daily: true, weekly: false, channelTypes: ['EMAIL'] }, topics: [topic] }
const key = ['notifications', 'report-subscriptions', 1]
function render(client, component = Dialog, props = {}) {
  return renderToStaticMarkup(createElement(QueryClientProvider, { client }, createElement(component,
    component === Dialog ? { recipient, onDismiss() {}, ...props } : { settings, ...props })))
}

test('aggregate choices appear once above a simple topic checkbox list and a single save action', context => {
  const client = new QueryClient()
  context.after(() => client.clear())
  client.setQueryData(key, settings)
  const markup = render(client)
  assert.match(markup, /^<dialog[^>]*id="recipient-settings-1"/)
  assert.match(markup, /role="tab"[^>]*aria-selected="true"[^>]*>보고서 알림/)
  assert.match(markup, /role="tab"[^>]*aria-selected="false"[^>]*tabindex="-1"[^>]*>이메일·텔레그램/)
  assert.equal(markup.split('일일 통합 받기').length - 1, 1)
  assert.equal(markup.split('주간 통합 받기').length - 1, 1)
  assert.ok(markup.indexOf('주간 통합 받기') < markup.indexOf('<legend>주제 보고서'))
  assert.ok(markup.includes('<li><label><input type="checkbox" checked=""/><span>반도체'))
  assert.match(markup, /<small>받는 중/)
  assert.equal(markup.split('>저장</button>').length - 1, 1)
  assert.doesNotMatch(markup, /<article|개인 추가|개인 설정 초기화|직접 등록|전략팀 그룹/)
})

test('aggregate choices remain available without topics or connected channels', context => {
  const client = new QueryClient()
  context.after(() => client.clear())
  const markup = render(client, Settings, { settings: { ...settings, topics: [],
    aggregates: { daily: false, weekly: true, channelTypes: [] } } })
  assert.match(markup, /등록된 주제가 없습니다/)
  assert.ok(markup.includes('<input type="checkbox" checked=""/>주간 통합 받기'))
  assert.match(markup, /전달 방식을 연결해 주세요/)
  assert.doesNotMatch(markup, /<fieldset disabled/)
})

test('topic rows distinguish unsubscribed, paused and disconnected subscriptions', context => {
  const client = new QueryClient()
  context.after(() => client.clear())
  const markup = render(client, Settings, { settings: { ...settings, topics: [
    { ...topic, excludedScopes: ['RUN'] }, { ...topic, topicId: 32, enabled: false },
    { ...topic, topicId: 33, channelTypes: [] },
  ] } })
  assert.match(markup, /받지 않음/)
  assert.match(markup, /주제 알림 중지/)
  assert.match(markup, /연결 필요/)
  assert.equal(markup.split('type="checkbox"').length - 1, 5)
})

test('a failed refresh keeps saved rows visible but locks editing until the lookup succeeds', async context => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  context.after(() => client.clear())
  assert.match(render(client), /주제 보고서 알림을 불러오는 중입니다/)
  client.setQueryData(key, settings)
  await assert.rejects(client.fetchQuery({ queryKey: key, queryFn: async () => { throw new Error('lookup failed') } }))
  const markup = render(client)
  assert.match(markup, /role="alert"/)
  assert.match(markup, /다시 불러오기/)
  assert.match(markup, /반도체/)
  assert.match(markup, /<fieldset disabled="" class="recipient-aggregate-choices"/)
  assert.match(markup, /<fieldset disabled="" class="recipient-simple-topics"/)
})

test('pending changes lock closing and tab switches until the request resolves', async context => {
  const client = new QueryClient()
  context.after(() => client.clear())
  const completion = Promise.withResolvers()
  const mutation = client.getMutationCache().build(client, { mutationFn: () => completion.promise })
  const request = mutation.execute()
  const markup = render(client)
  assert.match(markup, /aria-busy="true"/)
  assert.match(markup, /disabled="" aria-label="수신 설정 닫기"/)
  assert.match(markup, /role="tab"[^>]*disabled=""[^>]*>보고서 알림/)
  assert.match(markup, /class="secondary-button" disabled="">닫기/)
  completion.resolve()
  await request
})

test('stored topic names remain text inside subscription controls', context => {
  const client = new QueryClient()
  context.after(() => client.clear())
  const markup = render(client, Settings, { settings: { ...settings, topics: [{ ...topic, topicName: '<img src=x onerror=alert(1)>' }] } })
  assert.doesNotMatch(markup, /<img/)
  assert.match(markup, /&lt;img/)
})
