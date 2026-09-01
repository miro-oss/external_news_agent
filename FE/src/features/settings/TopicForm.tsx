import { useState } from 'react'
import { useCreateTopic, useSources } from '../../api/queries'
import { FormStatus } from './FormStatus'

const EMPTY = {
  name: '',
  queryText: '',
  requiredKeywords: '',
  optionalKeywords: '',
  excludedKeywords: '',
  intervalMinutes: '1440',
}

const COLLECTION_INTERVALS = [
  { value: '60', label: '1시간마다' },
  { value: '360', label: '6시간마다' },
  { value: '720', label: '12시간마다' },
  { value: '1440', label: '매일 한 번 (권장)' },
] as const

/** 쉼표로 나눠 받는다. 빈 칸은 필터 없음이고, 빈 문자열은 필터에 넣지 않는다. */
function toKeywords(value: string): string[] | undefined {
  const items = value
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean)
  return items.length > 0 ? items : undefined
}

export function TopicForm() {
  const [form, setForm] = useState(EMPTY)
  const [done, setDone] = useState<string | null>(null)
  const [queryTouched, setQueryTouched] = useState(false)
  const sources = useSources()
  const { mutate, isPending, error, reset } = useCreateTopic()

  const activeSources = sources.data?.content ?? []
  /** SEARCH 소스를 연결하면 검색어가 필수다(TOPIC400). 보내기 전에 화면에서 먼저 알려 준다. */
  const needsQueryText = activeSources.some((source) => source.sourceKind === 'SEARCH')
  /**
   * 필수라는 사실을 라벨에 길게 적어 두는 대신 한 번 만졌다가 비운 순간에만 말한다. 아직
   * 손대지 않은 칸에 빨간 글씨를 띄우면 잘못한 것이 없는데 혼나는 것처럼 읽힌다.
   */
  const queryMissing = needsQueryText && queryTouched && form.queryText.trim().length === 0

  function update<K extends keyof typeof EMPTY>(key: K, value: string) {
    setForm((prev) => ({ ...prev, [key]: value }))
    setDone(null)
    reset()
  }

  function submit(event: React.FormEvent) {
    event.preventDefault()
    const intervalMinutes = Number(form.intervalMinutes)

    mutate(
      {
        name: form.name.trim(),
        queryText: form.queryText.trim() || undefined,
        requiredKeywords: toKeywords(form.requiredKeywords),
        optionalKeywords: toKeywords(form.optionalKeywords),
        excludedKeywords: toKeywords(form.excludedKeywords),
        intervalMinutes,
        sourceIds: activeSources.map((source) => source.id),
      },
      {
        onSuccess: (created) => {
          setForm(EMPTY)
          setQueryTouched(false)
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
          검색어{needsQueryText && <span className="required"> 필수</span>}
        </label>
        <input
          id="topic-query"
          value={form.queryText}
          onChange={(event) => update('queryText', event.target.value)}
          onBlur={() => setQueryTouched(true)}
          placeholder="HBM 반도체"
          maxLength={500}
          required={needsQueryText}
          pattern={needsQueryText ? '.*\\S.*' : undefined}
          title="검색어에는 공백이 아닌 문자를 입력하세요."
          aria-invalid={queryMissing || undefined}
          aria-describedby="topic-query-hint"
        />
        {queryMissing
          ? <p className="error" id="topic-query-hint">검색어를 입력해 주세요.</p>
          : <p className="hint" id="topic-query-hint">검색 소스에 넘길 질의어입니다.</p>}
      </div>

      <div className="field">
        <label htmlFor="topic-required">필수 키워드</label>
        <input
          id="topic-required"
          value={form.requiredKeywords}
          onChange={(event) => update('requiredKeywords', event.target.value)}
          placeholder="HBM"
        />
        <p className="hint">쉼표로 구분합니다. 모두 포함된 기사만 통과합니다(AND).</p>
      </div>

      <div className="field">
        <label htmlFor="topic-optional">선택 키워드</label>
        <input
          id="topic-optional"
          value={form.optionalKeywords}
          onChange={(event) => update('optionalKeywords', event.target.value)}
          placeholder="SK하이닉스, 삼성전자, 마이크론"
        />
        <p className="hint">하나라도 포함되면 통과합니다(OR).</p>
      </div>

      <div className="field">
        <label htmlFor="topic-excluded">제외 키워드</label>
        <input
          id="topic-excluded"
          value={form.excludedKeywords}
          onChange={(event) => update('excludedKeywords', event.target.value)}
          placeholder="광고, 채용"
        />
        <p className="hint">하나라도 포함되면 제외합니다(NOT).</p>
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
          현재는 자동 반복 없이 <strong>지금 실행</strong>을 눌렀을 때만 수집합니다.
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
      <FormStatus error={error} successMessage={done} />
    </form>
  )
}
