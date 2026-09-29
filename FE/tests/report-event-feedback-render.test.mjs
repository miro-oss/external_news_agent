import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { after, before, test } from 'node:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { createServer } from 'vite'
import react from '@vitejs/plugin-react'

let server, emptyEnvDir, Card, Reading, ReadingWithFeedback
before(async () => {
  emptyEnvDir = await mkdtemp(join(tmpdir(), 'report-event-feedback-render-'))
  server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false,
    envDir: emptyEnvDir, cacheDir: join(emptyEnvDir, 'vite-cache'), plugins: [react()],
    optimizeDeps: { noDiscovery: true, include: [] }, server: { middlewareMode: true, watch: null, ws: false }, logLevel: 'error' })
  const module = await server.ssrLoadModule('/src/features/reports/ReportReadingWithFeedback.tsx')
  Card = module.ReportEventFeedbackCard
  ReadingWithFeedback = module.ReportReadingWithFeedback
  Reading = (await server.ssrLoadModule('/src/features/reports/ReportReadingContent.tsx')).ReportReadingContent
})
after(async () => { await server?.close(); if (emptyEnvDir) await rm(emptyEnvDir, { recursive: true, force: true, maxRetries: 3 }) })
const event = { title: '서로 다른 공급 계약', summaryKo: '두 기업의 계획을 묶은 이벤트입니다.', significance: '분리해서 확인해야 합니다.', sourceFindingIds: [501, 502, 503] }
const target = { ...event, summary: event.summaryKo, eventIndex: 0, eventKey: 'a'.repeat(64) }
const report = { id: 17, markdownBody: '', collectionContexts: [], findings: [501, 502, 503].map((id, index) => ({
  id, articleId: index < 2 ? 10 : 20, runId: 100 + index, articleTitle: `근거 기사 ${index < 2 ? 1 : 2}`, keyPoints: [],
})), structuredContent: { executiveSummary: ['핵심 요약'], importantEvents: [event], watchItems: [], sourceNotes: [] } }
const actions = { onFeedback() {}, async onRefreshReport() {} }
const saved = { id: 1, eventKey: target.eventKey, category: 'WRONG_CLUSTER', comment: '각각 다른 계약이에요.', status: 'PENDING', diagnosis: null, verdict: null, createdAt: '2026-09-29T12:00:00+09:00' }
function card(props = {}) { return createElement(Card, { reportId: 17, target, ...actions, ...props }) }

test('the important event itself owns the inline composer, with automatic multi-source binding and no article/recipient picker', () => {
  const bound = []
  const markup = renderToStaticMarkup(createElement(Reading, { report, onEvidenceSelect() {}, eventFeedback: (item, index) => {
    bound.push({ item, index }); return card()
  } }))
  assert.deepEqual(bound, [{ item: event, index: 0 }])
  assert.match(markup, /<article class="report-event-card">.*서로 다른 공급 계약.*report-event-sources.*report-event-feedback/s)
  assert.equal(markup.match(/>근거 기사 1/g)?.length, 1, 'source links deduplicate articles while the feedback target keeps all three findings')
  assert.match(markup, /<summary>의견 남기기/)
  assert.match(markup, /aria-label="서로 다른 공급 계약 의견"/)
  assert.match(markup, /의견 보내기/)
  assert.match(markup, /개인 알림 기준에는 적용하지 않습니다/)
  assert.doesNotMatch(markup, /<select|type="checkbox"|소식을 선택|수신자 선택|feedback\?token|다른 소식에 의견/)
})

test('pending and final results stay within the same card and prevent another opinion', () => {
  for (const [status, label] of [['PENDING', '접수 완료'], ['PROCESSING', '검토 중'], ['COMPLETED', '검토 완료'], ['FAILED', '검토하지 못함']]) {
    const markup = renderToStaticMarkup(card({ feedback: { ...saved, status,
      ...(status === 'COMPLETED' ? { verdict: 'INSUFFICIENT_EVIDENCE', diagnosis: '두 번째 기사의 당시 근거를 확인할 수 없습니다.' } : {}) } }))
    assert.match(markup, new RegExp(label))
    assert.match(markup, /각각 다른 계약이에요/)
    assert.doesNotMatch(markup, /<form|의견 보내기|의견 남기기/)
    if (status === 'COMPLETED') assert.match(markup, /근거 부족.*두 번째 기사의 당시 근거/s)
  }
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
  assert.doesNotMatch(legacy, /의견 남기기|의견 작성 기능|<form/)
  assert.deepEqual(calls, [])
})
