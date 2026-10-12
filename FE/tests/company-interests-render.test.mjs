import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { after, afterEach, before, test } from 'node:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { createServer } from 'vite'
import react from '@vitejs/plugin-react'
import { companyCriterion, companyApplication, companyCriteria } from './fixtures/company-interests.mjs'

const fixtureId = '\0test:company-interest-context'
const fixturePlugin = {
  name: 'company-interest-context-fixture', enforce: 'pre',
  resolveId(source, importer) {
    if (source === 'test:company-interest-context' || (source.endsWith('/useCompanyInterests') && importer)) return fixtureId
  },
  load(id) {
    if (id !== fixtureId) return
    return `const defaults = { criteria: null, error: null, busy: true, refresh() {}, record() {} };
      let fixture = defaults;
      export function setInterestFixture(value = {}) { fixture = { ...defaults, ...value }; }
      export function useCompanyInterests() { return fixture; }`
  },
}
let server, emptyEnvDir, Card, Page, Result, setFixture
before(async () => {
  emptyEnvDir = await mkdtemp(join(tmpdir(), 'company-interest-render-'))
  server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false,
    envDir: emptyEnvDir, cacheDir: join(emptyEnvDir, 'vite-cache'), plugins: [fixturePlugin, react()],
    optimizeDeps: { noDiscovery: true, include: [] }, server: { middlewareMode: true, watch: null, ws: false }, logLevel: 'error' })
  const module = await server.ssrLoadModule('/src/features/interests/CompanyInterestsPage.tsx')
  Card = module.CompanyInterestCard
  Page = module.CompanyInterestsPage
  Result = (await server.ssrLoadModule('/src/features/reports/ReportReadingWithFeedback.tsx')).ReportEventFeedbackResult
  setFixture = (await server.ssrLoadModule('test:company-interest-context')).setInterestFixture
})
afterEach(() => setFixture?.())
after(async () => { await server?.close(); if (emptyEnvDir) await rm(emptyEnvDir, { recursive: true, force: true, maxRetries: 3 }) })
const card = (criterion = companyCriterion, props = {}) => renderToStaticMarkup(createElement(Card, { criterion, onSaved() {}, onReload() {}, ...props }))

test('drafts show the original opinion, editable exact criterion and topic, with explicit activation after review', () => {
  const markup = card()
  assert.match(markup, /의견 검토 완료/)
  assert.match(markup, /기준 확인 대기/)
  assert.match(markup, /다음 보고서에 적용할 기준/)
  assert.match(markup, /<textarea[^>]*maxLength="500"[^>]*>사업 활용이나 제품 도입/)
  assert.match(markup, /<select[^>]*>.*AI·LLM 기술 및 산업 동향/s)
  assert.match(markup, /AI가 수학문제를 얼마나 잘 풀었는지는 우리 회사의 관심사가 아님/)
  assert.match(markup, /확인한 기준 적용하기/)
  assert.doesNotMatch(markup, /실제 반영 완료|개인화 동의/)
})

test('activation alone is never shown as actual application; persisted receipts include scope, time and explicit zero exclusion', () => {
  const active = { ...companyCriterion, status: 'ACTIVE', activatedAt: '2026-10-12T10:30:00+09:00' }
  const waiting = card(active)
  assert.match(waiting, /의견 검토 완료/)
  assert.match(waiting, /다음 보고서 반영 대기/)
  assert.doesNotMatch(waiting, /실제 반영 완료|반영 보고서 #/)
  const applied = card({ ...active, lastApplication: { ...companyApplication, suppressedCount: 0 } })
  assert.match(applied, /실제 반영 완료/)
  assert.match(applied, /href="#\/reports\?reportScope=DAILY&amp;reportId=18"/)
  assert.match(applied, /검토 12건 · 제외 0건/)
  assert.match(applied, /제외 대상은 없었습니다/)
  assert.match(applied, /dateTime="2026-10-13T08:00:00\+09:00"/)
  assert.doesNotMatch(applied, /다음 보고서 반영 대기/)
  const revised = card({ ...active, version: 3, lastApplication: null })
  assert.match(revised, /다음 보고서 반영 대기/)
  assert.doesNotMatch(revised, /실제 반영 완료/)
})

test('revoked criteria stay manageable and a highlighted link has a keyboard focus target', () => {
  const markup = card({ ...companyCriterion, status: 'REVOKED' }, { highlighted: true })
  assert.match(markup, /사용 중지/)
  assert.match(markup, /확인한 기준 다시 적용/)
  assert.match(markup, /id="company-interest-31"/)
  assert.match(markup, /tabindex="-1"/)
  assert.match(markup, /is-highlighted/)
  assert.doesNotMatch(markup, /기준 사용 중지<\/button>/)
})

test('report feedback keeps its review result separate from the company criterion and links the exact criterion', () => {
  const feedback = { id: 10, category: 'PREFERENCE', status: 'COMPLETED', verdict: 'PREFERENCE',
    comment: companyCriterion.comment, diagnosis: companyCriterion.diagnosis, createdAt: companyCriterion.createdAt }
  for (const criterion of [companyCriterion, { ...companyCriterion, status: 'ACTIVE' },
    { ...companyCriterion, status: 'ACTIVE', lastApplication: companyApplication }]) {
    const markup = renderToStaticMarkup(createElement(Result, { feedback, criterion }))
    assert.match(markup, /검토 완료/)
    assert.match(markup, /href="#\/company-interests\?criterionId=31"/)
    assert.match(markup, /회사 관심 기준 확인하기/)
    assert.doesNotMatch(markup, /확인한 기준 적용하기|<form/)
  }
  const unavailable = renderToStaticMarkup(createElement(Result, { feedback, criterion: { ...companyCriterion, status: 'ACTIVE', lastApplication: companyApplication }, criteriaError: true }))
  assert.match(unavailable, /현재 상태를 확인하지 못했습니다/)
  assert.doesNotMatch(unavailable, /실제 반영 완료/)
})

test('the management page offers loading, empty, error and populated states without creating criteria during rendering', t => {
  const requests = []
  t.mock.method(globalThis, 'fetch', (...args) => { requests.push(args); throw new Error('Unexpected request while rendering') })
  assert.match(renderToStaticMarkup(createElement(Page)), /회사 관심 기준을 불러오고 있습니다/)
  setFixture({ criteria: [], busy: false })
  const empty = renderToStaticMarkup(createElement(Page))
  assert.match(empty, /아직 제안된 회사 관심 기준이 없습니다/)
  assert.match(empty, /리포트에서 의견 남기기/)
  setFixture({ criteria: companyCriteria, busy: false })
  const loaded = renderToStaticMarkup(createElement(Page))
  assert.match(loaded, /1개 확인 대기 · 3개 사용 중/)
  assert.match(loaded, /기준 확인 대기/)
  assert.match(loaded, /다음 보고서 반영 대기/)
  assert.match(loaded, /실제 반영 완료/)
  assert.match(loaded, /검토 12건 · 제외 0건/)
  assert.match(loaded, /메일·텔레그램 전달 제외는 기준 활성화와 별도로, 의견 접수 즉시 적용됩니다/)
  setFixture({ criteria: [companyCriterion], error: new Error('offline'), busy: false })
  const failed = renderToStaticMarkup(createElement(Page))
  assert.match(failed, /role="alert"/)
  assert.match(failed, /회사 관심 기준을 불러오지 못했습니다/)
  assert.match(failed, /<fieldset disabled="">/)
  assert.deepEqual(requests, [])
})

test('returned criteria are text, and stale/loading state disables mutations', () => {
  const markup = card({ ...companyCriterion, eventTitle: '<script>bad()</script>', comment: '<img src=x onerror=bad()>', instruction: '<script>instruction()</script>' }, { disabled: true })
  assert.doesNotMatch(markup, /<script|<img/)
  assert.match(markup, /&lt;script/)
  assert.match(markup, /&lt;img/)
  assert.match(markup, /<fieldset disabled="">/)
  assert.match(markup, /<button type="submit" class="primary-button" disabled="">/)
})
