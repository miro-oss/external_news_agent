import { useEffect, useId, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { DART_POLL_WINDOW, reportDartCompanies, reportDartOptions, retryReportDartOptions, type DartCompany, type DartFiling,
  type DartFinancialData, type DartQuarter, type ReportDartCompany } from '../../api/dart'
import type { ReportDetail } from '../../api/types'
import { Segmented } from '../../components/Segmented'
import { dartAmountUnit, dartChartAmount, dartChartSegments, dartQuarterLabel, formatDartAmount, formatDartMargin, safeDartFilingUrl } from './dartDisplay'
import './report-dart.css'

export function ReportDartPanel({ report }: { report: ReportDetail }) {
  const companies = reportDartCompanies(report)
  if (!companies.length) return null
  return <ConnectedReportDartPanel key={report.id} reportId={report.id} companies={companies} />
}

function ConnectedReportDartPanel({ reportId, companies }: { reportId: number; companies: DartCompany[] }) {
  const headingId = useId()
  const [selectedCode, setSelectedCode] = useState(companies[0].corpCode)
  const [pollUntil, setPollUntil] = useState(() => Date.now() + DART_POLL_WINDOW)
  const [pollExpired, setPollExpired] = useState(false)
  useEffect(() => {
    const timer = window.setTimeout(() => setPollExpired(true), Math.max(0, pollUntil - Date.now()))
    return () => window.clearTimeout(timer)
  }, [pollUntil])
  const result = useQuery(reportDartOptions(reportId, pollExpired ? 0 : pollUntil))
  const retry = useMutation(retryReportDartOptions(useQueryClient(), reportId))
  const entries = result.data?.reportId === reportId ? result.data.companies : undefined
  const selected = entries?.find(entry => entry.company.corpCode === selectedCode) ?? entries?.[0]
  const choices = entries?.map(entry => entry.company) ?? companies
  const busy = result.isFetching || retry.isPending
  function refresh() {
    retry.reset()
    setPollExpired(false)
    setPollUntil(Date.now() + DART_POLL_WINDOW)
    void result.refetch()
  }
  function retryFailed() {
    retry.mutate(undefined, { onSuccess: () => {
      setPollExpired(false)
      setPollUntil(Date.now() + DART_POLL_WINDOW)
    } })
  }
  if (entries?.length === 0) return null
  return <section className="report-dart-panel" aria-labelledby={headingId} aria-busy={busy}>
    <div className="report-dart-heading"><div>
      <h3 id={headingId}>회사 실적 · 공시</h3>
      <p>보고서에 연결된 회사의 최근 12분기 실적과 공시입니다.</p>
    </div></div>
    {choices.length > 1 && <Segmented label="공시 회사 선택" className="report-dart-tabs"
      options={choices.map(company => ({ value: company.corpCode, label: company.corpName }))}
      value={selected?.company.corpCode ?? selectedCode} onSelect={setSelectedCode} />}
    {result.isPending && <p className="report-dart-state" role="status">공시 자료를 확인하고 있습니다.</p>}
    {result.isError && <div className="report-dart-state" role="alert"><p>{result.error.message}</p>
      <button type="button" className="text-button" disabled={busy} onClick={refresh}>다시 불러오기</button>
    </div>}
    {retry.isError && <p className="error" role="alert">{retry.error.message}</p>}
    {selected && <ReportDartCompanyContent entry={selected} busy={busy} pollExpired={pollExpired} onRefresh={refresh} onRetry={retryFailed} />}
  </section>
}

export function ReportDartCompanyContent({ entry, busy = false, pollExpired = false, onRefresh, onRetry }: {
  entry: ReportDartCompany
  busy?: boolean
  pollExpired?: boolean
  onRefresh: () => void
  onRetry: () => void
}) {
  const ready = entry.status === 'READY' || entry.status === 'PARTIAL'
  return <div className="report-dart-company">
    <div className="report-dart-company-name"><h4>{entry.company.corpName}</h4>
      <span>{entry.company.stockCode || '비상장'} · DART {entry.company.corpCode}</span></div>
    {entry.status !== 'READY' && <div className="report-dart-state" role={entry.status === 'FAILED' ? 'alert' : 'status'}>
      <p>{entry.message}</p>
      {entry.status === 'PENDING' && <>
        <p>{pollExpired ? '자동 확인 시간이 지났습니다. 잠시 후 새로고침으로 준비 상태를 확인해 주세요.' : '자료가 준비되면 자동으로 표시합니다. 뉴스 리포트는 먼저 확인할 수 있습니다.'}</p>
        <button type="button" className="text-button" disabled={busy} onClick={onRefresh}>공시 자료 새로고침</button>
      </>}
      {(entry.status === 'FAILED' || entry.status === 'DISABLED') && <button type="button" className="text-button" disabled={busy} onClick={onRetry}>
        {busy ? '확인 중…' : entry.status === 'DISABLED' ? '설정 후 다시 확인' : '공시 자료 다시 준비'}
      </button>}
    </div>}
    {ready && entry.data && <DartFinancialContent data={entry.data} companyName={entry.company.corpName} />}
  </div>
}

function DartFinancialContent({ data, companyName }: { data: DartFinancialData; companyName: string }) {
  const latest = data.quarters.at(-1)
  const unit = dartAmountUnit(data.currency)
  const fetched = new Date(data.fetchedAt)
  const fetchedLabel = Number.isNaN(fetched.getTime()) ? '확인 시각 미상'
    : new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', dateStyle: 'medium', timeStyle: 'short' }).format(fetched)
  return <>
    <div className="report-dart-meta"><span>{data.fsDiv === 'CFS' ? '연결재무제표' : data.fsDiv === 'OFS' ? '별도재무제표' : '재무 기준 미확인'}</span>
      <span>단위: {unit}</span><span>자료 확인 <time dateTime={data.fetchedAt}>{fetchedLabel} (한국시간)</time></span></div>
    {latest && <div className="report-dart-latest">
      <p>{dartQuarterLabel(latest)}{latest.derived ? ' · 4분기 산출값' : ''}</p>
      <dl className="report-dart-metrics">
        <div><dt>매출액</dt><dd title={latest.revenue === null ? undefined : `${latest.revenue} ${data.currency ?? ''}`}>{formatDartAmount(latest.revenue, data.currency)}<small>{latest.revenue === null ? '' : unit}</small></dd></div>
        <div><dt>영업이익</dt><dd title={latest.operatingProfit === null ? undefined : `${latest.operatingProfit} ${data.currency ?? ''}`}>{formatDartAmount(latest.operatingProfit, data.currency)}<small>{latest.operatingProfit === null ? '' : unit}</small></dd></div>
        <div><dt>영업이익률</dt><dd>{formatDartMargin(latest.operatingMargin)}</dd></div>
      </dl>
    </div>}
    {data.quarters.length > 0 ? <>
      <DartTrendChart quarters={data.quarters} currency={data.currency} companyName={companyName} />
      <details className="report-dart-table-details"><summary>분기별 상세 실적과 근거 공시 보기</summary>
        <div className="report-dart-table-scroll" tabIndex={0} role="region" aria-label="분기 실적 표 가로 스크롤">
          <table className="report-dart-table"><caption>{companyName} 분기 실적 · 단위 {unit} · 자료 없음은 0이 아닙니다.</caption>
            <thead><tr><th scope="col">분기</th><th scope="col">매출액</th><th scope="col">영업이익</th><th scope="col">영업이익률</th><th scope="col">근거 공시</th></tr></thead>
            <tbody>{data.quarters.map(quarter => <tr key={`${quarter.year}-${quarter.quarter}`}>
              <th scope="row">{dartQuarterLabel(quarter)}{quarter.derived && <small>산출값</small>}</th>
              <td title={quarter.revenue ?? undefined}>{formatDartAmount(quarter.revenue, data.currency)}</td>
              <td title={quarter.operatingProfit ?? undefined}>{formatDartAmount(quarter.operatingProfit, data.currency)}</td>
              <td>{formatDartMargin(quarter.operatingMargin)}</td>
              <td>{quarter.sources.length ? <ul>{quarter.sources.map(filing => <li key={filing.rceptNo}><DartFilingLink filing={filing} /></li>)}</ul> : '자료 없음'}</td>
            </tr>)}</tbody>
          </table>
        </div>
      </details>
      {data.quarters.some(quarter => quarter.derived) && <p className="report-dart-note">4분기 산출값은 같은 회계 기준의 연간 금액에서 3분기 누적 금액을 뺀 값입니다.</p>}
    </> : <p className="report-dart-note">표시할 분기 실적 자료가 없습니다.</p>}
    {data.notes.length > 0 && <ul className="report-dart-notes">{data.notes.map((note, index) => <li key={index}>{note}</li>)}</ul>}
    <details className="report-dart-disclosures">
      <summary>최근 공시 보기</summary>
      {data.disclosures.length > 0 ? <ul>{data.disclosures.map(filing => <li key={filing.rceptNo}>
        <DartFilingLink filing={filing} /><time dateTime={filing.filedAt}>{filing.filedAt}</time>
      </li>)}</ul> : <p className="report-dart-note">확인된 공시가 없습니다.</p>}
    </details>
    <p className="report-dart-note">공시와 실적은 회사 전체 기준이며 뉴스 키워드로 필터하지 않습니다. 자료 확인 시 확보한 내용을 보존하며, 이후 정정공시가 반영된 최신 수치와 다를 수 있습니다.</p>
  </>
}

function DartFilingLink({ filing }: { filing: DartFiling }) {
  const url = safeDartFilingUrl(filing.url, filing.rceptNo)
  return <span className="report-dart-filing">{filing.corrected && <span className="report-dart-correction">정정</span>}
    {url ? <a href={url} target="_blank" rel="noopener noreferrer">{filing.reportName}<span aria-hidden="true"> ↗</span></a>
      : <span>{filing.reportName} (원문 링크 확인 불가)</span>}
  </span>
}

function DartTrendChart({ quarters, currency, companyName }: { quarters: DartQuarter[]; currency: string | null; companyName: string }) {
  const titleId = useId()
  const descriptionId = useId()
  const tooltipId = useId()
  type Point = { index: number; field: 'revenue' | 'operatingProfit' }
  const [hovered, setHovered] = useState<Point | null>(null)
  const [focused, setFocused] = useState<Point | null>(null)
  const scrollRef = useRef<HTMLDivElement>(null)
  const [viewport, setViewport] = useState({ left: 0, right: 720 })
  const revenue = quarters.map(quarter => dartChartAmount(quarter.revenue, currency))
  const profit = quarters.map(quarter => dartChartAmount(quarter.operatingProfit, currency))
  const values = [...revenue, ...profit].filter((value): value is number => value !== null)
  const hasValues = values.length > 0
  useEffect(() => {
    const scroll = scrollRef.current
    const svg = scroll?.querySelector('svg')
    if (!scroll || !svg) return
    const updateViewport = () => {
      const width = svg.getBoundingClientRect().width
      if (width <= 0) return
      const scale = 720 / width
      setViewport({ left: scroll.scrollLeft * scale, right: (scroll.scrollLeft + scroll.clientWidth) * scale })
    }
    const observer = new ResizeObserver(updateViewport)
    observer.observe(scroll)
    observer.observe(svg)
    scroll.addEventListener('scroll', updateViewport, { passive: true })
    return () => { observer.disconnect(); scroll.removeEventListener('scroll', updateViewport) }
  }, [hasValues])
  if (!hasValues) return <p className="report-dart-note">확인된 금액이 없어 실적 차트를 표시할 수 없습니다.</p>
  const lower = Math.min(0, ...values)
  const upper = Math.max(0, ...values)
  const padding = Math.max((upper - lower) * 0.1, 1)
  const min = lower < 0 ? lower - padding : 0
  const max = upper + padding
  const x = (index: number) => 88 + index / Math.max(1, quarters.length - 1) * 592
  const y = (value: number) => 212 - (value - min) / (max - min) * 184
  const active = hovered ?? focused
  const activeValue = active ? (active.field === 'revenue' ? revenue : profit)[active.index] : null
  const activeQuarter = active && activeValue != null ? quarters[active.index] : null
  const tooltipX = active ? Math.max(viewport.left + 8, Math.min(viewport.right - 260 - 8, x(active.index) - 130)) : 0
  const tooltipY = activeValue != null ? (y(activeValue) >= 94 ? y(activeValue) - 86 : y(activeValue) + 12) : 0
  return <figure className="report-dart-chart">
    <figcaption>최근 {quarters.length}분기 추이 <span>단위: {dartAmountUnit(currency)}</span></figcaption>
    <div className="report-dart-chart-legend"><span className="dart-revenue">● 매출액</span><span className="dart-profit">◆ 영업이익</span></div>
    <div ref={scrollRef} className="report-dart-chart-scroll" tabIndex={0} role="region" aria-label="분기 실적 차트 가로 스크롤">
      <svg viewBox="0 0 720 252" role="group" aria-labelledby={`${titleId} ${descriptionId}`}
        onKeyDown={event => {
          if (event.key === 'Escape') { setHovered(null); setFocused(null) }
        }}>
        <title id={titleId}>{`${companyName} 매출액과 영업이익 분기 추이`}</title>
        <desc id={descriptionId}>자료가 없는 분기는 선을 연결하지 않습니다. 점에 마우스를 올리거나 키보드로 이동하면 해당 분기의 금액을 표시합니다. 정확한 금액과 근거는 아래 분기별 상세 실적 표에서 확인할 수 있습니다.</desc>
        {[0, 0.5, 1].map(fraction => {
          const value = min + (max - min) * fraction
          return <g key={fraction}><line className="dart-chart-grid" x1={88} x2={680} y1={y(value)} y2={y(value)} />
            <text className="dart-chart-label" x={78} y={y(value) + 4} textAnchor="end">{value.toLocaleString('ko-KR', { maximumFractionDigits: 0 })}</text>
          </g>
        })}
        <line className="dart-chart-zero" x1={88} x2={680} y1={y(0)} y2={y(0)} />
        {([{ values: revenue, field: 'revenue', label: '매출액' }, { values: profit, field: 'operatingProfit', label: '영업이익' }] as const).map(series => <g key={series.field} className={series.field === 'revenue' ? 'dart-chart-revenue' : 'dart-chart-profit'}>
          {dartChartSegments(series.values).map((segment, index) => <polyline key={index} points={segment.map(point => `${x(point.index)},${y(point.value)}`).join(' ')} />)}
          {series.values.map((value, index) => value === null ? null : <g key={index} className="dart-chart-point"
            tabIndex={0} role="img"
            aria-label={`${dartQuarterLabel(quarters[index])} ${series.label} ${formatDartAmount(quarters[index][series.field], currency)} ${dartAmountUnit(currency)}`}
            aria-describedby={active?.index === index && active.field === series.field ? tooltipId : undefined}
            onPointerEnter={() => setHovered({ index, field: series.field })} onPointerLeave={() => setHovered(null)}
            onFocus={() => { setHovered(null); setFocused({ index, field: series.field }) }} onBlur={() => setFocused(null)}>
            <circle className="dart-chart-hit-area" cx={x(index)} cy={y(value)} r={10} />
            {series.field === 'revenue' ? <circle className="dart-chart-marker" cx={x(index)} cy={y(value)} r={3.5} />
              : <path className="dart-chart-marker" d={`M ${x(index)} ${y(value) - 4.5} l 4.5 4.5 l -4.5 4.5 l -4.5 -4.5 Z`} />}
          </g>)}
        </g>)}
        {quarters.map((quarter, index) => <text key={`${quarter.year}-${quarter.quarter}`} x={x(index)} y={236} className="dart-chart-label" textAnchor="middle">
          {String(quarter.year).slice(-2)}.{quarter.quarter}Q
        </text>)}
        {activeQuarter && <g id={tooltipId} className="dart-chart-tooltip" role="tooltip" transform={`translate(${tooltipX} ${tooltipY})`}>
          <rect width={260} height={74} rx={7} />
          <text className="dart-chart-tooltip-heading" x={12} y={20}>{dartQuarterLabel(activeQuarter)} · {dartAmountUnit(currency)}</text>
          <text className="dart-chart-tooltip-label" x={12} y={42}>매출액</text>
          <text className="dart-chart-tooltip-value" x={248} y={42} textAnchor="end">{formatDartAmount(activeQuarter.revenue, currency)}</text>
          <text className="dart-chart-tooltip-label" x={12} y={61}>영업이익</text>
          <text className="dart-chart-tooltip-value" x={248} y={61} textAnchor="end">{formatDartAmount(activeQuarter.operatingProfit, currency)}</text>
        </g>}
      </svg>
    </div>
  </figure>
}
