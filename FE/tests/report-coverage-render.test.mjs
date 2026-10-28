import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { after, before, test } from 'node:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createServer } from 'vite'

let server, emptyEnvDir, ReadingContent, ReportsPage, Sources, fixture
before(async () => {
  emptyEnvDir = await mkdtemp(join(tmpdir(), 'report-coverage-test-'))
  server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false,
    envDir: emptyEnvDir, cacheDir: join(emptyEnvDir, 'vite-cache'),
    optimizeDeps: { noDiscovery: true, include: [] },
    server: { middlewareMode: true, watch: null, ws: false }, logLevel: 'error' })
  ReadingContent = (await server.ssrLoadModule('/src/features/reports/ReportReadingContent.tsx')).ReportReadingContent
  ReportsPage = (await server.ssrLoadModule('/src/features/reports/ReportsPage.tsx')).ReportsPage
  Sources = (await server.ssrLoadModule('/src/features/reports/ReportCoverage.tsx')).ReportCoverageSources
  fixture = JSON.parse(await readFile(new URL('./fixtures/refactor-report.json', import.meta.url), 'utf8')).report
})
after(async () => {
  delete globalThis.window
  await server?.close()
  if (emptyEnvDir) await rm(emptyEnvDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 })
})

const available = (overrides = {}) => ({ status: 'AVAILABLE', articleCount: 12, publisherCount: 4,
  unknownPublisherArticleCount: 0, representativeArticles: [], ...overrides })
const article = (overrides = {}) => ({ articleId: 801, title: '대표 기사 제목', publisher: '예시일보',
  publishedAt: '2026-10-28T23:30:00Z', canonicalUrl: 'https://example.com/news/801', ...overrides })
function report(eventIds = [1], coverageEvents = []) {
  return {
    id: 17, reportScope: 'DAILY', markdownBody: '',
    findings: [{ id: 1, articleId: 21, runId: 42, articleTitle: '요약을 뒷받침한 기사', issueId: 99, keyPoints: [],
      issue: { articleCount: 999, publisherCount: 999 } }],
    structuredContent: { executiveSummary: [], sourceNotes: [], watchItems: [], importantEvents: [
      { title: '반도체 공급 변화', summaryKo: '저장된 요약', significance: null, sourceFindingIds: eventIds },
    ] },
    coverage: { ruleVersion: 'report-coverage.v1', capturedAt: '2026-10-29T09:00:00+09:00',
      periodLabel: '2026-10-28 수집 기준', events: coverageEvents, findings: [] },
  }
}
const render = value => renderToStaticMarkup(createElement(ReadingContent, { report: value, onEvidenceSelect() {} }))

test('event coverage matches the unique finding ID set rather than order, title, position or live issue counts', () => {
  const value = report([2, 1, 1], [
    { ...available({ articleCount: 88 }), sourceFindingIds: [1] },
    { ...available({ articleCount: 7, publisherCount: 3 }), sourceFindingIds: [1, 2] },
  ])
  const before = structuredClone(value)
  const html = render(value)
  assert.match(html, /관련 기사 7건 · 3개 매체/)
  assert.doesNotMatch(html, /관련 기사 (88|999)건/)
  assert.deepEqual(value, before)
  value.structuredContent.importantEvents[0].sourceFindingIds = [1, 3]
  assert.match(render(value), /집계 자료 없음/)
})

test('legacy and unavailable coverage are distinct from verified zero and unknown publishers', () => {
  const legacy = report()
  delete legacy.coverage
  assert.match(render(legacy), /집계 자료 없음/)
  assert.doesNotMatch(render(legacy), /관련 기사 0건|매체 999/)
  const unavailable = report([1], [{ ...available({ status: 'UNAVAILABLE', articleCount: null,
    publisherCount: null, unknownPublisherArticleCount: null }), sourceFindingIds: [1] }])
  assert.match(render(unavailable), /집계 자료 없음/)
  const zero = report([1], [{ ...available({ articleCount: 0, publisherCount: 0 }), sourceFindingIds: [1] }])
  assert.match(render(zero), /관련 기사 0건 · 0개 매체/)
  assert.doesNotMatch(render(zero), /집계 자료 없음|매체 미확인/)
  const unknown = report([1], [{ ...available({ unknownPublisherArticleCount: 2 }), sourceFindingIds: [1] }])
  assert.match(render(unknown), /관련 기사 12건 · 4개 매체 · 매체 미확인 2건/)
})

test('representative reports are separate from summary evidence and retain snapshot metadata', () => {
  const value = report([1], [{ ...available({ representativeArticles: [article()] }), sourceFindingIds: [1] }])
  const html = render(value)
  assert.match(html, /대표 보도/)
  assert.match(html, /요약 근거/)
  assert.match(html, /href="https:\/\/example.com\/news\/801" target="_blank" rel="noreferrer"/)
  assert.match(html, /대표 기사 제목/)
  assert.match(html, /예시일보 · 2026\. 10\. 29\./)
  assert.match(html, /<button[^>]*>요약을 뒷받침한 기사 ↗<\/button>/)
  value.coverage.events[0] = { ...available({ articleCount: 0, publisherCount: 0 }), sourceFindingIds: [1] }
  assert.doesNotMatch(render(value), /대표 기사 제목|example.com\/news\/801/)
})

test('representative links cap at two and reject non-HTTP or credentialed URLs without inferring missing metadata', () => {
  for (const url of ['javascript:alert(1)', 'data:text/html,hello', 'https://user:password@example.com/', null]) {
    const html = renderToStaticMarkup(createElement(Sources, { coverage: available({ representativeArticles: [
      article({ title: '<img src=x onerror=alert(1)>', canonicalUrl: url, publisher: null, publishedAt: null }),
      article({ articleId: 802, title: '둘째 기사', canonicalUrl: 'https://example.com/two' }),
      article({ articleId: 803, title: '셋째 기사' }),
    ] }) }))
    assert.match(html, /&lt;img/)
    assert.match(html, /매체 미확인 · 발행일 미확인/)
    assert.equal((html.match(/<a /g) ?? []).length, 1)
    assert.doesNotMatch(html, /<img|href="javascript:|user:password|셋째 기사/)
  }
})

test('finding cards show fixed report counts and keep live counts explicitly separate', () => {
  const value = structuredClone(fixture)
  value.findings = value.findings.slice(0, 1)
  const finding = value.findings[0]
  finding.articleTitle = '생성 당시 반도체 공급 발표'
  finding.summary = '생성 당시 저장한 공급 일정 요약'
  finding.issue = { ...finding.issue, articleCount: 99, publisherCount: 88,
    title: '병합 후 다른 사건 제목', summary: '병합 후 달라진 요약' }
  value.coverage = { ruleVersion: 'report-coverage.v1', capturedAt: '2026-10-29T09:00:00+09:00',
    periodLabel: '2026-10-28 수집 기준', events: [],
    findings: [{ ...available({ representativeArticles: [article()] }), findingId: finding.id }] }
  globalThis.window = { location: { hash: `#/reports?reportId=${value.id}` } }
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity, gcTime: Infinity } } })
  client.setQueryData(['reports', value.id], value)
  client.setQueryData(['reports', 'list', value.reportScope], { content: [value] })
  client.setQueryData(['auto-deliveries', value.id], [])
  try {
    const html = renderToStaticMarkup(createElement(QueryClientProvider, { client }, createElement(ReportsPage)))
    assert.match(html, /보도량 집계<\/span>2026-10-28 수집 기준/)
    const card = html.slice(html.indexOf('class="issue-card"'))
    assert.match(card, /관련 기사 12건 · 4개 매체/)
    assert.match(card, /현재 관련 99건 · 매체 88곳/)
    assert.match(card, /생성 당시 반도체 공급 발표/)
    assert.match(card, /생성 당시 저장한 공급 일정 요약/)
    assert.doesNotMatch(card, /병합 후 다른 사건 제목|병합 후 달라진 요약/)
    assert.ok(card.indexOf('report-coverage-summary') < card.indexOf('issue-card-summary'))
    assert.ok(card.indexOf('report-coverage-sources') > card.indexOf('issue-detail-toggle'), 'links stay outside the primary card button')
  } finally { client.clear() }
})
