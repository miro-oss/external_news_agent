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

let server, emptyEnvDir, ActiveCollectionRuns
before(async () => {
  emptyEnvDir = await mkdtemp(join(tmpdir(), 'collection-run-stages-test-'))
  server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false,
    envDir: emptyEnvDir, cacheDir: join(emptyEnvDir, 'vite-cache'), plugins: [react()],
    optimizeDeps: { noDiscovery: true, include: [] },
    server: { middlewareMode: true, watch: null, ws: false }, logLevel: 'error' })
  ;({ ActiveCollectionRuns } = await server.ssrLoadModule('/src/features/settings/ActiveCollectionRuns.tsx'))
})
after(async () => { await server?.close(); if (emptyEnvDir) await rm(emptyEnvDir, { recursive: true, force: true, maxRetries: 3 }) })

function renderRuns(runs) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
  client.setQueryData(['collection-queue'], { running: runs.length, pending: 0 })
  client.setQueryData(['collection-queue', 'runs', 'RUNNING', 0], {
    content: runs.map((run, index) => ({ runId: 601 + index, status: 'RUNNING',
      triggerType: 'SCHEDULED', queuedAt: '2026-10-07T07:43:13+09:00',
      startedAt: '2026-10-07T07:44:02+09:00', reportId: null, ...run })),
    page: 0, size: 10, totalElements: runs.length, totalPages: 1, hasNext: false,
  })
  runs.forEach((run, index) => client.setQueryData(['collection-run-topics', run.runId ?? 601 + index], {
    breakdown: [{ topicId: 4397, topicName: '반도체 산업 동향' }],
  }))
  try {
    return renderToStaticMarkup(createElement(QueryClientProvider, { client }, createElement(ActiveCollectionRuns)))
  } finally {
    client.clear()
  }
}

test('each active run displays its current server stage beside the topic without changing its receipt time', () => {
  const stages = { COLLECTING: '수집', CLUSTERING: '분류', ANALYZING: '분석',
    INVESTIGATING: '추가 조사', GENERATING_REPORT: '보고서 생성', FINALIZING: '마무리' }
  const html = renderRuns(Object.keys(stages).map(stage => ({ stage })))
  assert.equal((html.match(/class="active-run-stage"/g) ?? []).length, 6)
  for (const label of Object.values(stages)) {
    assert.ok(html.includes(`aria-label="현재 단계: ${label}">${label}</span>`))
  }
  assert.equal((html.match(/<strong>반도체 산업 동향<\/strong>/g) ?? []).length, 6)
  assert.equal((html.match(/ 접수<\/span>/g) ?? []).length, 6)
})

test('legacy missing or unknown stages say progress instead of falsely claiming collection', () => {
  const html = renderRuns([{ stage: null }, {}, { stage: 'FUTURE_STAGE' }])
  assert.equal((html.match(/aria-label="현재 단계: 진행 중"/g) ?? []).length, 3)
})

test('pending and terminal statuses never display a stale stage tag', () => {
  const html = renderRuns(['PENDING', 'SUCCESS', 'PARTIAL', 'FAILED'].map(status => ({ status, stage: 'ANALYZING' })))
  assert.ok(!html.includes('class="active-run-stage"'))
})
