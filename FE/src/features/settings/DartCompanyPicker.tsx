import { useEffect, useId, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { dartCompanySearchOptions, type DartCompany } from '../../api/dart'
import './dart-company-picker.css'

export function DartCompanyPicker({ value, onChange, disabled = false }: {
  value: DartCompany | null
  onChange: (company: DartCompany | null) => void
  disabled?: boolean
}) {
  const id = useId()
  const input = useRef<HTMLInputElement>(null)
  const [search, setSearch] = useState({ query: '', page: 0 })
  const [debounced, setDebounced] = useState('')
  const query = search.query.trim()
  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(query), 300)
    return () => window.clearTimeout(timer)
  }, [query])
  function select(company: DartCompany) {
    onChange(company)
    setSearch({ query: '', page: 0 })
    input.current?.focus()
  }
  return <div className="field dart-company-picker">
    <label htmlFor={`${id}-search`}>공시 회사 연결 <span className="dart-optional">선택사항</span></label>
    <p id={`${id}-hint`} className="hint">회사 한 곳을 연결하면 이후 실행별 리포트에 최근 12분기 실적과 공시를 표시합니다.</p>
    {value && <div className="dart-company-selection" role="status">
      <span><strong>{value.corpName}</strong><small>{value.stockCode || '비상장'}</small></span>
      <button type="button" className="text-button" disabled={disabled} onClick={() => { onChange(null); input.current?.focus() }}
        aria-label={`${value.corpName} 공시 회사 연결 해제`}>연결 해제</button>
    </div>}
    <input ref={input} id={`${id}-search`} type="search" value={search.query} disabled={disabled} maxLength={100}
      placeholder={value ? '연결할 다른 회사 검색' : '회사명 또는 종목코드 2자 이상'}
      autoComplete="off" aria-describedby={`${id}-hint`} onChange={event => setSearch({ query: event.target.value, page: 0 })} />
    {query.length === 1 && <p className="hint">회사명 또는 종목코드를 2자 이상 입력해 주세요.</p>}
    {query.length >= 2 && query !== debounced && <p className="hint" role="status">검색 중…</p>}
    {query.length >= 2 && query === debounced && <DartCompanySearchResults key={`${query}:${search.page}`}
      query={query} page={search.page} selectedCode={value?.corpCode} disabled={disabled} onSelect={select}
      onPageChange={page => setSearch(current => ({ ...current, page }))} />}
  </div>
}

function DartCompanySearchResults({ query, page, selectedCode, disabled, onSelect, onPageChange }: {
  query: string
  page: number
  selectedCode?: string
  disabled: boolean
  onSelect: (company: DartCompany) => void
  onPageChange: (page: number) => void
}) {
  const result = useQuery(dartCompanySearchOptions(query, page))
  if (result.isPending) return <p className="hint" role="status">공시 회사를 검색하고 있습니다.</p>
  if (result.isError) return <div className="dart-search-state" role="alert">
    <p>{result.error.message}</p><button type="button" className="text-button" disabled={disabled || result.isFetching}
      onClick={() => void result.refetch()}>다시 검색</button>
  </div>
  if (result.data.status !== 'READY') return <div className="dart-search-state" role="status">
    <p>{result.data.message}</p>
    <button type="button" className="text-button" disabled={disabled || result.isFetching}
      onClick={() => void result.refetch()}>{result.isFetching ? '확인 중…' : '다시 확인'}</button>
  </div>
  return <div className="dart-search-results" aria-busy={result.isFetching}>
    <p className="hint" role="status">{result.data.totalElements > 0 ? `검색 결과 ${result.data.totalElements.toLocaleString('ko-KR')}곳` : '검색 결과가 없습니다. 정식 회사명이나 종목코드로 검색해 주세요.'}</p>
    <ul aria-label="공시 회사 검색 결과">{result.data.content.map(company => <li key={company.corpCode}>
      <button type="button" className="dart-company-result" disabled={disabled || result.isFetching}
        aria-pressed={selectedCode === company.corpCode} onClick={() => onSelect(company)}>
        <span><strong>{company.corpName}</strong><small>{company.stockCode || '비상장'}</small></span>
        <span>{selectedCode === company.corpCode ? '선택됨' : '선택'}</span>
      </button>
    </li>)}</ul>
    {result.data.totalPages > 1 && <nav className="dart-search-pagination" aria-label="공시 회사 검색 결과 페이지">
      <button type="button" className="text-button" disabled={disabled || page === 0 || result.isFetching} onClick={() => onPageChange(page - 1)}>이전</button>
      <span>{page + 1} / {result.data.totalPages}</span>
      <button type="button" className="text-button" disabled={disabled || !result.data.hasNext || result.isFetching} onClick={() => onPageChange(page + 1)}>다음</button>
    </nav>}
    <p className="hint">회사에 따라 분기 재무정보가 제공되지 않을 수 있습니다.</p>
  </div>
}
