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

let server, emptyEnvDir, Dialog
before(async () => {
  emptyEnvDir = await mkdtemp(join(tmpdir(), 'delivery-settings-test-'))
  server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false,
    envDir: emptyEnvDir, cacheDir: join(emptyEnvDir, 'vite-cache'), plugins: [react()],
    optimizeDeps: { noDiscovery: true, include: [] },
    server: { middlewareMode: true, watch: null, ws: false }, logLevel: 'error' })
  Dialog = (await server.ssrLoadModule('/src/features/settings/CollectionDeliveryPicker.tsx')).CollectionDeliveryDialog
})
after(async () => { await server?.close(); if (emptyEnvDir) await rm(emptyEnvDir, { recursive: true, force: true, maxRetries: 3 }) })

const value = { enabled: true, mode: 'ONCE', run: true, daily: false, groupIds: [1], recipientIds: [], channelIds: [1] }
function render(props = {}) {
  const client = new QueryClient()
  client.setQueryData(['notifications', 'groups'], { content: [{ id: 1, name: '그룹', active: true, activeMemberCount: 1 }] })
  client.setQueryData(['notifications', 'recipients'], { content: [] })
  client.setQueryData(['notifications', 'channels'], [{ id: 1, name: '이메일', channelType: 'EMAIL', active: true }])
  try {
    return renderToStaticMarkup(createElement(QueryClientProvider, { client }, createElement(Dialog,
      { id: 'settings', value, onDismiss() {}, onApply() {}, ...props })))
  } finally { client.clear() }
}

test('new collection retains scope choices, while saved topic edits have a fixed scope and save action', () => {
  const create = render()
  assert.match(create, /이번 수집만/)
  assert.match(create, /선택한 주제에 계속 적용/)
  assert.match(create, /기존 설정 사용/)
  assert.match(create, />선택 완료<\/button>/)
  const topic = render({ context: { scope: 'TOPIC', name: '반도체' } })
  assert.doesNotMatch(topic, /전달 설정 적용 범위|기존 설정 사용|선택 완료/)
  assert.match(topic, /<button[^>]*class="primary-button"[^>]*>저장<\/button>/)
  assert.match(topic, /이번 수집에 별도 저장한 설정이 있으면 그 설정을 사용합니다/)
})

test('stale recipients can be retained when switching notifications off', () => {
  const stale = { ...value, groupIds: [99], recipientIds: [98], channelIds: [97] }
  assert.match(render({ value: stale }), /class="primary-button" disabled=""/)
  const disabled = render({ value: { ...stale, enabled: false }, context: { scope: 'RUN', name: '수집 #42' } })
  assert.match(disabled, /<button type="button" class="primary-button">저장<\/button>/)
})

test('inherited settings use an explicit off action, and completing a run removes save', () => {
  const inherited = render({ value: { ...value, enabled: false }, context: { scope: 'RUN', name: '수집 #42', inherited: true } })
  assert.match(inherited, /이번 수집에 별도로 보내기/)
  assert.match(inherited, />이번 수집 알림 끄기<\/button>/)
  const ended = render({ readOnly: true, context: { scope: 'RUN', name: '수집 #42' } })
  assert.match(ended, /<fieldset class="collection-delivery-fields" disabled="">/)
  assert.doesNotMatch(ended, /class="primary-button"/)
  assert.match(ended, />닫기<\/button>/)
})

test('pending saves prevent editing and duplicate submission; errors stay inside the dialog', () => {
  const pending = render({ pending: true, context: { scope: 'TOPIC', name: '반도체' } })
  assert.match(pending, /aria-busy="true"/)
  assert.match(pending, /class="primary-button" disabled="">저장 중…/)
  assert.match(pending, /class="secondary-button" disabled="">취소/)
  assert.match(render({ error: '저장 실패' }), /role="alert">저장 실패/)
})
