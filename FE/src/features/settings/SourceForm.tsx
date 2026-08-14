import { useState } from 'react'
import { useCreateSource } from '../../api/queries'
import { SEARCH_PROVIDERS, type SourceKind } from '../../api/types'
import { FormStatus } from './FormStatus'

const EMPTY = {
  sourceKind: 'FEED' as SourceKind,
  name: '',
  urlTemplate: '',
  country: 'KR',
  language: 'ko',
}

export function SourceForm() {
  const [form, setForm] = useState(EMPTY)
  const [done, setDone] = useState<string | null>(null)
  const { mutate, isPending, error, reset } = useCreateSource()

  const isSearch = form.sourceKind === 'SEARCH'

  function update<K extends keyof typeof EMPTY>(key: K, value: (typeof EMPTY)[K]) {
    setForm((prev) => ({ ...prev, [key]: value }))
    setDone(null)
    reset()
  }

  /**
   * 종류를 바꾸면 URL 칸을 비운다. FEED의 URL이 SEARCH 칸에 남아 있으면 provider 키가 아니라서
   * 서버가 SOURCE400으로 거절하는데, 사용자는 자기가 지운 줄 알았던 값 때문에 막힌 것을 모른다.
   */
  function changeKind(sourceKind: SourceKind) {
    setForm((prev) => ({ ...prev, sourceKind, urlTemplate: '' }))
    setDone(null)
    reset()
  }

  function submit(event: React.FormEvent) {
    event.preventDefault()
    mutate(
      {
        sourceKind: form.sourceKind,
        name: form.name.trim(),
        urlTemplate: form.urlTemplate.trim(),
        country: form.country.trim() || undefined,
        language: form.language.trim() || undefined,
      },
      {
        onSuccess: (created) => {
          setForm(EMPTY)
          setDone(`"${created.name}"을(를) 등록했습니다.`)
        },
      },
    )
  }

  return (
    <form onSubmit={submit}>
      <div className="field">
        <label htmlFor="source-kind">소스 종류</label>
        <select
          id="source-kind"
          value={form.sourceKind}
          onChange={(event) => changeKind(event.target.value as SourceKind)}
        >
          <option value="FEED">FEED — 고정 RSS 주소</option>
          <option value="SEARCH">SEARCH — 검색 provider</option>
        </select>
      </div>

      <div className="field">
        <label htmlFor="source-name">소스명</label>
        <input
          id="source-name"
          value={form.name}
          onChange={(event) => update('name', event.target.value)}
          placeholder={isSearch ? 'Naver 뉴스 검색' : 'ETNews 반도체'}
          maxLength={200}
          required
        />
      </div>

      <div className="field">
        <label htmlFor="source-url">{isSearch ? 'Provider' : '피드 URL'}</label>
        {/* SEARCH는 자유 입력이 아니다. 셋은 인증 방식이 달라 URL 하나로 표현되지 않아 키로 고른다. */}
        {isSearch ? (
          <select
            id="source-url"
            value={form.urlTemplate}
            onChange={(event) => update('urlTemplate', event.target.value)}
            required
          >
            <option value="">선택하세요</option>
            {SEARCH_PROVIDERS.map((provider) => (
              <option key={provider} value={provider}>
                {provider}
              </option>
            ))}
          </select>
        ) : (
          <input
            id="source-url"
            type="url"
            value={form.urlTemplate}
            onChange={(event) => update('urlTemplate', event.target.value)}
            placeholder="https://rss.etnews.com/Section902.xml"
            maxLength={1000}
            required
          />
        )}
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

      <button type="submit" disabled={isPending}>
        {isPending ? '등록 중…' : '소스 등록'}
      </button>
      <FormStatus error={error} successMessage={done} />
    </form>
  )
}
