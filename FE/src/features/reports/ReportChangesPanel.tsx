import { useId } from 'react'
import { useReportChanges } from '../../api/reportChanges'
import type { ReportChangeClaim, ReportChangeItem, ReportChanges, ReportChangeSide, ReportKeywordChanges } from '../../api/types'
import { changeShare, groupReportChanges, keywordChangesMessage, percentagePointLabel, prioritizeReportChanges, reportDateGap, REPORT_CHANGE_LABELS, REPORT_CHANGE_TYPES, REPORT_CHANGES_MESSAGES, safeReportEvidenceUrl } from './reportChangesDisplay'
import './report-changes.css'

export function ReportChangesPanel({ reportId }: { reportId: number }) {
  const comparison = useReportChanges(reportId)
  const headingId = useId()
  return <section className="report-changes-panel" aria-labelledby={headingId}>
    <header className="report-changes-heading">
      <h3 id={headingId}>지난 보고서와 달라진 점</h3>
      <p>같은 주제의 이전 보고서와 비교해 달라진 내용과 키워드 비중을 확인합니다.</p>
    </header>
    {comparison.isPending && <div className="report-changes-state" role="status"><p>저장된 비교 결과를 불러오고 있습니다.</p></div>}
    {comparison.isError ? <div className="report-changes-state" role="alert">
      <strong>비교 결과를 불러오지 못했습니다.</strong>
      <p>{comparison.error.message}</p>
      <button type="button" className="text-button" disabled={comparison.isFetching}
        onClick={() => { void comparison.refetch() }}>{comparison.isFetching ? '불러오는 중…' : '다시 불러오기'}</button>
    </div> : comparison.data && <ReportChangesContent changes={comparison.data} refreshing={comparison.isFetching}
      onRefresh={() => { void comparison.refetch() }} />}
  </section>
}

export function ReportChangesContent({ changes, refreshing, onRefresh }: {
  changes: ReportChanges
  refreshing: boolean
  onRefresh: () => void
}) {
  const { counts, visible, unchanged } = groupReportChanges(changes.items)
  const ordered = prioritizeReportChanges(visible)
  const first = ordered.slice(0, 3)
  const remaining = ordered.slice(3)
  const previousLabel = changes.periods?.previousLabel ?? changes.baseReportDate
  const currentLabel = changes.periods?.currentLabel ?? changes.reportDate
  const gap = !changes.periods || changes.periods.basis === 'DAILY'
    ? reportDateGap(changes.baseReportDate, changes.reportDate) : null
  const active = changes.status === 'PENDING' || changes.status === 'RUNNING'
  const message = changes.message || REPORT_CHANGES_MESSAGES[changes.status]
  return <>
    {currentLabel && <div className="report-changes-dates" aria-label="비교하는 보고서 기간">
      <span className="report-changes-period"><small>이전{changes.periods?.basis === 'RUN' ? ' 실행' : ''}</small><strong>{previousLabel ?? '비교 대상 없음'}</strong></span>
      <span aria-hidden="true">→</span>
      <span className="report-changes-period"><small>현재{changes.periods?.basis === 'RUN' ? ' 실행' : ''}</small><strong>{currentLabel}</strong></span>
      {changes.baseReportId && <a href={`#/reports?reportId=${changes.baseReportId}`}>이전 보고서 보기 ↗</a>}
    </div>}
    {gap !== null && gap > 1 && <p className="report-changes-notice">{gap}일 전 보고서와 비교합니다. 두 집계일 사이의 변화를 모두 담고 있지는 않을 수 있습니다.</p>}
    {changes.scopeChanged && <p className="report-changes-notice">두 보고서의 수집 범위가 다릅니다. 같은 조건으로 수집한 공통 주제를 중심으로 비교하며, 조건이 달라진 항목은 판단을 보류할 수 있습니다.</p>}
    {changes.notes.length > 0 && <ul className="report-changes-notes" aria-label="비교 범위 안내">
      {changes.notes.map((note, index) => <li key={index}>{note}</li>)}
    </ul>}
    {changes.status !== 'READY' ? <div className="report-changes-state" role="status">
      <p>{message}</p>
      {active && <button type="button" className="text-button" disabled={refreshing} onClick={onRefresh}>
        {refreshing ? '불러오는 중…' : '결과 새로고침'}
      </button>}
    </div> : <>
      <ul className="report-changes-counts" aria-label="변화 유형별 이슈 수">
        {REPORT_CHANGE_TYPES.map(type => <li key={type}><span className="report-change-badge" data-type={type}>
          {REPORT_CHANGE_LABELS[type]} <strong>{counts[type]}</strong>
        </span></li>)}
      </ul>
      <span className="report-changes-status" role="status">{message}</span>
      {changes.items.length === 0 && <div className="report-changes-state"><p>두 보고서에서 비교할 수 있는 이슈가 없습니다. 변화가 없다는 뜻은 아닙니다.</p></div>}
      {visible.length === 0 && unchanged.length > 0 && <div className="report-changes-state"><p>비교한 이슈에서 내용 변화를 확인하지 못했습니다. 아래에서 근거를 펼쳐볼 수 있습니다.</p></div>}
      {first.length > 0 && <div className="report-changes-list" aria-label="먼저 확인할 변화">{first.map(item => <ChangeItem key={item.id} item={item}
        previousDate={previousLabel} currentDate={currentLabel} compact />)}</div>}
      {changes.keywordChanges && <KeywordChanges changes={changes.keywordChanges} />}
      {remaining.length > 0 && <details className="report-changes-unchanged">
        <summary>나머지 비교 {remaining.length}건 보기</summary>
        <div className="report-changes-list">{remaining.map(item => <ChangeItem key={item.id} item={item}
          previousDate={previousLabel} currentDate={currentLabel} />)}</div>
      </details>}
      {unchanged.length > 0 && <details className="report-changes-unchanged">
        <summary>변화 확인 안 됨 {unchanged.length}건 보기</summary>
        <div className="report-changes-list">{unchanged.map(item => <ChangeItem key={item.id} item={item}
          previousDate={previousLabel} currentDate={currentLabel} />)}</div>
      </details>}
      <p className="report-changes-footnote">‘새로 포함’은 비교 대상인 이전 보고서에 없던 항목이라는 뜻입니다. 사건의 발생이나 종료를 의미하지 않습니다. 근거는 각 보고서에 저장된 문장으로 확인합니다.</p>
    </>}
    {changes.status !== 'READY' && changes.keywordChanges?.status === 'UNAVAILABLE'
      && <p className="report-changes-footnote">{keywordChangesMessage(changes.keywordChanges)}</p>}
  </>
}

function KeywordChanges({ changes }: { changes: ReportKeywordChanges }) {
  return <section className="report-keyword-changes" aria-label="키워드 비중 변화">
    <h4>키워드 비중 변화</h4>
    {changes.status === 'UNAVAILABLE' ? <p className="report-keyword-changes-unavailable">{keywordChangesMessage(changes)}</p> : <>
      <p className="report-keyword-changes-description">전체 유효 이슈 <strong>이전 {changes.previousDenominatorIssueCount ?? '미확인'}개 → 현재 {changes.currentDenominatorIssueCount ?? '미확인'}개</strong> 중 키워드를 다룬 비중입니다.</p>
      {changes.lowSample && <p className="report-changes-notice">비교 이슈가 5개 미만인 기간이 있습니다. 적은 표본이므로 변화 해석에 유의해 주세요.</p>}
      {changes.items.length === 0 ? <p className="report-keyword-changes-unavailable">비교할 등록 키워드가 없습니다.</p> : <>
        <p className="report-keyword-change-legend"><span data-period="previous">이전</span><span data-period="current">현재</span><span>막대 기준 0–100%</span></p>
        <ul className="report-keyword-changes-list">{changes.items.slice(0, 6).map(item => {
          const previous = changeShare(item.previousSharePercent)
          const current = changeShare(item.currentSharePercent)
          const delta = previous !== null && current !== null ? item.changePercentagePoints : null
          return <li className="report-keyword-change" key={item.keyword}>
            <div className="report-keyword-change-heading"><strong>{item.keyword}</strong>
              <span className="report-keyword-change-delta" data-direction={delta === null ? 'unknown' : delta > 0 ? 'up' : delta < 0 ? 'down' : 'same'}>{percentagePointLabel(delta)}</span></div>
            <div className="report-keyword-change-bars">
              <ShareBar label="이전" value={previous} period="previous" />
              <ShareBar label="현재" value={current} period="current" />
            </div>
            <p className="report-keyword-change-count">고유 이슈 {item.previousIssueCount}개 → {item.currentIssueCount}개
              {item.previousIssueCount === 0 && item.currentIssueCount > 0 && <span className="report-keyword-change-new">신규 집계</span>}</p>
          </li>
        })}</ul>
      </>}
      <p className="report-changes-footnote">%p는 비중의 차이입니다. 같은 이슈가 여러 키워드에 포함될 수 있으며, 이 보고서의 집계가 시장 전체 추세를 뜻하지는 않습니다.</p>
    </>}
  </section>
}

function ShareBar({ label, value, period }: { label: string; value: number | null; period: 'previous' | 'current' }) {
  return <div className="report-keyword-change-bar-row" data-period={period}>
    <span>{label}</span>
    <div className="report-keyword-change-track" aria-hidden="true">{value !== null && <span style={{ width: `${value}%` }} />}</div>
    <span className="report-keyword-change-value">{value === null ? '자료 없음' : `${value}%`}</span>
  </div>
}

function ChangeItem({ item, previousDate, currentDate, compact = false }: {
  item: ReportChangeItem
  previousDate: string | null
  currentDate: string | null
  compact?: boolean
}) {
  const longSummary = compact && Array.from(item.summary).length > 220
  return <article className="report-change-item">
    <span className="report-change-badge" data-type={item.type}>{REPORT_CHANGE_LABELS[item.type]}</span>
    <h4>{item.title}</h4>
    <p>{longSummary ? '설명이 길어 아래 상세에 전체 내용을 담았습니다.' : item.summary}</p>
    {item.type === 'UNDETERMINED' && <p className="report-change-caution">저장된 근거만으로는 변화 여부를 판단하기 어렵습니다.</p>}
    {item.type === 'REFUTATION' && <p className="report-change-caution">반박·정정 근거가 추가되었다는 뜻이며, 이전 주장이 거짓이라고 단정하지 않습니다.</p>}
    <details className="report-change-details">
      <summary>{longSummary ? '변화 설명과 양쪽 근거 보기' : '두 보고서의 주장과 근거 보기'}</summary>
      {longSummary && <p className="report-change-full-summary">{item.summary}</p>}
      <div className="report-change-sides">
        <SnapshotSide side={item.previous} label="이전 보고서" date={previousDate} />
        <SnapshotSide side={item.current} label="현재 보고서" date={currentDate} />
      </div>
    </details>
  </article>
}

function SnapshotSide({ side, label, date }: { side: ReportChangeSide | null; label: string; date: string | null }) {
  return <section className="report-change-side" aria-label={`${label}에 저장된 내용`}>
    <div className="report-change-side-label"><strong>{label}</strong>{date && <span>{date}</span>}</div>
    {side ? <>
      <h5>{side.title}</h5>
      <p>{side.summary}</p>
      {side.claims.length > 0 ? <ul className="report-change-claims">{side.claims.map(claim => <li key={claim.id}><SnapshotClaim claim={claim} /></li>)}</ul>
        : <p>비교에 사용할 저장된 주장과 근거가 없습니다.</p>}
    </> : <p>대응하는 이전 보고서 항목이 없습니다.</p>}
  </section>
}

function SnapshotClaim({ claim }: { claim: ReportChangeClaim }) {
  return <details className="report-change-claim">
    <summary>{claim.text}<small>근거 {claim.evidence.length}개</small></summary>
    {claim.evidence.map((evidence, index) => {
      const href = safeReportEvidenceUrl(evidence.canonicalUrl)
      return <blockquote className="report-change-evidence" key={`${evidence.findingId}:${evidence.sentenceIndex}:${index}`}>
        <p>{evidence.text}</p>
        <cite>{href ? <a href={href} target="_blank" rel="noopener noreferrer">{evidence.articleTitle} ↗</a> : evidence.articleTitle}
          {' · '}문장 {evidence.sentenceIndex + 1}</cite>
      </blockquote>
    })}
  </details>
}
