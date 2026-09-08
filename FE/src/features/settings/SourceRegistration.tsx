import { useEffect, useId, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { useCreateSource } from '../../api/queries'
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
  const [open, setOpen] = useState(false)
  const id = useId()

  return <div className="source-registration-entry" id="source">
    <button type="button" className="text-button source-registration-trigger"
      aria-haspopup="dialog" aria-expanded={open} aria-controls={open ? id : undefined}
      onClick={() => setOpen(true)}>
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
        strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="m9 5 7 7-7 7" /></svg>
      RSS 피드 등록
    </button>
    {open && createPortal(<SourceDialog id={id} onDismiss={() => setOpen(false)} />, document.body)}
  </div>
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
      onClick={event => {
        if (event.target !== event.currentTarget) return
        const bounds = event.currentTarget.getBoundingClientRect()
        if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) dismiss()
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
