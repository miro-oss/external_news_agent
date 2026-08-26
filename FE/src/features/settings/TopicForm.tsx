import { useMemo, useState } from 'react'
import { ApiError } from '../../api/client'
import { useCreateTopic, useSources } from '../../api/queries'
import type { Source } from '../../api/types'
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

/**
 * 목록에서 소스가 설 자리. 작을수록 위다.
 *
 * <p>검색 provider는 기본으로 깔려 있어서 주제를 만들 때 거의 항상 고르는 소스이고, RSS 피드는
 * 필요한 사람이 하나씩 더해 가는 목록이다. 서버가 주는 순서(등록순)를 그대로 쓰면 기본 제공인
 * 검색 provider가 나중에 등록된 피드에 밀려 목록 아래로 내려간다. 스크롤해야 보이는 자리에 제일
 * 자주 고르는 것이 있는 셈이다.
 */
const SOURCE_KIND_ORDER: Record<Source['sourceKind'], number> = {
  SEARCH: 0,
  FEED: 1,
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

  /*
    검색 provider를 위로 올린다. 종류만 비교하고 그 안은 건드리지 않는다 — Array.prototype.sort는
    안정 정렬이라 같은 종류끼리는 서버가 준 순서(등록순)가 그대로 남는다. 이름순으로 다시 세우면
    같은 종류 안에서 자리가 바뀌어, 어제 본 위치를 기억하고 찾는 사람이 헤맨다.

    화면에 그릴 때만 바꾸는 순서다. 서버로 보내는 sourceIds는 사용자가 고른 순서(selected)를
    그대로 쓰므로 이 정렬에 영향을 받지 않는다.
  */
  const options = useMemo(
    () => [...(sources.data?.content ?? [])].sort(
      (left, right) => SOURCE_KIND_ORDER[left.sourceKind] - SOURCE_KIND_ORDER[right.sourceKind],
    ),
    [sources.data],
  )
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

  /**
   * 소스가 열 몇 개씩 쌓이면 하나씩 체크하는 게 일이다. 한 번에 다 켜고 끄는 길을 둔다.
   *
   * <p>개수가 아니라 목록에 든 소스가 전부 골라졌는지로 판단한다. 개수만 보면, 고른 소스가
   * 지워지고 다른 소스가 대신 생겨 수가 같아졌을 때 버튼은 "전체 선택"인데 누르면 비워진다.
   * 버튼 글자를 정하는 allSelected와 같은 기준을 써야 둘이 어긋나지 않는다.
   */
  function toggleAllSources() {
    setSelected((prev) => (
      options.length > 0 && options.every((source) => prev.includes(source.id))
        ? []
        : options.map((source) => source.id)
    ))
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
          <span>연결할 소스</span>
          {options.length > 0 && (
            <button type="button" className="chip-button" onClick={toggleAllSources}>
              {allSelected ? '전체 해제' : '전체 선택'}
            </button>
          )}
          {selected.length > 0 && <em>{selected.length}개 선택</em>}
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

      <button type="submit" disabled={isPending}>
        {isPending ? '등록 중…' : '주제 등록'}
      </button>
      <FormStatus error={error} successMessage={done} />
    </form>
  )
}
