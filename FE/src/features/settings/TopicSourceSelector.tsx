import { useId, useState } from 'react'
import type { Source } from '../../api/types'
import { recommendedTechnicalSourceIds, recommendedTopicSourceIds } from './topicPresets'
import './topic-presets.css'

export function TopicSourceSelector({ sources, selected, onChange, disabled = false, open, onToggle, allowEmpty = false, preserveInactive = false }: {
  sources: Source[]
  selected: number[]
  onChange: (ids: number[]) => void
  disabled?: boolean
  open: boolean
  onToggle: (open: boolean) => void
  allowEmpty?: boolean
  preserveInactive?: boolean
}) {
  const id = useId()
  const [search, setSearch] = useState('')
  const matches = sources.filter(source => source.name.toLocaleLowerCase().includes(search.trim().toLocaleLowerCase()))
  const recommended = recommendedTechnicalSourceIds(sources)
  return <details className="topic-source-selector" open={open} onToggle={event => onToggle(event.currentTarget.open)}>
    <summary>수집 소스 <span>{selected.length}개 선택</span></summary>
    <div className="topic-source-selector-body">
      <p className="hint">선택한 소스에서만 기사를 모읍니다. 기술 소스 추천은 등록된 공식 기술·연구 블로그와 개발자 블로그를 선택합니다.</p>
      <div className="topic-source-actions">
        <button type="button" className="text-button" disabled={disabled || recommended.length === 0}
          onClick={() => onChange(recommendedTopicSourceIds(sources, preserveInactive ? selected : []))}>기술 소스 추천</button>
        <button type="button" className="text-button" disabled={disabled} onClick={() => onChange(sources.map(source => source.id))}>전체 선택</button>
        <button type="button" className="text-button" disabled={disabled || selected.length === 0} onClick={() => onChange([])}>선택 해제</button>
      </div>
      <label className="topic-source-search-label" htmlFor={`${id}-search`}>소스 검색</label>
      <input id={`${id}-search`} type="search" placeholder="소스 이름으로 찾기" value={search}
        disabled={disabled} onChange={event => setSearch(event.target.value)} />
      <div className="topic-source-choices" role="group" aria-label="수집 소스 선택">
        {matches.map(source => <label key={source.id}>
          <input type="checkbox" disabled={disabled} checked={selected.includes(source.id)}
            onChange={() => onChange(selected.includes(source.id) ? selected.filter(id => id !== source.id) : [...selected, source.id])} />
          <span>{source.name}</span><small>{!source.active ? '중지' : source.sourceKind === 'FEED' ? 'RSS' : '검색'}</small>
        </label>)}
        {matches.length === 0 && <p className="hint">검색 결과가 없습니다.</p>}
      </div>
      {preserveInactive && sources.some(source => !source.active) && <p className="hint topic-source-retained-hint">중지된 소스 연결도 유지합니다. 기술 소스 추천으로 바뀌지 않으며, 체크를 해제하면 연결을 제거합니다.</p>}
      {selected.length === 0 && <p className={allowEmpty ? 'hint' : 'error'}>{allowEmpty ? '저장하면 모든 소스 연결이 해제됩니다.' : '수집할 소스를 하나 이상 선택해 주세요.'}</p>}
    </div>
  </details>
}
