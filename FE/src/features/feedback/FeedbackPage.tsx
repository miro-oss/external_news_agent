import { useEffect, useId, useRef, useState } from 'react'
import bistelligenceLogo from '../../assets/bistelligence-logo.png'
import { ApiError } from '../../api/client'
import {
  revokePersonalPolicy, submitFeedback, type FeedbackCategory, type FeedbackContext,
  type FeedbackItem, type FeedbackRequest, type PersonalPolicy, type ReaderFeedback,
} from '../../api/feedback'
import { formatFullDate } from '../../lib/datetime'
import {
  FEEDBACK_CATEGORIES, FEEDBACK_STATUS_LABELS, feedbackDraftError, feedbackTokenFromHash,
  pendingFeedback, prepareFeedbackRequest, safeFeedbackSource,
} from './feedbackState'
import { useFeedbackContext } from './useFeedbackContext'
import './feedback.css'

export function FeedbackPage({ token }: { token: string | null }) {
  const context = useFeedbackContext(token)
  useEffect(() => {
    // Keep the capability in this mounted page only, never in history, browser storage or app caches.
    if (token && feedbackTokenFromHash(window.location.hash) === token) window.history.replaceState(null, '', '#/feedback')
  }, [token])

  return <div className="feedback-shell">
    <header className="feedback-brand">
      <img src={bistelligenceLogo} alt="BISTelligence" width={136} height={23} />
      <span>News Signal Desk</span>
      <span className="feedback-private-label">보고서 의견</span>
    </header>
    <main className="feedback-page">
      {context.unavailable ? <section className="feedback-link-state">
        <span className="feedback-eyebrow">링크 확인이 필요해요</span>
        <h1>이 의견 링크를 사용할 수 없습니다.</h1>
        <p>링크가 만료되었거나 올바르지 않습니다. 본인에게 온 이메일 또는 텔레그램 보고서에서 의견 링크를 다시 열어 주세요.</p>
        <p>다시 열어도 같은 안내가 나오면 보고서를 보낸 담당자에게 새 보고서 전달을 요청해 주세요.</p>
      </section> : !context.data ? <section className="feedback-link-state" aria-busy={context.busy}>
        <h1>{context.error ? '보고서를 불러오지 못했습니다.' : '받으신 보고서를 확인하고 있어요.'}</h1>
        {context.error ? <><p role="alert">{feedbackError(context.error)}</p><button type="button" disabled={context.busy} onClick={context.refresh}>{context.busy ? '확인 중…' : '다시 확인'}</button></>
          : <p role="status">의견을 남길 소식과 이전 검토 결과를 불러옵니다.</p>}
      </section> : <FeedbackWorkspace context={context.data} token={token!}
        busy={context.busy} paused={context.paused} refreshError={context.error}
        onRefresh={context.refresh} onFeedback={context.recordFeedback} onPolicy={context.recordPolicy} />}
      <footer className="feedback-footer">이 화면을 닫은 뒤 다시 확인하려면, 받으신 보고서의 의견 링크를 열어 주세요.</footer>
    </main>
  </div>
}

function feedbackError(error: unknown) {
  return error instanceof ApiError ? error.message : '서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

export function FeedbackWorkspace({ context, token, busy = false, paused = false, refreshError = null,
  onRefresh, onFeedback, onPolicy }: {
  context: FeedbackContext; token: string; busy?: boolean; paused?: boolean; refreshError?: unknown
  onRefresh: () => void; onFeedback: (feedback: ReaderFeedback) => void; onPolicy: (policy: PersonalPolicy) => void
}) {
  const pending = pendingFeedback(context.feedback)
  return <>
    <header className="feedback-heading">
      <span className="feedback-eyebrow">더 도움이 되는 보고서를 위해</span>
      <h1>어떤 소식에 의견이 있나요?</h1>
      <p>관심에 맞지 않는 소식과 확인이 필요한 내용을 알려 주세요. 당시 기사와 분석을 바탕으로 검토합니다.</p>
    </header>
    <section className="feedback-report-context" aria-label="의견을 남기는 보고서">
      <div><span>받으신 보고서</span><h2>{context.reportTitle}</h2></div>
      <p>링크 사용 기한 <time dateTime={context.expiresAt}>{formatFullDate(context.expiresAt)}</time></p>
    </section>
    <p className="feedback-identity-note">이 링크는 보고서를 받은 분의 의견과 개인 기준에 연결됩니다. 다른 분에게 전달받은 링크라면, 본인에게 온 보고서의 링크를 이용해 주세요.</p>
    {refreshError != null && <div className="feedback-refresh-error" role="alert">
      <p>{feedbackError(refreshError)} 최신 상태를 확인한 뒤 의견을 남겨 주세요.</p>
      <button className="text-button" type="button" disabled={busy} onClick={onRefresh}>다시 확인</button>
    </div>}
    <div className="feedback-columns">
      <section className="feedback-compose feedback-card" aria-labelledby="feedback-compose-title">
        <h2 id="feedback-compose-title">의견 남기기</h2>
        {context.items.length ? <FeedbackForm items={context.items} submittedItemIds={context.feedback.map(item => item.itemId)} token={token} disabled={refreshError != null}
          onFeedback={onFeedback} onRefresh={onRefresh} />
          : <p className="feedback-empty">이 보고서에는 의견을 남길 수 있는 소식이 없습니다. 보고서를 보낸 담당자에게 알려 주세요.</p>}
      </section>
      <aside className="feedback-history feedback-card" aria-labelledby="feedback-history-title">
        <div className="feedback-section-heading"><h2 id="feedback-history-title">남긴 의견과 검토 결과</h2>
          <button type="button" className="text-button" disabled={busy} onClick={onRefresh}>{busy ? '확인 중…' : '새로 확인'}</button>
        </div>
        <p className="feedback-section-description">검토 결과는 의견의 내용과 원문 근거를 함께 살펴본 설명입니다.</p>
        {pending && <p className="feedback-poll-note" role="status">{paused
          ? '검토가 이어지고 있습니다. 자동 확인을 잠시 멈췄어요. 나중에 새로 확인해 주세요.'
          : '접수한 의견을 검토하고 있습니다. 결과를 잠시 동안 자동으로 확인합니다.'}</p>}
        {context.feedback.length === 0 ? <div className="feedback-empty"><strong>아직 남긴 의견이 없어요.</strong><p>의견을 보내면 이곳에서 접수 상태와 검토 결과를 확인할 수 있습니다.</p></div>
          : <ol className="feedback-history-list">{context.feedback.map(feedback => <FeedbackHistoryItem key={feedback.id}
            feedback={feedback} item={context.items.find(item => item.itemId === feedback.itemId)} />)}</ol>}
      </aside>
    </div>
    <section className="feedback-policies feedback-card" aria-labelledby="feedback-policies-title">
      <div className="feedback-section-heading"><h2 id="feedback-policies-title">내 보고서에 적용하는 관심 기준</h2><span>{context.policies.filter(policy => policy.status === 'ACTIVE').length}개 적용 중</span></div>
      <p className="feedback-section-description">직접 동의한 관심 의견을 검토해 만든 기준입니다. 이후 내게 전달할 보고서에서 참고하며, 언제든 적용을 중지할 수 있습니다.</p>
      {context.policies.length === 0 ? <p className="feedback-empty">아직 적용한 개인 기준이 없습니다. 의견을 남기는 것만으로 기준이 만들어지지는 않습니다.</p>
        : <ul className="feedback-policy-list">{context.policies.map(policy => <PolicyItem key={`${policy.id}:${policy.version}`} policy={policy}
          token={token} disabled={refreshError != null} onPolicy={onPolicy} onRefresh={onRefresh} />)}</ul>}
    </section>
  </>
}

export function FeedbackForm({ items, submittedItemIds = [], token, disabled = false, onFeedback, onRefresh }: {
  items: FeedbackItem[]; submittedItemIds?: number[]; token: string; disabled?: boolean
  onFeedback: (feedback: ReaderFeedback) => void; onRefresh: () => void
}) {
  const id = useId()
  const [itemId, setItemId] = useState('')
  const [category, setCategory] = useState<FeedbackCategory>('PREFERENCE')
  const [comment, setComment] = useState('')
  const [allowPersonalization, setAllowPersonalization] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [pending, setPending] = useState(false)
  const [submitted, setSubmitted] = useState(false)
  const [canReplay, setCanReplay] = useState(false)
  const request = useRef<FeedbackRequest | null>(null)
  const controller = useRef<AbortController | null>(null)
  const selected = items.find(item => item.itemId === Number(itemId))
  useEffect(() => () => controller.current?.abort(), [])

  function changed() { setError(null); setCanReplay(false) }
  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (controller.current || disabled || submitted) return
    if (selected && submittedItemIds.includes(selected.itemId) && !canReplay) {
      setError('이 소식에는 이미 의견을 남겼습니다. 남긴 의견과 검토 결과에서 확인해 주세요.')
      return
    }
    const draft = { itemId: selected?.itemId ?? 0, category, comment, allowPersonalization }
    const validation = feedbackDraftError(draft)
    if (validation) { setError(validation); return }
    request.current = prepareFeedbackRequest(request.current, draft)
    const active = new AbortController()
    controller.current = active
    setPending(true)
    setError(null)
    try {
      const result = await submitFeedback(token, request.current, active.signal)
      if (active.signal.aborted) return
      setSubmitted(true)
      onFeedback(result)
    } catch (failure) {
      if (active.signal.aborted) return
      setError(feedbackError(failure))
      setCanReplay(true)
      if (failure instanceof ApiError && (failure.status === 404 || failure.status === 409)) onRefresh()
    } finally {
      if (!active.signal.aborted) { controller.current = null; setPending(false) }
    }
  }

  if (!submitted && !canReplay && items.every(item => submittedItemIds.includes(item.itemId))) {
    return <p className="feedback-empty">모든 소식에 의견을 남겼습니다. 남긴 의견과 검토 결과에서 진행 상황을 확인해 주세요.</p>
  }
  return <form onSubmit={event => { void submit(event) }} className="feedback-form" aria-busy={pending}>
    <fieldset disabled={disabled || pending || submitted}>
      <label className="feedback-field" htmlFor={`${id}-item`}><span>1. 의견을 남길 소식</span>
        <select id={`${id}-item`} value={itemId} required onChange={event => { setItemId(event.target.value); setAllowPersonalization(false); changed() }}>
          <option value="" disabled>보고서에 담긴 소식을 선택해 주세요</option>
          {items.map(item => <option key={item.itemId} value={item.itemId}
            disabled={submittedItemIds.includes(item.itemId) && !(canReplay && item.itemId === Number(itemId))}>
            {submittedItemIds.includes(item.itemId) ? '[의견 남김] ' : ''}{item.topicName} · {item.title}
          </option>)}
        </select>
      </label>
      <p className="feedback-section-description">한 소식에 한 번 의견을 남길 수 있습니다. 함께 전하고 싶은 내용은 아래에 적어 주세요.</p>
      {selected && <div className="feedback-selected-item">
        <span>{selected.topicName}</span><h3>{selected.title}</h3><p>{selected.summary}</p>
        {selected.sources.length > 0 && <details><summary>함께 확인할 원문 {selected.sources.length}개</summary>
          <ul>{selected.sources.map((source, index) => {
            const url = safeFeedbackSource(source.url)
            return <li key={`${source.articleId}:${index}`}>{url ? <a href={url} target="_blank" rel="noopener noreferrer" referrerPolicy="no-referrer">{source.title} ↗</a> : <span>{source.title}</span>}</li>
          })}</ul>
        </details>}
      </div>}
      <fieldset className="feedback-category-options"><legend>2. 어떤 의견인가요?</legend>
        {FEEDBACK_CATEGORIES.map(option => <label key={option.value} className={category === option.value ? 'is-selected' : ''}>
          <input type="radio" name={`${id}-category`} value={option.value} checked={category === option.value}
            onChange={() => { setCategory(option.value); setAllowPersonalization(false); changed() }} />
          <span><strong>{option.label}</strong><small>{option.description}</small></span>
        </label>)}
      </fieldset>
      <label className="feedback-field" htmlFor={`${id}-comment`}><span>3. 조금 더 알려 주세요</span>
        <textarea id={`${id}-comment`} rows={4} value={comment} maxLength={2000} required
          aria-describedby={`${id}-comment-help ${id}-count`}
          placeholder={category === 'PREFERENCE' ? '예: 시장 전망보다 실제 생산·공급 계약 소식을 더 보고 싶어요.' : '어떤 내용이 어떻게 달랐는지, 확인할 원문이나 문장을 함께 알려 주세요.'}
          onChange={event => { setComment(event.target.value); changed() }} />
      </label>
      <div className="feedback-comment-help"><span id={`${id}-comment-help`}>의견을 1~2,000자로 입력해 주세요.</span><span id={`${id}-count`}>{comment.length.toLocaleString()} / 2,000</span></div>
      {category === 'PREFERENCE' ? <div className="feedback-personal-choice">
        <label><input type="checkbox" checked={allowPersonalization} onChange={event => { setAllowPersonalization(event.target.checked); changed() }} />
          <span>이 관심 기준을 앞으로 내 보고서에 반영해 주세요.<small>선택 사항입니다. 검토 후 이 주제의 개인 기준으로 적용할 수 있으며, 아래에서 중지할 수 있습니다.</small></span>
        </label>
      </div> : <p className="feedback-fact-note">확인이 필요한 내용으로 접수합니다. 제보만으로 사실 오류가 확정되거나 공통 분석 기준이 바뀌지는 않습니다.</p>}
    </fieldset>
    {error && <div className="feedback-form-error" role="alert"><p>{error}</p>{canReplay && <p>같은 내용으로 다시 확인하면 중복 접수하지 않습니다.</p>}</div>}
    {submitted ? <div className="feedback-submitted" role="status"><strong>의견을 접수했습니다.</strong><p>검토 결과에서 진행 상황을 확인할 수 있습니다.</p>
      {items.some(item => !submittedItemIds.includes(item.itemId)) && <button type="button" className="text-button"
        onClick={() => { request.current = null; setSubmitted(false); setItemId(''); setComment(''); setAllowPersonalization(false); changed() }}>다른 소식에 의견 남기기</button>}
    </div> : <button type="submit" className="primary-button feedback-submit" disabled={disabled || pending}>
      {pending ? '접수 확인 중…' : error && canReplay ? '접수 결과 다시 확인' : '의견 보내기'}
    </button>}
  </form>
}

function FeedbackHistoryItem({ feedback, item }: { feedback: ReaderFeedback; item?: FeedbackItem }) {
  return <li className="feedback-history-item">
    <div className="feedback-history-meta"><span className={`feedback-status feedback-status-${feedback.status.toLowerCase()}`}>{FEEDBACK_STATUS_LABELS[feedback.status]}</span>
      <time dateTime={feedback.createdAt}>{formatFullDate(feedback.createdAt)}</time></div>
    <h3>{item?.title ?? '받은 보고서의 소식'}</h3>
    <span className="feedback-category-label">{FEEDBACK_CATEGORIES.find(option => option.value === feedback.category)?.label ?? '남긴 의견'}</span>
    <p className="feedback-user-comment">{feedback.comment}</p>
    {feedback.diagnosis && <div className="feedback-diagnosis"><strong>검토 설명</strong><p>{feedback.diagnosis}</p></div>}
    {feedback.status === 'COMPLETED' && !feedback.diagnosis && <p className="feedback-section-description">검토를 마쳤습니다. 추가 설명이 제공되지 않았습니다.</p>}
    {feedback.status === 'FAILED' && <p className="feedback-failure-note">검토를 완료하지 못했습니다. 이 상태로 개인 기준을 새로 적용하지 않습니다. 남긴 의견은 보관됩니다.</p>}
  </li>
}

function PolicyItem({ policy, token, disabled, onPolicy, onRefresh }: {
  policy: PersonalPolicy; token: string; disabled: boolean
  onPolicy: (policy: PersonalPolicy) => void; onRefresh: () => void
}) {
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const controller = useRef<AbortController | null>(null)
  useEffect(() => () => controller.current?.abort(), [])
  async function revoke() {
    if (controller.current || disabled) return
    const active = new AbortController()
    controller.current = active
    setPending(true)
    setError(null)
    try {
      const result = await revokePersonalPolicy(token, policy, active.signal)
      if (!active.signal.aborted) onPolicy(result)
    } catch (failure) {
      if (!active.signal.aborted) {
        setError(feedbackError(failure))
        if (failure instanceof ApiError && (failure.status === 404 || failure.status === 409)) onRefresh()
      }
    } finally {
      if (!active.signal.aborted) { controller.current = null; setPending(false) }
    }
  }
  return <li className={policy.status === 'REVOKED' ? 'feedback-policy is-revoked' : 'feedback-policy'}>
    <div><span className="feedback-policy-topic">{policy.topicName} · {policy.status === 'ACTIVE' ? '적용 중' : '적용 중지됨'}</span><p>{policy.instruction}</p>
      <small>{formatFullDate(policy.createdAt)}에 관심 의견을 검토해 만든 기준</small></div>
    {policy.status === 'ACTIVE' && <button className="secondary-button" type="button" disabled={disabled || pending}
      aria-label={`${policy.topicName} 개인 기준 적용 중지`} onClick={() => { void revoke() }}>{pending ? '중지 중…' : '적용 중지'}</button>}
    {error && <p className="feedback-form-error" role="alert">{error}</p>}
  </li>
}
