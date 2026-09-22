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

let server, emptyEnvDir, Dialog, topicEditDraft, buildTopicEditRequest
before(async () => {
  emptyEnvDir = await mkdtemp(join(tmpdir(), 'topic-edit-dialog-test-'))
  server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false,
    envDir: emptyEnvDir, cacheDir: join(emptyEnvDir, 'vite-cache'), plugins: [react()],
    optimizeDeps: { noDiscovery: true, include: [] },
    server: { middlewareMode: true, watch: null, ws: false }, logLevel: 'error' })
  Dialog = (await server.ssrLoadModule('/src/features/settings/TopicEditSettings.tsx')).TopicEditDialog
  ;({ topicEditDraft, buildTopicEditRequest } = await server.ssrLoadModule('/src/features/settings/topicEditDraft.ts'))
})
after(async () => { await server?.close(); if (emptyEnvDir) await rm(emptyEnvDir, { recursive: true, force: true, maxRetries: 3 }) })

const topic = { id: 31, name: '반도체 공급망', queryText: '반도체 수출', requiredKeywords: ['반도체'],
  optionalKeywords: ['수출 규제', '공급망'], excludedKeywords: ['채용'], batchSize: 100, intervalMinutes: 720,
  active: false, sources: [{ id: 1, name: 'NAVER', sourceKind: 'SEARCH' }], lastCollectedAt: null }
const request = (changes = {}, initial = topic) => buildTopicEditRequest(initial, { ...topicEditDraft(initial), ...changes })
function render(initial = topic, props = {}) {
  return renderToStaticMarkup(createElement(Dialog, {
    id: 'topic-edit-test', initial, onDismiss() {}, onSave() {}, ...props,
  }))
}

test('opening an existing topic preserves every condition and produces no unchanged PATCH fields', () => {
  const initial = { ...topic, queryText: '반도체  수출', requiredKeywords: [], optionalKeywords: ['고대역폭 메모리', '첨단 패키징'] }
  assert.deepEqual(topicEditDraft(initial), { name: '반도체 공급망', queryText: '반도체  수출',
    requiredKeywords: '', optionalKeywords: '고대역폭 메모리, 첨단 패키징', excludedKeywords: '채용' })
  assert.deepEqual(request({}, initial), {})
  assert.deepEqual(request({}, { ...initial, queryText: null }), {})
})

test('renaming or editing search keywords sends only the changed normalized fields', () => {
  assert.deepEqual(request({ name: '  HBM 시장 동향  ' }), { name: 'HBM 시장 동향' })
  assert.deepEqual(request({ queryText: '  HBM,   고대역폭 메모리  ' }), { queryText: 'HBM 고대역폭 메모리' })
  assert.deepEqual(request({ name: ' 반도체 공급망 ', queryText: ' 반도체,  수출 ' }), {})
})

test('search edits never derive required conditions or overwrite existing empty conditions', () => {
  for (const requiredKeywords of [[], ['반도체', '메모리']]) {
    const initial = { ...topic, requiredKeywords }
    assert.deepEqual(request({ queryText: 'HBM 공급망' }, initial), { queryText: 'HBM 공급망' })
    assert.deepEqual(initial.requiredKeywords, requiredKeywords)
  }
})

test('clearing article conditions sends explicit empty arrays without changing collection state or schedule', () => {
  assert.deepEqual(request({ requiredKeywords: '', optionalKeywords: ', , ', excludedKeywords: '  ' }), {
    requiredKeywords: [], optionalKeywords: [], excludedKeywords: [],
  })
  assert.deepEqual(request({ optionalKeywords: '' }), { optionalKeywords: [] })
  assert.equal(topic.active, false)
  assert.equal(topic.intervalMinutes, 720)
  assert.equal(topic.batchSize, 100)
})

test('article conditions preserve phrases, trim entries, and remove repeated keywords', () => {
  assert.deepEqual(request({ optionalKeywords: '  고대역폭 메모리,\n첨단 패키징, 고대역폭 메모리, , ' }), {
    optionalKeywords: ['고대역폭 메모리', '첨단 패키징'],
  })
  assert.deepEqual(request({ optionalKeywords: ' 수출 규제, 공급망, 수출 규제, ' }), {})
})

test('the editor renders long article conditions in labeled multiline fields without truncating values', () => {
  const long = Array.from({ length: 18 }, (_, i) => `고대역폭 메모리 공급망과 첨단 패키징 투자 계획 ${i + 1}`)
  const html = render({ ...topic, requiredKeywords: long, optionalKeywords: ['긴 문구를 포함한 키워드'], excludedKeywords: ['채용', '광고'] })
  const textareas = html.match(/<textarea\b[^>]*>[\s\S]*?<\/textarea>/g) ?? []
  assert.equal(textareas.length, 3)
  assert.ok(textareas.every(field => /rows="3"/.test(field)))
  assert.ok(textareas.some(field => field.includes(long.join(', '))))
  for (const label of ['모두 포함', '하나 이상 포함', '제외']) assert.ok(html.includes(label))
  assert.match(html, /aria-labelledby="topic-edit-test-title"/)
  assert.match(html, /<button[^>]*type="submit"[^>]*disabled=""[^>]*>저장<\/button>/)
})

test('in-flight saves lock all fields and dismissal controls and expose pending status', () => {
  const html = render(topic, { pending: true })
  assert.match(html, /aria-busy="true"/)
  const controls = html.match(/<(?:input|textarea|button)\b[^>]*>/g) ?? []
  assert.ok(controls.length >= 8)
  assert.ok(controls.every(control => control.includes('disabled=""')))
  assert.match(html, />저장 중…<\/button>/)
})

test('save failures remain visible in the editor with the loaded conditions available to correct', () => {
  const html = render(topic, { error: '주제 이름이 이미 존재합니다.' })
  assert.match(html, /<footer[^>]*><p[^>]*role="alert">주제 이름이 이미 존재합니다./)
  assert.match(html, /value="반도체 공급망"/)
  assert.match(html, />수출 규제, 공급망<\/textarea>/)
  assert.match(html, /<button[^>]*>취소<\/button>/)
})
