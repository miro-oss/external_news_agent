import { useCallback, useId, useMemo, useState } from 'react'
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
  RISK_LEVEL_LABELS,
  type Audience,
  type AudienceRelevance,
  type ReportDetail,
  type ReportFinding,
  type ReportSummary,
} from '../../api/types'
import { KeyPointList } from '../../components/KeyPointList'
import { formatFullDate, formatShortDate } from '../../lib/datetime'
import { normalizeKeyPoints } from '../../lib/keyPoints'
import { ArticleDetailModal } from '../articles/ArticleDetailModal'
import { MutationStatus } from '../settings/MutationStatus'

export function ReportsPage() {
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [audienceOverride, setAudienceOverride] = useState<Audience | null>(null)
  const [evidenceSelection, setEvidenceSelection] = useState<{
    articleId: number | null
    runId: number | null
    sentences: number[]
  }>({ articleId: null, runId: null, sentences: [] })
  const reports = useReports()
  const audienceSetting = useAudienceSetting()
  const latest = useLatestReport()
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
            {activeReportData && (
              <ReportView
                report={activeReportData}
                audience={activeAudience}
                defaultAudience={audienceSetting.data?.audience}
                onAudienceSelect={setAudienceOverride}
                onEvidenceSelect={(articleId, sentences) => {
                  setEvidenceSelection({
                    articleId,
                    runId: activeReportData.runId,
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
      <span className="report-list-date">{formatShortDate(report.generatedAt)}</span>
      <strong>{report.title}</strong>
      <span className="report-list-meta">
        이슈 {report.findingCount}건
        {report.highRiskCount > 0 && <em>{RISK_LEVEL_LABELS.high} {report.highRiskCount}</em>}
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
  const stats = report.summaryStats
  const findings = useMemo(
    () => sortFindingsForAudience(report.findings ?? [], audience),
    [audience, report.findings],
  )
  const evidenceCount = useMemo(() => countEvidenceSentences(report.findings ?? []), [report.findings])
  return (
    <article className="report-document">
      <header className="report-document-header">
        <h2>{report.title}</h2>
        <time dateTime={report.generatedAt}>{formatFullDate(report.generatedAt)}</time>
        <div className="report-ai-context">
          <span>{generationKind(report.modelName)} · {AUDIENCE_LABELS[audience]} 관점 · 원문 근거 {evidenceCount}문장</span>
          <details>
            <summary aria-label="관점 적용 방식 안내">ⓘ</summary>
            <p>‘{AUDIENCE_LABELS[audience]}’ 관점으로 중요도를 다시 매긴 결과입니다. 같은 이슈도 관점에 따라 순서와 강조가 달라집니다.</p>
          </details>
        </div>
        <div className="report-stat-row" aria-label="보고서 요약 통계">
          <ReportStat value={stats.findingCount} label="전체 이슈" />
          <ReportStat value={stats.newCount} label={CHANGE_TYPE_LABELS.NEW} />
          <ReportStat value={stats.updatedCount} label={CHANGE_TYPE_LABELS.UPDATED} />
          <ReportStat value={stats.byRiskLevel.high ?? 0} label={RISK_LEVEL_LABELS.high} tone="danger" />
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
          <span>{findings.length}건 · {AUDIENCE_LABELS[audience]} 관점순</span>
        </div>
        {findings.length > 0 ? (
          <div className="finding-list">
            {findings.map((finding) => (
              <IssueCard
                finding={finding}
                key={finding.id}
                audience={audience}
                onEvidenceSelect={onEvidenceSelect}
              />
            ))}
          </div>
        ) : <p className="muted">이 보고서에 포함된 주요 이슈가 없습니다.</p>}
      </section>

      <ReportDisclaimer report={report} audience={audience} />
    </article>
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
      <div className="report-perspective-tabs" role="tablist" aria-label="독자 관점">
        {AUDIENCES.map((item) => (
          <button
            type="button"
            role="tab"
            aria-selected={audience === item}
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

function IssueCard({ finding, audience, onEvidenceSelect }: {
  finding: ReportFinding
  audience: Audience
  onEvidenceSelect: (articleId: number, sentences: number[]) => void
}) {
  const [open, setOpen] = useState(false)
  const panelId = `${useId()}-issue-detail`
  // FE와 BE가 따로 재시작되는 로컬 환경에서도 구버전 응답의 누락 값을 잘못 조회하지 않는다.
  const issueId = finding.issueId ?? null
  const issue = useIssue(issueId)
  const keyPoints = useMemo(() => normalizeKeyPoints(finding.keyPoints), [finding.keyPoints])
  const perspective = perspectiveFor(finding, audience)
  const title = issue.data?.title || finding.articleTitle
  const summary = limitText(issue.data?.summary || finding.summary, 120)
  const keywords = unique([
    ...(issue.data?.entities ?? []),
    ...(issue.data?.topicName ? [issue.data.topicName] : []),
  ]).slice(0, 4)

  return (
    <article className="issue-card">
      <div className="issue-card-primary">
        <div className="issue-card-topline">
          <span className={`signal-dot risk-${finding.riskLevel}`} aria-hidden="true" />
          <span className={`status-pill risk-label-${finding.riskLevel}`}>
            {issue.data?.sensitivityScore !== null && issue.data?.sensitivityScore !== undefined
              ? `민감 ${Math.round(issue.data.sensitivityScore)}`
              : RISK_LEVEL_LABELS[finding.riskLevel]}
          </span>
          <span>{finding.category}</span>
          {issue.data && <time dateTime={issue.data.lastSeenAt}>{formatShortDate(issue.data.lastSeenAt)}</time>}
        </div>
        <h4>{title}</h4>
        <p className="issue-card-summary">{summary}</p>
        <div className="issue-card-footer">
          <span className="issue-source-count">
            {issue.isPending && issueId !== null
              ? '관련 기사 확인 중'
              : `관련 ${issue.data?.articleCount ?? 1}건 · 매체 ${issue.data?.publisherCount ?? 1}곳`}
          </span>
          <div className="issue-keywords" aria-label="이슈 키워드">
            {keywords.map((keyword) => <span key={keyword}>#{keyword}</span>)}
            {issue.isPending && issueId !== null && <span className="issue-keywords-loading">키워드 확인 중</span>}
          </div>
          <button
            type="button"
            className="issue-detail-toggle"
            aria-expanded={open}
            aria-controls={panelId}
            onClick={() => setOpen((current) => !current)}
          >
            {open ? '접기' : '자세히'} <span aria-hidden="true">{open ? '▴' : '▾'}</span>
          </button>
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

          <KeyPointList
            points={keyPoints}
            articleTitle={title}
            onEvidenceSelect={(sentenceId) => onEvidenceSelect(finding.articleId, [sentenceId])}
          />

          <section className="issue-related-articles">
            <div className="issue-detail-heading">
              <h5>관련 기사</h5>
              <span>{issue.data?.articles.length ?? 1}건</span>
            </div>
            {issue.isPending && <p className="issue-detail-state">관련 기사를 불러오는 중입니다.</p>}
            {issue.isError && <p className="issue-detail-state error">이슈 묶음을 불러오지 못했습니다. 대표 기사로 이동할 수 있습니다.</p>}
            {issue.data ? (
              <ul>
                {issue.data.articles.map((article) => (
                  <li key={article.id}>
                    <div><strong>{article.title}</strong><span>{article.publisher || '매체 미상'} · {formatShortDate(article.publishedAt)}</span></div>
                    <div className="issue-article-actions">
                      <button type="button" className="text-button" onClick={() => onEvidenceSelect(article.id, [])}>본문 보기</button>
                      <a href={article.canonicalUrl} target="_blank" rel="noreferrer" aria-label={`${article.title} 원문 열기`}>원문 ↗</a>
                    </div>
                  </li>
                ))}
              </ul>
            ) : !issue.isPending && (
              <div className="issue-legacy-actions">
                <button type="button" className="text-button" onClick={() => onEvidenceSelect(finding.articleId, [])}>대표 기사 본문 보기</button>
                <a href={finding.canonicalUrl} target="_blank" rel="noreferrer">원문 열기 ↗</a>
              </div>
            )}
          </section>

          <div className="issue-reference-meta">
            <span>{CHANGE_TYPE_LABELS[finding.changeType]}</span>
            {issue.data && <span>독립 원문 {issue.data.independentContentCount}건</span>}
            {finding.intent && <span>발표 맥락 · {finding.intent}</span>}
          </div>
        </div>
      )}
    </article>
  )
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
          <li>시장·투자 관점은 정보 제공용이며 투자 자문이 아닙니다.</li>
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

function sortFindingsForAudience(findings: ReportFinding[], audience: Audience) {
  return findings
    .map((finding, index) => ({ finding, index }))
    .sort((left, right) => {
      const rankDifference = perspectiveRank(right.finding, audience) - perspectiveRank(left.finding, audience)
      return rankDifference || left.index - right.index
    })
    .map(({ finding }) => finding)
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

function unique(values: string[]) {
  return [...new Set(values.filter((value) => value.trim().length > 0))]
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
