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

let server, emptyEnvDir, Dialog, Panel, ApiError
before(async () => {
  emptyEnvDir = await mkdtemp(join(tmpdir(), 'keyword-proposal-test-'))
  server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false,
    envDir: emptyEnvDir, cacheDir: join(emptyEnvDir, 'vite-cache'), plugins: [react()],
    optimizeDeps: { noDiscovery: true, include: [] },
    server: { middlewareMode: true, watch: null, ws: false }, logLevel: 'error' })
  const module = await server.ssrLoadModule('/src/features/settings/TopicKeywordProposalPanel.tsx')
  Dialog = module.ProposalDetailDialog
  Panel = module.TopicKeywordProposalPanel
  ApiError = (await server.ssrLoadModule('/src/api/client.ts')).ApiError
})
after(async () => { await server?.close(); if (emptyEnvDir) await rm(emptyEnvDir, { recursive: true, force: true, maxRetries: 3 }) })

const proposal = {
  id: 1, topicId: 3, topicName: '반도체', status: 'PENDING',
  currentKeywords: { requiredKeywords: ['HBM'], optionalKeywords: ['공급망'], excludedKeywords: ['광고'] },
  changes: ['REQUIRED', 'OPTIONAL', 'EXCLUDED'].flatMap(bucket => ['ADD', 'REMOVE'].map(action =>
    ({ bucket, action, keyword: `${bucket}-${action}`, reason: '주제 관련 키워드' }))),
}
function render(overrides = {}, props = {}) {
  return renderToStaticMarkup(createElement(Dialog, {
    proposal: { ...proposal, ...overrides }, isActing: false, error: null,
    fallbackFocus: { current: null }, completedReviewIds: { current: new Set() },
    onDismiss() {}, onApprove() {}, onReject() {}, ...props,
  }))
}
const checkboxes = html => html.match(/<input type="checkbox"[^>]*>/g) ?? []

test('pending proposals expose all add/remove buckets unchecked and require an explicit selection', () => {
  const html = render()
  assert.equal(checkboxes(html).length, 6)
  assert.ok(checkboxes(html).every(input => !input.includes('checked') && !input.includes('disabled')))
  for (const bucket of ['모두 포함', '하나 이상 포함', '제외']) {
    for (const action of ['추가', '제거']) assert.ok(html.includes(`${bucket} · ${action}`))
  }
  assert.match(html, /0 \/ 6개 선택/)
  assert.match(html, /<button type="button" disabled="">선택 0개 적용<\/button>/)
  assert.match(html, />전체 선택<\/button>/)
  assert.match(html, /disabled="">선택 해제<\/button>/)
})

test('approved history retains every original change and distinguishes chosen rows from unselected rows', () => {
  const html = render({ status: 'APPROVED', selectedChangeIndexes: [0, 3, 5] })
  const inputs = checkboxes(html)
  assert.equal(inputs.length, 6)
  assert.ok(inputs.every(input => input.includes('disabled')))
  assert.deepEqual(inputs.map(input => input.includes('checked')), [true, false, false, true, false, true])
  assert.equal((html.match(/>선택됨<\/span>/g) ?? []).length, 3)
  assert.equal((html.match(/>미선택<\/span>/g) ?? []).length, 3)
  assert.doesNotMatch(html, /전체 선택|선택 해제|선택 \d+개 적용/)
  assert.match(html, />반려<\/button>/)
})

test('legacy approvals show the original whole selection while rejected proposals start a fresh selection', () => {
  for (const selectedChangeIndexes of [undefined, null]) {
    assert.ok(checkboxes(render({ status: 'APPROVED', selectedChangeIndexes })).every(input => input.includes('checked')))
  }
  const rejected = render({ status: 'REJECTED', selectedChangeIndexes: [1, 4] })
  assert.ok(checkboxes(rejected).every(input => !input.includes('checked') && !input.includes('disabled')))
  assert.match(rejected, /disabled="">선택 0개 적용<\/button>/)
  assert.doesNotMatch(rejected, />반려<\/button>|>선택됨<\/span>/)
})

test('in-flight review locks checkboxes, selection controls and dismissal, and failures appear in the dialog', () => {
  const pending = render({}, { isActing: true })
  assert.match(pending, /aria-busy="true"/)
  assert.ok(checkboxes(pending).every(input => input.includes('disabled')))
  assert.match(pending, /aria-label="키워드 제안 상세 닫기" disabled=""/)
  assert.match(pending, /disabled="">전체 선택<\/button>/)
  assert.doesNotMatch(pending, /<button[^>]*(?<!disabled="")>처리 중…<\/button>/)
  const failed = render({}, { error: new ApiError('TOPIC409', '주제 키워드가 변경되었습니다.', 409) })
  assert.match(failed, /role="alert">주제 키워드가 변경되었습니다./)
})

test('empty proposals cannot be approved, and cards lead into selection rather than direct approval', () => {
  assert.match(render({ changes: [] }), /변경 항목이 없습니다./)
  assert.match(render({ changes: [] }), /disabled="">선택 0개 적용<\/button>/)
  const client = new QueryClient()
  client.setQueryData(['topic-keyword-proposals', 'PENDING'], { content: [proposal] })
  try {
    const html = renderToStaticMarkup(createElement(QueryClientProvider, { client }, createElement(Panel)))
    assert.match(html, />선택해서 적용<\/button>/)
    assert.doesNotMatch(html, />승인<\/button>/)
  } finally { client.clear() }
})
