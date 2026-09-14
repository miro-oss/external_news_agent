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

let server, emptyEnvDir, ArticleDetailModal
before(async () => {
  emptyEnvDir = await mkdtemp(join(tmpdir(), 'article-body-test-'))
  server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false,
    envDir: emptyEnvDir, cacheDir: join(emptyEnvDir, 'vite-cache'),
    // Static SSR rendering does not need the background browser dependency optimizer.
    optimizeDeps: { noDiscovery: true, include: [] },
    server: { middlewareMode: true, watch: null, ws: false }, logLevel: 'error' })
  ArticleDetailModal = (await server.ssrLoadModule('/src/features/articles/ArticleDetailModal.tsx')).ArticleDetailModal
})
after(async () => {
  await server?.close()
  if (emptyEnvDir) await rm(emptyEnvDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 })
})

function renderBody(bodyText, sentences = []) {
  const client = new QueryClient({ defaultOptions: { queries: { staleTime: Infinity, retry: false } } })
  client.setQueryData(['article', 10, null], {
    id: 10, title: '본문 표시 검증', topicName: '검증 주제', publisher: '검증 매체',
    canonicalUrl: 'https://example.invalid/article', publishedAt: '2026-09-14T10:00:00+09:00',
    language: 'ko', fetchStatus: 'OK', analysisArticleId: 10, issueId: null,
    analysis: null, relatedArticles: [], bodyText, sentences,
  })
  try {
    return renderToStaticMarkup(createElement(QueryClientProvider, { client },
      createElement(ArticleDetailModal, { articleId: 10, onClose() {} })))
  } finally { client.clear() }
}

test('full text without analysis sentence indexes is readable instead of shown as blocked', () => {
  const html = renderBody('확보한 첫 문단.\n\n<script>두 번째 문단</script>')
  assert.match(html, /확보한 첫 문단/)
  assert.match(html, /&lt;script&gt;두 번째 문단&lt;\/script&gt;/)
  assert.doesNotMatch(html, /본문을 가져올 수 없는 기사|<script>/)
})

test('saved analysis sentences retain their existing evidence IDs', () => {
  const html = renderBody('현재 본문', [{ index: 7, text: '분석 시점의 근거 문장' }])
  assert.match(html, /article-10-sentence-7/)
  assert.match(html, /분석 시점의 근거 문장/)
  assert.doesNotMatch(html, /현재 본문|본문을 가져올 수 없는 기사/)
})
