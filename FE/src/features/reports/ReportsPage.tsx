import { Fragment, useState, type ReactNode } from 'react'
import { useLatestReport, useReport, useReports } from '../../api/queries'
import type { ReportDetail, ReportFinding, ReportSummary } from '../../api/types'

export function ReportsPage() {
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const reports = useReports()
  const latest = useLatestReport()
  const activeId = selectedId ?? latest.data?.id ?? null
  const detail = useReport(activeId)
  const isInitialLoading = latest.isPending || reports.isPending
  const initialError = latest.isError ? latest.error : reports.isError ? reports.error : null

  return (
    <main className="reports-page">
      <header className="page-header report-header">
        <div>
          <p className="eyebrow">PHASE 2 · M5</p>
          <h1>뉴스 리포트</h1>
          <p className="muted">한 번의 수집 실행에서 발견한 신호를 보고서 한 장으로 읽습니다.</p>
        </div>
        <div className="summary-count" aria-live="polite">
          <strong>{reports.data?.totalElements ?? 0}</strong>
          <span>생성 완료</span>
        </div>
      </header>

      {isInitialLoading && <div className="state-panel" aria-busy="true">최신 보고서를 불러오는 중입니다.</div>}
      {initialError && <div className="state-panel error" role="alert">보고서를 불러오지 못했습니다. {initialError.message}</div>}
      {!isInitialLoading && !initialError && latest.data === null && (
        <div className="state-panel report-empty">
          <span className="empty-mark" aria-hidden="true">⌁</span>
          <strong>아직 생성된 보고서가 없습니다.</strong>
          <span>수집을 실행하면 분석 완료 후 첫 보고서가 자동으로 만들어집니다.</span>
        </div>
      )}

      {reports.data && reports.data.content.length > 0 && activeId !== null && (
        <div className="report-workspace">
          <aside className="report-list" aria-label="생성된 보고서">
            <div className="report-list-heading">
              <span>REPORT ARCHIVE</span>
              <strong>{reports.data.content.length}</strong>
            </div>
            {reports.data.content.map((report) => (
              <ReportListItem
                key={report.id}
                report={report}
                active={report.id === activeId}
                onSelect={() => setSelectedId(report.id)}
              />
            ))}
          </aside>

          <section className="report-detail-shell" aria-live="polite">
            {detail.isPending && <div className="report-detail-state">보고서 본문을 불러오는 중입니다.</div>}
            {detail.isError && <div className="report-detail-state error" role="alert">{detail.error.message}</div>}
            {detail.data && <ReportView report={detail.data} />}
          </section>
        </div>
      )}
    </main>
  )
}

function ReportListItem({ report, active, onSelect }: {
  report: ReportSummary
  active: boolean
  onSelect: () => void
}) {
  return (
    <button
      type="button"
      className={active ? 'report-list-item active' : 'report-list-item'}
      aria-pressed={active}
      onClick={onSelect}
    >
      <span className="report-list-date">{formatDate(report.generatedAt)}</span>
      <strong>{report.title}</strong>
      <span className="report-list-meta">
        finding {report.findingCount}
        {report.highRiskCount > 0 && <em>high {report.highRiskCount}</em>}
      </span>
    </button>
  )
}

function ReportView({ report }: { report: ReportDetail }) {
  const stats = report.summaryStats
  return (
    <article className="report-document">
      <header className="report-document-header">
        <p className="eyebrow">RUN #{report.runId} · {report.modelName}</p>
        <h2>{report.title}</h2>
        <time dateTime={report.generatedAt}>{formatFullDate(report.generatedAt)}</time>
        <div className="report-stat-row" aria-label="보고서 요약 통계">
          <ReportStat value={stats.findingCount} label="findings" />
          <ReportStat value={stats.newCount} label="new" />
          <ReportStat value={stats.updatedCount} label="updated" />
          <ReportStat value={stats.byRiskLevel.high ?? 0} label="high risk" tone="danger" />
        </div>
      </header>

      <section className="markdown-report" aria-label="마크다운 보고서 본문">
        <MarkdownBody markdown={report.markdownBody} />
      </section>

      <section className="report-findings">
        <div className="section-heading report-section-heading">
          <div>
            <p className="eyebrow">EVIDENCE</p>
            <h3>근거 findings</h3>
          </div>
          <span>{report.findings?.length ?? 0}건</span>
        </div>
        {report.findings && report.findings.length > 0 ? (
          <div className="finding-list">
            {report.findings.map((finding) => <FindingCard finding={finding} key={finding.id} />)}
          </div>
        ) : <p className="muted">이 보고서에 포함된 finding이 없습니다.</p>}
      </section>
    </article>
  )
}

function ReportStat({ value, label, tone }: { value: number; label: string; tone?: 'danger' }) {
  return (
    <div className={tone === 'danger' ? 'report-stat danger' : 'report-stat'}>
      <strong>{value}</strong>
      <span>{label}</span>
    </div>
  )
}

function FindingCard({ finding }: { finding: ReportFinding }) {
  return (
    <article className="finding-card">
      <div className="finding-card-meta">
        <span>{finding.category}</span>
        <span className={`status-pill risk-label-${finding.riskLevel}`}>{riskLabel(finding.riskLevel)}</span>
        <span>{finding.changeType}</span>
      </div>
      <h4>{finding.articleTitle}</h4>
      <p>{finding.summary}</p>
      {finding.keyPoints.length > 0 && (
        <ul>{finding.keyPoints.map((point) => <li key={point}>{point}</li>)}</ul>
      )}
      <a href={finding.canonicalUrl} target="_blank" rel="noreferrer">원문 열기 <span aria-hidden="true">↗</span></a>
    </article>
  )
}

/** 서버가 만든 제한된 마크다운(제목·문단·불릿·autolink)을 HTML 주입 없이 렌더링한다. */
function MarkdownBody({ markdown }: { markdown: string }) {
  const nodes: ReactNode[] = []
  let bullets: string[] = []
  const flushBullets = () => {
    if (bullets.length === 0) return
    const current = bullets
    bullets = []
    nodes.push(<ul key={`list-${nodes.length}`}>{current.map((text, index) => (
      <li key={`${text}-${index}`}>{linkify(text)}</li>
    ))}</ul>)
  }

  markdown.split(/\r?\n/).forEach((rawLine, index) => {
    const line = rawLine.trim()
    if (line.startsWith('- ')) {
      bullets.push(line.slice(2))
      return
    }
    flushBullets()
    if (!line) return
    if (line.startsWith('### ')) nodes.push(<h4 key={index}>{line.slice(4)}</h4>)
    else if (line.startsWith('## ')) nodes.push(<h3 key={index}>{line.slice(3)}</h3>)
    else if (line.startsWith('# ')) nodes.push(<h2 key={index}>{line.slice(2)}</h2>)
    else nodes.push(<p key={index}>{linkify(line)}</p>)
  })
  flushBullets()
  return <>{nodes.map((node, index) => <Fragment key={index}>{node}</Fragment>)}</>
}

function linkify(text: string): ReactNode {
  const match = text.match(/^(.*)<(https?:\/\/[^>]+)>(.*)$/)
  if (!match) return text
  return <>{match[1]}<a href={match[2]} target="_blank" rel="noreferrer">{match[2]}</a>{match[3]}</>
}

function riskLabel(value: ReportFinding['riskLevel']) {
  return { high: '높은 위험', medium: '중간 위험', low: '낮은 위험' }[value]
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('ko-KR', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
    .format(new Date(value))
}

function formatFullDate(value: string) {
  return new Intl.DateTimeFormat('ko-KR', { dateStyle: 'full', timeStyle: 'short' }).format(new Date(value))
}
