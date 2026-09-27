import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { after, before, test } from 'node:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { createServer } from 'vite'

let server, emptyEnvDir, Dialog, topicListOptions
before(async () => {
  emptyEnvDir = await mkdtemp(join(tmpdir(), 'topic-weekly-dialog-test-'))
  server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false,
    envDir: emptyEnvDir, cacheDir: join(emptyEnvDir, 'vite-cache'),
    optimizeDeps: { noDiscovery: true, include: [] },
    server: { middlewareMode: true, watch: null, ws: false }, logLevel: 'error' })
  Dialog = (await server.ssrLoadModule('/src/features/reports/TopicWeeklyReportDialog.tsx')).TopicWeeklyReportDialog
  topicListOptions = (await server.ssrLoadModule('/src/api/queries.ts')).topicListOptions
})
after(async () => {
  await server?.close()
  if (emptyEnvDir) await rm(emptyEnvDir, { recursive: true, force: true, maxRetries: 3 })
})

const now = new Date('2026-09-28T00:00:00+09:00')
const topics = [{ id: 29, name: 'HBM 시장', active: true }, { id: 31, name: '중지한 반도체 주제', active: false }]
const render = (props = {}) => renderToStaticMarkup(createElement(Dialog, {
  id: 'weekly-dialog', topics, now, onRetryTopics() {}, onDismiss() {}, onCreate() {}, ...props,
}))

test('the dialog defaults to the last closed KST week and offers only active topics with accessible labels', () => {
  const html = render({ initialTopicId: 29 })
  assert.match(html, /aria-labelledby="weekly-dialog-title"/)
  assert.match(html, /<option value="29" selected="">HBM 시장/)
  assert.doesNotMatch(html, /중지한 반도체 주제|수집 중지/)
  assert.match(html, /활성화된 수집 주제만 표시합니다/)
  assert.match(html, /type="date"[^>]*max="2026-09-27"[^>]*value="2026-09-21"/)
  assert.match(html, /aria-label="이전 주 선택"/)
  assert.match(html, /aria-label="다음 주 선택" disabled=""/)
  assert.match(html, /<button type="submit" class="primary-button">보고서 만들기/)
  assert.match(html, /같은 주제·주차의 보고서가 있으면 기존 보고서를 엽니다/)
})

test('topic loading, empty, and error states prevent invalid submission and provide recovery', () => {
  const loading = render({ topics: [], topicsLoading: true })
  assert.match(loading, /aria-busy="true"/)
  assert.match(loading, /활성화된 수집 주제를 불러오는 중/)
  const empty = render({ topics: [] })
  assert.match(empty, /활성화된 수집 주제가 없습니다/)
  const pausedOnly = render({ topics: topics.filter(topic => !topic.active), initialTopicId: 31 })
  assert.match(pausedOnly, /활성화된 수집 주제가 없습니다/)
  assert.doesNotMatch(pausedOnly, /<option value="31"/)
  const pausedSelection = render({ initialTopicId: 31 })
  assert.match(pausedSelection, /<option value="" selected="">주제를 선택해 주세요/)
  const failure = render({ topicsError: '주제 조회 실패' })
  assert.match(failure, /role="alert"><p class="error">주제 조회 실패/)
  assert.match(failure, /주제 다시 불러오기/)
  for (const html of [loading, empty, failure, pausedOnly, pausedSelection, render()]) {
    assert.match(html, /type="submit" class="primary-button" disabled=""/)
  }
})

test('creating locks changes and dismissal; observing an existing pending report permits dismissal only', () => {
  const pending = render({ initialTopicId: 29, pending: true })
  const controls = pending.match(/<(?:input|select|button)\b[^>]*>/g) ?? []
  assert.ok(controls.length >= 7)
  assert.ok(controls.every(control => control.includes('disabled=""')))
  const waiting = render({ initialTopicId: 29, waiting: true })
  assert.match(waiting, /이 창을 닫아도 생성은 계속됩니다/)
  assert.match(waiting, /<button type="button" class="secondary-button">닫기/)
  assert.match(waiting, /<button type="submit" class="primary-button" disabled="">만드는 중/)
})

test('API failures are shown inside the form with the selected topic retained', () => {
  const html = render({ initialTopicId: 29, error: '선택한 주제와 기간에 보고서를 만들 수 있는 분석 자료가 없습니다.' })
  assert.match(html, /<option value="29" selected="">HBM 시장/)
  assert.match(html, /class="error topic-weekly-save-error" role="alert">선택한 주제와 기간/)
})

test('the active-topic query preserves the active filter on every page', async context => {
  const calls = []
  context.mock.method(globalThis, 'fetch', async url => {
    calls.push(url)
    const page = Number(new URL(url, 'https://fixture.invalid').searchParams.get('page'))
    const content = page === 0 ? Array.from({ length: 100 }, (_, index) => ({ id: 100 + index, name: `주제 ${index}`, active: true }))
      : [{ id: 1, name: '두 번째 페이지의 활성 주제', active: true }]
    return new Response(JSON.stringify({ isSuccess: true, code: 'COMMON200', message: '성공입니다.',
      result: { content, page, size: 100, totalElements: 101, totalPages: 2, hasNext: page === 0 } }))
  })
  const all = await topicListOptions(true).queryFn()
  assert.deepEqual(calls, ['/api/news/topics?active=true&page=0&size=100', '/api/news/topics?active=true&page=1&size=100'])
  assert.equal(all.content.length, 101)
  assert.equal(all.content.at(-1).active, true)
  assert.equal(all.hasNext, false)
  assert.match(render({ topics: all.content, initialTopicId: 1 }), /두 번째 페이지의 활성 주제/)
})
