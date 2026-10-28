import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { after, before, test } from 'node:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { createServer } from 'vite'

let server, emptyEnvDir, KeywordStats, Reading
before(async () => {
  emptyEnvDir = await mkdtemp(join(tmpdir(), 'report-keywords-test-'))
  server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false,
    envDir: emptyEnvDir, cacheDir: join(emptyEnvDir, 'vite-cache'), optimizeDeps: { noDiscovery: true, include: [] },
    server: { middlewareMode: true, watch: null, ws: false }, logLevel: 'error' })
  KeywordStats = (await server.ssrLoadModule('/src/features/reports/ReportKeywordStats.tsx')).ReportKeywordStats
  Reading = (await server.ssrLoadModule('/src/features/reports/ReportReadingContent.tsx')).ReportReadingContent
})
after(async () => {
  await server?.close()
  if (emptyEnvDir) await rm(emptyEnvDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 })
})

const fixture = () => ({ status: 'AVAILABLE', unavailableReason: null, ruleVersion: 'report-keywords.v1',
  capturedAt: '2026-10-29T09:00:00+09:00', periodLabel: '2026-10-28 수집 기준 (한국시간)', denominatorIssueCount: 12,
  items: [
    { keyword: 'HBM', issueCount: 8, sharePercent: 66.7, relatedKeywords: [{ keyword: '패키징', issueCount: 4 }] },
    { keyword: '패키징', issueCount: 7, sharePercent: 58.3, relatedKeywords: [{ keyword: 'HBM', issueCount: 4 }] },
    { keyword: '장비', issueCount: 0, sharePercent: 0, relatedKeywords: [] },
  ], omittedKeywordCount: 2 })
const render = stats => renderToStaticMarkup(createElement(KeywordStats, { stats }))

test('counts and percentages use the complete server denominator instead of displayed rows or findings', () => {
  const stats = fixture(), original = structuredClone(stats)
  const html = render(stats)
  assert.match(html, /전체 유효 이슈 <strong>12건<\/strong> · 상위 선정 전/)
  assert.match(html, /8건 \/ 전체 12건/)
  assert.match(html, /66\.7%/)
  assert.match(html, /width:66\.7%/)
  assert.match(html, /58\.3%/)
  assert.match(html, /비중 합계는 100%를 넘을 수 있습니다/)
  assert.match(html, /등록 키워드 5개 중 상위 3개/)
  assert.match(html, /2026-10-28 수집 기준 \(한국시간\)/)
  assert.deepEqual(stats, original)
})

test('related registered words retain same-issue counts and do not become inferred trends', () => {
  const html = render(fixture())
  assert.match(html, /함께 등장/)
  assert.match(html, /패키징<span> · 4건<\/span>/)
  assert.match(html, /기사 제목·본문에서 확인한 등록 키워드 기준/)
  assert.match(html, /같은 이슈에 연결된 키워드/)
  assert.doesNotMatch(html, /급상승|전주 대비|시장 점유율|<canvas|<svg/)
})

test('zero matching issues is a real zero percent, while an empty denominator has no percentage', () => {
  const populated = render(fixture())
  assert.match(populated, /0건 \/ 전체 12건/)
  assert.match(populated, /<strong>0%<\/strong>/)
  const stats = fixture()
  stats.denominatorIssueCount = 0
  stats.items = [{ keyword: 'HBM', issueCount: 0, sharePercent: null, relatedKeywords: [] }]
  stats.omittedKeywordCount = 0
  const empty = render(stats)
  assert.match(empty, /유효 이슈가 0건이어서 비중을 계산하지 않았습니다/)
  assert.match(empty, /<strong>비중 없음<\/strong>/)
  assert.doesNotMatch(empty, /<strong>0%<\/strong>|NaN|Infinity/)
})

test('an available scope without registered words is distinct from missing historical data', () => {
  const stats = fixture()
  stats.items = []
  stats.omittedKeywordCount = 0
  const html = render(stats)
  assert.match(html, /전체 유효 이슈 <strong>12건/)
  assert.match(html, /집계할 등록 키워드가 없습니다/)
  assert.doesNotMatch(html, /집계 자료 없음|report-keyword-track/)
})

test('unavailable reasons explain missing coverage without leaking stale items or zero counts', () => {
  const messages = {
    NO_SNAPSHOT: '생성 당시의 키워드 집계 자료가 없습니다',
    INCOMPLETE_SOURCE: '일부 원본 보고서의 집계 자료가 없어',
    MISSING_COLLECTION_CONTEXT: '당시 수집 키워드를 확인할 수 없어',
    INVALID_SNAPSHOT: '저장된 집계 자료를 확인할 수 없어',
    COMPANY_SCOPE_UNVERIFIED: '전체 후보에 회사 관심 기준이 적용되었는지',
    PERSONALIZATION_UNVERIFIED: '전체 후보에 개인 기준이 적용되었는지',
  }
  for (const [reason, message] of Object.entries(messages)) {
    const html = render({ ...fixture(), status: 'UNAVAILABLE', unavailableReason: reason, denominatorIssueCount: null })
    assert.match(html, /집계 자료 없음/)
    assert.ok(html.includes(message))
    assert.doesNotMatch(html, /HBM|66\.7%|전체 유효 이슈|report-keyword-track/)
  }
})

test('older servers omit the card, and keyword text is escaped without remote resources', () => {
  assert.equal(render(undefined), '')
  assert.equal(render(null), '')
  const stats = fixture()
  stats.items[0].keyword = '<img src="https://example.com/tracker" onerror="alert(1)">'
  stats.items[0].relatedKeywords[0].keyword = '<script>alert(1)</script>'
  const html = render(stats)
  assert.match(html, /&lt;img/)
  assert.match(html, /&lt;script&gt;/)
  assert.doesNotMatch(html, /<img|<script|<iframe/)
})

test('briefing and selected perspective lead the chart, before preserved report detail', () => {
  const report = { id: 17, reportScope: 'RUN', markdownBody: '',
    structuredContent: { executiveSummary: ['원래 상세 요약'], importantEvents: [], watchItems: [], sourceNotes: [] },
    briefing: { version: 'report-briefing.v1', items: [], watchItems: [], omittedItemCount: 0 }, keywordStats: fixture() }
  const html = renderToStaticMarkup(createElement(Reading, { report, onEvidenceSelect() {},
    afterBriefing: createElement('aside', null, '선택 관점 분석') }))
  assert.ok(html.indexOf('핵심 브리핑') < html.indexOf('선택 관점 분석'))
  assert.ok(html.indexOf('선택 관점 분석') < html.indexOf('키워드로 보는 이슈'))
  assert.ok(html.indexOf('키워드로 보는 이슈') < html.indexOf('class="report-reading-details"'))
  assert.match(html, /8건 \/ 전체 12건/)
  assert.match(html, /원래 상세 요약/)
})

test('a legacy markdown report can explain unavailable stats without fabricating a chart', () => {
  const report = { id: 17, reportScope: 'RUN', markdownBody: '## 핵심 요약\n저장된 원래 내용',
    keywordStats: { ...fixture(), status: 'UNAVAILABLE', unavailableReason: 'NO_SNAPSHOT', denominatorIssueCount: null } }
  const html = renderToStaticMarkup(createElement(Reading, { report, onEvidenceSelect() {} }))
  assert.match(html, /키워드로 보는 이슈/)
  assert.match(html, /집계 자료 없음/)
  assert.match(html, /저장된 원래 내용/)
  assert.doesNotMatch(html, /HBM|report-keyword-track|핵심 브리핑/)
})
