import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { after, afterEach, before, test } from 'node:test'
import { createElement } from 'react'
import { setImmediate } from 'node:timers/promises'
import { renderToStaticMarkup } from 'react-dom/server'
import { createServer } from 'vite'
import react from '@vitejs/plugin-react'

const fixtureId = '\0test:report-event-feedback-context'
const readingFixtureId = '\0test:report-event-feedback-reading'
const contextFixturePlugin = {
  name: 'report-event-feedback-context-fixture',
  enforce: 'pre',
  resolveId(source, importer) {
    if (source === 'test:report-event-feedback-context'
      || (source === './useReportEventFeedback' && (importer?.endsWith('/ReportReadingWithFeedback.tsx')
        || importer?.endsWith('/ReportFeedbackHistory.tsx')))) return fixtureId
    if (source === 'test:report-event-feedback-reading'
      || (source === './ReportReadingContent' && importer?.endsWith('/ReportReadingWithFeedback.tsx'))) return readingFixtureId
  },
  load(id) {
    if (id === readingFixtureId) return `
      import { createElement } from 'react';
      import { ReportReadingContent as Reading } from '/src/features/reports/ReportReadingContent.tsx';
      let props;
      export function ReportReadingContent(value) { props = value; return createElement(Reading, value); }
      export function getReadingProps() { return props; }
    `
    if (id !== fixtureId) return
    return `
      const defaults = { data: null, error: null, busy: false, paused: false, refresh() {}, recordFeedback() {} };
      let fixture = defaults;
      export function setFeedbackFixture(overrides = {}) { fixture = { ...defaults, ...overrides }; }
      export function useReportEventFeedback() { return fixture; }
    `
  },
}

let server, emptyEnvDir, Card, Reading, ReadingWithFeedback, History, setFeedbackFixture, getReadingProps
before(async () => {
  emptyEnvDir = await mkdtemp(join(tmpdir(), 'report-event-feedback-render-'))
  server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false,
    envDir: emptyEnvDir, cacheDir: join(emptyEnvDir, 'vite-cache'), plugins: [contextFixturePlugin, react()],
    optimizeDeps: { noDiscovery: true, include: [] }, server: { middlewareMode: true, watch: null, ws: false }, logLevel: 'error' })
  const module = await server.ssrLoadModule('/src/features/reports/ReportReadingWithFeedback.tsx')
  Card = module.ReportEventFeedbackCard
  ReadingWithFeedback = module.ReportReadingWithFeedback
  Reading = (await server.ssrLoadModule('/src/features/reports/ReportReadingContent.tsx')).ReportReadingContent
  setFeedbackFixture = (await server.ssrLoadModule('test:report-event-feedback-context')).setFeedbackFixture
  getReadingProps = (await server.ssrLoadModule('test:report-event-feedback-reading')).getReadingProps
  History = (await server.ssrLoadModule('/src/features/reports/ReportFeedbackHistory.tsx')).ReportFeedbackHistory
})
afterEach(() => { setFeedbackFixture?.() })
after(async () => { await server?.close(); if (emptyEnvDir) await rm(emptyEnvDir, { recursive: true, force: true, maxRetries: 3 }) })
const event = { title: '서로 다른 공급 계약', summaryKo: '두 기업의 계획을 묶은 이벤트입니다.', significance: '분리해서 확인해야 합니다.', sourceFindingIds: [501, 502, 503] }
const target = { ...event, summary: event.summaryKo, eventIndex: 0, eventKey: 'a'.repeat(64) }
const report = { id: 17, markdownBody: '', collectionContexts: [], findings: [501, 502, 503].map((id, index) => ({
  id, articleId: index < 2 ? 10 : 20, runId: 100 + index, articleTitle: `근거 기사 ${index < 2 ? 1 : 2}`, keyPoints: [],
})), structuredContent: { executiveSummary: ['핵심 요약'], importantEvents: [event], watchItems: [], sourceNotes: [] } }
const actions = { onFeedback() {}, async onRefreshReport() {} }
const saved = { id: 1, eventKey: target.eventKey, category: 'WRONG_CLUSTER', comment: '각각 다른 계약이에요.', status: 'PENDING', diagnosis: null, verdict: null, createdAt: '2026-09-29T12:00:00+09:00' }
const watch = { topic: '양산 일정 확인', reason: '후속 계약 발표를 확인합니다.', sourceFindingIds: [503, 501] }
function card(props = {}) { return createElement(Card, { reportId: 17, target, ...actions, ...props }) }

function eventCardKeys(events, reportId = report.id) {
  const tree = Reading({ report: { ...report, id: reportId,
    structuredContent: { ...report.structuredContent, importantEvents: events } }, onEvidenceSelect() {} })
  const keys = []
  function visit(node) {
    if (Array.isArray(node)) { node.forEach(visit); return }
    if (!node || typeof node !== 'object') return
    if (node.type === 'article' && node.props.className === 'report-event-card') keys.push(node.key)
    visit(node.props?.children)
  }
  visit(tree)
  return keys
}

test('removing an earlier event preserves surviving outer card identities and their draft ownership', () => {
  const earlier = { ...event, title: '검토 후 제외된 이벤트' }
  const later = { ...event, title: '뒤에 남아 있는 이벤트', sourceFindingIds: [503, 501] }
  const before = eventCardKeys([earlier, event, later])
  assert.deepEqual(eventCardKeys([event, later]), before.slice(1))
  assert.deepEqual(eventCardKeys([later, event]), [before[2], before[1]])
  assert.notEqual(eventCardKeys([event], 18)[0], before[1], 'different reports never share a draft identity')
  for (const change of [{ title: '바뀐 제목' }, { summaryKo: '바뀐 요약' }, { significance: '바뀐 의미' },
    { sourceFindingIds: [503, 502, 501] }]) {
    assert.notEqual(eventCardKeys([{ ...event, ...change }])[0], before[1], 'changed event content gets a fresh card')
  }
})

test('identical event content has distinct card identities that survive removing an unrelated event', () => {
  const earlier = { ...event, title: '서로 다른 앞 이벤트' }
  const before = eventCardKeys([earlier, event, { ...event, sourceFindingIds: [...event.sourceFindingIds] }])
  assert.equal(new Set(before).size, 3)
  assert.deepEqual(eventCardKeys([event, event]), before.slice(1))
})

test('watch cards offer promotion below their source links, even when the report has no important events', () => {
  const markup = renderToStaticMarkup(createElement(ReadingWithFeedback, {
    report: { ...report, structuredContent: { ...report.structuredContent, importantEvents: [], watchItems: [watch] } },
    onEvidenceSelect() {}, ...actions,
  }))
  assert.match(markup, /report-watch-items[\s\S]*양산 일정 확인[\s\S]*report-event-sources[\s\S]*report-watch-promote/)
  assert.match(markup, /aria-label="양산 일정 확인 중요 이벤트로 올리기"/)
  assert.doesNotMatch(markup, /의견 보내기|<form|disabled=""/)
})

test('watch promotion refreshes report caches and feedback context without using the opinion exclusion callback', async () => {
  const calls = []
  setFeedbackFixture({ refresh() { calls.push('context') } })
  renderToStaticMarkup(createElement(ReadingWithFeedback, {
    report: { ...report, structuredContent: { ...report.structuredContent, watchItems: [watch] } }, onEvidenceSelect() {},
    async onRefreshReport() { calls.push('report') }, async onFeedbackSubmitted() { calls.push('opinion') },
  }))
  await getReadingProps().watchItemActions(watch, 0).props.onRefresh()
  assert.deepEqual(calls, ['report', 'context'])
})

test('promoting an earlier watch item preserves other cards and changes identity when switching reports', () => {
  function keys(items, id = report.id) {
    const tree = Reading({ report: { ...report, id, structuredContent: { ...report.structuredContent, importantEvents: [], watchItems: items } }, onEvidenceSelect() {} })
    const result = []
    function visit(node) {
      if (Array.isArray(node)) { node.forEach(visit); return }
      if (!node || typeof node !== 'object') return
      if (node.type === 'article' && node.props.className === 'report-event-card') result.push(node.key)
      visit(node.props?.children)
    }
    visit(tree)
    return result
  }
  const earlier = { ...watch, topic: '앞 관찰 항목' }
  assert.deepEqual(keys([earlier, watch]).slice(1), keys([watch]))
  assert.notEqual(keys([watch], 18)[0], keys([watch])[0])
  assert.notEqual(keys([{ ...watch, reason: '새 이유' }])[0], keys([watch])[0])
  assert.equal(new Set(keys([watch, watch])).size, 2)
})

test('the important event itself owns the inline composer, with automatic multi-source binding and no article/recipient picker', () => {
  const bound = []
  const markup = renderToStaticMarkup(createElement(Reading, { report, onEvidenceSelect() {}, eventFeedback: (item, index) => {
    bound.push({ item, index }); return card()
  } }))
  assert.deepEqual(bound, [{ item: event, index: 0 }])
  assert.match(markup, /<article class="report-event-card"[^>]*>.*서로 다른 공급 계약.*report-event-sources.*report-event-feedback/s)
  assert.equal(markup.match(/>근거 기사 1/g)?.length, 1, 'source links deduplicate articles while the feedback target keeps all three findings')
  assert.match(markup, /<summary>의견 남기기/)
  assert.match(markup, /aria-label="서로 다른 공급 계약 의견"/)
  assert.match(markup, /의견 보내기/)
  assert.match(markup, /의견이 접수되면 해당 이슈는 검토 완료를 기다리지 않고 보고서 화면과 이후 메일·텔레그램 발송에서 제외됩니다/)
  assert.match(markup, /핵심 브리핑과 집계도 다시 반영합니다/)
  assert.match(markup, /개인 알림 기준에는 적용하지 않습니다/)
  assert.doesNotMatch(markup, /<select|type="checkbox"|소식을 선택|수신자 선택|feedback\?token|다른 소식에 의견/)
})

test('pending and final results stay within the same card and prevent another opinion', () => {
  for (const [status, label] of [['PENDING', '접수 완료'], ['PROCESSING', '검토 중'], ['COMPLETED', '검토 완료'], ['FAILED', '검토하지 못함']]) {
    const markup = renderToStaticMarkup(card({ feedback: { ...saved, status,
      ...(status === 'COMPLETED' ? { verdict: 'INSUFFICIENT_EVIDENCE', diagnosis: '두 번째 기사의 당시 근거를 확인할 수 없습니다.' } : {}) } }))
    assert.match(markup, new RegExp(label))
    assert.match(markup, /각각 다른 계약이에요/)
    assert.match(markup, /메일·텔레그램 전달 제외/)
    assert.match(markup, /의견 접수 즉시 적용되었습니다/)
    assert.match(markup, /검토 상태·결과와 관계없이 해당 이슈를 보고서 화면과 이후 발송에서 제외합니다/)
    assert.match(markup, /이미 발송된 내용은 바뀌지 않습니다/)
    assert.doesNotMatch(markup, /<form|의견 보내기|의견 남기기/)
    if (status === 'COMPLETED') assert.match(markup, /근거 부족.*두 번째 기사의 당시 근거/s)
  }
})

test('submitted event hides stale report while preserving other mounted drafts until the server refresh finishes', () => {
  const second = { ...event, title: '다음 공급 계약' }
  const third = { ...event, title: '별도 공급 계약' }
  const events = [event, second, third]
  const targets = events.map((item, eventIndex) => ({ ...item, summary: item.summaryKo, eventIndex,
    eventKey: 'abc'[eventIndex].repeat(64) }))
  for (const status of ['PENDING', 'PROCESSING', 'COMPLETED']) {
    setFeedbackFixture({ data: { reportId: report.id, events: targets, feedback: [{ ...saved, status }] }, busy: true })
    const markup = renderToStaticMarkup(createElement(ReadingWithFeedback, {
      report: { ...report, structuredContent: { ...report.structuredContent, importantEvents: events } },
      onEvidenceSelect() {}, ...actions,
    }))
    const cards = markup.match(/<article class="report-event-card"[^>]*>[\s\S]*?<\/article>/g)
    assert.match(markup, /의견이 접수된 이슈를 제외하고 보고서를 갱신하고 있습니다/)
    assert.match(markup, /<div hidden="">[\s\S]*report-reading-content/)
    assert.equal(cards.length, 3)
    assert.match(cards[0], /각각 다른 계약이에요/)
    assert.doesNotMatch(cards[0], /<form/)
    for (const [index, item] of [[1, second], [2, third]]) {
      assert.match(cards[index], new RegExp(`aria-label="${item.title} 의견"`))
      assert.match(cards[index], /의견 보내기/)
      assert.doesNotMatch(cards[index], /<fieldset disabled|<button type="submit" disabled/)
    }
  }
})

test('successful feedback records acceptance and immediately refreshes the entire report without waiting for diagnosis', async () => {
  const calls = []
  setFeedbackFixture({ data: { reportId: report.id, events: [target], feedback: [] },
    recordFeedback(value) { calls.push(['saved', value]) }, refresh() { calls.push(['context']) } })
  renderToStaticMarkup(createElement(ReadingWithFeedback, { report, onEvidenceSelect() {},
    async onRefreshReport() { calls.push(['manual']) },
    async onFeedbackSubmitted() { calls.push(['report']) },
  }))
  getReadingProps().eventFeedback(event, 0).props.onFeedback(saved)
  assert.deepEqual(calls.slice(0, 2), [['saved', saved], ['report']])
  await setImmediate()
  assert.deepEqual(calls, [['saved', saved], ['report'], ['context']])
})

test('management history retains every submitted status even when no report events remain', () => {
  const feedback = ['PENDING', 'PROCESSING', 'FAILED', 'COMPLETED'].map((status, index) => ({
    ...saved, id: index + 1, status, comment: `보관된 의견 ${index + 1}`,
    ...(status === 'COMPLETED' ? { verdict: 'NOT_CONFIRMED', diagnosis: '검토를 마쳤습니다.' } : {}),
  }))
  setFeedbackFixture({ data: { reportId: report.id, events: [], feedback } })
  const markup = renderToStaticMarkup(createElement(History, { reportId: report.id }))
  for (let index = 1; index <= 4; index++) assert.match(markup, new RegExp(`보관된 의견 ${index}`))
  assert.match(markup, /검토를 마쳤습니다/)
  assert.match(markup, /#\/reports\?reportId=17/)
  assert.doesNotMatch(markup, /의견 보내기|<form/)
})

test('one mismatched event does not disable another event whose position and full evidence still match', () => {
  const second = { ...event, title: '변경되지 않은 다음 계약' }
  setFeedbackFixture({ data: { reportId: report.id, events: [
    { ...target, summary: '서버에서 변경된 첫 이벤트 요약' },
    { ...second, summary: second.summaryKo, eventIndex: 1, eventKey: 'b'.repeat(64) },
  ], feedback: [] } })
  const markup = renderToStaticMarkup(createElement(ReadingWithFeedback, {
    report: { ...report, structuredContent: { ...report.structuredContent, importantEvents: [event, second] } },
    onEvidenceSelect() {}, ...actions,
  }))
  const cards = markup.match(/<article class="report-event-card"[^>]*>[\s\S]*?<\/article>/g)
  assert.equal(cards.length, 2)
  assert.match(markup, /보고서 내용이 바뀌었습니다/)
  assert.doesNotMatch(cards[0], /<form/)
  assert.match(cards[1], /aria-label="변경되지 않은 다음 계약 의견"/)
  assert.match(cards[1], /의견 보내기/)
  assert.doesNotMatch(cards[1], /<fieldset disabled|<button type="submit" disabled/)
})

test('unavailable context disables an existing composer and returned text is never executable markup', () => {
  const disabled = renderToStaticMarkup(card({ disabled: true }))
  assert.match(disabled, /<fieldset disabled="">/)
  assert.match(disabled, /<button type="submit" disabled=""/)
  const escaped = renderToStaticMarkup(card({ feedback: { ...saved, status: 'COMPLETED', verdict: 'CONFIRMED_ERROR',
    comment: '<script>bad()</script>', diagnosis: '<img src=x onerror=bad()>' } }))
  assert.doesNotMatch(escaped, /<script|<img/)
  assert.match(escaped, /&lt;script/)
  assert.match(escaped, /&lt;img/)
})

test('loading feedback does not hide the report or submit anything; legacy markdown gets no invented event target', t => {
  const calls = []
  t.mock.method(globalThis, 'fetch', (...args) => { calls.push(args); throw new Error('Unexpected render side effect') })
  const loading = renderToStaticMarkup(createElement(ReadingWithFeedback, { report, onEvidenceSelect() {}, ...actions }))
  assert.match(loading, /서로 다른 공급 계약/)
  assert.match(loading, /의견 작성 기능을 불러오고 있어요/)
  assert.doesNotMatch(loading, /<form/)
  const legacy = renderToStaticMarkup(createElement(ReadingWithFeedback, {
    report: { ...report, structuredContent: null, markdownBody: '## 중요 이벤트\n### 옛 이벤트\n옛 내용' }, onEvidenceSelect() {}, ...actions,
  }))
  assert.match(legacy, /옛 이벤트/)
  assert.doesNotMatch(legacy, /의견 남기기|의견 작성 기능|중요 이벤트로 올리기|<form/)
  assert.deepEqual(calls, [])
})
