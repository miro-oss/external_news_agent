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
import { weeklyReportFixture } from '../scripts/weekly-report-fixtures.mjs'

let server, emptyEnvDir, ReportsPage, Sources, fixture
before(async () => {
  emptyEnvDir = await mkdtemp(join(tmpdir(), 'weekly-report-test-'))
  server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false,
    envDir: emptyEnvDir, cacheDir: join(emptyEnvDir, 'vite-cache'),
    optimizeDeps: { noDiscovery: true, include: [] },
    server: { middlewareMode: true, watch: null, ws: false }, logLevel: 'error' })
  ReportsPage = (await server.ssrLoadModule('/src/features/reports/ReportsPage.tsx')).ReportsPage
  Sources = (await server.ssrLoadModule('/src/features/reports/WeeklyReportSources.tsx')).WeeklyReportSources
  fixture = JSON.parse(await readFile(new URL('./fixtures/refactor-report.json', import.meta.url), 'utf8'))
})
after(async () => {
  delete globalThis.window
  await server?.close()
  if (emptyEnvDir) await rm(emptyEnvDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 })
})

function renderPage(report, scope = report.reportScope, hash = `#/reports?reportId=${report.id}`, reports = [report]) {
  globalThis.window = { location: { hash } }
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity, gcTime: Infinity } } })
  for (const item of reports) client.setQueryData(['reports', item.id], item)
  client.setQueryData(['reports', 'list', scope], { content: reports })
  const html = renderToStaticMarkup(createElement(QueryClientProvider, { client }, createElement(ReportsPage)))
  const changesQueries = client.getQueryCache().findAll().filter(query => query.queryKey.at(-1) === 'changes')
  client.clear()
  return { html, changesQueries }
}

test('weekly list and initial selection use the newest aggregation period while older deep links stay selected', () => {
  const base = weeklyReportFixture(fixture.report)
  const reports = [
    { ...base, id: 301, reportDate: '2026-08-31', reportEndDate: '2026-09-06', generatedAt: '2026-09-23T00:00:00+09:00' },
    { ...base, id: 302, reportDate: '2026-09-07', reportEndDate: '2026-09-13', generatedAt: '2026-09-22T00:00:00+09:00' },
    { ...base, id: 303, reportDate: '2026-09-14', reportEndDate: '2026-09-20', generatedAt: '2026-09-21T00:00:00+09:00' },
  ]
  const listItems = html => [...html.matchAll(/<button\b[^>]*class="report-list-item(?: active)?"[\s\S]*?<\/button>/gu)].map(match => match[0])
  const { html } = renderPage(reports[0], 'WEEKLY', '#/reports?reportScope=WEEKLY', reports)
  const items = listItems(html)
  assert.equal(items.length, 3)
  for (const [index, date] of ['2026-09-14', '2026-09-07', '2026-08-31'].entries()) {
    assert.ok(items[index].includes(`집계 기간 ${date}`))
  }
  assert.match(items[0], /class="report-list-item active"/)
  assert.match(html.split('class="report-detail-shell"')[1], /2026-09-14 ~ 2026-09-20 주간 통합 뉴스 보고서/)
  const linked = renderPage(reports[0], 'WEEKLY', '#/reports?reportId=301', reports).html
  assert.match(listItems(linked)[2], /class="report-list-item active"/)
  assert.match(linked.split('class="report-detail-shell"')[1], /2026-08-31 ~ 2026-09-06 주간 통합 뉴스 보고서/)
})

test('weekly deep links select the weekly tab and render source coverage without requesting comparisons', () => {
  const report = weeklyReportFixture(fixture.report)
  const { html, changesQueries } = renderPage(report)
  assert.match(html, /aria-pressed="true"[^>]*>주간 통합/)
  assert.match(html, /2026-09-07 ~ 2026-09-13 주간 통합 뉴스 보고서/)
  assert.match(html, /7일 중 <strong>2일<\/strong>/)
  assert.match(html, /일일 보고서가 없는 날/)
  assert.match(html, /href="#\/reports\?reportId=116"/)
  assert.match(html, /href="#\/reports\?reportId=117"/)
  assert.match(html, /선택한 기간과 겹치는 주간 보고서/)
  assert.doesNotMatch(html, /지난 보고서와 달라진 점|report-changes-panel/)
  assert.equal(changesQueries.length, 0)
})

test('weekly findings preserve saved titles and distinct findings even when live issue IDs merged', () => {
  const report = weeklyReportFixture(fixture.report)
  report.structuredContent = { executiveSummary: [], importantEvents: [], watchItems: [], sourceNotes: [] }
  report.findings = report.findings.slice(0, 2).map((finding, index) => ({ ...finding, issueId: 777,
    articleTitle: `저장된 주간 근거 ${index + 1}`, summary: `저장된 주간 요약 ${index + 1}`,
    issue: { ...finding.issue, id: 777, title: '현재 합쳐진 이슈 제목', summary: '현재 변경된 이슈 요약' } }))
  const { html } = renderPage(report)
  assert.equal((html.match(/class="issue-card"/g) ?? []).length, 2)
  assert.match(html, /저장된 주간 근거 1/)
  assert.match(html, /저장된 주간 근거 2/)
  assert.match(html, /저장된 주간 요약 1/)
  assert.doesNotMatch(html, /현재 합쳐진 이슈 제목|현재 변경된 이슈 요약/)
})

test('daily comparison remains mounted and scope-only weekly URLs render the weekly list', () => {
  const daily = { ...fixture.report, id: 117, runId: null, reportScope: 'DAILY', reportDate: '2026-09-08' }
  const { html, changesQueries } = renderPage(daily)
  assert.match(html, /지난 보고서와 달라진 점/)
  assert.equal(changesQueries.length, 1)
  assert.match(renderPage(weeklyReportFixture(fixture.report), 'WEEKLY', '#/reports?reportScope=WEEKLY').html, /data-report-scope="WEEKLY"/)
})

test('complete weekly coverage omits missing-day warnings; legacy missing fields do not create invalid links', () => {
  const full = { sourceReportCount: 7, sourceReportIds: [1, 2, 3, 4, 5, 6, 7],
    sourceReportDates: Array.from({ length: 7 }, (_, index) => `2026-09-${String(index + 7).padStart(2, '0')}`), missingReportDates: [] }
  const html = renderToStaticMarkup(createElement(Sources, { report: full }))
  assert.equal((html.match(/<a /g) ?? []).length, 7)
  assert.match(html, /7일 중 <strong>7일<\/strong>/)
  assert.doesNotMatch(html, /일일 보고서가 없는 날/)
  const legacy = renderToStaticMarkup(createElement(Sources, { report: {} }))
  assert.match(legacy, /원본 일일 보고서 날짜 기록이 없습니다/)
  assert.doesNotMatch(legacy, /href=|undefined|0개/)
})

test('weekly loading waits for the list before explaining the Monday generation schedule', () => {
  globalThis.window = { location: { hash: '#/reports?reportScope=WEEKLY' } }
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity, gcTime: Infinity } } })
  const render = () => renderToStaticMarkup(createElement(QueryClientProvider, { client }, createElement(ReportsPage)))
  const loading = render()
  assert.match(loading, /보고서 목록과 본문을 불러오는 중/)
  assert.doesNotMatch(loading, /표시할 보고서가 없습니다/)
  client.setQueryData(['reports', 'list', 'WEEKLY'], { content: [] })
  const empty = render()
  assert.match(empty, /표시할 보고서가 없습니다/)
  assert.match(empty, /매주 월요일, 지난주 월~일의 일일 통합 보고서를 모아 주간 보고서를 만듭니다/)
  assert.doesNotMatch(empty, /report-changes-panel|수집을 실행하면 분석 완료/)
  client.clear()
})
