import type { ReportCoverageItem } from '../../api/types'
import { safeReportEvidenceUrl } from './reportChangesDisplay'
import { coverageArticleDate, coverageAvailable, coverageLabel } from './reportCoverageDisplay'
import { ReportKeywordText } from './ReportKeywordText'
import './report-coverage.css'

export function ReportCoverageSummary({ coverage }: { coverage: ReportCoverageItem | null | undefined }) {
  return <p className="report-coverage-summary" title="보고서 수집 범위의 보도량입니다. 매체 수는 원문 사이트 기준이며 독립 검증 횟수나 중요도 점수가 아닙니다.">
    {coverageLabel(coverage)}
  </p>
}

export function ReportCoverageSources({ coverage, terms = [] }: {
  coverage: ReportCoverageItem | null | undefined
  terms?: string[]
}) {
  if (!coverageAvailable(coverage) || coverage.representativeArticles.length === 0) return null
  return <div className="report-coverage-sources">
    <span className="report-source-label">대표 보도</span>
    <ul>{coverage.representativeArticles.slice(0, 2).map(article => {
      const href = safeReportEvidenceUrl(article.canonicalUrl ?? '')
      const title = <ReportKeywordText text={article.title || '제목 미확인'} terms={terms} />
      return <li key={article.articleId}>
        {href ? <a href={href} target="_blank" rel="noreferrer">{title} ↗</a> : <span className="report-coverage-source-title">{title}</span>}
        <span className="report-coverage-source-meta">{article.publisher?.trim() || '매체 미확인'} · {coverageArticleDate(article.publishedAt)}</span>
      </li>
    })}</ul>
  </div>
}
