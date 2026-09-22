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

let server, emptyEnvDir, Dialog, Row
before(async () => {
  emptyEnvDir = await mkdtemp(join(tmpdir(), 'recipient-settings-test-'))
  server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false,
    envDir: emptyEnvDir, cacheDir: join(emptyEnvDir, 'vite-cache'), plugins: [react()],
    optimizeDeps: { noDiscovery: true, include: [] },
    server: { middlewareMode: true, watch: null, ws: false }, logLevel: 'error' })
  const module = await server.ssrLoadModule('/src/features/notifications/RecipientSettingsDialog.tsx')
  Dialog = module.RecipientSettingsDialog
  Row = module.RecipientSubscriptionRow
})
after(async () => { await server?.close(); if (emptyEnvDir) await rm(emptyEnvDir, { recursive: true, force: true, maxRetries: 3 }) })

const recipient = { id: 1, name: '김수신', active: true, email: 'reader@example.invalid', destinations: [], groupNames: ['전략팀'] }
const topic = { topicId: 31, topicName: '반도체', enabled: true, configuredScopes: ['RUN', 'DAILY', 'WEEKLY'],
  excludedScopes: ['DAILY'], channelTypes: ['EMAIL'], direct: true, groupNames: ['전략팀', '기술팀'] }
const key = ['notifications', 'report-subscriptions', 1]
function render(client, component = Dialog, props = {}) {
  return renderToStaticMarkup(createElement(QueryClientProvider, { client }, createElement(component,
    component === Dialog ? { recipient, onDismiss() {}, ...props } : { recipientId: 1, topic, ...props })))
}

test('recipient settings opens on report subscriptions with linked tabs and preserves each target attribution', context => {
  const client = new QueryClient()
  context.after(() => client.clear())
  client.setQueryData(key, { recipientId: 1, topics: [topic] })
  const markup = render(client)
  assert.match(markup, /^<dialog[^>]*id="recipient-settings-1"/)
  assert.match(markup, /role="tab"[^>]*aria-selected="true"[^>]*>주제 보고서 알림/)
  assert.match(markup, /role="tab"[^>]*aria-selected="false"[^>]*tabindex="-1"[^>]*>이메일·텔레그램/)
  assert.match(markup, /직접 등록 · 전략팀 그룹 · 기술팀 그룹/)
  assert.match(markup, /수집별 · 주간 통합 받는 중/)
  assert.match(markup, /<input type="checkbox"\/><span>일일 통합/)
  assert.match(markup, /<input type="checkbox" checked=""\/><span>주간 통합/)
  assert.doesNotMatch(markup, /recipient-email-form|연결 링크 만들기/)
  assert.match(markup, /다른 포함 주제에서 같은 보고서를 받도록 설정했다면 한 번 전달될 수 있습니다/)
})

test('paused policies, fully excluded subscriptions and unavailable channels are not called receiving', context => {
  const client = new QueryClient()
  context.after(() => client.clear())
  const paused = render(client, Row, { topic: { ...topic, enabled: false } })
  assert.match(paused, /주제 알림 중지/)
  assert.doesNotMatch(paused, /받는 중/)
  const excluded = render(client, Row, { topic: { ...topic, excludedScopes: ['RUN', 'DAILY', 'WEEKLY'] } })
  assert.match(excluded, /받지 않음/)
  assert.match(excluded, /반도체 알림 저장/)
  assert.doesNotMatch(excluded, /checked=""/)
  const disconnected = render(client, Row, { topic: { ...topic, channelTypes: [] } })
  assert.match(disconnected, /수신 가능한 전달 방식 없음/)
  assert.doesNotMatch(disconnected, /받는 중/)
  const former = render(client, Row, { topic: { ...topic, enabled: false, configuredScopes: [], direct: false, groupNames: [] } })
  assert.match(former, /현재 주제의 수신 대상이 아닙니다/)
  assert.match(former, /개인 해제 초기화/)
  assert.equal((former.match(/주제 설정 없음/g) ?? []).length, 3)
})

test('loading and an empty saved list have separate states and never claim the recipient receives reports', context => {
  const client = new QueryClient()
  context.after(() => client.clear())
  assert.match(render(client), /주제 보고서 알림을 불러오는 중입니다/)
  client.setQueryData(key, { recipientId: 1, topics: [] })
  const empty = render(client)
  assert.match(empty, /등록된 주제 보고서 알림이 없습니다/)
  assert.doesNotMatch(empty, /받는 중|불러오는 중/)
})

test('a failed refresh keeps saved rows visible but locks editing until the lookup succeeds', async context => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  context.after(() => client.clear())
  client.setQueryData(key, { recipientId: 1, topics: [topic] })
  await assert.rejects(client.fetchQuery({ queryKey: key, queryFn: async () => { throw new Error('lookup failed') } }))
  const markup = render(client)
  assert.match(markup, /role="alert"/)
  assert.match(markup, /주제 보고서 알림을 불러오지 못했습니다/)
  assert.match(markup, /다시 불러오기/)
  assert.match(markup, /반도체/)
  assert.match(markup, /<fieldset disabled="" class="recipient-subscription-scopes"/)
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
  assert.match(markup, /role="tab"[^>]*disabled=""[^>]*>주제 보고서 알림/)
  assert.match(markup, /class="secondary-button" disabled="">닫기/)
  completion.resolve()
  await request
})

test('stored topic and group names remain text inside subscription controls', context => {
  const client = new QueryClient()
  context.after(() => client.clear())
  const markup = render(client, Row, { topic: { ...topic, topicName: '<img src=x onerror=alert(1)>', groupNames: ['<script>alert(1)</script>'] } })
  assert.doesNotMatch(markup, /<script>|<img/)
  assert.match(markup, /&lt;img/)
})
