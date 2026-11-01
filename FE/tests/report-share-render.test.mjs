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

let server, emptyEnvDir, Panel, Selector, EmailPreview
before(async () => {
  emptyEnvDir = await mkdtemp(join(tmpdir(), 'report-share-render-'))
  server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false,
    envDir: emptyEnvDir, cacheDir: join(emptyEnvDir, 'vite-cache'), plugins: [react()],
    optimizeDeps: { noDiscovery: true, include: [] },
    server: { middlewareMode: true, watch: null, ws: false }, logLevel: 'error' })
  const module = await server.ssrLoadModule('/src/features/notifications/ReportSharePanel.tsx')
  Panel = module.ReportSharePanel
  Selector = module.ReportShareInsightSelector
  EmailPreview = (await server.ssrLoadModule('/src/features/notifications/EmailMessagePreview.tsx')).EmailMessagePreview
})
after(async () => { await server?.close(); if (emptyEnvDir) await rm(emptyEnvDir, { recursive: true, force: true, maxRetries: 3 }) })

test('manual report sharing places an optional insight selection between targets and send actions', () => {
  const client = new QueryClient()
  client.setQueryData(['notifications', 'groups'], { content: [] })
  client.setQueryData(['notifications', 'recipients'], { content: [{ id: 3, name: '수신자', active: true }] })
  client.setQueryData(['notifications', 'channels'], [{ id: 1, name: '이메일', channelType: 'EMAIL', active: true }])
  client.setQueryData(['auto-deliveries', 17], [])
  try {
    const html = renderToStaticMarkup(createElement(QueryClientProvider, { client }, createElement(Panel, { reportId: 17 })))
    assert.match(html, /delivery-target-picker[\s\S]*report-share-insight[\s\S]*report-share-actions/)
    assert.match(html, /<option value="" selected="">포함 안 함<\/option>/)
    assert.match(html, /이번 전달에만 적용됩니다/)
    assert.match(html, /이메일·텔레그램에는 요약/)
    assert.match(html, /이메일 PDF에는 상세 분석/)
    assert.match(html, /선택한 관점 하나/)
    assert.doesNotMatch(html, /크레딧 사용|분석 생성/)
  } finally { client.clear() }
})

test('the perspective picker provides every existing audience and locks while sending', () => {
  for (const value of ['', 'CHIP_MAKER', 'EQUIPMENT_MAKER', 'MARKET_INVESTOR', 'IT_INFRA']) {
    const html = renderToStaticMarkup(createElement(Selector, { value, disabled: true, onChange() {} }))
    assert.match(html, /<select[^>]*disabled=""/)
    assert.match(html, new RegExp(`<option value="${value}" selected="">`))
    for (const label of ['포함 안 함', '반도체 제조사', '장비·소재사', '시장·투자', 'IT 인프라']) assert.ok(html.includes(label), label)
    const id = html.match(/<select id="([^"]+)"/)[1]
    assert.ok(html.includes(`for="${id}"`), 'the visible label is connected to the picker')
    assert.ok(html.includes(`aria-describedby="${id}-help"`))
  }
})

test('email preview provides width controls and an isolated, titled frame without execution privileges', () => {
  const html = renderToStaticMarkup(createElement(EmailPreview, { body: '<script>parent.probe=1</script>' }))
  assert.match(html, /넓은 화면/)
  assert.match(html, /좁은 화면 · 360px/)
  assert.match(html, /공용 본문 미리보기/)
  assert.match(html, /<iframe[^>]*title="이메일 본문 미리보기"/)
  const sandbox = html.match(/sandbox="([^"]*)"/)[1]
  assert.equal(sandbox, 'allow-popups allow-popups-to-escape-sandbox')
  assert.doesNotMatch(sandbox, /allow-scripts|allow-same-origin|allow-forms|allow-top-navigation/)
  assert.match(html, /referrerPolicy="no-referrer"/)
  assert.doesNotMatch(html, /<script>|parent\.probe/)
  const id = html.match(/<iframe id="([^"]+)"/)[1]
  assert.equal(html.match(new RegExp(`aria-controls="${id}"`, 'g')).length, 2)
})
