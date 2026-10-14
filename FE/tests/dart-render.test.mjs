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

let server, emptyEnvDir, Content, Panel, Picker
before(async () => {
  emptyEnvDir = await mkdtemp(join(tmpdir(), 'dart-render-test-'))
  server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false,
    envDir: emptyEnvDir, cacheDir: join(emptyEnvDir, 'vite-cache'), plugins: [react()],
    optimizeDeps: { noDiscovery: true, include: [] },
    server: { middlewareMode: true, watch: null, ws: false }, logLevel: 'error' })
  const module = await server.ssrLoadModule('/src/features/reports/ReportDartPanel.tsx')
  Content = module.ReportDartCompanyContent
  Panel = module.ReportDartPanel
  Picker = (await server.ssrLoadModule('/src/features/settings/DartCompanyPicker.tsx')).DartCompanyPicker
})
after(async () => { await server?.close(); if (emptyEnvDir) await rm(emptyEnvDir, { recursive: true, force: true, maxRetries: 3 }) })

const company = { corpCode: '00126380', corpName: '삼성전자', stockCode: '005930' }
const filing = { rceptNo: '20260814001234', reportName: '반기보고서 (2026.06)', filedAt: '2026-08-14',
  url: 'https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260814001234', corrected: true }
function data() {
  return { fsDiv: 'CFS', currency: 'KRW', fetchedAt: '2026-10-14T10:00:00+09:00',
    quarters: Array.from({ length: 12 }, (_, index) => ({ year: 2023 + Math.floor((index + 2) / 4), quarter: (index + 2) % 4 + 1,
      revenue: index === 2 ? null : index === 0 ? '0' : '1234500000000',
      operatingProfit: index === 2 ? null : '-165000000', operatingMargin: index === 2 ? null : -0.01,
      derived: (index + 2) % 4 === 3, sources: index === 2 ? [] : [filing] })),
    disclosures: [filing], notes: ['일부 분기의 실적을 확인할 수 없습니다.'] }
}
function render(status = 'PARTIAL', props = {}) {
  return renderToStaticMarkup(createElement(Content, { entry: { company, status, message: `${status} 상태 안내`,
    data: ['READY', 'PARTIAL'].includes(status) ? data() : null }, onRefresh() {}, onRetry() {}, ...props }))
}

test('financial summary and 12-quarter table preserve losses, zero, nulls, provenance and exact source URLs', () => {
  const html = render()
  assert.match(html, /삼성전자/)
  assert.match(html, /005930 · DART 00126380/)
  assert.match(html, /연결재무제표/)
  assert.match(html, /단위: 억원/)
  assert.match(html, /자료 확인/)
  assert.match(html, /한국시간/)
  assert.match(html, /최근 12분기 추이/)
  assert.match(html, /<svg[^>]+role="img"/)
  assert.match(html, /자료가 없는 분기는 선을 연결하지 않습니다/)
  assert.equal((html.match(/<th scope="row">/g) ?? []).length, 12)
  assert.match(html, /<td title="0">0<\/td>/)
  assert.match(html, /<td>자료 없음<\/td>/)
  assert.match(html, />-1.7<\/td>/)
  assert.match(html, /4분기 산출값은 같은 회계 기준의 연간 금액에서 3분기 누적 금액/)
  assert.match(html, /href="https:\/\/dart.fss.or.kr\/dsaf001\/main.do\?rcpNo=20260814001234" target="_blank" rel="noopener noreferrer"/)
  assert.match(html, />정정<\/span>/)
  assert.match(html, /자료 확인 시 확보한 내용을 보존/)
  assert.match(html, /PARTIAL 상태 안내/)
  assert.doesNotMatch(html, /NaN|Infinity|공시 자료 다시 준비/)
})

test('pending, expired polling, failed and disabled states offer appropriate recovery without invented financial data', () => {
  const pending = render('PENDING')
  assert.match(pending, /자료가 준비되면 자동으로 표시/)
  assert.match(pending, /공시 자료 새로고침/)
  assert.match(render('PENDING', { pollExpired: true }), /자동 확인 시간이 지났습니다/)
  assert.match(render('FAILED'), /공시 자료 다시 준비/)
  assert.match(render('DISABLED'), /설정 후 다시 확인/)
  for (const status of ['PENDING', 'FAILED', 'DISABLED']) {
    const html = render(status)
    assert.ok(html.includes(`${status} 상태 안내`))
    assert.doesNotMatch(html, /report-dart-metrics|<svg|<table|최근 공시/)
  }
  assert.match(render('FAILED', { busy: true }), /<button[^>]+disabled=""/)
})

test('malicious company or filing text is escaped and invalid source links are not clickable', () => {
  const values = data()
  values.disclosures[0] = { ...filing, reportName: '<script>bad</script>', url: 'javascript:alert(1)' }
  const html = render('READY', { entry: { company: { ...company, corpName: '<script>company</script>' }, status: 'READY', message: '완료', data: values } })
  assert.match(html, /&lt;script&gt;company&lt;\/script&gt;/)
  assert.match(html, /&lt;script&gt;bad&lt;\/script&gt;/)
  assert.match(html, /원문 링크 확인 불가/)
  assert.doesNotMatch(html, /<script>|href="javascript:/)
})

test('unsupported financial statements show notes without fabricated charts and separately show disclosures', () => {
  const html = render('PARTIAL', { entry: { company, status: 'PARTIAL', message: '일부 실적 자료를 확인할 수 없습니다.',
    data: { ...data(), fsDiv: null, currency: null, quarters: [], notes: ['12월 결산 외 회계기간은 지원하지 않습니다.'] } } })
  assert.match(html, /재무 기준 미확인/)
  assert.match(html, /통화 미확인/)
  assert.match(html, /표시할 분기 실적 자료가 없습니다/)
  assert.match(html, /12월 결산 외 회계기간은 지원하지 않습니다/)
  assert.match(html, /최근 공시/)
  assert.doesNotMatch(html, /<svg|<table|report-dart-metrics/)
})

test('legacy, unlinked and aggregate reports render nothing without requiring a query provider', () => {
  const contexts = [{ topics: [{ dartCompany: company }] }]
  for (const report of [{ id: 17, reportScope: 'RUN' }, { id: 17, reportScope: 'RUN', collectionContexts: [{ topics: [{ dartCompany: null }] }] },
    { id: 17, reportScope: 'DAILY', collectionContexts: contexts }, { id: 17, reportScope: 'WEEKLY', collectionContexts: contexts }]) {
    assert.equal(renderToStaticMarkup(createElement(Panel, { report })), '')
  }
})

test('multi-company panel uses the report snapshot and offers individual company selectors', () => {
  const client = new QueryClient()
  try {
    const another = { corpCode: '00164779', corpName: 'SK하이닉스', stockCode: '000660' }
    const companies = [company, another].map(value => ({ company: value, status: 'READY', message: '완료', data: data() }))
    client.setQueryData(['report-dart', 17], { reportId: 17, companies })
    const report = { id: 17, reportScope: 'RUN', collectionContexts: [{ topics: [company, another].map(value => ({ dartCompany: value })) }] }
    const html = renderToStaticMarkup(createElement(QueryClientProvider, { client }, createElement(Panel, { report })))
    assert.match(html, /aria-label="공시 회사 선택"/)
    assert.match(html, /aria-pressed="true"[^>]*>삼성전자/)
    assert.match(html, /aria-pressed="false"[^>]*>SK하이닉스/)
    assert.match(html, /최근 12분기 추이/)
  } finally { client.clear() }
})

test('company picker shows optional selection, code, replacement search and disabled clear action', () => {
  const html = renderToStaticMarkup(createElement(Picker, { value: company, disabled: true, onChange() {} }))
  assert.match(html, /선택사항/)
  assert.match(html, /연결할 다른 회사 검색/)
  assert.match(html, /005930 · DART 00126380/)
  assert.match(html, /type="button"[^>]*disabled=""[^>]*aria-label="삼성전자 공시 회사 연결 해제"/)
  assert.match(html, /<input[^>]+disabled=""/)
})
