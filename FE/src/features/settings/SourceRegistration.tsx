import { useEffect, useId, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { ApiError } from '../../api/client'
import { useCreateSource, useSources } from '../../api/queries'
import { FormStatus } from './FormStatus'
import '../notifications/notifications-refinement.css'
import './source-registration.css'

const EMPTY = {
  name: '',
  urlTemplate: '',
  country: 'KR',
  language: 'ko',
}

export function SourceRegistration() {
  const [open, setOpen] = useState<'register' | 'feeds' | null>(null)
  const id = useId()

  return <div className="source-registration-entry" id="source">
    <button type="button" className="text-button source-registration-trigger"
      aria-haspopup="dialog" aria-expanded={open === 'register'} aria-controls={open === 'register' ? `${id}-register` : undefined}
      onClick={() => setOpen('register')}>
      <SourceEntryChevron /> RSS 피드 등록
    </button>
    <button type="button" className="text-button source-registration-trigger"
      aria-haspopup="dialog" aria-expanded={open === 'feeds'} aria-controls={open === 'feeds' ? `${id}-feeds` : undefined}
      onClick={() => setOpen('feeds')}>
      <SourceEntryChevron /> 활성 RSS 피드 보기
    </button>
    {open === 'register' && createPortal(<SourceDialog id={`${id}-register`} onDismiss={() => setOpen(null)} />, document.body)}
    {open === 'feeds' && createPortal(<SourceFeedDialog id={`${id}-feeds`} onDismiss={() => setOpen(null)} />, document.body)}
  </div>
}

function SourceEntryChevron() {
  return <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
    strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="m9 5 7 7-7 7" /></svg>
}

function SourceFeedDialog({ id, onDismiss }: { id: string; onDismiss: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null)
  const closeButton = useRef<HTMLButtonElement>(null)
  const sources = useSources()
  const feeds = sources.data?.content.filter(source => source.active && source.sourceKind === 'FEED') ?? []

  useEffect(() => {
    const element = dialog.current
    const previousFocus = document.activeElement
    element?.showModal()
    closeButton.current?.focus()
    document.body.classList.add('modal-open')
    return () => {
      element?.close()
      document.body.classList.remove('modal-open')
      if (previousFocus instanceof HTMLElement && previousFocus.isConnected) previousFocus.focus()
    }
  }, [])

  return <dialog ref={dialog} id={id} className="recipient-selection-dialog source-feed-dialog"
    aria-labelledby={`${id}-title`} aria-describedby={`${id}-description`}
    onCancel={event => { event.preventDefault(); onDismiss() }}
    onClick={event => {
      if (event.target !== event.currentTarget) return
      const bounds = event.currentTarget.getBoundingClientRect()
      if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) onDismiss()
    }}>
    <header className="recipient-selection-heading source-feed-heading">
      <div>
        <h2 id={`${id}-title`}>활성 RSS 피드
          {sources.data && !sources.error && <span className="collapsible-count">{feeds.length}개</span>}
        </h2>
        <p id={`${id}-description`}>현재 활성화된 RSS 피드 목록입니다.</p>
      </div>
      <button ref={closeButton} type="button" className="text-button recipient-selection-close"
        aria-label="활성 RSS 피드 닫기" onClick={onDismiss}>×</button>
    </header>
    <div className="source-feed-body" role="region" aria-label="활성 RSS 피드 목록" tabIndex={0}>
    {sources.isPending ? <p className="source-feed-status muted" role="status">활성 RSS 피드를 불러오고 있습니다.</p>
      : sources.error ? <div className="source-feed-status">
        <p className="error" role="alert">{sources.error instanceof ApiError ? sources.error.message : '활성 RSS 피드를 불러오지 못했습니다.'}</p>
        <button type="button" className="secondary-button" disabled={sources.isFetching}
          onClick={() => { void sources.refetch() }}>{sources.isFetching ? '다시 불러오는 중…' : '다시 시도'}</button>
      </div>
      : <>
        {sources.isFetching && <p className="muted source-feed-status" role="status">목록을 새로고침하고 있습니다.</p>}
        {feeds.length === 0 ? <p className="source-feed-status muted">활성화된 RSS 피드가 없습니다. RSS 피드를 등록해 주세요.</p>
          : <ul className="source-feed-list">
              {feeds.map(source => <li key={source.id} className="source-feed-item">
                <div className="source-feed-info">
                  <strong>{source.name}</strong>
                  {/^https?:\/\//i.test(source.urlTemplate)
                    ? <a href={source.urlTemplate} target="_blank" rel="noopener noreferrer"
                      aria-label={`${source.name} 피드 열기 (새 탭)`}>{source.urlTemplate}</a>
                    : <span className="source-feed-url">{source.urlTemplate}</span>}
                </div>
                <dl className="source-feed-meta">
                  <div><dt>국가</dt><dd>{source.country || '미지정'}</dd></div>
                  <div><dt>언어</dt><dd>{source.language || '미지정'}</dd></div>
                </dl>
              </li>)}
            </ul>}
      </>}
    </div>
    <footer className="recipient-selection-actions">
      <button type="button" className="secondary-button" disabled={sources.isFetching}
        onClick={() => { void sources.refetch() }}>새로고침</button>
      <button type="button" onClick={onDismiss}>닫기</button>
    </footer>
  </dialog>
}

function SourceDialog({ id, onDismiss }: { id: string; onDismiss: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null)
  const nameInput = useRef<HTMLInputElement>(null)
  const [form, setForm] = useState(EMPTY)
  const { mutate, isPending, error, reset } = useCreateSource()

  useEffect(() => {
    const element = dialog.current
    const previousFocus = document.activeElement
    element?.showModal()
    nameInput.current?.focus()
    document.body.classList.add('modal-open')
    return () => {
      element?.close()
      document.body.classList.remove('modal-open')
      if (previousFocus instanceof HTMLElement && previousFocus.isConnected) previousFocus.focus()
    }
  }, [])

  function dismiss() {
    if (!isPending) onDismiss()
  }

  function update<K extends keyof typeof EMPTY>(key: K, value: (typeof EMPTY)[K]) {
    if (isPending) return
    setForm((prev) => ({ ...prev, [key]: value }))
    reset()
  }

  function submit(event: React.FormEvent) {
    event.preventDefault()
    if (isPending) return
    mutate(
      {
        // 검색 provider는 서버가 기본 소스로 시드한다. 이 폼은 사용자가 추가할 RSS만 등록한다.
        sourceKind: 'FEED',
        name: form.name.trim(),
        urlTemplate: form.urlTemplate.trim(),
        country: form.country.trim() || undefined,
        language: form.language.trim() || undefined,
      },
      {
        onSuccess: onDismiss,
      },
    )
  }

  return (
    <dialog ref={dialog} id={id} className="recipient-selection-dialog source-registration-dialog"
      aria-labelledby={`${id}-title`} aria-describedby={`${id}-description`}
      onCancel={event => { event.preventDefault(); dismiss() }}
      onMouseDown={event => {
        if (event.button !== 0 || event.target !== event.currentTarget) return
        const bounds = event.currentTarget.getBoundingClientRect()
        if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) {
          event.preventDefault()
          dismiss()
        }
      }}>
      <header className="recipient-selection-heading">
        <div><h2 id={`${id}-title`}>RSS 피드 등록</h2><p id={`${id}-description`}>추가로 수집할 RSS 피드 주소를 입력해 주세요.</p></div>
        <button type="button" className="text-button recipient-selection-close" aria-label="RSS 피드 등록 닫기"
          disabled={isPending} onClick={dismiss}>×</button>
      </header>
      <form onSubmit={submit} className="source-registration-form" aria-busy={isPending}>
        <div className="source-registration-body">
          <fieldset disabled={isPending}>
            <div className="field">
              <label htmlFor="source-name">소스명</label>
              <input
                ref={nameInput}
                id="source-name"
                value={form.name}
                onChange={(event) => update('name', event.target.value)}
                placeholder="ETNews 반도체"
                maxLength={200}
                required
              />
            </div>

            <div className="field">
              <label htmlFor="source-url">피드 URL</label>
              <input
                id="source-url"
                type="url"
                value={form.urlTemplate}
                onChange={(event) => update('urlTemplate', event.target.value)}
                placeholder="https://rss.etnews.com/Section902.xml"
                maxLength={1000}
                required
              />
            </div>

            <div className="field-row">
              <div className="field">
                <label htmlFor="source-country">국가</label>
                <input
                  id="source-country"
                  value={form.country}
                  onChange={(event) => update('country', event.target.value)}
                  maxLength={2}
                  placeholder="KR"
                />
              </div>
              <div className="field">
                <label htmlFor="source-language">언어</label>
                <input
                  id="source-language"
                  value={form.language}
                  onChange={(event) => update('language', event.target.value)}
                  maxLength={5}
                  placeholder="ko"
                />
              </div>
            </div>
          </fieldset>
          <FormStatus error={error} successMessage={null} />
        </div>
        <footer className="recipient-selection-actions">
          <button type="button" className="secondary-button" disabled={isPending} onClick={dismiss}>취소</button>
          <button type="submit" disabled={isPending}>
            {isPending ? '등록 중…' : '등록'}
          </button>
        </footer>
      </form>
    </dialog>
  )
}
