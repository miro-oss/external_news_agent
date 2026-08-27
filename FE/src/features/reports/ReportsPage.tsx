import { useState } from 'react'
import Markdown from 'react-markdown'
import {
  useLatestReport,
  useNotificationChannels,
  useNotificationGroups,
  usePreviewNotification,
  useReport,
  useReports,
  useSendNotification,
} from '../../api/queries'
import type { ReportDetail, ReportFinding, ReportSummary } from '../../api/types'
import { formatFullDate, formatShortDate } from '../../lib/datetime'
import { MutationStatus } from '../settings/MutationStatus'

export function ReportsPage() {
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const reports = useReports()
  const latest = useLatestReport()
  const activeId = selectedId ?? latest.data?.id ?? null
  const selectedReport = useReport(selectedId)
  const activeReport = selectedId === null ? latest : selectedReport
  const isInitialLoading = latest.isPending || reports.isPending
  const initialError = latest.isError ? latest.error : reports.isError ? reports.error : null

  return (
    <main className="reports-page">
      <header className="page-header report-header">
        <div>
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
      {!isInitialLoading && !initialError && latest.data !== null && reports.data?.content.length === 0 && (
        <div className="state-panel report-empty">
          <strong>최신 보고서는 있지만 아카이브 목록이 비어 있습니다.</strong>
          <span>잠시 후 새로고침해 보고, 계속되면 보고서 목록 API 상태를 확인해 주세요.</span>
        </div>
      )}

      {reports.data && reports.data.content.length > 0 && activeId !== null && (
        <div className="report-workspace">
          <aside className="report-list" aria-label="생성된 보고서">
            <div className="report-list-heading">
              <span>생성된 보고서</span>
              <strong>{reports.data.content.length}</strong>
            </div>
            {reports.data.content.map((report) => (
              <ReportListItem
                key={report.id}
                report={report}
                active={report.id === activeId}
                onSelect={() => setSelectedId(report.id === latest.data?.id ? null : report.id)}
              />
            ))}
          </aside>

          <section className="report-detail-shell" aria-live="polite">
            {activeReport.isPending && <div className="report-detail-state">보고서 본문을 불러오는 중입니다.</div>}
            {activeReport.isError && (
              <div className="report-detail-state error" role="alert">{activeReport.error.message}</div>
            )}
            {activeReport.data && <ReportView report={activeReport.data} />}
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
      <span className="report-list-date">{formatShortDate(report.generatedAt)}</span>
      <strong>{report.title}</strong>
      <span className="report-list-meta">
        근거 {report.findingCount}건
        {report.highRiskCount > 0 && <em>높은 위험 {report.highRiskCount}</em>}
      </span>
    </button>
  )
}

function ReportView({ report }: { report: ReportDetail }) {
  const stats = report.summaryStats
  return (
    <article className="report-document">
      <header className="report-document-header">
        {/* 실행 번호와 모델명은 운영자가 로그를 찾을 때 쓰는 값이지 보고서를 읽는 사람에게 줄 정보가 아니다. */}
        <h2>{report.title}</h2>
        <time dateTime={report.generatedAt}>{formatFullDate(report.generatedAt)}</time>
        <div className="report-stat-row" aria-label="보고서 요약 통계">
          <ReportStat value={stats.findingCount} label="전체 근거" />
          <ReportStat value={stats.newCount} label="신규" />
          <ReportStat value={stats.updatedCount} label="후속" />
          <ReportStat value={stats.byRiskLevel.high ?? 0} label="높은 위험" tone="danger" />
        </div>
      </header>

      <ReportDeliveryActions key={report.id} reportId={report.id} />

      <section className="markdown-report" aria-label="마크다운 보고서 본문">
        <MarkdownBody markdown={report.markdownBody} />
      </section>

      <section className="report-findings">
        <div className="section-heading report-section-heading">
          <h3>근거</h3>
          <span>{report.findings?.length ?? 0}건</span>
        </div>
        {report.findings && report.findings.length > 0 ? (
          <div className="finding-list">
            {report.findings.map((finding) => <FindingCard finding={finding} key={finding.id} />)}
          </div>
        ) : <p className="muted">이 보고서에 포함된 근거가 없습니다.</p>}
      </section>
    </article>
  )
}

function ReportDeliveryActions({ reportId }: { reportId: number }) {
  const channels = useNotificationChannels()
  const groups = useNotificationGroups()
  const preview = usePreviewNotification()
  const send = useSendNotification()
  const activeChannels = channels.data?.filter((channel) => channel.active) ?? []
  const activeGroups = groups.data?.content.filter((group) => group.active) ?? []
  const [channelId, setChannelId] = useState<number | null>(null)
  const [groupId, setGroupId] = useState<number | null>(null)
  const selectedChannelId = channelId ?? activeChannels[0]?.id ?? null
  const selectedGroupId = groupId ?? activeGroups[0]?.id ?? null
  const [idempotencyKey, setIdempotencyKey] = useState(() => newDeliveryIdempotencyKey(reportId))

  function changeGroup(nextGroupId: number) {
    send.reset()
    setIdempotencyKey(newDeliveryIdempotencyKey(reportId))
    setGroupId(nextGroupId)
  }

  function changeChannel(nextChannelId: number) {
    preview.reset()
    send.reset()
    setIdempotencyKey(newDeliveryIdempotencyKey(reportId))
    setChannelId(nextChannelId)
  }

  return (
    <section className="report-delivery-panel" aria-label="보고서 발송">
      <div className="report-delivery-heading">
        <div><h3>보고서 전달</h3><p>채널에 보일 내용을 먼저 확인한 뒤 수신 그룹으로 보냅니다.</p></div>
        <span>{activeGroups.length}개 그룹 · {activeChannels.length}개 채널</span>
      </div>
      <div className="report-delivery-controls">
        <label>수신 그룹<select value={selectedGroupId ?? ''} onChange={(event) => changeGroup(Number(event.target.value))}>
          {activeGroups.length === 0 && <option value="">활성 그룹 없음</option>}
          {activeGroups.map((group) => <option value={group.id} key={group.id}>{group.name} · {group.activeMemberCount}명</option>)}
        </select></label>
        <label>채널<select value={selectedChannelId ?? ''} onChange={(event) => changeChannel(Number(event.target.value))}>
          {activeChannels.length === 0 && <option value="">활성 채널 없음</option>}
          {activeChannels.map((channel) => <option value={channel.id} key={channel.id}>{channel.name}</option>)}
        </select></label>
        <div className="report-delivery-buttons">
          <button type="button" className="secondary-button" disabled={selectedChannelId === null || preview.isPending}
            onClick={() => selectedChannelId !== null && preview.mutate({ reportId, channelId: selectedChannelId })}>
            {preview.isPending ? '준비 중…' : '미리보기'}
          </button>
          <button type="button" className="primary-button" disabled={selectedChannelId === null || selectedGroupId === null || send.isPending}
            onClick={() => selectedChannelId !== null && selectedGroupId !== null && send.mutate({
              reportId,
              groupIds: [selectedGroupId],
              channelIds: [selectedChannelId],
              idempotencyKey,
            })}>
            {send.isPending ? '발송 중…' : '발송하기'}
          </button>
        </div>
      </div>
      <MutationStatus
        error={preview.error ?? send.error}
        success={send.data && send.data.failedCount === 0
          ? `발송 ${send.data.sentCount}건 성공 · ${send.data.skippedCount}건 건너뜀`
          : null}
        warning={send.data && send.data.failedCount > 0
          ? `발송 ${send.data.sentCount}건 성공 · ${send.data.failedCount}건 실패 · ${send.data.skippedCount}건 건너뜀`
          : null}
      />
      {preview.data && (
        <div className="notification-preview">
          <div><strong>{preview.data.channelType === 'EMAIL' ? preview.data.subject : '텔레그램 메시지'}</strong><span>{preview.data.chunkCount}개 조각</span></div>
          {preview.data.chunks.map((chunk) => <pre key={chunk.seq}>{chunk.body}</pre>)}
        </div>
      )}
    </section>
  )
}

function newDeliveryIdempotencyKey(reportId: number) {
  return `r${reportId}-${globalThis.crypto.randomUUID()}`
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
        <span>{changeTypeLabel(finding.changeType)}</span>
      </div>
      <h4>{finding.articleTitle}</h4>
      <p>{finding.summary}</p>
      {finding.keyPoints.length > 0 && (
        <ul>{finding.keyPoints.map((point, index) => <li key={`${index}-${point}`}>{point}</li>)}</ul>
      )}
      <a href={finding.canonicalUrl} target="_blank" rel="noreferrer">원문 열기 <span aria-hidden="true">↗</span></a>
    </article>
  )
}

/** 서버가 만든 마크다운을 HTML 주입 없이 표준 문법으로 렌더링한다. */
function MarkdownBody({ markdown }: { markdown: string }) {
  const lines = markdown.split(/\r?\n/)
  const firstContentLine = lines.findIndex((line) => line.trim().length > 0)
  if (firstContentLine >= 0 && lines[firstContentLine].trim().startsWith('# ')) {
    lines.splice(firstContentLine, 1)
  }
  return (
    <Markdown
      components={{
        // 문서 제목이 이미 페이지 헤더의 h2다. 본문 제목은 한 단계씩 낮춰야 바깥 구조와 어긋나지 않는다.
        h1: ({ children }) => <h2>{children}</h2>,
        h2: ({ children }) => <h3>{children}</h3>,
        h3: ({ children }) => <h4>{children}</h4>,
        a: ({ href, children }) => (
          <a href={href} target="_blank" rel="noreferrer">{children}</a>
        ),
      }}
    >
      {lines.join('\n')}
    </Markdown>
  )
}

function riskLabel(value: ReportFinding['riskLevel']) {
  return { high: '높은 위험', medium: '중간 위험', low: '낮은 위험' }[value]
}

function changeTypeLabel(value: ReportFinding['changeType']) {
  return { NEW: '신규', UPDATED: '후속' }[value]
}
