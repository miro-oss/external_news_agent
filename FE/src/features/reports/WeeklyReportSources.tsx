import type { ReportDetail } from '../../api/types'

type WeeklySources = Pick<ReportDetail, 'sourceReportCount' | 'sourceReportIds' | 'sourceReportDates' | 'missingReportDates'>

export function WeeklyReportSources({ report }: { report: WeeklySources }) {
  const dates = report.sourceReportDates ?? []
  const ids = report.sourceReportIds ?? []
  const missing = report.missingReportDates ?? []
  const count = report.sourceReportCount ?? (report.sourceReportDates ? dates.length : null)
  return <div className="report-weekly-sources" aria-label="주간 보고서 집계 범위">
    {count !== null && <p className="report-daily-count">7일 중 <strong>{count}일</strong>의 일일 통합 보고서를 모았습니다.</p>}
    {missing.length > 0 && <p className="report-weekly-missing">일일 보고서가 없는 날: <strong>{missing.join(' · ')}</strong><br />해당 날짜는 이번 주간 통합에 포함되지 않았습니다.</p>}
    {dates.length > 0 ? <div className="report-weekly-originals">
      <span>원본 일일 보고서</span>
      <ul>{dates.map((date, index) => <li key={date}>
        {Number.isSafeInteger(ids[index]) && ids[index] > 0
          ? <a href={`#/reports?reportId=${ids[index]}`} aria-label={`${date} 일일 통합 보고서 보기`}><time dateTime={date}>{date}</time></a>
          : <time dateTime={date}>{date}</time>}
      </li>)}</ul>
    </div> : <p className="muted">원본 일일 보고서 날짜 기록이 없습니다.</p>}
  </div>
}
