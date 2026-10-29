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

const keywordComparison = (overrides = {}) => ({ status: 'AVAILABLE', reason: null, message: '같은 조건의 저장 자료를 비교했습니다.',
  previousDenominatorIssueCount: 10, currentDenominatorIssueCount: 20, lowSample: false,
  items: [
    { keyword: 'HBM', previousIssueCount: 2, currentIssueCount: 9, previousSharePercent: 20, currentSharePercent: 45, changePercentagePoints: 25 },
    { keyword: '첨단 패키징', previousIssueCount: 0, currentIssueCount: 3, previousSharePercent: 0, currentSharePercent: 15, changePercentagePoints: 15 },
    { keyword: 'AI', previousIssueCount: 7, currentIssueCount: 8, previousSharePercent: 70, currentSharePercent: 40, changePercentagePoints: -30 },
  ], ...overrides })

test('weekly and weekly-scheduled RUN comparisons use saved period labels without inventing daily dates', () => {
  const weekly = { ...reportChangesFixture(), reportDate: '2026-10-19', baseReportDate: '2026-10-12',
    periods: { basis: 'WEEKLY', previousLabel: '2026-10-12 ~ 2026-10-18', currentLabel: '2026-10-19 ~ 2026-10-25' } }
  const weeklyHtml = render(weekly)
  assert.match(weeklyHtml, /2026-10-12 ~ 2026-10-18/)
  assert.match(weeklyHtml, /2026-10-19 ~ 2026-10-25/)
  assert.doesNotMatch(weeklyHtml, /7일 전 보고서|어제|지난주/)
  const run = { ...weekly, reportDate: null, baseReportDate: null,
    periods: { basis: 'RUN', previousLabel: '2026-10-21 09:00 실행', currentLabel: '2026-10-28 09:00 실행' } }
  const runHtml = render(run)
  assert.match(runHtml, /이전 실행/)
  assert.match(runHtml, /2026-10-21 09:00 실행/)
  assert.match(runHtml, /2026-10-28 09:00 실행/)
  assert.match(runHtml, /href="#\/reports\?reportId=116"/)
  assert.doesNotMatch(runHtml, /null|7일 전 보고서|지난주/)
})

test('keyword comparison shows both denominators, paired shares and percentage points without growth-rate claims', () => {
  const html = render({ ...reportChangesFixture(), keywordChanges: keywordComparison() })
  assert.match(html, /이전 10개 → 현재 20개/)
  assert.match(html, /고유 이슈 2개 → 9개/)
  assert.match(html, />20%<|>45%</)
  assert.match(html, /width:20%/)
  assert.match(html, /width:45%/)
  assert.match(html, /\+25%p/)
  assert.match(html, /-30%p/)
  assert.match(html, /고유 이슈 0개 → 3개/)
  assert.match(html, /신규 집계/)
  assert.match(html, /시장 전체 추세를 뜻하지는 않습니다/)
  assert.doesNotMatch(html, /Infinity|NaN|증가율|\+25%[^p]/)
})

test('missing shares and zero shares stay distinct and low samples have an explicit notice', () => {
  const keywords = keywordComparison({ previousDenominatorIssueCount: 2, lowSample: true,
    items: [{ keyword: '자료 없음', previousIssueCount: 0, currentIssueCount: 1,
      previousSharePercent: null, currentSharePercent: 5, changePercentagePoints: null }] })
  const html = render({ ...reportChangesFixture(), keywordChanges: keywords })
  assert.match(html, /비교 이슈가 5개 미만인 기간/)
  assert.match(html, /자료 없음/)
  assert.match(html, /비중 차이 계산 불가/)
  assert.doesNotMatch(html, /width:0%|>0%<|null%|NaN/)
  const zeroHtml = render({ ...reportChangesFixture(), keywordChanges: keywordComparison({ items: [
    { keyword: '등록어', previousIssueCount: 0, currentIssueCount: 0, previousSharePercent: 0, currentSharePercent: 0, changePercentagePoints: 0 },
  ] }) })
  assert.match(zeroHtml, /width:0%/)
  assert.match(zeroHtml, />0%p</)
  assert.doesNotMatch(zeroHtml, /신규 집계|비중 차이 계산 불가/)
})

test('unavailable keyword comparisons show a reason and cannot revive retained numeric items', () => {
  for (const reason of ['CONDITIONS_CHANGED', 'CONDITIONS_UNVERIFIED', 'INCOMPLETE_PERIOD', 'EMPTY_SAMPLE', 'FILTERED_INPUT', 'NO_SNAPSHOT']) {
    const html = render({ ...reportChangesFixture(), keywordChanges: keywordComparison({ status: 'UNAVAILABLE', reason, message: '' }) })
    assert.match(html, /키워드 비중 변화/)
    assert.doesNotMatch(html, /고유 이슈 2개 → 9개|width:20%|\+25%p/)
  }
  const notReady = render({ ...reportChangesFixture(117, 'failed'), keywordChanges: keywordComparison() })
  assert.doesNotMatch(notReady, /고유 이슈 2개 → 9개|width:20%|\+25%p/)
})

test('first three verified changes stay concise while all other changes and long explanations remain readable on expansion', () => {
  const data = reportChangesFixture()
  const longText = '긴 설명의 조건과 근거를 모두 보존합니다. '.repeat(15) + '마지막 반증 조건도 유지합니다.'
  data.items[0].summary = longText
  const html = render(data)
  const briefStart = html.indexOf('aria-label="먼저 확인할 변화"')
  const restStart = html.indexOf('나머지 비교 1건 보기')
  assert.ok(briefStart >= 0 && restStart > briefStart)
  const brief = html.slice(briefStart, restStart)
  assert.ok(brief.indexOf('공장 가동 중단 보도에 대한 해명') < brief.indexOf('HBM4 양산 일정 구체화'))
  assert.doesNotMatch(brief, /신규 고객 공급 계약/)
  assert.match(html.slice(restStart), /신규 고객 공급 계약/)
  assert.match(html, /설명이 길어 아래 상세에 전체 내용을 담았습니다/)
  assert.ok(html.includes(`<p class="report-change-full-summary">${longText}</p>`))
  assert.doesNotMatch(html, /<details[^>]* open/)
})
