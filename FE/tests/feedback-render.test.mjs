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
import { feedbackFixture } from '../scripts/feedback-fixtures.mjs'

let server, emptyEnvDir, App, Workspace, Page
before(async () => {
  emptyEnvDir = await mkdtemp(join(tmpdir(), 'feedback-render-'))
  server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false,
    envDir: emptyEnvDir, cacheDir: join(emptyEnvDir, 'vite-cache'), plugins: [react()],
    optimizeDeps: { noDiscovery: true, include: [] },
    server: { middlewareMode: true, watch: null, ws: false }, logLevel: 'error' })
  const module = await server.ssrLoadModule('/src/features/feedback/FeedbackPage.tsx')
  Workspace = module.FeedbackWorkspace
  Page = module.FeedbackPage
  App = (await server.ssrLoadModule('/src/App.tsx')).default
})
after(async () => { await server?.close(); if (emptyEnvDir) await rm(emptyEnvDir, { recursive: true, force: true, maxRetries: 3 }) })
const actions = { onRefresh() {}, onFeedback() {}, onPolicy() {} }
function render(props = {}) {
  return renderToStaticMarkup(createElement(Workspace, { token: 'synthetic-private-capability', context: feedbackFixture(), ...actions, ...props }))
}

test('capability routes render independently without mounting administrative queries or navigation', context => {
  const previous = globalThis.window
  context.after(() => { if (previous) globalThis.window = previous; else delete globalThis.window })
  globalThis.window = { location: { hash: '#/feedback?token=synthetic-private-capability' }, scrollY: 0 }
  // No QueryClientProvider: mounting an admin page would throw while attempting its queries.
  const markup = renderToStaticMarkup(createElement(App))
  assert.match(markup, /받으신 보고서를 확인하고 있어요/)
  assert.doesNotMatch(markup, /<nav|수집 설정|알림 관리|synthetic-private-capability/)
  globalThis.window.location.hash = '#/feedback'
  assert.match(renderToStaticMarkup(createElement(App)), /이 의견 링크를 사용할 수 없습니다/)
})

test('recovery screen never offers access to report lists or administrator settings', () => {
  const markup = renderToStaticMarkup(createElement(Page, { token: null }))
  assert.match(markup, /본인에게 온 이메일 또는 텔레그램/)
  assert.match(markup, /새 보고서 전달을 요청/)
  assert.doesNotMatch(markup, /<a |수집 설정|알림 관리|<form/)
})

test('selection is explicit, personalization starts unchecked, and review versus preference is explained', () => {
  const markup = render()
  assert.match(markup, /<option value="" disabled="" selected="">보고서에 담긴 소식을 선택/)
  assert.match(markup, /<input type="checkbox"\/>/)
  assert.doesNotMatch(markup, /type="checkbox" checked/)
  assert.match(markup, /내용이 틀린 것은 아니지만/)
  assert.match(markup, /선택 사항입니다/)
  assert.match(markup, /다른 분에게 전달받은 링크라면/)
  assert.match(markup, /maxLength="2000"/i)
  assert.doesNotMatch(markup, /synthetic-private-capability|ACE|GEPA/)
  assert.match(markup, /<option value="701" disabled="">\[의견 남김\]/)
  assert.match(markup, /<option value="703">/)
})

test('once every delivered item has feedback only saved results and personal-policy controls remain', () => {
  const fixture = feedbackFixture()
  fixture.items = fixture.items.slice(0, 2)
  const markup = render({ context: fixture })
  assert.match(markup, /모든 소식에 의견을 남겼습니다/)
  assert.doesNotMatch(markup, /<form|의견 보내기/)
  assert.match(markup, /검토 설명/)
  assert.match(markup, /개인 기준 적용 중지/)
  fixture.items = []
  const empty = render({ context: fixture })
  assert.match(empty, /의견을 남길 수 있는 소식이 없습니다/)
  assert.match(empty, /개인 기준 적용 중지/)
})

test('review outcomes, failed and paused states are distinct and policies can be revoked individually', () => {
  const fixture = feedbackFixture()
  fixture.feedback.push({ ...fixture.feedback[0], id: 603, status: 'FAILED', diagnosis: null })
  fixture.feedback.push({ ...fixture.feedback[1], id: 604, status: 'PROCESSING', diagnosis: null })
  fixture.policies.push({ ...fixture.policies[0], id: 502, status: 'REVOKED' })
  const markup = render({ context: fixture, paused: true })
  assert.match(markup, /검토 완료/)
  assert.match(markup, /검토하지 못함/)
  assert.match(markup, /검토 중/)
  assert.match(markup, /자동 확인을 잠시 멈췄어요/)
  assert.match(markup, /검토 설명/)
  assert.match(markup, /적용 중지됨/)
  assert.equal(markup.match(/aria-label="반도체 공급망 개인 기준 적용 중지"/g)?.length, 1)
})

test('server content is escaped and unavailable refresh disables writing without concealing saved results', () => {
  const fixture = feedbackFixture()
  fixture.reportTitle = '<img src=x onerror=alert(1)>'
  fixture.feedback[0].comment = '<script>alert(1)</script>'
  fixture.feedback[0].diagnosis = '<iframe src=x></iframe>'
  fixture.policies[0].instruction = '<script>policy()</script>'
  const markup = render({ context: fixture, refreshError: new Error('offline') })
  assert.doesNotMatch(markup, /<script|<iframe|<img/)
  assert.match(markup, /&lt;script/)
  assert.match(markup, /&lt;img/)
  assert.match(markup, /<fieldset disabled="">/)
  assert.match(markup, /disabled="" aria-label="반도체 공급망 개인 기준 적용 중지"/)
  assert.match(markup, /검토 완료/)
})
