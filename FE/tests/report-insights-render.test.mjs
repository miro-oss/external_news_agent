import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { after, before, test } from 'node:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { createServer } from 'vite'
import react from '@vitejs/plugin-react'
import { reportInsightFixture } from '../scripts/report-insight-fixtures.mjs'
let server, emptyEnvDir, Content
const report = JSON.parse(await readFile(new URL('./fixtures/refactor-report.json', import.meta.url), 'utf8')).report
before(async () => {
  emptyEnvDir = await mkdtemp(join(tmpdir(), 'report-insights-render-'))
  server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false,
    envDir: emptyEnvDir, cacheDir: join(emptyEnvDir, 'vite-cache'), plugins: [react()],
    optimizeDeps: { noDiscovery: true, include: [] },
    server: { middlewareMode: true, watch: null, ws: false }, logLevel: 'error' })
  Content = (await server.ssrLoadModule('/src/features/reports/ReportInsightsPanel.tsx')).ReportInsightsContent
})
after(async () => { await server?.close(); if (emptyEnvDir) await rm(emptyEnvDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 }) })
function render(result, findings = report.findings) {
  return renderToStaticMarkup(createElement(Content, { result, insight: result.insights[0], findings, onEvidenceSelect() {} }))
}
test('report importance, priority, conditions, watch criteria, original claims and evidence are visible without confidence percentages', () => {
  const result = reportInsightFixture(report)
  const html = render(result)
  for (const value of ['리포트 중요도 높음', '우선순위 1', '직접 관련성', '영향 크기', '시급성', '비교 근거 없음',
    '영향 경로', '성립 조건', '판단 변경 조건', '확인할 지표', '판단 기준', '원문 근거 문장 1 보기', report.findings[0].keyPoints[0].text]) assert.ok(html.includes(value), value)
  assert.match(html, /저장된 분석/)
  assert.match(html, /href="https:\/\/example.invalid\/news\/0"/)
  assert.match(html, /rel="noopener noreferrer"/)
  assert.doesNotMatch(html, /신뢰도|confidence|<details[^>]* open/)
})
test('forecasts and attributed opinions retain their original type and unsafe URLs/text do not execute', () => {
  const result = reportInsightFixture(report), findings = structuredClone(report.findings)
  result.insights[0].facts[0].claimType = 'FORECAST'
  result.insights[0].facts[1].claimType = 'OPINION'
  result.insights[0].facts[1].attributedTo = '검증 발표자'
  result.insights[0].facts[0].text = '<script>claim</script>'
  findings[0].canonicalUrl = 'javascript:alert(1)'
  const html = render(result, findings)
  assert.match(html, /계획·전망/); assert.match(html, /의견·발언 · 검증 발표자/)
  assert.match(html, /&lt;script&gt;claim&lt;\/script&gt;/)
  assert.doesNotMatch(html, /href="javascript:|<script>/)
})
test('empty and unavailable analysis preserve uncertainty and do not offer paid regeneration inside saved content', () => {
  const empty = render(reportInsightFixture(report, 'CHIP_MAKER', 'empty'))
  assert.match(empty, /직접 연결되는 검증된 분석이 없습니다/)
  assert.doesNotMatch(empty, /먼저 살펴볼 이슈|크레딧 사용/)
  const uncertain = render(reportInsightFixture(report, 'CHIP_MAKER', 'unavailable'))
  assert.match(uncertain, /리포트 중요도 판단 보류/)
  assert.match(uncertain, /<dt>직접 관련성<\/dt><dd>판단 보류/)
})
test('unresolvable evidence cannot link to a later or different article', () => {
  const result = reportInsightFixture(report), fact = result.insights[0].facts[0]
  fact.articleId = 999999
  const html = render(result)
  assert.match(html, /현재 보고서에서 근거 기사를 확인할 수 없습니다/)
  assert.doesNotMatch(html, /href="https:\/\/example.invalid\/news\/0"/)
})
