import { useEffect, useId, useRef, useState, type FormEvent } from 'react'
import { ApiError } from '../../api/client'
import { updateCompanyInterest, type CompanyInterestCriterion, type CompanyInterestStatus, type CompanyInterestUpdate } from '../../api/companyInterests'
import { Segmented, type SegmentedOption } from '../../components/Segmented'
import { formatMediumDate } from '../../lib/datetime'
import { CompanyInterestProgress } from './CompanyInterestProgress'
import { companyInterestDraftError, companyInterestIdFromHash } from './companyInterestState'
import { useCompanyInterests } from './useCompanyInterests'
import './companyInterests.css'

const FILTERS: ReadonlyArray<SegmentedOption<CompanyInterestStatus | 'ALL'>> = [
  { value: 'ALL', label: '전체' }, { value: 'DRAFT', label: '확인 대기' },
  { value: 'ACTIVE', label: '사용 중' }, { value: 'REVOKED', label: '사용 중지' },
]
const selectedCriterion = () => typeof window === 'undefined' ? null : companyInterestIdFromHash(window.location.hash)

export function CompanyInterestsPage() {
  const { criteria, error, busy, refresh, record } = useCompanyInterests()
  const [filter, setFilter] = useState<CompanyInterestStatus | 'ALL'>('ALL')
  const [selected, setSelected] = useState(selectedCriterion)
  const [notice, setNotice] = useState('')
  useEffect(() => {
    function sync() { setSelected(selectedCriterion()); setFilter('ALL') }
    window.addEventListener('hashchange', sync)
    return () => window.removeEventListener('hashchange', sync)
  }, [])
  function saved(criterion: CompanyInterestCriterion) {
    record(criterion)
    setNotice(criterion.status === 'ACTIVE' ? '기준을 저장했습니다. 다음 보고서가 생성되면 실제 반영 결과를 확인할 수 있습니다.'
      : criterion.status === 'DRAFT' ? '초안을 저장했습니다. 적용하기를 눌러야 다음 보고서 선정에 사용합니다.'
        : '기준 사용을 중지했습니다.')
  }
  const visible = criteria?.filter(criterion => filter === 'ALL' || criterion.status === filter)
  return <main className="company-interests-page">
    <header className="page-header">
      <div><h1>회사 관심 기준</h1><p className="muted">보고서에 남긴 관심 의견을 검토하고, 회사에 필요한 소식을 고르는 기준으로 적용합니다.</p></div>
      <button type="button" className="secondary-button" disabled={busy} onClick={refresh}>{busy ? '확인 중…' : '최신 상태 확인'}</button>
    </header>
    <ol className="company-interest-steps" aria-label="회사 관심 기준 반영 순서">
      <li><strong>1. 의견 검토 완료</strong><span>관심 의견에서 기준 초안을 만듭니다.</span></li>
      <li><strong>2. 기준 확인 후 적용</strong><span>기준과 주제를 확인해 사용을 시작합니다.</span></li>
      <li><strong>3. 실제 반영 완료</strong><span>새 보고서의 검토·제외 결과를 확인합니다.</span></li>
    </ol>
    <p className="company-interest-scope">적용한 기준은 선택한 주제의 이후 보고서 선정에 사용합니다. 기존 보고서는 유지되며, 개인 알림 기준에는 적용하지 않습니다.</p>
    <div className="company-interest-toolbar">
      <Segmented options={FILTERS} value={filter} onSelect={setFilter} label="회사 관심 기준 상태" />
      {criteria && <span>{criteria.filter(criterion => criterion.status === 'DRAFT').length}개 확인 대기 · {criteria.filter(criterion => criterion.status === 'ACTIVE').length}개 사용 중</span>}
    </div>
    {notice && <p className="company-interest-notice" role="status">{notice}</p>}
    {error != null && <div className="state-panel error" role="alert">
      <p>{error instanceof ApiError ? error.message : '회사 관심 기준을 불러오지 못했습니다.'}</p>
      <button type="button" className="secondary-button" disabled={busy} onClick={refresh}>다시 불러오기</button>
    </div>}
    {!criteria && busy && <div className="state-panel" role="status">회사 관심 기준을 불러오고 있습니다.</div>}
    {criteria && !busy && selected !== null && !criteria.some(criterion => criterion.id === selected) && <p className="company-interest-notice" role="status">연결된 기준을 찾지 못했습니다. 아래 목록에서 현재 기준을 확인해 주세요.</p>}
    {criteria && visible?.length === 0 && <div className="state-panel company-interest-empty">
      <strong>{criteria.length ? '이 상태의 기준이 없습니다.' : '아직 제안된 회사 관심 기준이 없습니다.'}</strong>
      {!criteria.length && <><p>리포트의 중요 이벤트에 관심에 대한 의견을 남기면, 검토 후 이곳에서 제안된 기준을 확인할 수 있습니다.</p>
        <a href="#/reports">리포트에서 의견 남기기</a></>}
    </div>}
    <div className="company-interest-list" aria-busy={busy}>
      {visible?.map(criterion => <CompanyInterestCard key={`${criterion.id}:${criterion.version}`} criterion={criterion}
        highlighted={criterion.id === selected} disabled={busy || error != null} onSaved={saved} onReload={refresh} />)}
    </div>
  </main>
}

export function CompanyInterestCard({ criterion, highlighted = false, disabled = false, onSaved, onReload }: {
  criterion: CompanyInterestCriterion; highlighted?: boolean; disabled?: boolean
  onSaved: (criterion: CompanyInterestCriterion) => void; onReload: () => void
}) {
  const id = useId()
  const card = useRef<HTMLElement | null>(null)
  const controller = useRef<AbortController | null>(null)
  const [instruction, setInstruction] = useState(criterion.instruction)
  const [topicId, setTopicId] = useState(criterion.topicId)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const [conflict, setConflict] = useState(false)
  useEffect(() => () => controller.current?.abort(), [])
  useEffect(() => {
    if (highlighted) { card.current?.scrollIntoView({ block: 'start' }); card.current?.focus({ preventScroll: true }) }
  }, [highlighted])
  const dirty = instruction.trim() !== criterion.instruction || topicId !== criterion.topicId
  const unavailable = disabled || pending || conflict

  async function save(status: CompanyInterestStatus) {
    if (controller.current || unavailable) return
    // Stopping a criterion never saves unrelated edits still in the form.
    const update: CompanyInterestUpdate = status === 'REVOKED'
      ? { version: criterion.version, instruction: criterion.instruction, topicId: criterion.topicId, status }
      : { version: criterion.version, instruction: instruction.trim(), topicId, status }
    const validation = status === 'REVOKED' ? null : companyInterestDraftError(criterion, update)
    if (validation) { setError(validation); return }
    const active = new AbortController()
    controller.current = active
    setPending(true)
    setError('')
    try {
      const saved = await updateCompanyInterest(criterion.id, update, active.signal)
      if (!active.signal.aborted) onSaved(saved)
    } catch (failure) {
      if (active.signal.aborted) return
      const stale = failure instanceof ApiError && failure.status === 409
      setConflict(stale)
      setError(failure instanceof ApiError ? failure.message : '저장 결과를 확인하지 못했습니다. 최신 상태를 확인한 뒤 다시 시도해 주세요.')
    } finally {
      if (!active.signal.aborted) { controller.current = null; setPending(false) }
    }
  }
  function submit(event: FormEvent) { event.preventDefault(); void save('ACTIVE') }
  function reload() {
    setInstruction(criterion.instruction)
    setTopicId(criterion.topicId)
    setConflict(false)
    setError('')
    onReload()
  }
  return <article ref={card} tabIndex={-1} id={`company-interest-${criterion.id}`}
    className={`company-interest-card${highlighted ? ' is-highlighted' : ''}`} aria-labelledby={`${id}-title`}>
    <header className="company-interest-card-heading">
      <div><span className="company-interest-review-status">의견 검토 완료</span><h2 id={`${id}-title`}>{criterion.eventTitle}</h2></div>
      <span className="company-interest-id">기준 #{criterion.id}</span>
    </header>
    <div className="company-interest-card-body">
      <section className="company-interest-origin" aria-label="기준을 제안한 의견">
        <h3>남긴 의견</h3><p className="company-interest-comment">{criterion.comment}</p>
        <p className="company-interest-origin-meta">보고서 #{criterion.sourceReportId} · <time dateTime={criterion.createdAt}>{formatMediumDate(criterion.createdAt)}</time></p>
        {criterion.reason && <><h3>제안 이유</h3><p>{criterion.reason}</p></>}
        {criterion.diagnosis && <details><summary>의견 검토 내용</summary><p>{criterion.diagnosis}</p></details>}
      </section>
      <form className="company-interest-form" onSubmit={submit} aria-busy={pending} aria-label={`${criterion.eventTitle} 회사 관심 기준`}>
        <fieldset disabled={unavailable}>
          <legend>다음 보고서에 적용할 기준</legend>
          <label htmlFor={`${id}-instruction`}>회사 관심 기준
            <textarea id={`${id}-instruction`} value={instruction} maxLength={500} rows={4} required
              aria-describedby={`${id}-help ${id}-count`} onChange={event => { setInstruction(event.target.value); setError('') }} />
          </label>
          <div className="company-interest-input-help"><span id={`${id}-help`}>관심 범위와 제외할 내용을 구체적으로 적어 주세요.</span><span id={`${id}-count`}>{instruction.length} / 500</span></div>
          <label htmlFor={`${id}-topic`}>적용 주제
            <select id={`${id}-topic`} value={topicId ?? ''} onChange={event => { setTopicId(event.target.value ? Number(event.target.value) : null); setError('') }}>
              <option value="">주제를 선택해 주세요</option>
              {criterion.topics.map(topic => <option key={topic.id} value={topic.id}>{topic.name}</option>)}
            </select>
          </label>
          <p className="company-interest-input-help">이 의견의 근거 기사와 연결된 주제 하나에 적용합니다.</p>
        </fieldset>
        <CompanyInterestProgress criterion={criterion} />
        {dirty && criterion.status === 'ACTIVE' && <p className="company-interest-edit-notice">작성 중인 변경은 아직 반영하지 않았습니다. 저장 후에는 새 기준의 다음 보고서 반영을 기다립니다.</p>}
        {error && <div className="company-interest-error" role="alert"><p>{error}</p>
          {conflict && <p>기준이 변경되었거나 같은 주제의 활성 기준 한도에 도달했을 수 있습니다. 최신 기준을 확인해 주세요. 불러오면 작성 중인 변경은 초기화됩니다.</p>}
          <button type="button" className="text-button" disabled={disabled || pending} onClick={reload}>최신 기준 불러오기</button>
        </div>}
        <div className="company-interest-actions">
          {criterion.status === 'DRAFT' && <button type="button" className="secondary-button" disabled={unavailable || !dirty}
            onClick={() => { void save('DRAFT') }}>초안 저장</button>}
          <button type="submit" className="primary-button" disabled={unavailable || (criterion.status === 'ACTIVE' && !dirty)}>
            {pending ? '저장 중…' : criterion.status === 'ACTIVE' ? '변경 사항 저장 및 적용' : criterion.status === 'REVOKED' ? '확인한 기준 다시 적용' : '확인한 기준 적용하기'}
          </button>
          {criterion.status !== 'REVOKED' && <button type="button" className="text-button company-interest-stop" disabled={unavailable}
            onClick={() => { void save('REVOKED') }}>{criterion.status === 'DRAFT' ? '이 기준 사용하지 않기' : '기준 사용 중지'}</button>}
        </div>
      </form>
    </div>
  </article>
}
