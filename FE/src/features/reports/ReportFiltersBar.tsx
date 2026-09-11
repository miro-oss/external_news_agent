import { useId, useState } from 'react'
import {
  hasReportFilters,
  reportDateRangeError,
  type ReportFilters,
  type ReportPeriod,
} from './reportFilters'
import './report-filters.css'

interface Props {
  filters: ReportFilters
  topicOptions: Array<{ id: number; label: string }>
  scope: 'RUN' | 'DAILY'
  totalCount: number
  resultCount: number
  loading: boolean
  disabled: boolean
  onChange: (filters: ReportFilters) => void
  onReset: () => void
}

export function ReportFiltersBar({ filters, topicOptions, scope, totalCount, resultCount, loading, disabled, onChange, onReset }: Props) {
  const id = useId()
  const [selectedTopic, setSelectedTopic] = useState<{ id: number; label: string } | null>(null)
  const missingSelectedTopic = filters.topicId !== null && !topicOptions.some(option => option.id === filters.topicId)
  const selectedTopicLabel = selectedTopic?.id === filters.topicId ? selectedTopic.label : `주제 #${filters.topicId}`
  const dateError = reportDateRangeError(filters)
  const dateLabel = scope === 'RUN' ? '수집 실행일' : '집계일'
  return (
    <section className="report-filters" aria-label="보고서 검색 및 필터">
      <div className="report-filter-fields">
        <label className="report-filter-field report-search-field">
          <span>보고서 검색</span>
          <div className="report-search-input">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" aria-hidden="true"><circle cx="10.5" cy="10.5" r="6.5" /><path d="m16 16 4.5 4.5" /></svg>
            <input
              type="search"
              placeholder="제목, 수집 주제, 키워드 검색"
              value={filters.search}
              disabled={disabled}
              onChange={event => onChange({ ...filters, search: event.target.value })}
            />
          </div>
        </label>
        <label className="report-filter-field report-topic-field">
          <span>수집 주제</span>
          <select value={filters.topicId ?? ''} disabled={disabled} onChange={event => {
            const topicId = event.target.value ? Number(event.target.value) : null
            setSelectedTopic(topicOptions.find(option => option.id === topicId) ?? (selectedTopic?.id === topicId ? selectedTopic : null))
            onChange({ ...filters, topicId })
          }}>
            <option value="">전체 주제</option>
            {missingSelectedTopic && <option value={filters.topicId!}>{selectedTopicLabel} (보고서 없음)</option>}
            {topicOptions.map(option => <option key={option.id} value={option.id}>{option.label}</option>)}
          </select>
        </label>
        <label className="report-filter-field report-period-field">
          <span>{dateLabel}</span>
          <select value={filters.period} disabled={disabled} onChange={event => onChange({ ...filters, period: event.target.value as ReportPeriod })}>
            <option value="ALL">전체 기간</option>
            <option value="TODAY">오늘</option>
            <option value="7">최근 7일</option>
            <option value="30">최근 30일</option>
            <option value="CUSTOM">직접 선택</option>
          </select>
        </label>
      </div>
      {filters.period === 'CUSTOM' && (
        <div className="report-custom-period">
          <div className="report-date-fields">
            <label className="report-filter-field">
              <span>시작일</span>
              <input type="date" value={filters.from} disabled={disabled} max={filters.to || undefined}
                aria-invalid={!!dateError} aria-describedby={dateError ? `${id}-error` : `${id}-date-hint`}
                onChange={event => onChange({ ...filters, from: event.target.value })} />
            </label>
            <span className="report-date-separator" aria-hidden="true">–</span>
            <label className="report-filter-field">
              <span>종료일</span>
              <input type="date" value={filters.to} disabled={disabled} min={filters.from || undefined}
                aria-invalid={!!dateError} aria-describedby={dateError ? `${id}-error` : `${id}-date-hint`}
                onChange={event => onChange({ ...filters, to: event.target.value })} />
            </label>
          </div>
          {dateError
            ? <p className="report-filter-error" id={`${id}-error`} role="alert">{dateError}</p>
            : <p className="report-date-hint" id={`${id}-date-hint`}>시작일과 종료일을 포함합니다. 한쪽 날짜만 선택할 수도 있습니다.</p>}
        </div>
      )}
      <div className="report-filter-summary">
        <p aria-live="polite" aria-atomic="true">
          {loading ? '보고서를 불러오는 중…' : <>전체 {totalCount.toLocaleString()}개 중 <strong>{resultCount.toLocaleString()}개</strong></>}
          <span className="report-filter-date-basis">{dateLabel} 기준 · 한국 시간</span>
        </p>
        <button type="button" className="text-button report-filter-reset" disabled={disabled || !hasReportFilters(filters)} onClick={onReset}>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M3 10a9 9 0 1 1 2 8M3 4v6h6" /></svg>
          초기화
        </button>
      </div>
    </section>
  )
}
