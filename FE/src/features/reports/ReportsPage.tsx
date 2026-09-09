import { useCallback, useEffect, useId, useMemo, useRef, useState, type KeyboardEvent, type ReactNode } from 'react'
import {
  useLatestReport,
  useAudienceSetting,
  useIssue,
  useReport,
  useReports,
  useDeleteReport,
} from '../../api/queries'
import {
  AUDIENCES,
  AUDIENCE_LABELS,
  SENSITIVITY_LEVEL_LABELS,
  type Audience,
  type AudienceRelevance,
  type IssueArticle,
  type IssueDetail,
  type ReportDetail,
  type ReportFinding,
  type ReportSummary,
  type SensitivityLevel,
} from '../../api/types'
import { KeyPointList } from '../../components/KeyPointList'
import { Segmented, type SegmentedOption } from '../../components/Segmented'
import { formatFullDate, formatShortDate } from '../../lib/datetime'
import { normalizeKeyPoints } from '../../lib/keyPoints'
import { prefersReducedMotion } from '../../lib/motion'
import { ArticleDetailModal } from '../articles/ArticleDetailModal'
import { ReportSharePanel } from '../notifications/ReportSharePanel'
import { ReportReadingContent } from './ReportReadingContent'
import { IssueTonePanel } from './IssueTonePanel'
import { collectionHighlightTerms, collectionKeywords } from './reportReading'
import { ReportKeywordText } from './ReportKeywordText'
import { categoryTone, dailyReportTopics, defaultSensitivity } from './reportDisplay'
import { reportDisplayTitle } from './reportTitle'
import { ReportDetailSkeleton, ReportWorkspaceSkeleton, RelatedArticlesSkeleton } from './ReportSkeletons'

type ReportScopeTab = 'DAILY' | 'RUN'

const REPORT_SCOPE_OPTIONS: ReadonlyArray<SegmentedOption<ReportScopeTab>> = [
  { value: 'RUN', label: '실행별' },
  { value: 'DAILY', label: '일일 통합' },
]

// 이름만으로는 두 보고서의 차이가 서지 않는다. 고른 범위가 무엇을 담는지 한 줄로 붙여 둔다.
const REPORT_SCOPE_HINTS: Record<ReportScopeTab, string> = {
  DAILY: '하루 동안 모인 같은 이슈를 한 장으로 묶었습니다.',
  RUN: '수집을 실행할 때마다 만들어진 보고서입니다.',
}

function reportIdFromHash() {
  const value = new URLSearchParams(window.location.hash.split('?')[1] ?? '').get('reportId')
  const id = Number(value)
  return value && Number.isSafeInteger(id) && id > 0 ? id : null
}

export function ReportsPage() {
  const [reportScope, setReportScope] = useState<ReportScopeTab>('RUN')
  const [selectedId, setSelectedId] = useState<number | null>(reportIdFromHash)
  const [audienceOverride, setAudienceOverride] = useState<Audience | null>(null)
  const [evidenceSelection, setEvidenceSelection] = useState<{
    articleId: number | null
    runId: number | null
    sentences: number[]
  }>({ articleId: null, runId: null, sentences: [] })
  const selectedReport = useReport(selectedId)
  const scopeFilter = selectedReport.data?.reportScope ?? reportScope
  const reports = useReports(scopeFilter)
  const audienceSetting = useAudienceSetting()
  const latest = useLatestReport(scopeFilter)
  const activeId = selectedId ?? latest.data?.id ?? null
  const activeReport = selectedId === null ? latest : selectedReport
  const activeReportData = activeReport.data
  const removeReport = useDeleteReport()
  useEffect(() => {
    const sync = () => {
      setSelectedId(reportIdFromHash())
      setEvidenceSelection({ articleId: null, runId: null, sentences: [] })
    }
    window.addEventListener('hashchange', sync)
    return () => window.removeEventListener('hashchange', sync)
  }, [])
  function selectReport(id: number) {
    if (id === activeId) return
    setSelectedId(id)
    setEvidenceSelection({ articleId: null, runId: null, sentences: [] })
    window.history.replaceState(null, '', `#/reports?reportId=${id}`)
  }
  async function deleteReport(id: number) {
    const nextId = reports.data?.content.find(report => report.id !== id)?.id ?? null
    const currentHash = window.location.hash
    await removeReport.mutateAsync(id)
    if (window.location.hash !== currentHash) return
    setReportScope(scopeFilter)
    setSelectedId(nextId)
    setEvidenceSelection({ articleId: null, runId: null, sentences: [] })
    window.history.replaceState(null, '', nextId === null ? '#/reports' : `#/reports?reportId=${nextId}`)
  }
  const isInitialLoading = latest.isPending || reports.isPending
  const initialError = latest.isError ? latest.error : reports.isError ? reports.error : null
  const hasWorkspace = !!reports.data?.content.length && activeId !== null
  const activeAudience = audienceOverride ?? audienceSetting.data?.audience ?? 'CHIP_MAKER'
  const closeArticle = useCallback(() => {
    setEvidenceSelection({ articleId: null, runId: null, sentences: [] })
  }, [])

  return (
    <main className="reports-page">
      <header className="page-header report-header">
        <div>
          <h1>뉴스 리포트</h1>
          <p className="muted">주요 소식과 관련 기사를 한곳에서 확인합니다.</p>
        </div>
      </header>

      <div className="report-scope-bar">
        <Segmented
          className="report-scope-tabs"
          label="보고서 범위"
          value={scopeFilter}
          options={REPORT_SCOPE_OPTIONS}
          disabled={removeReport.isPending}
          onSelect={(scope) => { setReportScope(scope); setSelectedId(null); window.history.replaceState(null, '', '#/reports') }}
        />
        <p className="report-scope-hint">{REPORT_SCOPE_HINTS[scopeFilter]}</p>
      </div>

      {isInitialLoading && !hasWorkspace && !initialError && <ReportWorkspaceSkeleton daily={scopeFilter === 'DAILY'} />}
      {initialError && <div className="state-panel error" role="alert">보고서를 불러오지 못했습니다. {initialError.message}</div>}
      {!isInitialLoading && !initialError && latest.data === null && !reports.data?.content.length && (
        <div className="state-panel report-empty">
          <span className="empty-mark" aria-hidden="true">⌁</span>
          <strong>표시할 보고서가 없습니다.</strong>
          <span>{scopeFilter === 'DAILY'
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
            </div>
            <div className="report-list-scroll">
            {reports.data.content.map((report) => (
              <ReportListItem
                key={report.id}
                report={report}
                active={report.id === activeId}
                disabled={removeReport.isPending}
                onSelect={() => selectReport(report.id)}
              />
            ))}
            </div>
          </aside>

          <section className="report-detail-shell" aria-live="polite">
            {activeReport.isPending && <ReportDetailSkeleton daily={scopeFilter === 'DAILY'} />}
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
                onDelete={() => deleteReport(activeReportData.id)}
                onEvidenceSelect={(articleId, runId, sentences) => {
                  setEvidenceSelection({
                    articleId,
                    runId,
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
        collectionContexts={activeReportData?.collectionContexts ?? []}
        onClose={closeArticle}
      />
    </main>
  )
}

function ReportListItem({ report, active, onSelect, disabled }: {
  report: ReportSummary
  active: boolean
  onSelect: () => void
  disabled: boolean
}) {
  return (
    <button
      type="button"
      className={active ? 'report-list-item active' : 'report-list-item'}
      aria-pressed={active}
      disabled={disabled}
      onClick={onSelect}
    >
      <strong title={report.title}>{reportDisplayTitle(report)}</strong>
      <time className="report-list-date" dateTime={report.reportScope === 'DAILY' ? report.reportDate ?? undefined : report.generatedAt}>
        {report.reportScope === 'DAILY' ? report.reportDate ?? '집계일 미상' : formatShortDate(report.generatedAt)}
      </time>
    </button>
  )
}

function ReportView({ report, audience, defaultAudience, onAudienceSelect, onEvidenceSelect, onDelete }: {
  report: ReportDetail
  audience: Audience
  defaultAudience?: Audience
  onAudienceSelect: (audience: Audience) => void
  onEvidenceSelect: (articleId: number, runId: number, sentences: number[]) => void
  onDelete: () => Promise<void>
}) {
  const [sensitivityOverride, setSensitivityOverride] = useState<ReportFindingFilters['sensitivityLevel'] | null>(null)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [deleteError, setDeleteError] = useState('')
  const contexts = report.collectionContexts ?? []
  const highlightTerms = collectionHighlightTerms(contexts)
  const topicNames = [...new Set(contexts.flatMap(context => context.topics.map(topic => topic.topicName)).filter(Boolean))]
  const keywords = collectionKeywords(contexts, null)
  const dailyTopics = dailyReportTopics(report)
  const findings = useMemo(
    () => report.reportScope === 'DAILY'
      ? sortFindingsForAudience(report.findings ?? [], audience)
      : selectFindingsForAudience(report.findings ?? [], audience),
    [audience, report.findings, report.reportScope],
  )
  const sensitivityLevel = sensitivityOverride ?? defaultSensitivity(findings)
  const filters: ReportFindingFilters = { sensitivityLevel }
  const filteredFindings = useMemo(() => {
    if (sensitivityLevel) {
      return findings.filter((finding) => finding.sensitivity.level === sensitivityLevel)
    }
    return [...findings].sort((left, right) => {
      const levelDifference = SENSITIVITY_RANK[right.sensitivity.level]
        - SENSITIVITY_RANK[left.sensitivity.level]
      const perspectiveDifference = perspectiveRank(right, audience) - perspectiveRank(left, audience)
      return levelDifference || perspectiveDifference || right.sensitivity.score - left.sensitivity.score
    })
  }, [audience, sensitivityLevel, findings])
  async function submitDelete() {
    setDeleting(true)
    setDeleteError('')
    try { await onDelete() }
    catch (error) {
      setDeleteError(error instanceof Error ? error.message : '보고서를 삭제하지 못했습니다. 다시 시도해 주세요.')
      setDeleting(false)
    }
  }
  return (
    <article className="report-document report-document-enter" data-report-scope={report.reportScope}>
      <header className="report-document-header">
        <div className="report-title-row">
          <h2 title={report.title}><ReportKeywordText text={reportDisplayTitle(report)} terms={highlightTerms} /></h2>
          <button type="button" className="text-button report-delete-button" aria-label="이 보고서 삭제" aria-expanded={confirmDelete}
            onClick={() => { setConfirmDelete(value => !value); setDeleteError('') }} disabled={deleting}>
            <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M3 6h18M9 6V4h6v2M5 6l1 14h12l1-14M10 10v6M14 10v6" /></svg>
            삭제
          </button>
        </div>
        {confirmDelete && <div className="report-delete-confirm" aria-label="보고서 삭제 확인">
          <div><strong>이 보고서를 삭제할까요?</strong><p>보고서 목록에서 사라집니다. 원문 기사와 발송 이력은 유지됩니다.</p></div>
          <div className="report-delete-actions">
            <button type="button" className="secondary-button" disabled={deleting} onClick={() => { setConfirmDelete(false); setDeleteError('') }}>취소</button>
            <button type="button" disabled={deleting} onClick={() => { void submitDelete() }}>{deleting ? '삭제 중…' : '삭제하기'}</button>
          </div>
          {deleteError && <p className="field-error" role="alert">{deleteError}</p>}
        </div>}
        <time dateTime={report.generatedAt}>{formatFullDate(report.generatedAt)}</time>
        {report.reportScope !== 'DAILY' && <div className="report-collection-context">
          {topicNames.length > 0 ? <>
            <p><span>수집 주제</span><strong>{topicNames.join(' · ')}</strong></p>
            {keywords.length > 0 && <p><span>키워드</span><span className="report-context-keywords">{keywords.join(' · ')}</span></p>}
          </> : <p className="report-context-unavailable">수집 당시 주제·키워드 기록이 없는 보고서입니다.</p>}
        </div>}
        {report.reportScope === 'DAILY' ? <div className="report-daily-summary">
          <span className="report-daily-date">{report.reportDate ?? '집계일 미상'}</span>
          {dailyTopics.names.length > 0 && <p className="report-daily-topics"><span>{dailyTopics.label}</span><strong>{dailyTopics.names.join(' · ')}</strong></p>}
          {report.sourceReportCount != null && report.sourceReportCount > 0
            && <p className="report-daily-count">실행별 보고서 <strong>{report.sourceReportCount}개</strong>를 통합했습니다.</p>}
          {keywords.length > 0 && <p className="report-daily-keywords"><span>키워드</span>{keywords.join(' · ')}</p>}
        </div> : report.articleStats && <div className="report-stat-row" aria-label="수집 기사 통계">
          <ReportStat value={report.articleStats.totalCount} label="전체 기사" />
          <ReportStat value={report.articleStats.newCount} label="신규 기사" />
          <ReportStat value={report.articleStats.existingCount} label="기존 기사" />
        </div>}
      </header>

      <ReportReadingContent report={report} onEvidenceSelect={onEvidenceSelect} />

      <section className="report-findings">
        <div className="section-heading report-section-heading">
          <div><h3>주요 이슈</h3><p className="report-issue-description">같은 소식을 다룬 기사들을 모아 정리했어요.</p></div>
          <span>
            {filteredFindings.length === findings.length
              ? `${findings.length}건`
              : `${filteredFindings.length} / ${findings.length}건`}
            {' · '}{filters.sensitivityLevel ? '' : '높은 민감도순 · '}{AUDIENCE_LABELS[audience]} 관점순
          </span>
        </div>
        <div className="report-finding-controls">
          <ReportPerspectiveSelector
            audience={audience}
            defaultAudience={defaultAudience}
            onSelect={onAudienceSelect}
          />
          <ReportFindingFilterBar
            filters={filters}
            onChange={(_, value) => setSensitivityOverride(value)}
          />
        </div>
        {filteredFindings.length > 0 ? (
          <PaginatedFindings key={`${audience}:${sensitivityLevel}`} findings={filteredFindings}>
            {(finding) => (
              <IssueCard
                finding={finding}
                key={finding.id}
                audience={audience}
                daily={report.reportScope === 'DAILY'}
                reportDate={report.reportScope === 'DAILY' ? report.reportDate : null}
                highlightTerms={collectionHighlightTerms(contexts, finding.runId)}
                onEvidenceSelect={onEvidenceSelect}
              />
            )}
          </PaginatedFindings>
        ) : findings.length > 0
          ? <p className="empty-block">조건에 맞는 주요 이슈가 없습니다. 필터를 바꿔보세요.</p>
          : <p className="empty-block">이 보고서에 포함된 주요 이슈가 없습니다.</p>}
      </section>

      <div className="report-sharing-section">
        <ReportSharePanel reportId={report.id} />
      </div>
      <ReportDisclaimer report={report} audience={audience} />
    </article>
  )
}

const FINDINGS_PER_PAGE = 3

function PaginatedFindings({ findings, children }: {
  findings: ReportFinding[]
  children: (finding: ReportFinding) => ReactNode
}) {
  const [page, setPage] = useState(0)
  const listRef = useRef<HTMLDivElement>(null)
  const pageCount = Math.ceil(findings.length / FINDINGS_PER_PAGE)
  // A background update can remove items from the current last page.
  const currentPage = Math.min(page, Math.max(0, pageCount - 1))
  const visibleFindings = findings.slice(currentPage * FINDINGS_PER_PAGE, (currentPage + 1) * FINDINGS_PER_PAGE)

  function changePage(nextPage: number) {
    setPage(nextPage)
    requestAnimationFrame(() => {
      const firstIssue = listRef.current?.querySelector<HTMLElement>('.issue-card-primary')
      firstIssue?.focus({ preventScroll: true })
      firstIssue?.scrollIntoView({ behavior: prefersReducedMotion() ? 'auto' : 'smooth', block: 'start' })
    })
  }

  return <>
    <div className="finding-list" ref={listRef}>{visibleFindings.map(children)}</div>
    {pageCount > 1 && <nav className="pagination report-issue-pagination" aria-label="주요 이슈 페이지 이동">
      <button type="button" className="secondary-button" aria-label="이전 이슈 페이지"
        disabled={currentPage === 0} onClick={() => changePage(currentPage - 1)}>이전</button>
      <span aria-live="polite" aria-atomic="true" aria-label={`${currentPage + 1} / ${pageCount} 페이지`}>
        <strong>{currentPage + 1}</strong> / {pageCount}
      </span>
      <button type="button" className="secondary-button" aria-label="다음 이슈 페이지"
        disabled={currentPage === pageCount - 1} onClick={() => changePage(currentPage + 1)}>다음</button>
    </nav>}
  </>
}

type ReportFindingFilters = {
  sensitivityLevel: '' | SensitivityLevel
}

const SENSITIVITY_FILTERS: ReadonlyArray<SegmentedOption<ReportFindingFilters['sensitivityLevel']>> = [
  { value: '', label: '전체' },
  { value: 'high', label: SENSITIVITY_LEVEL_LABELS.high },
  { value: 'medium', label: SENSITIVITY_LEVEL_LABELS.medium },
  { value: 'low', label: SENSITIVITY_LEVEL_LABELS.low },
]

function ReportFindingFilterBar({
  filters,
  onChange,
}: {
  filters: ReportFindingFilters
  onChange: <K extends keyof ReportFindingFilters>(key: K, value: ReportFindingFilters[K]) => void
}) {
  return (
    <div className="report-finding-filter">
      <span className="filter-label" id="finding-sensitivity-label">민감도</span>
      <Segmented
        labelledBy="finding-sensitivity-label"
        value={filters.sensitivityLevel}
        options={SENSITIVITY_FILTERS}
        onSelect={(next) => onChange('sensitivityLevel', next)}
      />
    </div>
  )
}

function ReportPerspectiveSelector({ audience, defaultAudience, onSelect }: {
  audience: Audience
  defaultAudience?: Audience
  onSelect: (audience: Audience) => void
}) {
  const labelId = useId()
  return (
    <div className="report-perspective">
      <span className="filter-label" id={labelId}>누구의 관점으로 볼까요?</span>
      <Segmented
        labelledBy={labelId}
        className="report-perspective-options"
        value={audience}
        options={AUDIENCES.map((item) => ({
          value: item,
          label: <>{AUDIENCE_LABELS[item]}{defaultAudience === item && <small>기본</small>}</>,
        }))}
        onSelect={onSelect}
      />
    </div>
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

function IssueCard({ finding, audience, daily, reportDate, onEvidenceSelect, highlightTerms }: {
  finding: ReportFinding
  audience: Audience
  daily: boolean
  reportDate: string | null
  onEvidenceSelect: (articleId: number, runId: number, sentences: number[]) => void
  highlightTerms: string[]
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
  const title = daily ? finding.articleTitle : issueInfo?.title || finding.articleTitle
  const summary = limitText(daily ? finding.summary : issueInfo?.summary || finding.summary, 120)
  const relatedArticles = issue.data?.articles ?? []
  const relatedArticleCount = issue.data ? relatedArticles.length : issueInfo?.articleCount ?? 1
  const visibleRelatedArticles = relatedArticles.slice(0, visibleRelatedCount)

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
          <span className={`issue-category-tag category-${categoryTone(finding.category)}`}>{finding.category}</span>
          {daily
            ? <time dateTime={reportDate ?? undefined}>{reportDate ?? '집계일 미상'} 집계</time>
            : issueInfo && <time dateTime={issueInfo.lastSeenAt}>{formatShortDate(issueInfo.lastSeenAt)}</time>}
        </div>
        <h4><ReportKeywordText text={title} terms={highlightTerms} /></h4>
        <p className="issue-card-summary"><ReportKeywordText text={summary} terms={highlightTerms} /></p>
        <div className="issue-card-footer">
          <span className="issue-source-count">
            {daily && '현재 '}
            {issue.isError && issueId !== null
              ? '관련 기사 상세 불러오기 실패'
              : issue.isLoading && issueId !== null
              ? '관련 기사 확인 중'
              : issueInfo
                ? `관련 ${relatedArticleCount}건 · 매체 ${issueInfo.publisherCount}곳`
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
            <p><ReportKeywordText text={perspective?.hook || '이 관점에 대한 별도 강조 없이 중립 요약을 유지합니다.'} terms={highlightTerms} /></p>
            {perspective && perspective.evidenceSentenceIds.length > 0 && (
              <button
                type="button"
                className="text-button"
                onClick={() => onEvidenceSelect(finding.articleId, finding.runId, [perspective.evidenceSentenceIds[0]])}
              >
                관점 근거 확인
              </button>
            )}
          </section>

          <KeyPointList
            points={keyPoints}
            articleTitle={finding.articleTitle}
            onEvidenceSelect={(sentenceId) => onEvidenceSelect(finding.articleId, finding.runId, [sentenceId])}
          />

          {issue.data && (
            <CrossSourcePanel issue={issue.data} onEvidenceSelect={(articleId, sentences) =>
              onEvidenceSelect(articleId, finding.runId, sentences)} />
          )}

          {issue.data?.toneDistribution && (
            <IssueTonePanel distribution={issue.data.toneDistribution} articleCount={relatedArticleCount} />
          )}

          <section className="issue-related-articles">
            <div className="issue-detail-heading">
              <h5>관련 기사</h5>
              <span>
                {issue.data && relatedArticles.length > RELATED_ARTICLE_BATCH_SIZE
                  ? `${visibleRelatedArticles.length} / ${relatedArticles.length}건`
                  : `${relatedArticleCount}건`}
              </span>
            </div>
            {issue.isLoading && <RelatedArticlesSkeleton />}
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
                      <div><strong><ReportKeywordText text={article.title} terms={highlightTerms} /></strong><span>{article.publisher || '매체 미상'} · {formatShortDate(article.publishedAt)}</span></div>
                      <div className="issue-article-actions">
                        <button type="button" className="text-button" onClick={() => onEvidenceSelect(article.id, finding.runId, [])}>본문 보기</button>
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
                <button type="button" className="text-button" onClick={() => onEvidenceSelect(finding.articleId, finding.runId, [])}>대표 기사 본문 보기</button>
                <a href={finding.canonicalUrl} target="_blank" rel="noreferrer">원문 열기 ↗</a>
              </div>
            )}
          </section>

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

function perspectiveRank(finding: ReportFinding, audience: Audience) {
  return PERSPECTIVE_RANK[perspectiveFor(finding, audience)?.relevance ?? 'none']
}

function perspectiveFor(finding: ReportFinding, audience: Audience) {
  return (finding.perspectiveTags ?? []).find((tag) => tag.audience === audience)
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
