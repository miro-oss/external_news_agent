import type { ReportKeywordStats as KeywordStats } from '../../api/types'
import './report-keyword-stats.css'

const unavailableMessages: Record<NonNullable<KeywordStats['unavailableReason']>, string> = {
  NO_SNAPSHOT: '이 보고서에는 생성 당시의 키워드 집계 자료가 없습니다.',
  INCOMPLETE_SOURCE: '일부 원본 보고서의 집계 자료가 없어 전체 비중을 표시할 수 없습니다.',
  MISSING_COLLECTION_CONTEXT: '당시 수집 키워드를 확인할 수 없어 비중을 표시할 수 없습니다.',
  INVALID_SNAPSHOT: '저장된 집계 자료를 확인할 수 없어 비중을 표시할 수 없습니다.',
  COMPANY_SCOPE_UNVERIFIED: '전체 후보에 회사 관심 기준이 적용되었는지 확인할 수 없어 집계를 표시하지 않습니다.',
  PERSONALIZATION_UNVERIFIED: '전체 후보에 개인 기준이 적용되었는지 확인할 수 없어 집계를 표시하지 않습니다.',
}

const number = (value: number) => value.toLocaleString('ko-KR', { maximumFractionDigits: 1 })

export function ReportKeywordStats({ stats }: { stats?: KeywordStats | null }) {
  if (!stats) return null
  const available = stats.status === 'AVAILABLE' && stats.denominatorIssueCount !== null
  const denominator = stats.denominatorIssueCount
  return <section className="card report-keyword-stats" aria-label="키워드로 보는 이슈">
    <header className="report-keyword-heading">
      <h3>키워드로 보는 이슈</h3>
      {available && <p className="report-keyword-basis">전체 유효 이슈 <strong>{number(denominator!)}건</strong> · 상위 선정 전</p>}
      <p className="report-keyword-period">{stats.periodLabel}</p>
    </header>
    {!available ? <p className="report-keyword-empty"><strong>집계 자료 없음</strong>
      {(stats.unavailableReason && unavailableMessages[stats.unavailableReason]) || '키워드 집계 자료를 확인할 수 없습니다.'}</p>
      : <>
        {denominator === 0 && <p className="report-keyword-empty">집계 대상인 유효 이슈가 0건이어서 비중을 계산하지 않았습니다.</p>}
        {stats.items.length === 0 ? <p className="report-keyword-empty">집계할 등록 키워드가 없습니다.</p>
          : <ol className="report-keyword-list">{stats.items.map(item => <li className="report-keyword-item" key={item.keyword}>
            <div className="report-keyword-row"><h4>{item.keyword}</h4>
              <div className="report-keyword-numbers"><span>{number(item.issueCount)}건 / 전체 {number(denominator!)}건</span>
                <strong>{item.sharePercent === null ? '비중 없음' : `${number(item.sharePercent)}%`}</strong></div>
            </div>
            <div className="report-keyword-track" aria-hidden="true"><span style={{ width: `${Math.max(0, Math.min(100, item.sharePercent ?? 0))}%` }} /></div>
            {item.relatedKeywords.length > 0 && <div className="report-keyword-related"><span>함께 등장</span>
              <ul>{item.relatedKeywords.map(related => <li key={related.keyword}>{related.keyword}<span> · {number(related.issueCount)}건</span></li>)}</ul>
            </div>}
          </li>)}</ol>}
        {stats.omittedKeywordCount > 0 && <p className="report-keyword-more">등록 키워드 {number(stats.items.length + stats.omittedKeywordCount)}개 중 상위 {number(stats.items.length)}개를 표시했습니다.</p>}
        <p className="report-keyword-note">한 이슈가 여러 키워드에 포함되어 비중 합계는 100%를 넘을 수 있습니다.<br />
          기사 제목·본문에서 확인한 등록 키워드 기준이며, 함께 등장은 같은 이슈에 연결된 키워드입니다.</p>
      </>}
  </section>
}
