import { useCallback, useId, useMemo, useState, type KeyboardEvent, type ReactNode } from 'react'
import Markdown from 'react-markdown'
import {
  useLatestReport,
  useAudienceSetting,
  useIssue,
  useNotificationChannels,
  useNotificationGroups,
  usePreviewNotification,
  useReport,
  useReports,
  useSendNotification,
} from '../../api/queries'
import {
  AUDIENCES,
  AUDIENCE_LABELS,
  CHANGE_TYPE_LABELS,
  SENSITIVITY_LEVEL_LABELS,
  type Audience,
  type AudienceRelevance,
  type IssueArticle,
  type IssueDetail,
  type ReportDetail,
  type ReportFinding,
  type ReportInvestigation,
  type ReportSummary,
  type SensitivityLevel,
} from '../../api/types'
import { KeyPointList } from '../../components/KeyPointList'
import { SensitivityAxes } from '../../components/SensitivityAxes'
import { formatFullDate, formatShortDate } from '../../lib/datetime'
import { normalizeKeyPoints } from '../../lib/keyPoints'
import { ArticleDetailModal } from '../articles/ArticleDetailModal'
import { MutationStatus } from '../settings/MutationStatus'

const INVESTIGATION_STATUS_LABELS = {
  CONCLUDED: '결론 도달',
  NO_NEW_EVIDENCE: '새 근거 없음',
  MAX_STEPS: '3단계 종료',
  BUDGET_LIMIT: '이번 실행 예산 도달',
  REJECTED: '안전 기준으로 생략',
  FAILED: '기존 분석 유지',
} as const

function investigationReasonText(investigation: ReportInvestigation) {
  const rejectionReason = investigation.rejectionReason?.trim()
  if (rejectionReason) {
    if (investigation.status === 'BUDGET_LIMIT') {
      return '이번 실행에 배정된 추가 조사 예산을 모두 사용했습니다.'
    }
    if (rejectionReason.includes('이미 수행한 검색')) {
      return '이미 확인한 검색과 겹쳐 추가 검색을 생략했습니다.'
    }
    if (rejectionReason.includes('허용 소스')) {
      return '사용 가능한 출처 범위를 벗어나 추가 검색을 생략했습니다.'
    }
    if (rejectionReason.includes('본문 미확보 기사')) {
      return '이번 실행에서 확인할 수 있는 본문 대상이 없어 생략했습니다.'
    }
    if (rejectionReason.includes('엔티티') || rejectionReason.includes('과거 비교')) {
      return '이 이슈와 직접 관련된 비교 대상이 아니어서 생략했습니다.'
    }
    return '조사 제안이 안전 기준을 충족하지 않아 생략했습니다.'
  }
  if (investigation.status === 'FAILED') {
    return '추가 조사 중 오류가 발생해 기존 분석 결과를 유지했습니다.'
  }
  return investigation.reason?.trim() || null
}

export function ReportsPage() {
  const [reportScope, setReportScope] = useState<'ALL' | 'RUN' | 'DAILY'>('ALL')
  const scopeFilter = reportScope === 'ALL' ? undefined : reportScope
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [audienceOverride, setAudienceOverride] = useState<Audience | null>(null)
  const [evidenceSelection, setEvidenceSelection] = useState<{
    articleId: number | null
    runId: number | null
    sentences: number[]
  }>({ articleId: null, runId: null, sentences: [] })
  const reports = useReports(scopeFilter)
  const audienceSetting = useAudienceSetting()
  const latest = useLatestReport(scopeFilter)
  const activeId = selectedId ?? latest.data?.id ?? null
  const selectedReport = useReport(selectedId)
  const activeReport = selectedId === null ? latest : selectedReport
  const activeReportData = activeReport.data
  const isInitialLoading = latest.isPending || reports.isPending
  const initialError = latest.isError ? latest.error : reports.isError ? reports.error : null
  const activeAudience = audienceOverride ?? audienceSetting.data?.audience ?? 'CHIP_MAKER'
  const closeArticle = useCallback(() => {
    setEvidenceSelection({ articleId: null, runId: null, sentences: [] })
  }, [])

  return (
    <main className="reports-page">
      <header className="page-header report-header">
        <div>
          <h1>뉴스 리포트</h1>
          <p className="muted">같은 사건을 묶은 주요 이슈와 원문 근거를 한 장에서 읽습니다.</p>
        </div>
        <div className="summary-count" aria-live="polite">
          <strong>{reports.data?.totalElements ?? 0}</strong>
          <span>생성 완료</span>
        </div>
      </header>

      <div className="report-perspective-tabs" role="group" aria-label="보고서 범위">
        {(['ALL', 'DAILY', 'RUN'] as const).map((scope) => (
          <button key={scope} type="button" aria-pressed={reportScope === scope}
            className={reportScope === scope ? 'report-perspective-tab active' : 'report-perspective-tab'}
            onClick={() => { setReportScope(scope); setSelectedId(null) }}>
            {{ ALL: '전체', DAILY: '일일 통합', RUN: '실행별' }[scope]}
          </button>
        ))}
      </div>

      {isInitialLoading && <div className="state-panel" aria-busy="true">최신 보고서를 불러오는 중입니다.</div>}
      {initialError && <div className="state-panel error" role="alert">보고서를 불러오지 못했습니다. {initialError.message}</div>}
      {!isInitialLoading && !initialError && latest.data === null && (
        <div className="state-panel report-empty">
          <span className="empty-mark" aria-hidden="true">⌁</span>
          <strong>아직 생성된 보고서가 없습니다.</strong>
          <span>{reportScope === 'DAILY'
            ? '하루의 수집이 모두 끝나면 다음 날 일일 통합 보고서가 자동으로 만들어집니다.'
            : '수집을 실행하면 분석 완료 후 첫 보고서가 자동으로 만들어집니다.'}</span>
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
            {activeReportData && (
              <ReportView
                key={activeReportData.id}
                report={activeReportData}
                audience={activeAudience}
                defaultAudience={audienceSetting.data?.audience}
                onAudienceSelect={setAudienceOverride}
                onEvidenceSelect={(articleId, sentences) => {
                  setEvidenceSelection({
                    articleId,
                    runId: activeReportData.findings?.find((finding) => finding.articleId === articleId)?.runId
                      ?? activeReportData.runId,
                    sentences,
                  })
                }}
              />
            )}
          </section>
        </div>
      )}

      <ArticleDetailModal
        articleId={evidenceSelection.articleId}
        runId={evidenceSelection.runId ?? undefined}
        defaultAudience={activeAudience}
        initialEvidence={evidenceSelection.sentences}
        onClose={closeArticle}
      />
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
      <span className="report-list-date">{report.reportScope === 'DAILY'
        ? `${report.reportDate} · 일일 통합` : `${formatShortDate(report.generatedAt)} · 실행별`}</span>
      <strong>{report.title}</strong>
      <span className="report-list-meta">
        분석 {report.findingCount}건
        {report.highSensitivityCount > 0 && (
          <em>{SENSITIVITY_LEVEL_LABELS.high} {report.highSensitivityCount}</em>
        )}
      </span>
    </button>
  )
}

function ReportView({ report, audience, defaultAudience, onAudienceSelect, onEvidenceSelect }: {
  report: ReportDetail
  audience: Audience
  defaultAudience?: Audience
  onAudienceSelect: (audience: Audience) => void
  onEvidenceSelect: (articleId: number, sentences: number[]) => void
}) {
  const [filters, setFilters] = useState<ReportFindingFilters>(DEFAULT_REPORT_FINDING_FILTERS)
  const findings = useMemo(
    () => report.reportScope === 'DAILY'
      ? sortFindingsForAudience(report.findings ?? [], audience)
      : selectFindingsForAudience(report.findings ?? [], audience),
    [audience, report.findings, report.reportScope],
  )
  const filteredFindings = useMemo(() => {
    if (filters.sensitivityLevel) {
      return findings.filter((finding) => finding.sensitivity.level === filters.sensitivityLevel)
    }
    return [...findings].sort((left, right) => {
      const levelDifference = SENSITIVITY_RANK[right.sensitivity.level]
        - SENSITIVITY_RANK[left.sensitivity.level]
      const perspectiveDifference = perspectiveRank(right, audience) - perspectiveRank(left, audience)
      return levelDifference || perspectiveDifference || right.sensitivity.score - left.sensitivity.score
    })
  }, [audience, filters, findings])
  const stats = useMemo(() => summarizeFindings(findings), [findings])
  const evidenceCount = useMemo(() => countEvidenceSentences(report.findings ?? []), [report.findings])
  return (
    <article className="report-document">
      <header className="report-document-header">
        <h2>{report.title}</h2>
        {report.reportScope === 'DAILY' && <p className="muted">
          {report.reportDate} · 한국 시간 기준 · 수집 {report.sourceRunIds.length}회 통합
        </p>}
        <time dateTime={report.generatedAt}>{formatFullDate(report.generatedAt)}</time>
        <div className="report-ai-context">
          <span>{generationKind(report.modelName)} · {AUDIENCE_LABELS[audience]} 관점 · 원문 근거 {evidenceCount}문장</span>
          <details>
            <summary aria-label="관점 적용 방식 안내">ⓘ</summary>
            <p>‘{AUDIENCE_LABELS[audience]}’ 관점으로 중요도를 다시 매긴 결과입니다. 같은 이슈도 관점에 따라 순서와 강조가 달라집니다.</p>
          </details>
        </div>
        <div className="report-stat-row" aria-label="보고서 요약 통계">
          <ReportStat value={findings.length} label="전체 이슈" />
          <ReportStat value={stats.newCount} label={CHANGE_TYPE_LABELS.NEW} />
          <ReportStat value={stats.updatedCount} label={CHANGE_TYPE_LABELS.UPDATED} />
          <ReportStat value={stats.highSensitivityCount} label={SENSITIVITY_LEVEL_LABELS.high} tone="danger" />
        </div>
      </header>

      <ReportPerspectiveSelector
        audience={audience}
        defaultAudience={defaultAudience}
        onSelect={onAudienceSelect}
      />

      <ReportDeliveryActions key={report.id} reportId={report.id} />

      <section className="markdown-report" aria-label="마크다운 보고서 본문">
        <MarkdownBody markdown={report.markdownBody} />
      </section>

      <section className="report-findings">
        <div className="section-heading report-section-heading">
          <h3>주요 이슈</h3>
          <span>
            {filteredFindings.length === findings.length
              ? `${findings.length}건`
              : `${filteredFindings.length} / ${findings.length}건`}
            {' · '}{filters.sensitivityLevel ? '' : '높은 민감도순 · '}{AUDIENCE_LABELS[audience]} 관점순
          </span>
        </div>
        <ReportFindingFilterBar
          filters={filters}
          audience={audience}
          defaultAudience={defaultAudience}
          onChange={(key, value) => setFilters((current) => ({ ...current, [key]: value }))}
          onAudienceSelect={onAudienceSelect}
          onReset={() => setFilters(EMPTY_REPORT_FINDING_FILTERS)}
        />
        {filteredFindings.length > 0 ? (
          <div className="finding-list">
            {filteredFindings.map((finding) => (
              <IssueCard
                finding={finding}
                key={finding.id}
                audience={audience}
                reportDate={report.reportScope === 'DAILY' ? report.reportDate : null}
                onEvidenceSelect={onEvidenceSelect}
              />
            ))}
          </div>
        ) : findings.length > 0
          ? <p className="muted report-findings-empty">조건에 맞는 주요 이슈가 없습니다. 필터를 바꿔보세요.</p>
          : <p className="muted report-findings-empty">이 보고서에 포함된 주요 이슈가 없습니다.</p>}
      </section>

      <ReportDisclaimer report={report} audience={audience} />
    </article>
  )
}

type ReportFindingFilters = {
  sensitivityLevel: '' | SensitivityLevel
}

const DEFAULT_REPORT_FINDING_FILTERS: ReportFindingFilters = {
  sensitivityLevel: 'high',
}

const EMPTY_REPORT_FINDING_FILTERS: ReportFindingFilters = {
  sensitivityLevel: '',
}

function ReportFindingFilterBar({
  filters,
  audience,
  defaultAudience,
  onChange,
  onAudienceSelect,
  onReset,
}: {
  filters: ReportFindingFilters
  audience: Audience
  defaultAudience?: Audience
  onChange: <K extends keyof ReportFindingFilters>(key: K, value: ReportFindingFilters[K]) => void
  onAudienceSelect: (audience: Audience) => void
  onReset: () => void
}) {
  const hasActiveFilters = Boolean(filters.sensitivityLevel)
  return (
    <div className="report-finding-filter" aria-label="주요 이슈 필터">
      <label>
        민감도
        <select
          value={filters.sensitivityLevel}
          onChange={(event) => onChange(
            'sensitivityLevel',
            event.target.value as ReportFindingFilters['sensitivityLevel'],
          )}
        >
          <option value="">전체</option>
          <option value="high">{SENSITIVITY_LEVEL_LABELS.high}</option>
          <option value="medium">{SENSITIVITY_LEVEL_LABELS.medium}</option>
          <option value="low">{SENSITIVITY_LEVEL_LABELS.low}</option>
        </select>
      </label>
      <label>
        관점
        <select value={audience} onChange={(event) => onAudienceSelect(event.target.value as Audience)}>
          {AUDIENCES.map((value) => (
            <option value={value} key={value}>
              {AUDIENCE_LABELS[value]}{defaultAudience === value ? ' · 내 기본' : ''}
            </option>
          ))}
        </select>
      </label>
      <button type="button" disabled={!hasActiveFilters} onClick={onReset}>
        전체 보기
      </button>
    </div>
  )
}

function ReportPerspectiveSelector({ audience, defaultAudience, onSelect }: {
  audience: Audience
  defaultAudience?: Audience
  onSelect: (audience: Audience) => void
}) {
  return (
    <section className="report-perspective" aria-label="리포트 관점 선택">
      <div className="report-perspective-heading">
        <div><h3>누구의 관점으로 볼까요?</h3><p>저장된 리포트는 그대로 두고 이슈 순서와 강조만 바꿉니다.</p></div>
        <span>추가 AI 호출 없음</span>
      </div>
      <div className="report-perspective-tabs" role="group" aria-label="독자 관점">
        {AUDIENCES.map((item) => (
          <button
            type="button"
            aria-pressed={audience === item}
            className={audience === item ? 'report-perspective-tab active' : 'report-perspective-tab'}
            key={item}
            onClick={() => onSelect(item)}
          >
            {AUDIENCE_LABELS[item]}
            {defaultAudience === item && <small>기본</small>}
          </button>
        ))}
      </div>
      <p className="report-perspective-note">화면에서 보는 관점만 바뀌며, 발송할 보고서 본문은 중립 원본을 유지합니다.</p>
    </section>
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

function IssueCard({ finding, audience, reportDate, onEvidenceSelect }: {
  finding: ReportFinding
  audience: Audience
  reportDate: string | null
  onEvidenceSelect: (articleId: number, sentences: number[]) => void
}) {
  const [open, setOpen] = useState(false)
  const [visibleRelatedCount, setVisibleRelatedCount] = useState(RELATED_ARTICLE_BATCH_SIZE)
  const panelId = `${useId()}-issue-detail`
  // FE와 BE가 따로 재시작되는 로컬 환경에서도 구버전 응답의 누락 값을 잘못 조회하지 않는다.
  const issueId = finding.issueId ?? null
  const issue = useIssue(issueId, open)
  const issueInfo = finding.issue ?? issue.data
  const keyPoints = useMemo(() => normalizeKeyPoints(finding.keyPoints), [finding.keyPoints])
  const perspective = perspectiveFor(finding, audience)
  // 일일 보고서는 이후 갱신된 이슈 제목 대신 저장된 근거 분석을 보여준다.
  const title = reportDate ? finding.articleTitle : issueInfo?.title || finding.articleTitle
  const summary = limitText(reportDate ? finding.summary : issueInfo?.summary || finding.summary, 120)
  const relatedArticles = issue.data?.articles ?? []
  const visibleRelatedArticles = relatedArticles.slice(0, visibleRelatedCount)
  const investigationReason = finding.investigation
    ? investigationReasonText(finding.investigation)
    : null

  function toggleOpen() {
    if (open) setVisibleRelatedCount(RELATED_ARTICLE_BATCH_SIZE)
    setOpen((current) => !current)
  }

  function handlePrimaryKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key !== 'Enter' && event.key !== ' ') return
    event.preventDefault()
    toggleOpen()
  }

  return (
    <article className="issue-card">
      <div
        className="issue-card-primary"
        role="button"
        tabIndex={0}
        aria-expanded={open}
        aria-controls={panelId}
        aria-label={`${title} ${open ? '접기' : '자세히 보기'}`}
        onClick={toggleOpen}
        onKeyDown={handlePrimaryKeyDown}
      >
        <div className="issue-card-topline">
          <span className={`signal-dot sensitivity-${finding.sensitivity.level}`} aria-hidden="true" />
          <span className={`status-pill sensitivity-label-${finding.sensitivity.level}`}>
            {SENSITIVITY_LEVEL_LABELS[finding.sensitivity.level]} · {finding.sensitivity.score.toFixed(1)}
          </span>
          <span>{finding.category}</span>
          {reportDate
            ? <time dateTime={reportDate}>{reportDate} 집계</time>
            : issueInfo && <time dateTime={issueInfo.lastSeenAt}>{formatShortDate(issueInfo.lastSeenAt)}</time>}
        </div>
        <h4>{title}</h4>
        <p className="issue-card-summary">{summary}</p>
        <div className="issue-card-footer">
          <span className="issue-source-count">
            {reportDate && '현재 '}
            {issue.isError && issueId !== null
              ? '관련 기사 상세 불러오기 실패'
              : issue.isLoading && issueId !== null
              ? '관련 기사 확인 중'
              : issueInfo
                ? `관련 ${issueInfo.articleCount}건 · 매체 ${issueInfo.publisherCount}곳`
                : issueId === null
                  ? '관련 1건 · 매체 1곳'
                  : '관련 기사 정보는 자세히에서 확인'}
          </span>
          <span className="issue-detail-toggle" aria-hidden="true">
            {open ? '접기' : '자세히'} <span aria-hidden="true">{open ? '▴' : '▾'}</span>
          </span>
        </div>
      </div>

      {open && (
        <div className="issue-card-details" id={panelId}>
          <section className="issue-perspective-hook">
            <span>{AUDIENCE_LABELS[audience]} 관점 · 왜 봐야 하나</span>
            <p>{perspective?.hook || '이 관점에 대한 별도 강조 없이 중립 요약을 유지합니다.'}</p>
            {perspective && perspective.evidenceSentenceIds.length > 0 && (
              <button
                type="button"
                className="text-button"
                onClick={() => onEvidenceSelect(finding.articleId, [perspective.evidenceSentenceIds[0]])}
              >
                관점 근거 확인
              </button>
            )}
          </section>

          <SensitivityAxes
            sensitivity={finding.sensitivity}
            onEvidenceSelect={(evidence) => onEvidenceSelect(finding.articleId, evidence)}
          />

          <KeyPointList
            points={keyPoints}
            articleTitle={finding.articleTitle}
            onEvidenceSelect={(sentenceId) => onEvidenceSelect(finding.articleId, [sentenceId])}
          />

          {issue.data && (
            <CrossSourcePanel issue={issue.data} onEvidenceSelect={onEvidenceSelect} />
          )}

          <section className="issue-related-articles">
            <div className="issue-detail-heading">
              <h5>관련 기사</h5>
              <span>
                {issue.data && relatedArticles.length > RELATED_ARTICLE_BATCH_SIZE
                  ? `${visibleRelatedArticles.length} / ${relatedArticles.length}건`
                  : `${relatedArticles.length || issueInfo?.articleCount || 1}건`}
              </span>
            </div>
            {issue.isLoading && <p className="issue-detail-state">관련 기사를 불러오는 중입니다.</p>}
            {issue.isError && <p className="issue-detail-state error">이슈 묶음을 불러오지 못했습니다. 대표 기사로 이동할 수 있습니다.</p>}
            {issue.data ? (
              <>
                {relatedArticles.length >= 50 && (
                  <p className="issue-cluster-warning">
                    기사 묶음이 매우 큽니다. 서로 다른 사건이 섞였을 수 있어 일부만 먼저 보여드립니다.
                  </p>
                )}
                <ul>
                  {visibleRelatedArticles.map((article) => (
                    <li key={article.id}>
                      <div><strong>{article.title}</strong><span>{article.publisher || '매체 미상'} · {formatShortDate(article.publishedAt)}</span></div>
                      <div className="issue-article-actions">
                        <button type="button" className="text-button" onClick={() => onEvidenceSelect(article.id, [])}>본문 보기</button>
                        <a href={article.canonicalUrl} target="_blank" rel="noreferrer" aria-label={`${article.title} 원문 열기`}>원문 ↗</a>
                      </div>
                    </li>
                  ))}
                </ul>
                {visibleRelatedArticles.length < relatedArticles.length && (
                  <button
                    type="button"
                    className="secondary-button issue-related-more"
                    onClick={() => setVisibleRelatedCount((current) => current + RELATED_ARTICLE_BATCH_SIZE)}
                  >
                    다음 {Math.min(RELATED_ARTICLE_BATCH_SIZE, relatedArticles.length - visibleRelatedArticles.length)}건 더 보기
                  </button>
                )}
              </>
            ) : !issue.isLoading && (
              <div className="issue-legacy-actions">
                <button type="button" className="text-button" onClick={() => onEvidenceSelect(finding.articleId, [])}>대표 기사 본문 보기</button>
                <a href={finding.canonicalUrl} target="_blank" rel="noreferrer">원문 열기 ↗</a>
              </div>
            )}
          </section>

          <div className="issue-reference-meta">
            <span>{CHANGE_TYPE_LABELS[finding.changeType]}</span>
            {issueInfo && <span>독립 원문 {issueInfo.independentContentCount}건</span>}
            {finding.intent && <span>발표 맥락 · {finding.intent}</span>}
            {finding.investigation && (
              <>
                <span>
                  추가 조사 · {INVESTIGATION_STATUS_LABELS[finding.investigation.status]}
                  {' · '}{finding.investigation.stepCount}단계
                  {' · '}기사 +{finding.investigation.addedArticleCount}
                  {' · '}근거 +{finding.investigation.addedEvidenceCount}
                </span>
                {investigationReason && <span>조사 사유 · {investigationReason}</span>}
              </>
            )}
          </div>
        </div>
      )}
    </article>
  )
}

function CrossSourcePanel({ issue, onEvidenceSelect }: {
  issue: IssueDetail
  onEvidenceSelect: (articleId: number, sentences: number[]) => void
}) {
  const { crossSource } = issue
  const hasObservations = crossSource.consensus.length > 0
    || crossSource.soleSource.length > 0
    || crossSource.conflicts.length > 0
    || crossSource.missingStakeholders.length > 0

  if (!hasObservations) return null

  const articleById = new Map(issue.articles.map((article) => [article.id, article]))
  return (
    <section className="issue-cross-source">
      <div className="issue-detail-heading">
        <h5>교차 출처 비교</h5>
        <span>매체별 차이</span>
      </div>

      {crossSource.consensus.length > 0 && (
        <CrossSourceGroup label="합의" tone="consensus">
          <ul>{crossSource.consensus.map((text) => <li key={text}>{text}</li>)}</ul>
        </CrossSourceGroup>
      )}

      {crossSource.soleSource.length > 0 && (
        <CrossSourceGroup label="단독 보도" tone="sole">
          {crossSource.soleSource.map((observation) => (
            <CrossSourceObservation
              key={`${observation.articleId}-${observation.text}`}
              text={observation.text}
              articles={[articleById.get(observation.articleId)].filter(isIssueArticle)}
              onEvidenceSelect={onEvidenceSelect}
            />
          ))}
        </CrossSourceGroup>
      )}

      {crossSource.conflicts.length > 0 && (
        <CrossSourceGroup label="충돌" tone="conflict">
          {crossSource.conflicts.map((observation) => (
            <CrossSourceObservation
              key={`${observation.articleIds.join('-')}-${observation.text}`}
              text={observation.text}
              articles={observation.articleIds.map((articleId) => articleById.get(articleId)).filter(isIssueArticle)}
              onEvidenceSelect={onEvidenceSelect}
            />
          ))}
        </CrossSourceGroup>
      )}

      {crossSource.missingStakeholders.length > 0 && (
        <CrossSourceGroup label="확인 필요" tone="missing">
          <div className="issue-missing-stakeholders">
            {crossSource.missingStakeholders.map((stakeholder) => <span key={stakeholder}>{stakeholder}</span>)}
          </div>
        </CrossSourceGroup>
      )}
    </section>
  )
}

function CrossSourceGroup({ label, tone, children }: {
  label: string
  tone: 'consensus' | 'sole' | 'conflict' | 'missing'
  children: ReactNode
}) {
  return (
    <div className={`issue-cross-source-group ${tone}`}>
      <strong>{label}</strong>
      <div>{children}</div>
    </div>
  )
}

function CrossSourceObservation({ text, articles, onEvidenceSelect }: {
  text: string
  articles: IssueArticle[]
  onEvidenceSelect: (articleId: number, sentences: number[]) => void
}) {
  return (
    <article className="issue-cross-source-observation">
      <p>{text}</p>
      <div className="issue-cross-source-articles">
        {articles.map((article) => (
          <div key={article.id}>
            <a href={article.canonicalUrl} target="_blank" rel="noreferrer">
              {article.publisher || '매체 미상'} · {article.title} ↗
            </a>
            {article.stanceSource === 'LLM' && (
              <button type="button" className="text-button" onClick={() => onEvidenceSelect(article.id, [])}>
                분석 본문 보기
              </button>
            )}
          </div>
        ))}
      </div>
    </article>
  )
}

function isIssueArticle(article: IssueArticle | undefined): article is IssueArticle {
  return article !== undefined
}

function ReportDisclaimer({ report, audience }: { report: ReportDetail; audience: Audience }) {
  return (
    <footer className="report-disclaimer">
      {audience === 'MARKET_INVESTOR' && (
        <p className="investment-disclaimer">시장·투자 관점은 정보 제공용이며 투자 자문이 아닙니다.</p>
      )}
      <details>
        <summary>리포트 생성 및 검증 안내</summary>
        <ul>
          <li>요약은 원문 문장에서 생성했고, 근거가 없는 문장은 보고서 대상에서 제외했습니다.</li>
          <li>검증을 거쳤지만 오류가 있을 수 있으니 중요한 판단 전에 원문을 확인해 주세요.</li>
          {audience !== 'MARKET_INVESTOR' && <li>시장·투자 관점은 정보 제공용이며 투자 자문이 아닙니다.</li>}
          <li>{generationMeta(report)}</li>
        </ul>
      </details>
    </footer>
  )
}

const PERSPECTIVE_RANK: Record<AudienceRelevance, number> = {
  high: 3,
  medium: 2,
  low: 1,
  none: 0,
}

const SENSITIVITY_RANK: Record<SensitivityLevel, number> = {
  high: 3,
  medium: 2,
  low: 1,
}

function sortFindingsForAudience(findings: ReportFinding[], audience: Audience) {
  return findings
    .map((finding, index) => ({ finding, index }))
    .sort((left, right) => {
      const rankDifference = perspectiveRank(right.finding, audience) - perspectiveRank(left.finding, audience)
      return rankDifference || left.index - right.index
    })
    .map(({ finding }) => finding)
}

function selectFindingsForAudience(findings: ReportFinding[], audience: Audience) {
  const seenIssueIds = new Set<number>()
  return sortFindingsForAudience(findings, audience).filter((finding) => {
    if (finding.issueId === null || finding.issueId === undefined) return true
    if (seenIssueIds.has(finding.issueId)) return false
    seenIssueIds.add(finding.issueId)
    return true
  })
}

function summarizeFindings(findings: ReportFinding[]) {
  return {
    newCount: findings.filter((finding) => finding.changeType === 'NEW').length,
    updatedCount: findings.filter((finding) => finding.changeType === 'UPDATED').length,
    highSensitivityCount: findings.filter((finding) => finding.sensitivity.level === 'high').length,
  }
}

function perspectiveRank(finding: ReportFinding, audience: Audience) {
  return PERSPECTIVE_RANK[perspectiveFor(finding, audience)?.relevance ?? 'none']
}

function perspectiveFor(finding: ReportFinding, audience: Audience) {
  return (finding.perspectiveTags ?? []).find((tag) => tag.audience === audience)
}

function countEvidenceSentences(findings: ReportFinding[]) {
  return new Set(findings.flatMap((finding) => finding.keyPoints.flatMap((point) =>
    point.evidence.map((sentenceId) => `${finding.articleId}:${sentenceId}`),
  ))).size
}

function generationKind(modelName: string) {
  return modelName.toLowerCase().includes('stub') ? '자동 생성' : 'AI 생성'
}

function generationMeta(report: ReportDetail) {
  const model = report.modelName.toLowerCase().includes('stub') ? '규칙 기반 대체 생성' : report.modelName
  const provider = report.llmProvider ? `${report.llmProvider} · ` : ''
  const prompt = report.promptVersion || '프롬프트 버전 기록 없음'
  return `생성 모델 · ${provider}${model} / ${prompt} / ${formatFullDate(report.generatedAt)}`
}

function limitText(value: string, limit: number) {
  const normalized = value.trim()
  return normalized.length <= limit ? normalized : `${normalized.slice(0, limit - 1).trimEnd()}…`
}

const RELATED_ARTICLE_BATCH_SIZE = 8

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
