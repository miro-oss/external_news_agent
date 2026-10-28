import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { after, afterEach, before, test } from 'node:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createServer } from 'vite'
import react from '@vitejs/plugin-react'
import { reportInsightFixture } from '../scripts/report-insight-fixtures.mjs'
const generationFixtureId = '\0test:report-insight-generation'
const generationFixturePlugin = {
  name: 'report-insight-generation-fixture', enforce: 'pre',
  resolveId(source, importer) {
    if (source === 'test:report-insight-generation'
      || (source === '../../api/reportInsights' && importer?.endsWith('/ReportInsightsPanel.tsx'))) return generationFixtureId
  },
  load(id) {
    if (id !== generationFixtureId) return
    return `export * from '/src/api/reportInsights.ts';
      import { useGenerateReportInsight as useActualGeneration } from '/src/api/reportInsights.ts';
      let fixture;
      export function setGenerationFixture(value) { fixture = value; }
      export function useGenerateReportInsight() { const actual = useActualGeneration(); return fixture ?? actual; }`
  },
}
let server, emptyEnvDir, Content, Panel, ApiError, insightKey, snapshot, generateOptions, setGenerationFixture
const report = JSON.parse(await readFile(new URL('./fixtures/refactor-report.json', import.meta.url), 'utf8')).report
before(async () => {
  emptyEnvDir = await mkdtemp(join(tmpdir(), 'report-insights-render-'))
  server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false,
    envDir: emptyEnvDir, cacheDir: join(emptyEnvDir, 'vite-cache'), plugins: [generationFixturePlugin, react()],
    optimizeDeps: { noDiscovery: true, include: [] },
    server: { middlewareMode: true, watch: null, ws: false }, logLevel: 'error' })
  const panel = await server.ssrLoadModule('/src/features/reports/ReportInsightsPanel.tsx')
  Content = panel.ReportInsightsContent
  Panel = panel.ReportInsightsPanel
  ApiError = (await server.ssrLoadModule('/src/api/client.ts')).ApiError
  const api = await server.ssrLoadModule('/src/api/reportInsights.ts')
  insightKey = api.reportInsightKey
  snapshot = api.reportInsightSnapshotKey(report)
  generateOptions = api.generateReportInsightOptions
  setGenerationFixture = (await server.ssrLoadModule('test:report-insight-generation')).setGenerationFixture
})
afterEach(() => setGenerationFixture?.())
after(async () => { await server?.close(); if (emptyEnvDir) await rm(emptyEnvDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 }) })
function render(result, findings = report.findings, options = {}) {
  return renderToStaticMarkup(createElement(Content, { result, insight: result.insights[0], findings, onEvidenceSelect() {}, ...options }))
}
function issueCards(html) {
  const list = html.match(/<ol class="report-insights-issues">([\s\S]*?)<\/section>/)?.[1] ?? ''
  return list.split(/<li class="report-insights-issue"(?: [^>]*)?>/).slice(1)
}
function renderPanel(state, panelReport = report) {
  // Freeze the supplied lifecycle state; API tests exercise the actual reads and retries.
  const client = new QueryClient({ defaultOptions: { queries: { enabled: false } } })
  const query = client.getQueryCache().build(client, { queryKey: insightKey(report.id, 'CHIP_MAKER', snapshot) })
  query.setState(state)
  try {
    return renderToStaticMarkup(createElement(QueryClientProvider, { client }, createElement(Panel, {
      report: panelReport, audience: 'CHIP_MAKER', selector: createElement('span', null, '관점 선택'), onEvidenceSelect() {},
    })))
  } finally { client.clear() }
}
test('report importance, priority, conditions, watch criteria, original claims and evidence are visible without confidence percentages', () => {
  const result = reportInsightFixture(report)
  const html = render(result)
  for (const value of ['리포트 중요도 높음', '우선순위 1', '직접 관련성', '영향 크기', '시급성', '비교 근거 없음',
    '영향 경로', '성립 조건', '판단 변경 조건', '확인할 지표', '판단 기준', '원문 근거 문장 1 보기', report.findings[0].keyPoints[0].text]) assert.ok(html.includes(value), value)
  assert.match(html, /저장된 분석/)
  assert.match(html, /href="https:\/\/example.invalid\/news\/0"/)
  assert.match(html, /rel="noopener noreferrer"/)
  assert.doesNotMatch(html, /신뢰도|confidence|<details[^>]* open/)
})
test('forecasts and attributed opinions retain their original type and unsafe URLs/text do not execute', () => {
  const result = reportInsightFixture(report), findings = structuredClone(report.findings)
  result.insights[0].facts[0].claimType = 'FORECAST'
  result.insights[0].facts[1].claimType = 'OPINION'
  result.insights[0].facts[1].attributedTo = '검증 발표자'
  result.insights[0].facts[0].text = '<script>claim</script>'
  findings[0].canonicalUrl = 'javascript:alert(1)'
  const html = render(result, findings)
  assert.match(html, /계획·전망/); assert.match(html, /의견·발언 · 검증 발표자/)
  assert.match(html, /&lt;script&gt;claim&lt;\/script&gt;/)
  assert.doesNotMatch(html, /href="javascript:|<script>/)
})
test('empty and unavailable analysis preserve uncertainty and do not offer paid regeneration inside saved content', () => {
  const empty = render(reportInsightFixture(report, 'CHIP_MAKER', 'empty'))
  assert.match(empty, /이 관점의 종합 해석이 없습니다/)
  assert.doesNotMatch(empty, /아래 이슈별 판단/)
  assert.doesNotMatch(empty, /먼저 살펴볼 이슈|크레딧 사용/)
  const uncertain = render(reportInsightFixture(report, 'CHIP_MAKER', 'unavailable'))
  assert.match(uncertain, /리포트 중요도 판단 보류/)
  assert.match(uncertain, /<dt>직접 관련성<\/dt><dd>미확인/)
  assert.match(uncertain, /관련성 미확인 · 이 관점의 업무와 연결되는지 판단할 근거가 부족합니다/)
  assert.doesNotMatch(uncertain, /영향 규모 미확인 · 업무 관련성은 확인/)
})
test('empty synthesis with assessed issues points to reasons and preserved evidence without claiming no source', () => {
  const result = reportInsightFixture(report, 'CHIP_MAKER', 'unavailable')
  Object.assign(result.insights[0], { overview: [], implications: [], watchItems: [],
    headline: '원문 근거는 있으나 이 관점의 업무 관련성을 판단하지 못했습니다.' })
  const html = render(result)
  assert.match(html, /이 관점의 종합 해석이 없습니다/)
  assert.match(html, /아래 이슈별 판단 사유와 원문 근거를 확인해 주세요/)
  assert.match(html, /원문 근거 문장 1 보기/)
  assert.match(html, /리포트 중요도 판단 보류/)
  assert.ok(html.includes(result.insights[0].issues[0].reason))
  assert.doesNotMatch(html, /직접 연결되는 검증된 분석이 없습니다|새 근거가 추가되면/)
  assert.doesNotMatch(render(reportInsightFixture(report)), /이 관점의 종합 해석이 없습니다/)
})
test('unavailable issues distinguish unknown relatedness from unknown impact without changing their importance', () => {
  const cases = [
    { directness: null, impact: 2, label: '관련성 미확인', absent: '영향 규모 미확인',
      reason: '제품 적용 대상이 제시되지 않아 제조 업무와 연결되는지 확인할 수 없습니다.' },
    { directness: 2, impact: null, label: '영향 규모 미확인', absent: '관련성 미확인',
      reason: '설계 IP 공정 인증은 제조 준비와 관련되지만 적용 범위와 생산량 변화는 확인되지 않았습니다.' },
  ]
  for (const { directness, impact, label, absent, reason } of cases) {
    const result = reportInsightFixture(report, 'CHIP_MAKER', 'unavailable')
    const issue = result.insights[0].issues[0]
    result.insights[0].issues = [issue]
    Object.assign(issue, { reason, axes: { directness, impact, urgency: null, novelty: null } })
    const html = render(result)
    const issuesHtml = html.match(/<ol class="report-insights-issues">([\s\S]*?)<\/ol>/)?.[1] ?? ''
    assert.match(html, /리포트 중요도 판단 보류/)
    assert.match(issuesHtml, /data-importance="unavailable">중요도 판단 보류/)
    assert.ok(issuesHtml.includes(label), label)
    assert.ok(!issuesHtml.includes(absent), absent)
    assert.ok(issuesHtml.includes(reason), 'the original reason remains visible')
    assert.match(issuesHtml, /<dt>시급성<\/dt><dd>미확인/)
    assert.match(issuesHtml, /원문 근거 문장 1 보기/)
    if (directness !== null) {
      assert.match(issuesHtml, /업무 관련성은 확인했지만 영향 크기의 근거가 부족해 중요도 판단을 보류/)
      assert.match(issuesHtml, /<dt>직접 관련성<\/dt><dd>2 \/ 3/)
    }
  }
})
test('zero relatedness remains low with unknown impact, while missing urgency alone does not imply unavailable', () => {
  const cases = [
    { importance: 'low', label: '낮음', axes: { directness: 0, impact: null, urgency: null, novelty: null } },
    { importance: 'high', label: '높음', axes: { directness: 3, impact: 3, urgency: null, novelty: null } },
  ]
  for (const { importance, label, axes } of cases) {
    const result = reportInsightFixture(report)
    const insight = result.insights[0], issue = insight.issues[0]
    insight.importance = importance
    insight.issues = [issue]
    Object.assign(issue, { importance, axes })
    const html = render(result)
    const issuesHtml = html.match(/<ol class="report-insights-issues">([\s\S]*?)<\/ol>/)?.[1] ?? ''
    assert.ok(html.includes(`리포트 중요도 ${label}`))
    assert.ok(issuesHtml.includes(`data-importance="${importance}">중요도 ${label}`))
    assert.doesNotMatch(issuesHtml, /판단 보류|관련성 미확인|영향 규모 미확인/)
    assert.match(issuesHtml, /<dt>시급성<\/dt><dd>미확인/)
    if (importance === 'low') {
      assert.match(issuesHtml, /관련성 낮음/)
      assert.match(issuesHtml, /<dt>직접 관련성<\/dt><dd>0 \/ 3/)
      assert.match(issuesHtml, /<dt>영향 크기<\/dt><dd>미확인/)
    }
    assert.match(html, /직접 관련성이 0이면 영향 크기가 미확인이어도 낮음/)
  }
})
test('a saved unavailable grade is preserved even when zero relatedness would be low under the updated rubric', () => {
  const result = reportInsightFixture(report, 'CHIP_MAKER', 'unavailable')
  result.insights[0].issues[0].axes.directness = 0
  const html = render(result)
  assert.match(html, /리포트 중요도 판단 보류/)
  assert.match(html, /data-importance="unavailable">중요도 판단 보류/)
  assert.doesNotMatch(html, /data-importance="low"/)
})
test('unresolvable evidence cannot link to a later or different article', () => {
  const result = reportInsightFixture(report), fact = result.insights[0].facts[0]
  fact.articleId = 999999
  const html = render(result)
  assert.match(html, /현재 보고서에서 근거 기사를 확인할 수 없습니다/)
  assert.doesNotMatch(html, /href="https:\/\/example.invalid\/news\/0"/)
})
test('one event card retains every judgment and evidence while ranking groups without changing saved input', () => {
  const findings = structuredClone(report.findings.slice(0, 3))
  Object.assign(findings[1], { issueId: findings[0].issueId, issue: findings[0].issue,
    articleId: findings[0].articleId, articleTitle: findings[0].articleTitle, canonicalUrl: findings[0].canonicalUrl })
  const result = reportInsightFixture({ ...report, findings })
  const [first, second, third] = result.insights[0].issues
  Object.assign(first, { rank: 1, reason: '전력과 냉각 준비가 필요한 판단입니다.' })
  Object.assign(second, { rank: 3, reason: '대규모 배치 계획의 영향 규모는 확인하지 못했습니다.', importance: 'unavailable',
    axes: { directness: 2, impact: null, urgency: null, novelty: null } })
  Object.assign(third, { rank: 5, reason: '다른 사건의 별도 판단입니다.', importance: 'medium' })
  result.insights[0].issues = [second, third, first]
  const savedInput = structuredClone({ result, findings })
  const cards = issueCards(render(result, findings))

  assert.equal(cards.length, 2)
  assert.match(cards[0], /우선순위 1/)
  assert.match(cards[0], /report-insights-issue-header[^]*?data-importance="high"/)
  assert.match(cards[1], /우선순위 2/)
  assert.doesNotMatch(cards.join(''), /우선순위 [35]/)
  assert.ok(cards[0].includes(first.reason))
  assert.ok(cards[0].includes(second.reason))
  assert.ok(!cards[0].includes(third.reason))
  assert.ok(cards[1].includes(third.reason))
  assert.equal(cards[0].split(`<h5>${findings[0].articleTitle}</h5>`).length - 1, 1)
  assert.match(cards[0], /<dt>직접 관련성<\/dt><dd>3 \/ 3/)
  assert.match(cards[0], /<dt>직접 관련성<\/dt><dd>2 \/ 3/)
  assert.match(cards[0], /<dt>영향 크기<\/dt><dd>미확인/)
  assert.match(cards[0], /영향 규모 미확인/)
  for (const finding of findings.slice(0, 2)) assert.ok(cards[0].includes(finding.keyPoints[0].text))
  assert.equal(cards[0].match(/원문 근거 문장 1 보기/g)?.length, 2)
  assert.deepEqual({ result, findings }, savedInput)
})
test('findings for one original article share a card by article ID or canonical URL when an event is absent', () => {
  for (const identity of ['articleId', 'canonicalUrl']) {
    const findings = structuredClone(report.findings.slice(0, 2))
    for (const finding of findings) Object.assign(finding, { issueId: null, issue: null })
    findings[1][identity] = findings[0][identity]
    const result = reportInsightFixture({ ...report, findings })
    const cards = issueCards(render(result, findings))
    assert.equal(cards.length, 1, identity)
    for (const issue of result.insights[0].issues) assert.ok(cards[0].includes(issue.reason), identity)
  }
})
test('a later judgment linking earlier groups by article and event preserves all judgments in server rank order', () => {
  const findings = structuredClone(report.findings.slice(0, 3))
  Object.assign(findings[2], { articleId: findings[0].articleId, issueId: findings[1].issueId, issue: findings[1].issue })
  const result = reportInsightFixture({ ...report, findings })
  const rankedIssues = result.insights[0].issues
  const reasons = ['첫 번째 원문에 대한 판단입니다.', '같은 사건의 다른 원문에 대한 판단입니다.', '두 기록의 연결을 확인하는 판단입니다.']
  rankedIssues.forEach((issue, index) => { issue.reason = reasons[index] })
  result.insights[0].issues = [rankedIssues[2], rankedIssues[0], rankedIssues[1]]
  const cards = issueCards(render(result, findings, { reportScope: 'RUN' }))

  assert.equal(cards.length, 1)
  const positions = reasons.map(reason => cards[0].indexOf(reason))
  assert.ok(positions.every(position => position >= 0), 'all three reasons remain in the merged card')
  assert.ok(positions[0] < positions[1] && positions[1] < positions[2], 'judgments retain their server rank order')
  for (const finding of findings) assert.ok(cards[0].includes(finding.keyPoints[0].text))
  assert.equal(cards[0].match(/원문 근거 문장 1 보기/g)?.length, 3)
})
test('equal article titles do not merge unrelated events', () => {
  const findings = structuredClone(report.findings.slice(0, 2))
  findings[1].articleTitle = findings[0].articleTitle
  const result = reportInsightFixture({ ...report, findings })
  const cards = issueCards(render(result, findings))
  assert.equal(cards.length, 2)
  for (const card of cards) assert.ok(card.includes(findings[0].articleTitle))
})
test('judgments with missing findings remain separate and retain their reasons and unresolved evidence', () => {
  const result = reportInsightFixture({ ...report, findings: report.findings.slice(0, 2) })
  const cards = issueCards(render(result, []))
  assert.equal(cards.length, 2)
  result.insights[0].issues.forEach((issue, index) => {
    assert.ok(cards[index].includes(`보고서 근거 #${issue.findingId}`))
    assert.ok(cards[index].includes(issue.reason))
    assert.match(cards[index], /현재 보고서에서 근거 기사를 확인할 수 없습니다/)
  })
})
test('historical reports use saved event membership even when current event IDs differ', () => {
  const findings = structuredClone(report.findings.slice(0, 3))
  findings[2].issueId = findings[0].issueId
  findings[2].issue = findings[0].issue
  const result = reportInsightFixture({ ...report, findings })
  result.insights[0].issues[1].reason = '같은 저장 사건의 추가 판단입니다.'
  result.insights[0].issues[2].reason = '나중에 같은 사건으로 연결된 다른 기록입니다.'
  const importantEvents = [{ title: '보고서 작성 시 저장한 사건', summaryKo: '당시의 사건 요약', significance: null,
    sourceFindingIds: findings.slice(0, 2).map(finding => finding.id) }]
  for (const reportScope of ['DAILY', 'WEEKLY']) {
    const cards = issueCards(render(result, findings, { reportScope, importantEvents }))
    assert.equal(cards.length, 2, reportScope)
    assert.ok(cards[0].includes(importantEvents[0].title))
    for (const issue of result.insights[0].issues.slice(0, 2)) assert.ok(cards[0].includes(issue.reason), reportScope)
    assert.ok(!cards[0].includes(result.insights[0].issues[2].reason), reportScope)
  }
})
test('historical reports do not merge separate articles solely because current event IDs match', () => {
  const findings = structuredClone(report.findings.slice(0, 2))
  Object.assign(findings[1], { issueId: findings[0].issueId, issue: findings[0].issue })
  const result = reportInsightFixture({ ...report, findings })
  assert.equal(issueCards(render(result, findings, { reportScope: 'RUN' })).length, 1)
  for (const reportScope of ['DAILY', 'WEEKLY']) {
    assert.equal(issueCards(render(result, findings, { reportScope })).length, 2, reportScope)
  }
})
test('explicit separate saved events stay separate despite a shared current event ID', () => {
  const findings = structuredClone(report.findings.slice(0, 2))
  Object.assign(findings[1], { issueId: findings[0].issueId, issue: findings[0].issue })
  const result = reportInsightFixture({ ...report, findings })
  const importantEvents = findings.map((finding, index) => ({ title: `저장 사건 ${index + 1}`, summaryKo: '',
    significance: null, sourceFindingIds: [finding.id] }))
  assert.equal(issueCards(render(result, findings, { importantEvents })).length, 2)
})
test('separate saved events sharing article ID, canonical URL and current event ID keep their own cards', () => {
  const findings = structuredClone(report.findings.slice(0, 2))
  Object.assign(findings[1], { articleId: findings[0].articleId, canonicalUrl: findings[0].canonicalUrl,
    issueId: findings[0].issueId, issue: findings[0].issue })
  const result = reportInsightFixture({ ...report, findings })
  const importantEvents = findings.map((finding, index) => ({ title: `별도로 저장한 사건 ${index + 1}`, summaryKo: '',
    significance: null, sourceFindingIds: [finding.id] }))
  const cards = issueCards(render(result, findings, { reportScope: 'RUN', importantEvents }))

  assert.equal(cards.length, 2)
  cards.forEach((card, index) => {
    assert.ok(card.includes(importantEvents[index].title))
    assert.ok(card.includes(result.insights[0].issues[index].reason))
    assert.ok(!card.includes(result.insights[0].issues[1 - index].reason))
  })
})
test('ambiguous saved event membership does not bridge otherwise separate cards', () => {
  const findings = structuredClone(report.findings.slice(0, 3))
  for (const finding of findings) Object.assign(finding, { issueId: null, issue: null })
  const result = reportInsightFixture({ ...report, findings })
  const importantEvents = [
    { title: '첫 번째 사건', summaryKo: '', significance: null, sourceFindingIds: [findings[0].id, findings[1].id] },
    { title: '두 번째 사건', summaryKo: '', significance: null, sourceFindingIds: [findings[1].id, findings[2].id] },
  ]
  assert.equal(issueCards(render(result, findings, { importantEvents })).length, 3)
})
test('an automatically queued report shows preparation and no manual generation prerequisite', () => {
  const reason = new ApiError('COMMON409', '동일한 리포트 관점 인사이트 생성 요청이 진행 중입니다. 잠시 후 다시 확인해주세요.', 409)
  const html = renderPanel({ status: 'pending', fetchStatus: 'fetching', fetchFailureCount: 1, fetchFailureReason: reason })
  assert.match(html, /보고서를 만들 때 네 관점의 분석을 자동으로 준비/)
  assert.match(html, /관점 분석을 자동으로 준비하고 있습니다/)
  assert.match(html, /완료되면 분석 결과가 자동으로 표시됩니다/)
  assert.match(html, /조회와 관점 전환은 추가 크레딧을 사용하지 않습니다/)
  assert.doesNotMatch(html, /<button|분석 결과가 없습니다|생성하지 못했습니다/)
})
test('preparation between stored-result polls still promises automatic completion without a manual refresh', () => {
  const reason = new ApiError('COMMON409', '동일한 리포트 관점 인사이트 생성 요청이 진행 중입니다. 잠시 후 다시 확인해주세요.', 409)
  const html = renderPanel({ status: 'error', fetchStatus: 'idle', error: reason, fetchFailureReason: reason })
  assert.match(html, /관점 분석을 자동으로 준비하고 있습니다/)
  assert.match(html, /저장된 결과를 계속 확인/)
  assert.match(html, /완료되면 분석 결과가 자동으로 표시됩니다/)
  assert.doesNotMatch(html, /<button|저장된 분석 다시 확인|분석 다시 준비 · 크레딧 사용|불러오지 못했습니다/)
})
test('terminal or legacy absence offers an explicit retry with its usage notice', () => {
  const reason = new ApiError('COMMON404', '저장된 리포트 관점 인사이트가 없습니다.', 404)
  const html = renderPanel({ status: 'error', fetchStatus: 'idle', error: reason, fetchFailureReason: reason })
  assert.match(html, /이 관점의 분석 결과가 없습니다/)
  assert.match(html, /자동 분석에 실패했거나 보고서 근거가 변경되었거나 아직 저장된 분석이 없는 경우/)
  assert.match(html, /이 관점 분석 다시 준비 · 크레딧 사용/)
  assert.match(html, /새 분석 생성 시 인사이트 크레딧을 사용/)
  assert.doesNotMatch(html, /이 관점으로 리포트 분석 생성|관점 분석을 자동으로 준비하고 있습니다/)
})
test('real lookup errors retain the server message and offer GET reload only', () => {
  const reason = new ApiError('COMMON409', '리포트 관점 인사이트 기능이 현재 비활성화되어 있습니다.', 409)
  const html = renderPanel({ status: 'error', fetchStatus: 'idle', error: reason, fetchFailureReason: reason })
  assert.match(html, /저장된 관점 분석을 불러오지 못했습니다/)
  assert.match(html, /리포트 관점 인사이트 기능이 현재 비활성화되어 있습니다/)
  assert.match(html, /다시 불러오기/)
  assert.doesNotMatch(html, /분석 다시 준비 · 크레딧 사용|관점 분석을 자동으로 준비하고 있습니다/)
})
test('a completed automatic result is displayed with evidence and no paid generation action', () => {
  const result = reportInsightFixture(report)
  const html = renderPanel({ status: 'success', fetchStatus: 'idle', data: result, dataUpdatedAt: Date.now() })
  assert.match(html, /리포트 중요도 높음/)
  assert.match(html, /원문 근거 문장 1 보기/)
  assert.match(html, /저장된 분석 새로고침/)
  assert.doesNotMatch(html, /분석 다시 준비 · 크레딧 사용|관점 분석을 자동으로 준비하고 있습니다|분석 결과가 없습니다/)
})

test('a fresh stored result or pending read supersedes the lost POST response in the panel', () => {
  setGenerationFixture({ variables: { reportId: report.id, audience: 'CHIP_MAKER', snapshot },
    isError: true, error: new TypeError('응답 연결을 잃었습니다.'), reset() {}, mutate() {} })
  const result = reportInsightFixture(report)
  const saved = renderPanel({ status: 'success', fetchStatus: 'idle', data: result, dataUpdatedAt: Date.now() })
  assert.match(saved, /리포트 중요도 높음/)
  assert.match(saved, /저장된 분석 새로고침/)
  assert.doesNotMatch(saved, /생성하지 못했습니다|응답 연결을 잃었습니다|분석 다시 준비 · 크레딧 사용/)

  const pending = new ApiError('COMMON409', '동일한 리포트 관점 인사이트 생성 요청이 진행 중입니다. 잠시 후 다시 확인해주세요.', 409)
  const preparing = renderPanel({ status: 'error', fetchStatus: 'idle', error: pending, fetchFailureReason: pending })
  assert.match(preparing, /관점 분석을 자동으로 준비하고 있습니다/)
  assert.doesNotMatch(preparing, /생성하지 못했습니다|응답 연결을 잃었습니다|분석 결과가 없습니다|분석 다시 준비 · 크레딧 사용/)
})

test('a failed post-response lookup is not absence, while a confirmed 404 keeps the explicit paid retry', () => {
  setGenerationFixture({ variables: { reportId: report.id, audience: 'CHIP_MAKER', snapshot },
    isError: true, error: new TypeError('응답 연결을 잃었습니다.'), reset() {}, mutate() {} })
  const lookupError = new ApiError('COMMON503', '저장된 결과를 확인하지 못했습니다.', 503)
  const unknown = renderPanel({ status: 'error', fetchStatus: 'idle', error: lookupError, fetchFailureReason: lookupError })
  assert.match(unknown, /저장된 관점 분석을 불러오지 못했습니다/)
  assert.match(unknown, /다시 불러오기/)
  assert.doesNotMatch(unknown, /이 관점의 분석 결과가 없습니다|분석 다시 준비 · 크레딧 사용/)

  const absent = new ApiError('COMMON404', '저장된 리포트 관점 인사이트가 없습니다.', 404)
  const missing = renderPanel({ status: 'error', fetchStatus: 'idle', error: absent, fetchFailureReason: absent })
  assert.match(missing, /이 관점의 분석 결과가 없습니다/)
  assert.match(missing, /이 관점 분석 다시 준비 · 크레딧 사용/)
  assert.match(missing, /새 분석 생성 시 인사이트 크레딧을 사용/)
})

test('a manually queued perspective shows waiting until admitted, including when its panel is opened later', async context => {
  const client = new QueryClient({ defaultOptions: { queries: { enabled: false } } })
  context.after(() => client.clear())
  const calls = []
  context.mock.method(globalThis, 'fetch', (_url, init) => new Promise(resolve => {
    const audience = JSON.parse(init.body).audiences[0]
    calls.push({ audience, finish: () => resolve(new Response(JSON.stringify({ isSuccess: true,
      code: 'COMMON200', message: '성공입니다.', result: reportInsightFixture(report, audience) }))) })
  }))
  const pending = ['IT_INFRA', 'EQUIPMENT_MAKER', 'CHIP_MAKER'].map(audience =>
    client.getMutationCache().build(client, generateOptions(client)).execute({ reportId: report.id, audience, snapshot }))
  const settle = () => new Promise(resolve => setImmediate(resolve))
  const markup = () => renderToStaticMarkup(createElement(QueryClientProvider, { client }, createElement(Panel, {
    report, audience: 'CHIP_MAKER', selector: createElement('span', null, '관점 선택'), onEvidenceSelect() {},
  })))
  await settle()
  const waiting = markup()
  assert.match(waiting, /앞선 관점 분석이 끝나기를 기다리고 있습니다/)
  assert.match(waiting, /분석은 두 관점씩 진행합니다/)
  assert.match(waiting, /차례가 되면 자동으로 시작하며, 다른 관점을 확인해도 요청은 유지됩니다/)
  assert.match(waiting, /aria-busy="true"/)
  assert.doesNotMatch(waiting, /리포트 전체를 분석하고 있습니다|분석 다시 준비 · 크레딧 사용/)
  assert.equal(calls.length, 2)
  calls[0].finish()
  await settle()
  const running = markup()
  assert.match(running, /리포트 전체를 분석하고 있습니다/)
  assert.doesNotMatch(running, /앞선 관점 분석이 끝나기를 기다리고 있습니다/)
  assert.equal(calls[2].audience, 'CHIP_MAKER')
  calls[1].finish()
  calls[2].finish()
  await Promise.all(pending)
  const completed = markup()
  assert.match(completed, /리포트 중요도 높음/)
  assert.doesNotMatch(completed, /aria-busy="true"|리포트 전체를 분석하고 있습니다|앞선 관점 분석이 끝나기를 기다리고 있습니다/)
})

test('compact perspective separates source claims, conditional meaning and next check before closed full analysis', () => {
  const result = reportInsightFixture(report)
  const insight = result.insights[0]
  insight.facts[0].claimType = 'FORECAST'
  insight.facts[1].claimType = 'OPINION'
  insight.facts[1].attributedTo = '공식 발표자'
  insight.briefing = { version: 'report-insight-briefing.v1', facts: insight.facts.slice(0, 2),
    overview: insight.overview, watchItems: insight.watchItems, omittedItemCount: 3 }
  const before = structuredClone(result)
  const html = render(result)
  const brief = html.slice(0, html.indexOf('<details class="report-insights-full"'))
  for (const value of ['원문에서 확인한 내용', '계획·전망', '의견·발언 · 공식 발표자', '업무상 의미',
    '해석의 조건', insight.overview[0].assumption, '다음 확인점', insight.watchItems[0].indicator,
    insight.watchItems[0].trigger, '더 읽을 판단·확인 항목 3개']) assert.ok(brief.includes(value), value)
  assert.doesNotMatch(brief, /리포트 중요도|먼저 살펴볼 이슈|직접 관련성|조건부 해석|리포트에 저장된 주장/)
  assert.match(html, /<details class="report-insights-full"><summary>관점 분석과 원문 근거 자세히 보기/)
  assert.match(html, /영향 경로|판단 변경 조건/)
  assert.doesNotMatch(html, /<details[^>]* open/)
  assert.deepEqual(result, before)
})

test('compact analysis drops an entire mixed-source meaning and watch item when any claim is not currently displayable', () => {
  const result = reportInsightFixture(report)
  const insight = result.insights[0]
  insight.briefing = { version: 'report-insight-briefing.v1', facts: insight.facts.slice(0, 2),
    overview: insight.overview, watchItems: insight.watchItems, omittedItemCount: 0 }
  const missingId = insight.facts[0].findingId
  const html = render(result, report.findings.filter(finding => finding.id !== missingId))
  const brief = html.slice(0, html.indexOf('<details class="report-insights-full"'))
  assert.match(brief, /간단히 표시할 관점 분석이 없습니다/)
  assert.ok(!brief.includes(insight.overview[0].text))
  assert.ok(!brief.includes(insight.watchItems[0].trigger))
  assert.ok(!brief.includes(insight.facts[1].text))
})

test('compact analysis preserves full conditions and escapes text without exposing a free-form headline', () => {
  const result = reportInsightFixture(report)
  const insight = result.insights[0]
  const assumption = '실제 실행으로 확인되는 경우에만 의미가 있으며, 계획이 유지되면 영향은 없다.'
  insight.briefing = { version: 'report-insight-briefing.v1', facts: insight.facts.slice(0, 2),
    overview: [{ ...insight.overview[0], text: '<script>untrusted meaning</script>', assumption }],
    watchItems: [], omittedItemCount: 0 }
  const html = render(result)
  const brief = html.slice(0, html.indexOf('<details class="report-insights-full"'))
  assert.match(brief, /&lt;script&gt;untrusted meaning&lt;\/script&gt;/)
  assert.ok(brief.includes(assumption))
  assert.ok(!brief.includes(insight.headline))
  assert.doesNotMatch(brief, /<script>|\.\.\.|…/)
})
