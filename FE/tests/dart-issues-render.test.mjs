import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { after, before, test } from 'node:test'
import { Children, createElement, isValidElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createServer } from 'vite'

let server, emptyEnvDir, Panel, Provider, Table, Companies, Buttons
before(async () => {
  emptyEnvDir = await mkdtemp(join(tmpdir(), 'dart-issues-test-'))
  server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false,
    envDir: emptyEnvDir, cacheDir: join(emptyEnvDir, 'vite-cache'), optimizeDeps: { noDiscovery: true, include: [] },
    server: { middlewareMode: true, watch: null, ws: false }, logLevel: 'error' })
  const module = await server.ssrLoadModule('/src/features/reports/ReportDartPanel.tsx')
  Panel = module.ReportDartPanel
  Provider = module.ReportDartProvider
  Table = module.DartFilingTable
  const issueModule = await server.ssrLoadModule('/src/features/reports/ReportIssueCompanies.tsx')
  Companies = issueModule.ReportIssueCompanies
  Buttons = issueModule.ReportIssueCompanyButtons
})
after(async () => {
  await server?.close()
  if (emptyEnvDir) await rm(emptyEnvDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 })
})

const samsung = { corpCode: '00126380', corpName: '삼성전자', stockCode: '005930' }
const hynix = { corpCode: '00164779', corpName: 'SK하이닉스', stockCode: '000660' }
const filing = (number = '20260814001234', corrected = false) => ({ rceptNo: number, reportName: '반기보고서 (2026.06)',
  filedAt: '2026-08-14', url: `https://dart.fss.or.kr/dsaf001/main.do?rcpNo=${number}`, corrected })
const link = id => ({ findingId: id, articleId: id + 10, issueId: 71, matchedName: '삼성전자',
  evidenceText: '삼성전자는 반기 실적을 공시했다.', evidenceUrl: 'https://example.com/article', rceptNos: ['20260814001234'] })
const entry = (company = samsung, ids = [1]) => ({ company, status: 'READY', message: '공시 자료를 확인했습니다.',
  issueLinks: ids.map(link), filingRows: [{ filing: filing(), roles: ['RECENT', 'FINANCIAL_SOURCE'], relevance: 'DIRECT_REFERENCE', sourceFindingIds: ids }],
  data: { fsDiv: 'CFS', currency: 'KRW', fetchedAt: '2026-10-30T09:00:00+09:00', quarters: [], disclosures: [filing()], notes: [] } })

function renderReport(report, entries) {
  const client = new QueryClient()
  try {
    client.setQueryData(['report-dart', 17], { reportId: 17, companies: entries })
    return renderToStaticMarkup(createElement(QueryClientProvider, { client }, createElement(Provider, { report },
      createElement(Panel, { report }), createElement(Companies, { sourceFindingIds: [1] }))))
  } finally { client.clear() }
}

test('M5 daily and aggregate weekly companies use saved identity links with financial and filing details', () => {
  for (const reportScope of ['DAILY', 'WEEKLY']) {
    const html = renderReport({ id: 17, reportScope, dartCompanies: [samsung], findings: [{ id: 1 }] }, [entry()])
    assert.match(html, /회사 실적 · 공시/)
    assert.match(html, /aria-label="삼성전자 실적과 공시 보기"/)
    assert.match(html, /aria-controls="report-17-companies"/)
    assert.match(html, /id="report-17-companies" tabindex="-1"/)
    assert.match(html, /공시 상세/)
    assert.match(html, /기사에서 직접 인용한 공시/)
    assert.match(html, /최근 공시/)
    assert.match(html, /실적 근거/)
    assert.match(html, /기간 전체 공시 목록이 아닙니다/)
    assert.ok(html.indexOf('회사 실적 · 공시') < html.indexOf('aria-label="이슈에서 확인한 기업"'))
  }
})

test('issue badges cannot infer company identity from a live issue, company names or unlinked selection', () => {
  const entries = [entry(), entry(hynix, [3]), { ...entry(hynix), issueLinks: undefined }]
  const render = ids => renderToStaticMarkup(createElement(Buttons, { entries, reportId: 17, sourceFindingIds: ids, onSelect() {} }))
  assert.equal(render([71]), '')
  assert.equal(render([]), '')
  const html = render([1, 1])
  assert.equal((html.match(/<button/g) ?? []).length, 1)
  assert.match(html, /삼성전자/)
  assert.doesNotMatch(html, /SK하이닉스/)
  assert.equal(renderToStaticMarkup(createElement(Companies, { sourceFindingIds: [1] })), '')
})

test('a company button selects exactly its source scope and moves keyboard focus without changing the route', context => {
  const calls = []
  const origin = { tagName: 'BUTTON' }
  const panel = { focus: options => calls.push(['focus', options]), scrollIntoView: options => calls.push(['scroll', options]) }
  globalThis.document = { getElementById: id => { calls.push(['target', id]); return panel } }
  globalThis.window = { matchMedia: () => ({ matches: true }) }
  globalThis.requestAnimationFrame = callback => callback()
  context.after(() => { delete globalThis.document; delete globalThis.window; delete globalThis.requestAnimationFrame })
  function buttons(node) {
    if (!isValidElement(node)) return []
    return node.type === 'button' ? [node] : Children.toArray(node.props.children).flatMap(buttons)
  }
  const tree = Buttons({ entries: [entry(), entry(hynix, [2])], reportId: 17, sourceFindingIds: [2],
    onSelect: (...args) => calls.push(['select', ...args]) })
  const targets = buttons(tree)
  assert.equal(targets.length, 1)
  targets[0].props.onClick({ currentTarget: origin })
  assert.deepEqual(calls, [['select', hynix.corpCode, [2], origin], ['target', 'report-17-companies'],
    ['focus', { preventScroll: true }], ['scroll', { behavior: 'auto', block: 'start' }]])
})

test('direct filing citations are scoped to the selected issue and corrections keep their separate receipt numbers', () => {
  const rows = [entry().filingRows[0], { filing: filing('20260814009999', true), roles: ['RECENT'],
    relevance: 'COMPANY_REFERENCE', sourceFindingIds: [] }]
  const html = renderToStaticMarkup(createElement(Table, { rows, companyName: samsung.corpName, sourceFindingIds: [2] }))
  assert.doesNotMatch(html, /기사에서 직접 인용한 공시/)
  assert.match(html, /확보한 공시에서 직접 인용 확인 안 됨/)
  assert.match(html, /이슈 관련성 미확인/)
  assert.match(html, /선택한 이슈/)
  assert.match(html, />정정<\/span>/)
  assert.match(html, /접수번호 20260814001234/)
  assert.match(html, /접수번호 20260814009999/)
  assert.equal((html.match(/<tbody><tr>|<\/tr><tr>/g) ?? []).length, 2)
  const direct = renderToStaticMarkup(createElement(Table, { rows, companyName: samsung.corpName, sourceFindingIds: [1] }))
  assert.equal((direct.match(/기사에서 직접 인용한 공시/g) ?? []).length, 1)
  assert.match(direct, /기사 내용 전체나 계약 실행·매출 반영을 검증한 것은 아닙니다/)
})

test('refreshing the report hides cached removed companies and strips direct citations from removed findings', () => {
  const report = { id: 17, reportScope: 'RUN', dartCompanies: [samsung], findings: [{ id: 2 }] }
  const html = renderReport(report, [entry(), entry(hynix)])
  assert.doesNotMatch(html, /SK하이닉스|기사에서 직접 인용한 공시|실적과 공시 보기/)
  assert.match(html, /회사 참고 공시/)
  assert.match(html, /확보한 공시에서 직접 인용 확인 안 됨/)
})

test('pending attachments still navigate but do not fabricate filing data; malformed links stay non-clickable', () => {
  const pending = { ...entry(), status: 'PENDING', data: null, filingRows: [] }
  const html = renderReport({ id: 17, reportScope: 'DAILY', dartCompanies: [samsung], findings: [{ id: 1 }] }, [pending])
  assert.match(html, /aria-label="삼성전자 실적과 공시 보기"/)
  assert.doesNotMatch(html, /<table|공시 상세/)
  const unsafe = { ...entry().filingRows[0], filing: { ...filing(), reportName: '<img src=x>', url: 'javascript:alert(1)' } }
  const table = renderToStaticMarkup(createElement(Table, { rows: [unsafe], companyName: '<script>bad</script>' }))
  assert.match(table, /&lt;img src=x&gt;/)
  assert.match(table, /원문 링크 확인 불가/)
  assert.doesNotMatch(table, /<img|<script|href="javascript:/)
})
