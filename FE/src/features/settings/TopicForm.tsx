import { useState } from 'react'
import { ApiError } from '../../api/client'
import { useCreateTopic, useSources } from '../../api/queries'
import { FormStatus } from './FormStatus'

const EMPTY = {
  name: '',
  queryText: '',
  requiredKeywords: '',
  optionalKeywords: '',
  excludedKeywords: '',
  batchSize: '10',
  intervalMinutes: '60',
}

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
  const [selected, setSelected] = useState<number[]>([])
  const [done, setDone] = useState<string | null>(null)
  const sources = useSources()
  const { mutate, isPending, error, reset } = useCreateTopic()

  const options = sources.data?.content ?? []
  const sourceError =
    sources.error instanceof ApiError
      ? `${sources.error.message} (${sources.error.code})`
      : '소스 목록을 불러오지 못했습니다.'
  /** SEARCH 소스를 연결하면 검색어가 필수다(TOPIC400). 보내기 전에 화면에서 먼저 알려 준다. */
  const needsQueryText = options.some(
    (source) => selected.includes(source.id) && source.sourceKind === 'SEARCH',
  )
  const allSelected = options.length > 0 && options.every((source) => selected.includes(source.id))

  function update<K extends keyof typeof EMPTY>(key: K, value: string) {
    setForm((prev) => ({ ...prev, [key]: value }))
    setDone(null)
    reset()
  }

  function toggleSource(id: number) {
    setSelected((prev) => (prev.includes(id) ? prev.filter((item) => item !== id) : [...prev, id]))
    setDone(null)
    reset()
  }

  /** 소스가 열 몇 개씩 쌓이면 하나씩 체크하는 게 일이다. 한 번에 다 켜고 끄는 길을 둔다. */
  function toggleAllSources() {
    setSelected((prev) => (prev.length === options.length ? [] : options.map((source) => source.id)))
    setDone(null)
    reset()
  }

  function submit(event: React.FormEvent) {
    event.preventDefault()
    const batchSize = form.batchSize.trim() ? Number(form.batchSize) : undefined
    const intervalMinutes = form.intervalMinutes.trim() ? Number(form.intervalMinutes) : undefined

    mutate(
      {
        name: form.name.trim(),
        queryText: form.queryText.trim() || undefined,
        requiredKeywords: toKeywords(form.requiredKeywords),
        optionalKeywords: toKeywords(form.optionalKeywords),
        excludedKeywords: toKeywords(form.excludedKeywords),
        batchSize,
        intervalMinutes,
        // 아무것도 고르지 않았으면 필드를 아예 빼서 보낸다. 명세가 "누락"과 "빈 배열"을 다르게 보므로
        // 빈 배열을 보내면 "연결 없음"이 아니라 "전체 해제"라는 다른 뜻이 된다.
        sourceIds: selected.length > 0 ? selected : undefined,
      },
      {
        onSuccess: (created) => {
          setForm(EMPTY)
          setSelected([])
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
          검색어{needsQueryText && <span className="required"> — SEARCH 소스를 골랐으므로 필수</span>}
        </label>
        <input
          id="topic-query"
          value={form.queryText}
          onChange={(event) => update('queryText', event.target.value)}
          placeholder="HBM 반도체"
          maxLength={500}
          required={needsQueryText}
          pattern={needsQueryText ? '.*\\S.*' : undefined}
          title="검색어에는 공백이 아닌 문자를 입력하세요."
        />
        <p className="hint">SEARCH 소스에 넘길 질의어입니다. FEED 소스만 연결한다면 비워 두어도 됩니다.</p>
      </div>

      <fieldset className="field">
        {/* legend는 fieldset의 첫 자식이어야 이름 역할을 한다. div로 감싸지 않고 안에서 배치한다. */}
        <legend className="checklist-legend">
          <span>
            연결할 소스
            {selected.length > 0 && <em>{selected.length}개 선택</em>}
          </span>
          {options.length > 0 && (
            <button type="button" className="link-button" onClick={toggleAllSources}>
              {allSelected ? '전체 해제' : '전체 선택'}
            </button>
          )}
        </legend>
        {sources.isPending && <p className="muted">소스를 불러오는 중…</p>}
        {sources.error && <p className="error">{sourceError}</p>}
        {!sources.isPending && options.length === 0 && (
          <p className="muted">등록된 소스가 없습니다. 먼저 소스를 등록하세요.</p>
        )}
        <div className="checklist">
          {options.map((source) => (
            <label key={source.id} className="check">
              <input
                type="checkbox"
                checked={selected.includes(source.id)}
                onChange={() => toggleSource(source.id)}
              />
              <span>{source.name}</span>
              <span className={`badge badge-${source.sourceKind.toLowerCase()}`}>{source.sourceKind}</span>
            </label>
          ))}
        </div>
        <p className="hint">고르지 않으면 주제만 만들어지고, 나중에 소스를 연결할 수 있습니다.</p>
      </fieldset>

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
        <label htmlFor="topic-batch">1회 수집 건수</label>
        <input
          id="topic-batch"
          type="number"
          min={1}
          max={100}
          value={form.batchSize}
          onChange={(event) => update('batchSize', event.target.value)}
        />
        <p className="hint">
          한 번 수집할 때 소스 하나에서 가져올 기사 수입니다. 소스 3개에 10이면 최대 30건입니다.
          검색 소스에만 적용되고, 피드 소스는 피드에 올라온 만큼 가져옵니다. 1~100.
        </p>
      </div>

      <div className="field">
        <label htmlFor="topic-interval">수집 주기(분)</label>
        <input
          id="topic-interval"
          type="number"
          min={10}
          value={form.intervalMinutes}
          onChange={(event) => update('intervalMinutes', event.target.value)}
        />
        <p className="hint">
          이 주제를 다시 수집하기까지 기다릴 시간입니다. 60이면 한 시간에 한 번입니다. 최소 10분.
          지금은 자동 반복 없이, 위 <strong>지금 실행</strong>을 눌렀을 때만 수집합니다.
        </p>
      </div>

      <button type="submit" disabled={isPending}>
        {isPending ? '등록 중…' : '주제 등록'}
      </button>
      <FormStatus error={error} successMessage={done} />
    </form>
  )
}
