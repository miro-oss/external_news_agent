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

const payload = '\"><img src="https://attacker.invalid/probe" onerror="globalThis.__probe=1"><script>globalThis.__probe=1</script>'
const modules = [
  ['TopicTable', 'settings'], ['CollectionTopicPicker', 'settings'],
  ['ArticleDetailModal', 'articles'], ['NotificationsPage', 'notifications'],
  ['RecipientEmailForm', 'notifications'], ['GroupRecipientPicker', 'notifications'],
  ['DeliveryTargetPicker', 'notifications'], ['TelegramConnectionCard', 'notifications'],
]
const components = {}
let server, emptyEnvDir
before(async () => {
  emptyEnvDir = await mkdtemp(join(tmpdir(), 'stored-input-security-test-'))
  server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false,
    envDir: emptyEnvDir, cacheDir: join(emptyEnvDir, 'vite-cache'),
    optimizeDeps: { noDiscovery: true, include: [] },
    server: { middlewareMode: true, watch: null, ws: false }, logLevel: 'error' })
  for (const [name, feature] of modules) {
    components[name] = (await server.ssrLoadModule(`/src/features/${feature}/${name}.tsx`))[name]
  }
})
after(async () => {
  await server?.close()
  if (emptyEnvDir) await rm(emptyEnvDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 })
})

const page = content => ({ content, page: 0, size: 100, totalElements: content.length, totalPages: 1, hasNext: false })
const topic = { id: 1, name: payload, queryText: payload,
  requiredKeywords: [payload], optionalKeywords: [payload], excludedKeywords: [payload],
  active: true, intervalMinutes: 60, lastCollectedAt: null,
  surgeKeywords: [{ keyword: payload, issueCount: 1, deltaIssueCount: 1, burst: false }],
  relatedKeywords: [{ keyword: payload, issueCount: 1, sharePercent: 100 }] }
const recipient = { id: 1, name: payload, email: payload, active: true, groupNames: [payload],
  destinations: [{ channelId: 1, channelType: 'EMAIL', address: payload, use: true, onboarded: true }] }

function clientWithSavedInputs() {
  const client = new QueryClient({ defaultOptions: { queries: { staleTime: Infinity, retry: false } } })
  client.setQueryData(['topics', true], page([topic]))
  client.setQueryData(['delivery-policy', 1], { enabled: false, run: false, daily: false, groupIds: [], recipientIds: [], channelIds: [] })
  client.setQueryData(['notifications', 'channels'], [{ id: 1, channelType: 'EMAIL', name: payload, active: true }])
  client.setQueryData(['notifications', 'recipients'], page([recipient]))
  client.setQueryData(['notifications', 'groups'], page([{ id: 1, name: payload, active: true, perspective: 'EXECUTIVE', memberCount: 1, activeMemberCount: 1 }]))
  client.setQueryData(['reports', 'list', 'ALL'], page([{ id: 1, title: payload }]))
  client.setQueryData(['notifications', 'delivery-logs', { page: 0 }], {
    ...page([{ id: 1, reportId: 1, recipientId: 1, recipientName: payload, status: 'FAILED', channelType: 'EMAIL',
      errorMessage: payload, sentAt: '2026-09-22T10:00:00+09:00' }]),
    summary: { sentCount: 0, failedCount: 1, skippedCount: 0 },
  })
  client.setQueryData(['telegram-connection', 1], { status: 'CONNECTED', expiresAt: null })
  client.setQueryData(['article', 10, null], { id: 10, title: payload, topicName: payload, publisher: '', sourceName: payload,
    canonicalUrl: 'javascript:globalThis.__probe=1', publishedAt: '2026-09-22T10:00:00+09:00', language: 'ko',
    fetchStatus: 'OK', analysisArticleId: 10, issueId: null, analysis: null, relatedArticles: [], bodyText: payload, sentences: [] })
  return client
}

function renderSavedInputs(client, name, props = {}) {
  const html = renderToStaticMarkup(createElement(QueryClientProvider, { client }, createElement(components[name], props)))
  assert.match(html, /&lt;img/, name)
  assert.match(html, /&lt;script&gt;/, name)
  assert.doesNotMatch(html, /<(?:script|img)\b/, name)
  assert.doesNotMatch(html, /\sonerror="globalThis/, name)
  return html
}

test('stored source, topic, search and keyword payloads remain text, including attribute contexts', () => {
  const client = clientWithSavedInputs()
  try {
    renderSavedInputs(client, 'TopicTable')
    renderSavedInputs(client, 'CollectionTopicPicker', { topics: [topic], selected: [1], onChange() {} })
    const article = renderSavedInputs(client, 'ArticleDetailModal', { articleId: 10, onClose() {} })
    assert.match(article, /React has blocked a javascript:/)
    assert.doesNotMatch(article, /href="javascript:globalThis/)
  } finally { client.clear() }
})

test('stored recipient, group, email and delivery-log payloads cannot create HTML or event handlers', () => {
  const client = clientWithSavedInputs()
  try {
    renderSavedInputs(client, 'NotificationsPage')
    renderSavedInputs(client, 'RecipientEmailForm', { recipient, emailChannelId: 1 })
    renderSavedInputs(client, 'GroupRecipientPicker', { recipients: [recipient], selected: [1], onChange() {} })
    renderSavedInputs(client, 'DeliveryTargetPicker', { value: { groupIds: [1], recipientIds: [1], channelIds: [1] }, onChange() {} })
    renderSavedInputs(client, 'TelegramConnectionCard', { recipientId: 1, recipientName: payload })
  } finally { client.clear() }
})
