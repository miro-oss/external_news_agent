import { useState } from 'react'
import { useCreateTopic, useSources } from '../../api/queries'
import { FormStatus } from './FormStatus'
import { baseTopicKeywords, buildTopicKeywordInput } from './topicKeywordInput'

const EMPTY = {
  name: '',
  queryText: '',
  requiredKeywords: null as string | null,
  optionalKeywords: '',
  excludedKeywords: '',
  intervalMinutes: '1440',
}

const COLLECTION_INTERVALS = [
  { value: '60', label: '1시간마다' },
  { value: '720', label: '12시간마다' },
  { value: '1440', label: '24시간마다' },
] as const

export function TopicForm() {
  const [form, setForm] = useState(EMPTY)
  const [done, setDone] = useState<string | null>(null)
  const [queryTouched, setQueryTouched] = useState(false)
  const [conditionsOpen, setConditionsOpen] = useState(false)
  const [validation, setValidation] = useState<string | null>(null)
  const sources = useSources()
  const { mutate, isPending, error, reset } = useCreateTopic()

  const activeSources = sources.data?.content ?? []
  const baseKeywords = baseTopicKeywords(form.queryText)
  const keywordInput = buildTopicKeywordInput(form)
  const hasFeedSource = activeSources.some((source) => source.sourceKind === 'FEED')
  /**
   * 필수라는 사실을 라벨에 길게 적어 두는 대신 한 번 만졌다가 비운 순간에만 말한다. 아직
   * 손대지 않은 칸에 빨간 글씨를 띄우면 잘못한 것이 없는데 혼나는 것처럼 읽힌다.
   */
  const queryMissing = queryTouched && baseKeywords.length === 0

  function update<K extends keyof typeof EMPTY>(key: K, value: string) {
    setForm((prev) => ({ ...prev, [key]: value }))
    setDone(null)
    reset()
    setValidation(null)
  }

  function submit(event: React.FormEvent) {
    event.preventDefault()
    const intervalMinutes = Number(form.intervalMinutes)
    if (baseKeywords.length === 0) {
      setQueryTouched(true)
      return
    }
    if (hasFeedSource && keywordInput.requiredKeywords.length === 0 && keywordInput.optionalKeywords.length === 0) {
      setValidation('RSS 기사도 주제에 맞게 모을 수 있도록 모두 포함 또는 하나 이상 포함 조건을 입력해 주세요.')
      setConditionsOpen(true)
      return
    }

    mutate(
      {
        name: form.name.trim(),
        ...keywordInput,
        intervalMinutes,
        sourceIds: activeSources.map((source) => source.id),
      },
      {
        onSuccess: (created) => {
          setForm(EMPTY)
          setQueryTouched(false)
          setConditionsOpen(false)
          setValidation(null)
          setDone(`"${created.name}"을(를) 등록했습니다.`)
        },
      },
    )
  }

  return (
    <form onSubmit={submit}>
      <div className="field">
        <label htmlFor="topic-name">주제명</label>
        <input
          id="topic-name"
          value={form.name}
          onChange={(event) => update('name', event.target.value)}
          placeholder="HBM"
          maxLength={200}
          required
        />
      </div>

      <div className="field">
        <label htmlFor="topic-query">
          검색 키워드
        </label>
        <input
          id="topic-query"
          value={form.queryText}
          onChange={(event) => update('queryText', event.target.value)}
          onBlur={() => setQueryTouched(true)}
          placeholder="HBM 반도체"
          maxLength={500}
          required
          pattern={'.*\\S.*'}
          title="검색 키워드를 입력하세요."
          aria-invalid={queryMissing || undefined}
          aria-describedby="topic-query-hint"
        />
        {queryMissing
          ? <p className="error" id="topic-query-hint">검색 키워드를 입력해 주세요.</p>
          : <p className="hint" id="topic-query-hint">공백이나 쉼표로 구분합니다. 기본적으로 입력한 키워드를 모두 포함한 기사를 모읍니다.</p>}
      </div>

      <div className="topic-advanced">
        <button type="button" className="secondary-button topic-advanced-toggle"
          aria-expanded={conditionsOpen} aria-controls="topic-advanced-conditions"
          onClick={() => setConditionsOpen((open) => !open)}>
          상세 기사 조건 {conditionsOpen ? '접기' : '설정'}
        </button>
        {conditionsOpen && (
          <div id="topic-advanced-conditions" className="topic-advanced-fields">
            <p className="hint">검색 결과와 RSS 기사의 제목·요약에 아래 조건을 적용합니다.</p>
            <div className="field">
              <label htmlFor="topic-required">모두 포함</label>
              <input id="topic-required" value={form.requiredKeywords ?? baseKeywords.join(', ')}
                onChange={(event) => update('requiredKeywords', event.target.value)} placeholder="HBM, 반도체" />
              <p className="hint">기본값은 검색 키워드입니다. 다른 조건이 필요하면 수정하거나 비울 수 있습니다.</p>
            </div>
            <div className="field">
              <label htmlFor="topic-optional">하나 이상 포함</label>
              <input id="topic-optional" value={form.optionalKeywords}
                onChange={(event) => update('optionalKeywords', event.target.value)} placeholder="SK하이닉스, 삼성전자, 마이크론" />
            </div>
            <div className="field">
              <label htmlFor="topic-excluded">제외</label>
              <input id="topic-excluded" value={form.excludedKeywords}
                onChange={(event) => update('excludedKeywords', event.target.value)} placeholder="광고, 채용" />
              <p className="hint">각 조건은 쉼표로 구분합니다. 제외 키워드가 들어간 기사는 모으지 않습니다.</p>
            </div>
          </div>
        )}
      </div>

      <div className="field">
        {/*
          #70에서는 이 두 칸이 뜻을 알 수 없다는 말에 숫자 입력 옆에 설명을 붙였는데, #76이
          아예 고를 것을 줄여 버렸다. 설명이 필요 없게 만든 쪽이 낫다 — 수집 건수는 화면에서
          빠졌고 주기는 정해진 보기 중에서 고른다. #76 것을 그대로 쓴다.
        */}
        <label htmlFor="topic-interval">수집 주기</label>
        <select
          id="topic-interval"
          value={form.intervalMinutes}
          onChange={(event) => update('intervalMinutes', event.target.value)}
        >
          {COLLECTION_INTERVALS.map((interval) => (
            <option key={interval.value} value={interval.value}>
              {interval.label}
            </option>
          ))}
        </select>
        <p className="hint">
          새로운 기사를 확인할 주기입니다. 수집 건수는 검색 결과와 중복 여부에 맞춰 시스템이 관리합니다.
        </p>
      </div>

      {sources.isPending && <p className="muted">활성 수집 소스를 확인하는 중…</p>}
      {sources.isError && <p className="error">활성 수집 소스를 불러오지 못했습니다.</p>}
      {!sources.isPending && !sources.isError && activeSources.length === 0 && (
        <p className="error topic-source-error">
          활성 수집 소스가 없습니다. 먼저 소스를 등록하거나 활성화하세요.
        </p>
      )}

      <button type="submit" disabled={isPending || sources.isPending || sources.isError || activeSources.length === 0}>
        {isPending ? '등록 중…' : '주제 등록'}
      </button>
      {validation && <p className="error" role="alert">{validation}</p>}
      <FormStatus error={error} successMessage={done} />
    </form>
  )
}
