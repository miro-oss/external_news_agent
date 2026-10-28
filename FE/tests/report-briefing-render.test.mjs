import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { after, before, test } from 'node:test'
import { Children, createElement, isValidElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { createServer } from 'vite'

let server, emptyEnvDir, Reading, Briefing
before(async () => {
  emptyEnvDir = await mkdtemp(join(tmpdir(), 'report-briefing-test-'))
  server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false,
    envDir: emptyEnvDir, cacheDir: join(emptyEnvDir, 'vite-cache'), optimizeDeps: { noDiscovery: true, include: [] },
    server: { middlewareMode: true, watch: null, ws: false }, logLevel: 'error' })
  Reading = (await server.ssrLoadModule('/src/features/reports/ReportReadingContent.tsx')).ReportReadingContent
  Briefing = (await server.ssrLoadModule('/src/features/reports/ReportBriefing.tsx')).ReportBriefing
})
after(async () => {
  await server?.close()
  if (emptyEnvDir) await rm(emptyEnvDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 })
})
function fixture() {
  const sourceFindingIds = [1]
  return { id: 17, reportScope: 'RUN', markdownBody: '', findings: [{ id: 1, articleId: 21, runId: 42,
    articleTitle: '저장된 기사', keyPoints: [] }],
    structuredContent: { executiveSummary: ['원래 핵심 요약'], importantEvents: [{ title: '양산 목표 발표',
      summaryKo: '회사는 양산 목표를 발표했다.', significance: '목표 변경이 실행을 뜻하지는 않는다.', sourceFindingIds }],
      watchItems: [], sourceNotes: [] },
    briefing: { version: 'report-briefing.v1', items: [{ title: '양산 목표 발표', summary: '회사는 양산 목표를 발표했다.',
      significance: '목표 변경이 실행을 뜻하지는 않는다.', sourceFindingIds }],
      watchItems: [{ topic: '후속 공식 발표', reason: '실제 생산 계획의 반영 여부를 확인한다.', sourceFindingIds }], omittedItemCount: 2 },
    coverage: { ruleVersion: 'report-coverage.v1', periodLabel: '해당 실행 수집 기준', events: [{ sourceFindingIds,
      status: 'AVAILABLE', articleCount: 12, publisherCount: 4, unknownPublisherArticleCount: 0, representativeArticles: [] }], findings: [] },
  }
}
const render = report => renderToStaticMarkup(createElement(Reading, { report, onEvidenceSelect() {},
  afterBriefing: createElement('aside', null, '선택 관점 카드') }))

test('briefing leads with coverage, complete qualifications and next check before perspective and closed detail', () => {
  const report = fixture(), before = structuredClone(report)
  const html = render(report)
  assert.ok(html.indexOf('핵심 브리핑') < html.indexOf('선택 관점 카드'))
  assert.ok(html.indexOf('선택 관점 카드') < html.indexOf('class="report-reading-details"'))
  const brief = html.slice(0, html.indexOf('class="report-reading-details"'))
  assert.match(brief, /관련 기사 12건 · 4개 매체/)
  assert.match(brief, /목표 변경이 실행을 뜻하지는 않는다\./)
  assert.match(brief, /실제 생산 계획의 반영 여부를 확인한다\./)
  assert.match(brief, /더 읽을 항목 2개/)
  assert.match(brief, /aria-controls="report-17-event-0"/)
  assert.match(html, /id="report-17-event-0" tabindex="-1"/)
  assert.match(html, /원래 핵심 요약/)
  assert.doesNotMatch(html, /<details[^>]* open|\.\.\.|…/)
  assert.deepEqual(report, before)
})

test('briefing never restores a missing referenced finding and leaves a mixed-source item out as a whole', () => {
  const report = fixture()
  report.briefing.items[0].sourceFindingIds = [1, 999]
  report.briefing.watchItems[0].sourceFindingIds = [999]
  const html = renderToStaticMarkup(createElement(Briefing, { report, terms: [] }))
  assert.doesNotMatch(html, /양산 목표 발표|후속 공식 발표|관련 기사/)
  assert.match(html, /간단히 표시할 핵심 항목이 없습니다/)
})

test('legacy responses retain their body and report text stays escaped', () => {
  const report = fixture()
  delete report.briefing
  assert.match(render(report), /원래 핵심 요약/)
  assert.doesNotMatch(render(report), /report-reading-details|핵심 브리핑/)
  report.briefing = fixture().briefing
  report.briefing.items[0].title = '<img src=x onerror=alert(1)>'
  const html = render(report)
  assert.match(html, /&lt;img src=x onerror=alert\(1\)&gt;/)
  assert.doesNotMatch(html, /<img|<script/)
})

test('M2 weekly details preserve a complete long significance omitted from the short briefing', () => {
  const report = fixture()
  report.reportScope = 'WEEKLY'
  const significance = '계획은 아직 확정되지 않았으며 실제 발주가 확인될 때만 일정을 변경할 수 있다. '.repeat(6)
  report.structuredContent.importantEvents[0].significance = significance
  report.briefing.items = []
  const html = render(report)
  const details = html.slice(html.indexOf('class="report-reading-details"'))
  assert.ok(details.includes(significance))
  assert.match(details, /report-event-significance/)
})

test('event navigation distinguishes different events with the same source set', () => {
  const report = fixture()
  report.structuredContent.importantEvents.unshift({ ...report.structuredContent.importantEvents[0],
    title: '같은 근거의 다른 사건', summaryKo: '별도로 정리한 다른 소식이다.' })
  const html = render(report)
  const brief = html.slice(0, html.indexOf('class="report-reading-details"'))
  assert.match(brief, /aria-controls="report-17-event-1"/)
  assert.doesNotMatch(brief, /aria-controls="report-17-event-0"/)
})

test('M2 weekly details retain the existing suppression of internal dated analysis chronology', () => {
  for (const label of ['주제별 분석 결과', '일일 보고서']) {
    const report = fixture()
    report.reportScope = 'WEEKLY'
    report.structuredContent.importantEvents[0].significance = `2026-10-12 ${label}: 반복된 내부 분석 내용이다.`
    report.briefing.items[0].significance = null
    const html = render(report)
    assert.doesNotMatch(html, /반복된 내부 분석|report-event-significance/)
    assert.match(html, /회사는 양산 목표를 발표했다\./)
  }
})

test('briefing remains readable when the API intentionally omits findings', () => {
  const report = fixture()
  delete report.findings
  const html = render(report)
  assert.match(html, /핵심 브리핑/)
  assert.match(html, /관련 기사 12건 · 4개 매체/)
  assert.match(html, /실제 생산 계획의 반영 여부를 확인한다\./)
})

test('a briefing source opens the preserved event without changing the hash route', context => {
  const report = fixture()
  report.briefing.items[0].sourceFindingIds = [1, 1]
  let focused = false, scrolled = false
  class Details { open = false; parentElement = null }
  const detail = new Details()
  const element = { parentElement: detail, focus: () => { focused = true }, scrollIntoView: () => { scrolled = true } }
  globalThis.document = { getElementById: id => id === 'report-17-event-0' ? element : null }
  globalThis.HTMLDetailsElement = Details
  globalThis.window = { matchMedia: () => ({ matches: true }) }
  context.after(() => { delete globalThis.document; delete globalThis.HTMLDetailsElement; delete globalThis.window })
  function buttons(node) {
    if (!isValidElement(node)) return []
    return node.type === 'button' ? [node] : Children.toArray(node.props.children).flatMap(buttons)
  }
  const targets = buttons(Briefing({ report, terms: [] }))
  assert.equal(targets.length, 1)
  targets[0].props.onClick()
  assert.equal(detail.open, true)
  assert.equal(focused, true)
  assert.equal(scrolled, true)
})
