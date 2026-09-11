import { useId } from 'react'
import { useReportChanges } from '../../api/reportChanges'
import type { ReportChangeClaim, ReportChangeItem, ReportChanges, ReportChangeSide } from '../../api/types'
import { groupReportChanges, reportDateGap, REPORT_CHANGE_LABELS, REPORT_CHANGE_TYPES, REPORT_CHANGES_MESSAGES, safeReportEvidenceUrl } from './reportChangesDisplay'
import './report-changes.css'

export function ReportChangesPanel({ reportId }: { reportId: number }) {
  const comparison = useReportChanges(reportId)
  const headingId = useId()
  return <section className="report-changes-panel" aria-labelledby={headingId}>
    <header className="report-changes-heading">
      <h3 id={headingId}>지난 보고서와 달라진 점</h3>
      <p>두 일일 보고서에 담긴 내용과 당시 근거를 함께 비교합니다.</p>
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
  const gap = reportDateGap(changes.baseReportDate, changes.reportDate)
  const active = changes.status === 'PENDING' || changes.status === 'RUNNING'
  const message = changes.message || REPORT_CHANGES_MESSAGES[changes.status]
  return <>
    {changes.baseReportDate && changes.reportDate && <div className="report-changes-dates" aria-label="비교하는 보고서 집계일">
      <span>이전 <time dateTime={changes.baseReportDate}>{changes.baseReportDate}</time></span>
      <span aria-hidden="true">→</span>
      <span>현재 <time dateTime={changes.reportDate}>{changes.reportDate}</time></span>
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
      {visible.length > 0 && <div className="report-changes-list">{visible.map(item => <ChangeItem key={item.id} item={item}
        previousDate={changes.baseReportDate} currentDate={changes.reportDate} />)}</div>}
      {unchanged.length > 0 && <details className="report-changes-unchanged">
        <summary>변화 확인 안 됨 {unchanged.length}건 보기</summary>
        <div className="report-changes-list">{unchanged.map(item => <ChangeItem key={item.id} item={item}
          previousDate={changes.baseReportDate} currentDate={changes.reportDate} />)}</div>
      </details>}
      <p className="report-changes-footnote">‘새로 포함’은 비교 대상인 이전 보고서에 없던 항목이라는 뜻입니다. 사건의 발생이나 종료를 의미하지 않습니다. 근거는 각 보고서에 저장된 문장으로 확인합니다.</p>
    </>}
  </>
}

function ChangeItem({ item, previousDate, currentDate }: {
  item: ReportChangeItem
  previousDate: string | null
  currentDate: string | null
}) {
  return <article className="report-change-item">
    <span className="report-change-badge" data-type={item.type}>{REPORT_CHANGE_LABELS[item.type]}</span>
    <h4>{item.title}</h4>
    <p>{item.summary}</p>
    {item.type === 'UNDETERMINED' && <p className="report-change-caution">저장된 근거만으로는 변화 여부를 판단하기 어렵습니다.</p>}
    {item.type === 'REFUTATION' && <p className="report-change-caution">반박·정정 근거가 추가되었다는 뜻이며, 이전 주장이 거짓이라고 단정하지 않습니다.</p>}
    <details className="report-change-details">
      <summary>두 보고서의 주장과 근거 보기</summary>
      <div className="report-change-sides">
        <SnapshotSide side={item.previous} label="이전 보고서" date={previousDate} />
        <SnapshotSide side={item.current} label="현재 보고서" date={currentDate} />
      </div>
    </details>
  </article>
}

function SnapshotSide({ side, label, date }: { side: ReportChangeSide | null; label: string; date: string | null }) {
  return <section className="report-change-side" aria-label={`${label}에 저장된 내용`}>
    <div className="report-change-side-label"><strong>{label}</strong>{date && <time dateTime={date}>{date}</time>}</div>
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
