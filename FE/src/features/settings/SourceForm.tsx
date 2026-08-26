import { useState } from 'react'
import { useCreateSource } from '../../api/queries'
import { FormStatus } from './FormStatus'

const EMPTY = {
  name: '',
  urlTemplate: '',
  country: 'KR',
  language: 'ko',
}

export function SourceForm() {
  const [form, setForm] = useState(EMPTY)
  const [done, setDone] = useState<string | null>(null)
  const { mutate, isPending, error, reset } = useCreateSource()

  function update<K extends keyof typeof EMPTY>(key: K, value: (typeof EMPTY)[K]) {
    setForm((prev) => ({ ...prev, [key]: value }))
    setDone(null)
    reset()
  }

  function submit(event: React.FormEvent) {
    event.preventDefault()
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
        <label htmlFor="source-name">소스명</label>
        <input
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

      <button type="submit" disabled={isPending}>
        {isPending ? '등록 중…' : 'RSS 피드 등록'}
      </button>
      <FormStatus error={error} successMessage={done} />
    </form>
  )
}
