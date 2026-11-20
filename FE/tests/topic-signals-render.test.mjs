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

let server, emptyEnvDir, TopicTable, TopicScheduleDialog
before(async () => {
  emptyEnvDir = await mkdtemp(join(tmpdir(), 'topic-signals-render-test-'))
  server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false,
    envDir: emptyEnvDir, cacheDir: join(emptyEnvDir, 'vite-cache'),
    optimizeDeps: { noDiscovery: true, include: [] },
    server: { middlewareMode: true, watch: null, ws: false }, logLevel: 'error' })
  ;({ TopicTable } = await server.ssrLoadModule('/src/features/settings/TopicTable.tsx'))
  ;({ TopicScheduleDialog } = await server.ssrLoadModule('/src/features/settings/TopicScheduleSettings.tsx'))
})
after(async () => {
  await server?.close()
  if (emptyEnvDir) await rm(emptyEnvDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 })
})

const topic = { id: 31, name: '반도체', queryText: '반도체 공급망', requiredKeywords: ['반도체'],
  optionalKeywords: ['공급망'], excludedKeywords: ['채용'], batchSize: 100, intervalMinutes: 1440,
  active: true, linkedSourceCount: 2, lastCollectedAt: null,
  surgeKeywords: [{ keyword: 'HBM4', articleCount: 12, previousArticleCount: 3, deltaArticleCount: 9, zScore: 1.5, burst: false }],
  relatedKeywords: [{ keyword: '첨단 패키징', articleCount: 8, sharePercent: 40 }] }
const page = content => ({ content, page: 0, size: 100, totalElements: content.length, totalPages: 1, hasNext: false })

function renderTable(initial = topic) {
  const client = new QueryClient({ defaultOptions: { queries: { staleTime: Infinity, retry: false } } })
  client.setQueryData(['topics', true], page([initial]))
  client.setQueryData(['delivery-policy', initial.id], {
    enabled: false, run: false, daily: false, weekly: false, groupIds: [], recipientIds: [], channelIds: [],
  })
  try {
    return renderToStaticMarkup(createElement(QueryClientProvider, { client }, createElement(TopicTable)))
  } finally { client.clear() }
}

function renderDialog(initial) {
  return renderToStaticMarkup(createElement(TopicScheduleDialog, {
    id: 'topic-schedule-test', initial, onDismiss() {}, onSave() {},
  }))
}

test('topic signals show article counts for both comparison periods and the server article share', () => {
  const html = renderTable()
  assert.match(html, />HBM4 \+9건<\/span>/)
  assert.match(html, /title="HBM4 · 직전 7일 3건 → 최근 7일 12건 · \+9건"/)
  assert.match(html, />첨단 패키징 40%<\/span>/)
  assert.match(html, /title="첨단 패키징 · 최근 7일 8건 · 수집 기사 중 40%"/)
  assert.match(html, /최근 7일에 키워드가 등장한 기사 수에서 직전 7일의 기사 수를 뺀 값/)
  assert.match(html, /전체 수집 기사 중 그 키워드가 등장한 비율/)
  assert.match(html, /수집 시각 기준/)
  assert.match(html, /같은 기사는 각 기간에서 한 번만/)
  assert.doesNotMatch(html, /이슈 수|전체 이슈|하나의 이슈|undefined|NaN/)
})

test('manual-only topics remain available in the active list and retain collection management', () => {
  const html = renderTable({ ...topic, intervalMinutes: 0 })
  assert.match(html, /수집 중인 주제/)
  assert.match(html, /<span>정기 수집 안 함<\/span>/)
  assert.match(html, />수집 중지<\/button>/)
  assert.match(html, /aria-label="반도체 수집 일정 설정"/)
  assert.doesNotMatch(html, /0일마다|0시간마다|0분마다/)
})

for (const active of [true, false]) test(`manual-only schedule is a selected, supported option and explains active=${active} behavior`, () => {
  const html = renderDialog({ ...topic, active, intervalMinutes: 0 })
  assert.match(html, /<option value="0" selected="">정기 수집 안 함<\/option>/)
  assert.match(html, /필요할 때 수동으로 수집할 수 있습니다/)
  assert.match(html, /이미 접수된 수집은 계속 진행됩니다/)
  assert.match(html, /<button[^>]*type="submit"[^>]*disabled=""[^>]*>저장<\/button>/)
  assert.doesNotMatch(html, /id="topic-schedule-test-unsupported"|0일마다|0시간마다|0분마다/)
  if (active) assert.doesNotMatch(html, /현재 수집이 중지된 주제/)
  else assert.match(html, /수집을 재개해도 자동 수집 없이 수동으로만 수집합니다/)
})

test('stopped recurring topics keep their saved interval and explain collection resumes only after reactivation', () => {
  const html = renderDialog({ ...topic, active: false, intervalMinutes: 720 })
  assert.match(html, /<option value="0">정기 수집 안 함<\/option>/)
  assert.match(html, /<option value="720" selected="">12시간마다<\/option>/)
  assert.match(html, /현재 수집이 중지된 주제입니다. 수집을 재개하면 저장한 주기로 수집합니다/)
  assert.doesNotMatch(html, /수집을 재개해도 자동 수집 없이/)
})
