import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { after, before, test } from 'node:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createServer } from 'vite'
import react from '@vitejs/plugin-react'

let server, emptyEnvDir, Provider, Banner, Create, taskKey
before(async () => {
  emptyEnvDir = await mkdtemp(join(tmpdir(), 'topic-weekly-task-render-'))
  server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false,
    envDir: emptyEnvDir, cacheDir: join(emptyEnvDir, 'vite-cache'), plugins: [react()],
    optimizeDeps: { noDiscovery: true, include: [] },
    server: { middlewareMode: true, watch: null, ws: false }, logLevel: 'error' })
  const module = await server.ssrLoadModule('/src/features/reports/TopicWeeklyReportTaskProvider.tsx')
  Provider = module.TopicWeeklyReportTaskProvider
  Banner = module.TopicWeeklyReportTaskBanner
  Create = (await server.ssrLoadModule('/src/features/reports/TopicWeeklyReportCreate.tsx')).TopicWeeklyReportCreate
  taskKey = (await server.ssrLoadModule('/src/features/reports/topicWeeklyReportTask.ts')).topicWeeklyReportTaskKey
})
after(async () => { await server?.close(); if (emptyEnvDir) await rm(emptyEnvDir, { recursive: true, force: true, maxRetries: 3 }) })

const base = { request: { topicId: 29, weekStartDate: '2026-09-14' }, topicName: 'HBM 시장',
  result: { reportId: 301, created: false, reportReady: false }, error: null }
function render(task, child = createElement(Banner), topics = [{ id: 29, name: 'HBM 시장', active: true }]) {
  const client = new QueryClient({ defaultOptions: { queries: { enabled: false } } })
  client.setQueryData(taskKey, task)
  client.setQueryData(['topics', true], { content: topics })
  try {
    return renderToStaticMarkup(createElement(QueryClientProvider, { client }, createElement(Provider, null, child)))
  } finally { client.clear() }
}
function createDialog() {
  return createElement(Create, { id: 'weekly-task-dialog', initialTopicId: 99,
    now: new Date('2026-09-28T00:00:00+09:00'), onDismiss() {}, onReady() {}, returnFocusRef: { current: null } })
}

test('task notices expose progress, waiting release, completion link, and terminal error across pages', () => {
  assert.equal(render(null), '')
  const creating = render({ ...base, status: 'creating', result: null })
  assert.match(creating, /role="status"/)
  assert.match(creating, /다른 화면을 이용해도 생성은 계속됩니다/)
  assert.match(creating, /HBM 시장 · 2026-09-14 ~ 2026-09-20/)
  assert.doesNotMatch(creating, /알림 닫기|보고서 열기|나중에 목록에서 확인/)
  const waiting = render({ ...base, status: 'waiting' })
  assert.match(waiting, /나중에 목록에서 확인/)
  assert.match(waiting, /생성은 취소되지 않습니다/)
  const ready = render({ ...base, status: 'ready', result: { ...base.result, reportReady: true } })
  assert.match(ready, /href="#\/reports\?reportScope=WEEKLY&amp;reportId=301"/)
  assert.match(ready, /보고서 열기/)
  assert.match(ready, /알림 닫기/)
  const failure = render({ ...base, status: 'error', error: '원래 생성 오류' })
  assert.match(failure, /role="alert"/)
  assert.match(failure, /원래 생성 오류/)
  assert.match(failure, /알림 닫기/)
})

test('reopened creation restores the submitted topic and week for pending, waiting, and failed tasks', () => {
  for (const status of ['creating', 'waiting', 'error']) {
    const html = render({ ...base, status, error: status === 'error' ? '원래 생성 오류' : null }, createDialog())
    assert.match(html, /<option value="29" selected="">HBM 시장/)
    assert.match(html, /type="date"[^>]*value="2026-09-14"/)
    if (status === 'error') {
      assert.match(html, /원래 생성 오류/)
      assert.match(html, /class="primary-button">보고서 만들기/)
    } else assert.match(html, /class="primary-button" disabled="">만드는 중/)
  }
})

test('an active task keeps its original topic label when the refreshed topic is absent or inactive', () => {
  for (const topics of [[], [{ id: 29, name: '바뀐 주제 이름', active: false }]]) {
    const html = render({ ...base, status: 'creating' }, createDialog(), topics)
    assert.match(html, /<option value="29" selected="">HBM 시장/)
    assert.match(html, /class="primary-button" disabled="">만드는 중/)
    assert.doesNotMatch(html, /바뀐 주제 이름/)
  }
  const fresh = render(null, createDialog(), [{ id: 29, name: '중지한 주제', active: false }])
  assert.doesNotMatch(fresh, /<option value="29"/)
})
