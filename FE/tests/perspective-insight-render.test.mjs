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

let server, emptyEnvDir, ArticleDetailModal, ApiError
before(async () => {
  emptyEnvDir = await mkdtemp(join(tmpdir(), 'perspective-insight-test-'))
  server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false,
    envDir: emptyEnvDir, cacheDir: join(emptyEnvDir, 'vite-cache'),
    optimizeDeps: { noDiscovery: true, include: [] },
    server: { middlewareMode: true, watch: null, ws: false }, logLevel: 'error' })
  ArticleDetailModal = (await server.ssrLoadModule('/src/features/articles/ArticleDetailModal.tsx')).ArticleDetailModal
  ApiError = (await server.ssrLoadModule('/src/api/client.ts')).ApiError
})
after(async () => {
  await server?.close()
  if (emptyEnvDir) await rm(emptyEnvDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 })
})

const creditAction = /이 관점으로 인사이트 보기 · 크레딧 1 사용/
const insightKey = (issueId = 20, audience = 'CHIP_MAKER') => ['insights', issueId, audience]

function fixture(context) {
  // Retain completed lookup errors while SSR renders the corresponding UI state.
  const client = new QueryClient({ defaultOptions: { queries: { staleTime: Infinity, retry: false, retryOnMount: false } } })
  context.after(() => client.clear())
  return client
}

function result({ audience = 'CHIP_MAKER', issueId = 20, cached = true, empty = false } = {}) {
  return {
    cached, targetType: 'ISSUE', targetId: issueId, inputHash: 'fixture', promptVersion: 'fixture',
    insights: [{
      audience, headline: `${audience}의 검증된 인사이트`, confidence: 0.85,
      facts: empty ? [] : [{ id: 'fact-1', claimType: 'FACT', text: '확인된 증설 계획',
        findingId: 1, articleId: 10, evidenceSentenceIds: [], groundedness: 'grounded', groundingReason: '공시 확인' }],
      implications: [], watchNext: [], relatedArticleCount: 0,
      llmProvider: null, llmModel: null, createdAt: '2026-09-21T09:00:00+09:00',
    }],
  }
}

function render(client, audience = 'CHIP_MAKER', issueId = 20) {
  client.setQueryData(['article', 10, null], {
    id: 10, title: '관점 인사이트 검증', topicName: '검증 주제', publisher: '검증 매체',
    canonicalUrl: 'https://example.invalid/article', publishedAt: '2026-09-21T09:00:00+09:00',
    language: 'ko', fetchStatus: 'OK', analysisArticleId: 10, issueId,
    relatedArticles: [], bodyText: '기사 본문', sentences: [],
    analysis: { summary: '기사 요약', keyPoints: [], intent: null, category: '반도체',
      sensitivity: { level: 'low', score: 1 }, relevance: 'watch', perspectiveTags: [] },
  })
  return renderToStaticMarkup(createElement(QueryClientProvider, { client },
    createElement(ArticleDetailModal, { articleId: 10, defaultAudience: audience, onClose() {} })))
}

async function failedLookup(client, error, issueId = 20, audience = 'CHIP_MAKER') {
  await assert.rejects(client.fetchQuery({ queryKey: insightKey(issueId, audience), queryFn: async () => { throw error } }),
    received => received === error)
}

test('saved and newly generated results hide the paid generation button', context => {
  const client = fixture(context)
  for (const cached of [true, false]) {
    client.setQueryData(insightKey(), result({ cached }))
    const html = render(client)
    assert.match(html, /CHIP_MAKER의 검증된 인사이트/)
    assert.match(html, cached ? /저장된 결과/ : /새 결과/)
    assert.doesNotMatch(html, creditAction)
  }
})

test('a completed result with no facts or implications cannot be generated again', context => {
  const client = fixture(context)
  client.setQueryData(insightKey(), result({ empty: true }))
  const html = render(client)
  assert.match(html, /직접 연결되는 검증된 인사이트가 없습니다/)
  assert.doesNotMatch(html, creditAction)
})

test('only a missing perspective shows the action and returning to a saved perspective hides it', async context => {
  const client = fixture(context)
  client.setQueryData(insightKey(), result())
  await failedLookup(client, new ApiError('COMMON404', '저장된 인사이트가 없습니다.', 404), 20, 'EQUIPMENT_MAKER')
  assert.doesNotMatch(render(client, 'CHIP_MAKER'), creditAction)
  const equipment = render(client, 'EQUIPMENT_MAKER')
  assert.match(equipment, creditAction)
  assert.doesNotMatch(equipment, /CHIP_MAKER의 검증된 인사이트/)
  assert.doesNotMatch(render(client, 'CHIP_MAKER'), creditAction)
})

test('a result for another issue does not hide this issue’s missing-perspective action', async context => {
  const client = fixture(context)
  client.setQueryData(insightKey(), result())
  await failedLookup(client, new ApiError('COMMON404', '저장된 인사이트가 없습니다.', 404), 21)
  const html = render(client, 'CHIP_MAKER', 21)
  assert.match(html, creditAction)
  assert.doesNotMatch(html, /CHIP_MAKER의 검증된 인사이트/)
})

test('initial stored-result lookup shows loading without an actionable credit button', context => {
  const html = render(fixture(context))
  assert.match(html, /저장된 관점 인사이트를 불러오는 중입니다/)
  assert.doesNotMatch(html, creditAction)
})

test('confirmed COMMON404 absence allows generation without displaying an error', async context => {
  const client = fixture(context)
  await failedLookup(client, new ApiError('COMMON404', '저장된 인사이트가 없습니다.', 404))
  const html = render(client)
  assert.match(html, creditAction)
  assert.doesNotMatch(html, /role="alert"|저장된 인사이트 다시 조회|<button[^>]*disabled/)
})

test('failed or unrecognized lookups offer stored-result retry without paid generation', async context => {
  const client = fixture(context)
  for (const error of [
    new ApiError('COMMON500', '서버 처리에 실패했습니다.', 500),
    new ApiError('NETWORK', '서버에 연결하지 못했습니다.', 502),
    new TypeError('Failed to fetch'),
    new ApiError('OTHER404', '다른 조회 오류입니다.', 404),
  ]) {
    await failedLookup(client, error)
    const html = render(client)
    assert.match(html, /role="alert"/)
    assert.ok(html.includes(error.message))
    assert.match(html, /저장된 인사이트 다시 조회/)
    assert.doesNotMatch(html, creditAction)
  }
})
