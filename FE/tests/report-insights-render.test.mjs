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
import react from '@vitejs/plugin-react'
import { reportInsightFixture } from '../scripts/report-insight-fixtures.mjs'
let server, emptyEnvDir, Content, Panel, ApiError, insightKey, snapshot
const report = JSON.parse(await readFile(new URL('./fixtures/refactor-report.json', import.meta.url), 'utf8')).report
before(async () => {
  emptyEnvDir = await mkdtemp(join(tmpdir(), 'report-insights-render-'))
  server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false,
    envDir: emptyEnvDir, cacheDir: join(emptyEnvDir, 'vite-cache'), plugins: [react()],
    optimizeDeps: { noDiscovery: true, include: [] },
    server: { middlewareMode: true, watch: null, ws: false }, logLevel: 'error' })
  const panel = await server.ssrLoadModule('/src/features/reports/ReportInsightsPanel.tsx')
  Content = panel.ReportInsightsContent
  Panel = panel.ReportInsightsPanel
  ApiError = (await server.ssrLoadModule('/src/api/client.ts')).ApiError
  const api = await server.ssrLoadModule('/src/api/reportInsights.ts')
  insightKey = api.reportInsightKey
  snapshot = api.reportInsightSnapshotKey(report)
})
after(async () => { await server?.close(); if (emptyEnvDir) await rm(emptyEnvDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 }) })
function render(result, findings = report.findings) {
  return renderToStaticMarkup(createElement(Content, { result, insight: result.insights[0], findings, onEvidenceSelect() {} }))
}
function renderPanel(state, panelReport = report) {
  // Freeze the supplied lifecycle state; API tests exercise the actual reads and retries.
  const client = new QueryClient({ defaultOptions: { queries: { enabled: false } } })
  const query = client.getQueryCache().build(client, { queryKey: insightKey(report.id, 'CHIP_MAKER', snapshot) })
  query.setState(state)
  try {
    return renderToStaticMarkup(createElement(QueryClientProvider, { client }, createElement(Panel, {
      report: panelReport, audience: 'CHIP_MAKER', selector: createElement('span', null, '관점 선택'), onEvidenceSelect() {},
    })))
  } finally { client.clear() }
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
test('an automatically queued report shows preparation and no manual generation prerequisite', () => {
  const reason = new ApiError('COMMON409', '동일한 리포트 관점 인사이트 생성 요청이 진행 중입니다. 잠시 후 다시 확인해주세요.', 409)
  const html = renderPanel({ status: 'pending', fetchStatus: 'fetching', fetchFailureCount: 1, fetchFailureReason: reason })
  assert.match(html, /보고서를 만들 때 네 관점의 분석을 자동으로 준비/)
  assert.match(html, /관점 분석을 자동으로 준비하고 있습니다/)
  assert.match(html, /완료되면 분석 결과가 자동으로 표시됩니다/)
  assert.match(html, /조회와 관점 전환은 추가 크레딧을 사용하지 않습니다/)
  assert.doesNotMatch(html, /<button|분석 결과가 없습니다|생성하지 못했습니다/)
})
test('pending preparation after the poll budget offers only a stored-result refresh', () => {
  const reason = new ApiError('COMMON409', '동일한 리포트 관점 인사이트 생성 요청이 진행 중입니다. 잠시 후 다시 확인해주세요.', 409)
  const html = renderPanel({ status: 'error', fetchStatus: 'idle', error: reason, fetchFailureReason: reason })
  assert.match(html, /관점 분석 준비가 계속되고 있습니다/)
  assert.match(html, /저장된 분석 다시 확인/)
  assert.doesNotMatch(html, /분석 다시 준비 · 크레딧 사용|불러오지 못했습니다/)
})
test('terminal or legacy absence offers an explicit retry with its usage notice', () => {
  const reason = new ApiError('COMMON404', '저장된 리포트 관점 인사이트가 없습니다.', 404)
  const html = renderPanel({ status: 'error', fetchStatus: 'idle', error: reason, fetchFailureReason: reason })
  assert.match(html, /이 관점의 분석 결과가 없습니다/)
  assert.match(html, /이 관점 분석 다시 준비 · 크레딧 사용/)
  assert.match(html, /새 분석 생성 시 인사이트 크레딧을 사용/)
  assert.doesNotMatch(html, /이 관점으로 리포트 분석 생성|관점 분석을 자동으로 준비하고 있습니다/)
})
test('real lookup errors retain the server message and offer GET reload only', () => {
  const reason = new ApiError('COMMON409', '리포트 관점 인사이트 기능이 현재 비활성화되어 있습니다.', 409)
  const html = renderPanel({ status: 'error', fetchStatus: 'idle', error: reason, fetchFailureReason: reason })
  assert.match(html, /저장된 관점 분석을 불러오지 못했습니다/)
  assert.match(html, /리포트 관점 인사이트 기능이 현재 비활성화되어 있습니다/)
  assert.match(html, /다시 불러오기/)
  assert.doesNotMatch(html, /분석 다시 준비 · 크레딧 사용|관점 분석을 자동으로 준비하고 있습니다/)
})
test('a completed automatic result is displayed with evidence and no paid generation action', () => {
  const result = reportInsightFixture(report)
  const html = renderPanel({ status: 'success', fetchStatus: 'idle', data: result, dataUpdatedAt: Date.now() })
  assert.match(html, /리포트 중요도 높음/)
  assert.match(html, /원문 근거 문장 1 보기/)
  assert.match(html, /저장된 분석 새로고침/)
  assert.doesNotMatch(html, /분석 다시 준비 · 크레딧 사용|관점 분석을 자동으로 준비하고 있습니다|분석 결과가 없습니다/)
})
