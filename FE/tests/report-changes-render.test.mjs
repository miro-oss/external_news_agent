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
import { reportChangesFixture } from '../scripts/report-changes-fixtures.mjs'

let server, emptyEnvDir, Content
before(async () => {
  emptyEnvDir = await mkdtemp(join(tmpdir(), 'report-changes-test-'))
  server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false,
    envDir: emptyEnvDir, cacheDir: join(emptyEnvDir, 'vite-cache'), plugins: [react()],
    server: { middlewareMode: true, watch: null, ws: false }, logLevel: 'error' })
  Content = (await server.ssrLoadModule('/src/features/reports/ReportChangesPanel.tsx')).ReportChangesContent
})
after(async () => { await server?.close(); if (emptyEnvDir) await rm(emptyEnvDir, { recursive: true, force: true }) })
const render = changes => renderToStaticMarkup(createElement(Content, { changes, refreshing: false, onRefresh() {} }))

test('saved before/after quotes render inline inside collapsed disclosures with safe source links', () => {
  const data = reportChangesFixture()
  data.items[0].previous.claims[0].evidence[0].canonicalUrl = 'javascript:alert(1)'
  data.items[0].previous.claims[0].evidence[0].text = '<script>historical evidence</script>'
  const html = render(data)
  assert.match(html, /이전 보고서/)
  assert.match(html, /현재 보고서/)
  assert.match(html, /&lt;script&gt;historical evidence&lt;\/script&gt;/)
  assert.doesNotMatch(html, /href="javascript:|<script>|<details[^>]* open/)
  assert.match(html, /class="report-changes-unchanged"><summary>변화 확인 안 됨 1건 보기/)
  assert.match(html, /rel="noopener noreferrer"/)
  assert.match(html, /문장 1/)
  assert.match(html, /비교 대상인 이전 보고서에 없던 항목/)
  assert.match(html, /판단하기 어렵습니다/)
})

test('no-baseline, missing snapshots, and failures remain distinct from an empty or unchanged comparison', () => {
  for (const variant of ['pending', 'running', 'failed', 'no-baseline', 'unavailable', 'not-applicable']) {
    const data = reportChangesFixture(117, variant)
    const html = render(data)
    assert.ok(html.includes(data.message), variant)
    assert.doesNotMatch(html, /report-change-item|변화 유형별 이슈 수/)
    assert.equal(html.includes('결과 새로고침'), variant === 'pending' || variant === 'running')
  }
  assert.match(render(reportChangesFixture(117, 'empty')), /비교할 수 있는 이슈가 없습니다. 변화가 없다는 뜻은 아닙니다/)
  assert.match(render(reportChangesFixture(117, 'unchanged')), /내용 변화를 확인하지 못했습니다/)
})

test('date gaps and scope differences are explicit, and uncertain-only results never claim no change', () => {
  assert.match(render(reportChangesFixture(117, 'gap')), /3일 전 보고서와 비교합니다/)
  assert.match(render(reportChangesFixture(117, 'scope')), /수집 범위가 다릅니다/)
  const html = render(reportChangesFixture(117, 'undetermined'))
  assert.match(html, /판단 보류/)
  assert.doesNotMatch(html, /내용 변화를 확인하지 못했습니다/)
})
