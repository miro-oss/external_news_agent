import { useId, type ReactNode } from 'react'
import { ApiError } from '../../api/client'
import {
  isReportInsightAbsent, isReportInsightPreparing, reportInsightSnapshotKey, selectReportInsight,
  useGenerateReportInsight, useReportInsight, useReportInsightGenerating,
  type ReportAudienceInsight, type ReportImportance, type ReportInsightFact, type ReportInsightIssue, type ReportInsightResult,
} from '../../api/reportInsights'
import { AUDIENCE_LABELS, type Audience, type ReportDetail, type ReportFinding } from '../../api/types'
import { formatFullDate } from '../../lib/datetime'
import { safeReportEvidenceUrl } from './reportChangesDisplay'
import './report-insights.css'

type EvidenceSelect = (articleId: number, runId: number, sentences: number[]) => void
const IMPORTANCE_LABELS: Record<ReportImportance, string> = { high: '높음', medium: '중간', low: '낮음', unavailable: '판단 보류' }
const CLAIM_LABELS = { FACT: '확인된 사실', FORECAST: '계획·전망', OPINION: '의견·발언' }

export function ReportInsightsPanel({ report, audience, selector, onEvidenceSelect }: {
  report: ReportDetail
  audience: Audience
  selector: ReactNode
  onEvidenceSelect: EvidenceSelect
}) {
  const headingId = useId()
  const snapshot = reportInsightSnapshotKey(report)
  const stored = useReportInsight(report.id, audience, snapshot)
  const generate = useGenerateReportInsight()
  const activeGeneration = generate.variables?.reportId === report.id && generate.variables.audience === audience
    && generate.variables.snapshot === snapshot
  const generating = useReportInsightGenerating(report.id, audience, snapshot)
  const generationError = activeGeneration && generate.isError ? generate.error : null
  const insight = !stored.isFetching && !stored.isError ? selectReportInsight(stored.data, report.id, audience) : undefined
  const missing = !stored.isFetching && isReportInsightAbsent(stored.error)
  const preparing = !insight && !generating && isReportInsightPreparing(stored.failureReason ?? stored.error)
  const blockedGeneration = generationError instanceof ApiError && !isReportInsightPreparing(generationError)
    && ['COMMON409', 'QUOTA429', 'COMMON400', 'AUDIENCE400', 'REPORT404'].includes(generationError.code)
  const canGenerate = missing && !generating && !blockedGeneration && (report.findings?.length ?? 0) > 0
  async function refresh() {
    if (!generating) generate.reset()
    await stored.refetch()
  }
  return <section className="report-insights-panel" aria-labelledby={headingId} aria-busy={stored.isFetching || generating}>
    <header className="report-insights-heading">
      <div><h3 id={headingId}>리포트 관점 분석</h3><p>보고서를 만들 때 네 관점의 분석을 자동으로 준비합니다. 관점을 선택해 중요한 이슈와 다음 확인 항목을 읽습니다.</p></div>
      {insight && <button type="button" className="text-button" onClick={() => { void refresh() }}>저장된 분석 새로고침</button>}
    </header>
    <div className="report-insights-selector">{selector}</div>
    <p className="report-insights-usage">자동 분석에는 인사이트 크레딧을 사용합니다. 저장된 분석 조회와 관점 전환은 추가 크레딧을 사용하지 않습니다.</p>
    {(stored.isPending || stored.isFetching) && !preparing && !generating && <div className="report-insights-state" role="status"><p>이 리포트의 저장된 {AUDIENCE_LABELS[audience]} 관점 분석을 확인하고 있습니다.</p></div>}
    {preparing && <div className="report-insights-state" role="status">
      <strong>{stored.isFetching ? '관점 분석을 자동으로 준비하고 있습니다.' : '관점 분석 준비가 계속되고 있습니다.'}</strong>
      <p>{stored.isFetching ? '여러 관점의 근거를 종합하므로 몇 분이 걸릴 수 있습니다. 완료되면 분석 결과가 자동으로 표시됩니다.'
        : '완료까지 시간이 더 걸리고 있습니다. 잠시 후 다시 확인해 주세요.'}</p>
      {!stored.isFetching && <button type="button" className="text-button" onClick={() => { void refresh() }}>저장된 분석 다시 확인</button>}
    </div>}
    {generating && <div className="report-insights-state" role="status"><strong>리포트 전체를 분석하고 있습니다.</strong><p>주요 이슈의 우선순위와 근거를 종합하는 동안 잠시 기다려 주세요.</p></div>}
    {generationError && !isReportInsightPreparing(generationError) && <div className="report-insights-state" role="alert"><strong>관점 분석을 생성하지 못했습니다.</strong><p>{generationError.message}</p>
      <button type="button" className="text-button" disabled={stored.isFetching || generating} onClick={() => { void refresh() }}>저장된 분석 다시 확인</button>
    </div>}
    {stored.isError && !stored.isFetching && !missing && !preparing && !generating && <div className="report-insights-state" role="alert">
      <strong>저장된 관점 분석을 불러오지 못했습니다.</strong><p>{stored.error.message}</p>
      <button type="button" className="text-button" onClick={() => { void refresh() }}>다시 불러오기</button>
    </div>}
    {missing && !generating && !blockedGeneration && <div className="report-insights-state">
      <strong>이 관점의 분석 결과가 없습니다.</strong>
      <p>{(report.findings?.length ?? 0) > 0 ? '자동 분석이 완료되지 않았거나 이전에 만든 보고서일 수 있습니다. 저장된 분석을 다시 확인하거나 이 관점의 분석을 다시 준비할 수 있습니다.'
        : '이 보고서에 포함된 주요 이슈가 없어 관점 분석을 생성할 수 없습니다.'}</p>
      <button type="button" className="text-button" onClick={() => { void refresh() }}>저장된 분석 다시 확인</button>
      {canGenerate && <><p className="report-insights-usage">새 분석 생성 시 인사이트 크레딧을 사용합니다. 저장된 결과 조회는 크레딧을 사용하지 않습니다.</p>
        <button type="button" className="primary-button" onClick={() => generate.mutate({ reportId: report.id, audience, snapshot })}>
          이 관점 분석 다시 준비 · 크레딧 사용
        </button></>}
    </div>}
    {insight && stored.data && !generating && <ReportInsightsContent result={stored.data} insight={insight}
      findings={report.findings ?? []} onEvidenceSelect={onEvidenceSelect} />}
  </section>
}

export function ReportInsightsContent({ result, insight, findings, onEvidenceSelect }: {
  result: ReportInsightResult
  insight: ReportAudienceInsight
  findings: ReportFinding[]
  onEvidenceSelect: EvidenceSelect
}) {
  const byFinding = new Map(findings.map(finding => [finding.id, finding]))
  const facts = new Map(insight.facts.map(fact => [fact.id, fact]))
  const evidence = (ids: string[]) => <ClaimEvidence ids={ids} facts={facts} findings={byFinding} onEvidenceSelect={onEvidenceSelect} />
  const hasAnalysis = insight.overview.length + insight.issues.length + insight.implications.length + insight.watchItems.length > 0
  return <div className="report-insights-content">
    <div className="report-insights-meta"><Importance value={insight.importance} report />
      <span>{AUDIENCE_LABELS[insight.audience]} 관점</span><span>{result.cached ? '저장된 분석' : '새로 생성한 분석'}</span>
      <span>근거 이슈 {result.inputFindingCount}건</span><time dateTime={insight.createdAt}>{formatFullDate(insight.createdAt)}</time>
    </div>
    <h4 className="report-insights-headline">{insight.headline}</h4>
    {!hasAnalysis && <p>이 관점과 직접 연결되는 검증된 분석이 없습니다. 새 근거가 추가되면 다시 확인해 주세요.</p>}
    {insight.overview.length > 0 && <ul className="report-insights-overview" aria-label="리포트 종합 판단">{insight.overview.map((item, index) => <li key={index}>
      <p>{item.text}</p><p className="report-insights-condition">해석의 조건 · {item.assumption}</p>{evidence(item.basisClaimIds)}
    </li>)}</ul>}
    {insight.issues.length > 0 && <section aria-label="관점별 주요 이슈 우선순위"><h4>먼저 살펴볼 이슈</h4>
      <ol className="report-insights-issues">{insight.issues.map(issue => <li className="report-insights-issue" key={issue.findingId}>
        <div className="report-insights-issue-header"><span className="report-insights-rank">우선순위 {issue.rank}</span><Importance value={issue.importance} /></div>
        <h5>{byFinding.get(issue.findingId)?.articleTitle ?? `보고서 근거 #${issue.findingId}`}</h5><p>{issue.reason}</p>
        <IssueAssessmentNote issue={issue} />
        <dl className="report-insights-axes">
          <div><dt>직접 관련성</dt><dd>{axisScore(issue.axes.directness)}</dd></div>
          <div><dt>영향 크기</dt><dd>{axisScore(issue.axes.impact)}</dd></div>
          <div><dt>시급성</dt><dd>{axisScore(issue.axes.urgency)}</dd></div>
          <div><dt>새 변화</dt><dd>비교 근거 없음</dd></div>
        </dl>{evidence(issue.basisClaimIds)}
      </li>)}</ol>
    </section>}
    {insight.implications.length > 0 && <section aria-label="조건부 해석"><h4>조건부 해석</h4><ul className="report-insights-implications">{insight.implications.map((item, index) => <li className="report-insights-implication" key={index}>
      <p>{item.text}</p><dl className="report-insights-explanation"><div><dt>영향 경로</dt><dd>{item.mechanism}</dd></div>
        <div><dt>성립 조건</dt><dd>{item.assumption}</dd></div><div><dt>판단 변경 조건</dt><dd>{item.falsifiedBy}</dd></div></dl>{evidence(item.basisClaimIds)}
    </li>)}</ul></section>}
    {insight.watchItems.length > 0 && <section aria-label="다음 확인 항목"><h4>다음 확인 항목</h4><ul className="report-insights-watch">{insight.watchItems.map((item, index) => <li className="report-insights-watch-item" key={index}>
      <h5>{item.topic}</h5><dl className="report-insights-explanation"><div><dt>확인할 지표</dt><dd>{item.indicator}</dd></div>
        <div><dt>판단 기준</dt><dd>{item.trigger}</dd></div></dl>{evidence(item.basisClaimIds)}
    </li>)}</ul></section>}
    {insight.facts.length > 0 && <ClaimEvidence ids={insight.facts.map(fact => fact.id)} facts={facts} findings={byFinding}
      onEvidenceSelect={onEvidenceSelect} label={`리포트에 저장된 주장 ${insight.facts.length}개 보기`} />}
    <p className="report-insights-footnote">리포트 중요도는 이 관점에서 가장 우선하는 이슈의 중요도입니다. 저장된 주장 목록은 원본이며, 분석에 인용한 근거는 각 항목에 표시됩니다. 계획·전망과 의견은 확인된 사실과 구분해 읽어 주세요.</p>
    <details className="report-insights-rubric"><summary>중요도 판단 기준 보기</summary>
      <p>직접 관련성·영향 크기·시급성을 각각 0~3점으로 평가합니다. 직접 관련성과 영향 크기의 비중은 각각 40%, 시급성은 20%입니다. 시급성 근거가 없으면 나머지 기준으로 계산합니다. 2.25점 이상은 높음, 1.25점 이상은 중간입니다. 직접 관련성이 0이면 영향 크기가 미확인이어도 낮음입니다. 그 외에 직접 관련성이 미확인이면 관련성 미확인으로, 관련성은 확인했지만 영향 크기가 미확인이면 영향 규모 미확인으로 표시하고 중요도 판단을 보류합니다. 이전 보고서와의 비교 근거가 없어 새 변화는 평가하지 않습니다.</p>
    </details>
  </div>
}
function axisScore(value: number | null) { return value === null ? '미확인' : `${value} / 3` }
function IssueAssessmentNote({ issue }: { issue: ReportInsightIssue }) {
  if (issue.importance === 'low' && issue.axes.directness === 0) {
    return <p className="report-insights-condition">관련성 낮음 · 이 관점의 업무와 직접 연결되지 않아 중요도를 낮음으로 분류했습니다.</p>
  }
  if (issue.importance !== 'unavailable') return null
  if (issue.axes.directness === null) {
    return <p className="report-insights-condition">관련성 미확인 · 이 관점의 업무와 연결되는지 판단할 근거가 부족합니다.</p>
  }
  if (issue.axes.directness > 0 && issue.axes.impact === null) {
    return <p className="report-insights-condition">영향 규모 미확인 · 업무 관련성은 확인했지만 영향 크기의 근거가 부족해 중요도 판단을 보류합니다.</p>
  }
  return null
}
function Importance({ value, report = false }: { value: ReportImportance; report?: boolean }) {
  return <span className="report-insight-importance" data-importance={value}>{report ? '리포트 중요도' : '중요도'} {IMPORTANCE_LABELS[value]}</span>
}
function ClaimEvidence({ ids, facts, findings, onEvidenceSelect, label }: {
  ids: string[]
  facts: Map<string, ReportInsightFact>
  findings: Map<number, ReportFinding>
  onEvidenceSelect: EvidenceSelect
  label?: string
}) {
  const claims = [...new Set(ids)].map(id => facts.get(id)).filter((fact): fact is ReportInsightFact => fact !== undefined)
  if (!claims.length) return null
  return <details className="report-insights-evidence"><summary>{label ?? `저장된 주장과 원문 근거 ${claims.length}개 보기`}</summary>
    <ul className="report-insights-facts">{claims.map(fact => {
      const finding = findings.get(fact.findingId)
      const matchingFinding = finding?.articleId === fact.articleId ? finding : undefined
      const href = safeReportEvidenceUrl(matchingFinding?.canonicalUrl ?? '')
      return <li className="report-insights-fact" key={fact.id}>
        <span className="report-insights-claim-type">{CLAIM_LABELS[fact.claimType]}{fact.attributedTo ? ` · ${fact.attributedTo}` : ''}</span>
        <p>{fact.text}</p><div className="report-insights-evidence-actions">
          {matchingFinding && <button type="button" className="text-button"
            onClick={() => onEvidenceSelect(fact.articleId, matchingFinding.runId, fact.evidenceSentenceIds)}>
            원문 근거 문장 {fact.evidenceSentenceIds.map(id => id + 1).join(', ')} 보기
          </button>}
          {href && <a href={href} target="_blank" rel="noopener noreferrer">기사 원문 ↗</a>}
          {!matchingFinding && <span>현재 보고서에서 근거 기사를 확인할 수 없습니다.</span>}
        </div>
      </li>
    })}</ul>
  </details>
}
