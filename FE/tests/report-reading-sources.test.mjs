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

for (const topicId of [null, 29]) {
  test(`saved ${topicId == null ? 'integrated' : 'topic'} weekly events keep summaries, highlights, sources and feedback without significance`, () => {
    const report = { ...reportWithSources([finding(1, 10, 42, 'AI 기사 원문'), finding(2, 10, 43)], [2, 1, 2]),
      id: 17, reportScope: 'WEEKLY', topicId,
      collectionContexts: [{ runId: 43, topics: [{ topicId: 29, topicName: 'AI', queryText: '',
        requiredKeywords: ['AI'], optionalKeywords: [], excludedKeywords: [] }] }] }
    const summary = '2026-10-13 AI 칩을 발표했다.  전력 효율은 40% 높다고 밝혔다.'
    const event = Object.freeze({ title: 'AI 칩 공개', summaryKo: summary,
      significance: '2026-10-13 주제별 분석 결과: 반복된 발표 내용. 2026-10-14 주제별 분석 결과: [전망] 추가 설명.',
      sourceFindingIds: Object.freeze([2, 1, 2]) })
    report.structuredContent.importantEvents = [event]
    const before = structuredClone(report)
    const selections = [], feedback = []
    const tree = ReportReadingContent({ report, onEvidenceSelect: (...args) => selections.push(args),
      eventFeedback: (value, index) => {
        feedback.push([value, index])
        return createElement('span', null, '기존 의견 입력')
      } })
    const html = renderToStaticMarkup(tree)
    const withoutHighlights = html.replace(/<mark\b[^>]*>|<\/mark>/g, '')

    assert.ok(withoutHighlights.includes(`<p>${summary}</p>`), 'preserve the complete saved summary, including dates and spacing')
    assert.match(html, /<mark class="collection-keyword-match">AI<\/mark> 칩/)
    assert.doesNotMatch(html, /report-event-significance|주제별 분석 결과|반복된 발표|추가 설명/)
    assert.match(html, /기존 의견 입력/)
    assert.equal(feedback.length, 1)
    assert.strictEqual(feedback[0][0], event, 'feedback must still receive the original event and its saved significance')
    assert.equal(feedback[0][1], 0)
    const buttons = sourceButtons(tree)
    assert.equal(buttons.length, 1, 'duplicate findings for one article still share one source button')
    buttons[0].props.onClick()
    assert.deepEqual(selections, [[10, 43, []]])
    assert.deepEqual(report, before, 'display-only omissions must not rewrite the stored event')
  })
}

test('daily and run events retain their significance', () => {
  for (const reportScope of ['DAILY', 'RUN']) {
    const report = { ...reportWithSources([finding(1)], [1]), reportScope }
    report.structuredContent.importantEvents[0].significance = '2026-10-13 주제별 분석 결과: 일일·수집 보고서에 남길 설명.'
    const html = renderToStaticMarkup(createElement(ReportReadingContent, { report, onEvidenceSelect() {} }))
    assert.match(html, /class="report-event-significance"/)
    assert.match(html, /2026-10-13 주제별 분석 결과: 일일·수집 보고서에 남길 설명\./)
  }
})

for (const heading of ['중요 이벤트', '주요 이슈와 주간 흐름']) {
  test(`legacy weekly ${heading} keeps each event summary and sources while omitting extra explanations`, () => {
    const report = { id: 17, reportScope: 'WEEKLY', structuredContent: null, markdownBody: `# 저장된 주간 보고서
## 핵심 요약
이번 주 전체 요약입니다.

## ${heading}
### 첫 번째 사건
2026-10-13 **AI** 칩이 공개됐다. [요약 근거](https://example.invalid/summary)

2026-10-13 주제별 분석 결과: 제거할 첫 번째 반복 설명. [설명 속 원문](https://example.invalid/first)

- 제거할 날짜 없는 중요성 설명.
- 2026-10-14 일일 보고서: 제거할 목록 설명. <https://example.invalid/bullet>

[추가 원문][extra]

[extra]: https://example.invalid/extra

### 두 번째 사건
둘째 사건은 2026-10-14 발표됐다.

제거할 두 번째 중요성 설명.

[둘째 원문](https://example.invalid/second)

## 관찰 항목
2026-10-15 주제별 분석 결과: 관찰 항목은 계속 표시한다.

## 추가 참고
2026-10-16 일일 보고서: 별도 참고도 계속 표시한다.

## 기타 분석
별도로 접어 둔 기사 분석.
` }
    const html = renderToStaticMarkup(createElement(ReportReadingContent, { report, onEvidenceSelect() {} }))
    assert.match(html, /첫 번째 사건/)
    assert.match(html, /두 번째 사건/)
    assert.match(html, /2026-10-13 <strong>AI<\/strong> 칩이 공개됐다\./)
    assert.match(html, /둘째 사건은 2026-10-14 발표됐다\./)
    assert.doesNotMatch(html, /제거할|2026-10-13 주제별 분석 결과|2026-10-14 일일 보고서/)
    for (const path of ['summary', 'first', 'bullet', 'extra', 'second']) {
      assert.ok(html.includes(`href="https://example.invalid/${path}"`), `missing source ${path}`)
    }
    assert.match(html, /2026-10-15 주제별 분석 결과: 관찰 항목은 계속 표시한다\./)
    assert.match(html, /2026-10-16 일일 보고서: 별도 참고도 계속 표시한다\./)
    assert.match(html, /기타 분석/)
    assert.match(html, /aria-expanded="false"/)
  })
}

test('legacy weekly inline chronologies stop after the saved summary and retain embedded source links', () => {
  for (const label of ['주제별 분석 결과', '일일 보고서']) {
    const report = { id: 17, reportScope: 'WEEKLY', structuredContent: null, markdownBody:
      `## 주요 이슈와 주간 흐름\n### 인수 논의\n\n2026-10-12 인수를 논의했다. 2026-10-13 ${label}: **제거할 반복 설명** [같은 문단 원문](https://example.invalid/inline).\n\n`
      + '- 제거할 추가 설명. <https://example.invalid/source_(full)>\n' }
    const html = renderToStaticMarkup(createElement(ReportReadingContent, { report, onEvidenceSelect() {} }))
    assert.match(html, /2026-10-12 인수를 논의했다\./)
    assert.doesNotMatch(html, /제거할|2026-10-13|주제별 분석 결과|일일 보고서/)
    assert.match(html, /href="https:\/\/example\.invalid\/inline"/)
    assert.match(html, /href="https:\/\/example\.invalid\/source_\(full\)"/)
  }
})

test('legacy daily and run reports retain extra event paragraphs and dated analysis', () => {
  for (const reportScope of ['DAILY', 'RUN']) {
    for (const heading of ['중요 이벤트', '주요 이슈와 주간 흐름']) {
      const report = { id: 17, reportScope, structuredContent: null, markdownBody:
        `## ${heading}\n### 기존 사건\n\n남길 첫 요약.\n\n남길 중요성 설명.\n\n2026-10-13 주제별 분석 결과: 남길 날짜별 근거.\n` }
      const html = renderToStaticMarkup(createElement(ReportReadingContent, { report, onEvidenceSelect() {} }))
      assert.match(html, /남길 첫 요약\./)
      assert.match(html, /남길 중요성 설명\./)
      assert.match(html, /2026-10-13 주제별 분석 결과: 남길 날짜별 근거\./)
    }
  }
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
