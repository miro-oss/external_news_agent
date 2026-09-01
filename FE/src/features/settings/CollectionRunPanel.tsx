import { useRef, useState } from 'react'
import {
  useAudienceSetting,
  useCombinations,
  useLlmPlan,
  useStartCollectionRun,
  useUpdateAudienceSetting,
} from '../../api/queries'
import {
  AUDIENCES,
  AUDIENCE_LABELS,
  type Audience,
  type LlmPlan,
} from '../../api/types'
import { MutationStatus } from './MutationStatus'

type RunScope = 'SELECTED' | 'ALL'

export function CollectionRunPanel() {
  const combinations = useCombinations()
  const planQuery = useLlmPlan()
  const startRun = useStartCollectionRun()
  const [scope, setScope] = useState<RunScope>('SELECTED')
  const [selectedTopicId, setSelectedTopicId] = useState<number | null>(null)
  const [runOverride, setRunOverride] = useState<'DEFAULT' | LlmPlan>('DEFAULT')
  const pendingRunKey = useRef<string | null>(null)

  if (combinations.isPending) {
    return <section className="collection-run-panel state-panel">수집 실행 정보를 불러오는 중입니다.</section>
  }
  if (combinations.error || !combinations.data) {
    return (
      <section className="collection-run-panel state-panel error" role="alert">
        수집 실행 정보를 불러오지 못했습니다.
      </section>
    )
  }

  const activeCombinations = combinations.data.content.filter((combination) => combination.active)
  const activeTopics = Array.from(
    new Map(activeCombinations.map((combination) => [
      combination.topicId,
      { id: combination.topicId, name: combination.topicName },
    ])).values(),
  ).sort((left, right) => left.name.localeCompare(right.name, 'ko'))
  const effectiveTopic = activeTopics.find((topic) => topic.id === selectedTopicId) ?? activeTopics[0]
  const targetCombinations = scope === 'ALL'
    ? activeCombinations
    : activeCombinations.filter((combination) => combination.topicId === effectiveTopic?.id)
  const setting = planQuery.data

  function resetRequestState() {
    pendingRunKey.current = null
    startRun.reset()
  }

  function changeScope(nextScope: RunScope) {
    resetRequestState()
    setScope(nextScope)
  }

  function changeTopic(topicId: number) {
    resetRequestState()
    setSelectedTopicId(topicId)
  }

  function changeRunOverride(value: 'DEFAULT' | LlmPlan) {
    resetRequestState()
    setRunOverride(value)
  }

  function runNow() {
    if (scope === 'SELECTED' && !effectiveTopic) return
    const idempotencyKey = pendingRunKey.current ?? `manual-${crypto.randomUUID()}`
    pendingRunKey.current = idempotencyKey
    startRun.mutate(
      {
        idempotencyKey,
        ...(scope === 'SELECTED' ? { topicIds: [effectiveTopic.id] } : {}),
        ...(runOverride === 'DEFAULT' ? {} : { plan: runOverride }),
      },
      { onSuccess: () => { pendingRunKey.current = null } },
    )
  }

  const canRun = targetCombinations.length > 0 && !startRun.isPending

  return (
    <section className="collection-run-panel" aria-labelledby="collection-run-title">
      <div className="collection-run-heading">
        <div>
          <h2 id="collection-run-title">수집 실행</h2>
          <p className="muted">기본은 주제 하나만 실행합니다. 실행 전에 대상 규모를 확인해 주세요.</p>
        </div>
        <span className="run-default-badge">선택 주제 기본</span>
      </div>

      <div className="collection-run-controls">
        <div className="field">
          <label htmlFor="run-scope">실행 범위</label>
          <select
            id="run-scope"
            value={scope}
            disabled={startRun.isPending}
            onChange={(event) => changeScope(event.target.value as RunScope)}
          >
            <option value="SELECTED">선택 주제</option>
            <option value="ALL">모든 활성 주제</option>
          </select>
        </div>

        <div className="field">
          <label htmlFor="run-topic">수집할 주제</label>
          <select
            id="run-topic"
            value={effectiveTopic?.id ?? ''}
            disabled={scope === 'ALL' || activeTopics.length === 0 || startRun.isPending}
            onChange={(event) => changeTopic(Number(event.target.value))}
          >
            {activeTopics.length === 0 && <option value="">활성 주제 없음</option>}
            {activeTopics.map((topic) => (
              <option value={topic.id} key={topic.id}>{topic.name}</option>
            ))}
          </select>
        </div>

        {setting?.allowRunOverride && (
          <div className="field">
            <label htmlFor="run-plan">이번 실행 플랜</label>
            <select
              id="run-plan"
              value={runOverride}
              disabled={startRun.isPending}
              onChange={(event) => changeRunOverride(event.target.value as 'DEFAULT' | LlmPlan)}
            >
              <option value="DEFAULT">기본 설정 사용 ({setting.plan})</option>
              <option value="FREE">이번 실행만 FREE</option>
              <option value="PAID">이번 실행만 PAID</option>
            </select>
          </div>
        )}
      </div>

      <DefaultAudienceSetting />

      <button className="collection-run-button" type="button" onClick={runNow} disabled={!canRun}>
        {startRun.isPending
          ? '실행 요청 중…'
          : scope === 'ALL' ? '모든 활성 주제 수집' : '선택 주제 수집'}
      </button>
      <MutationStatus
        error={startRun.error}
        success={startRun.data
          ? `실행 #${startRun.data.runId}을 ${startRun.data.llmPlan} 플랜으로 시작했습니다.`
          : null}
      />
    </section>
  )
}

function DefaultAudienceSetting() {
  const audienceQuery = useAudienceSetting()
  const updateAudience = useUpdateAudienceSetting()
  const [saved, setSaved] = useState(false)

  function saveAudience(event: React.FormEvent) {
    event.preventDefault()
    setSaved(false)
    const values = new FormData(event.currentTarget as HTMLFormElement)
    updateAudience.mutate(
      values.get('audience') as Audience,
      { onSuccess: () => setSaved(true) },
    )
  }

  if (audienceQuery.isPending) {
    return <div className="run-audience-setting state-panel">기본 관점을 불러오는 중입니다.</div>
  }
  if (audienceQuery.error || !audienceQuery.data) {
    return (
      <div className="run-audience-setting state-panel error" role="alert">
        기본 관점 설정을 불러오지 못했습니다.
      </div>
    )
  }

  const audience = audienceQuery.data.audience
  return (
    <form key={audience} className="run-audience-setting" onSubmit={saveAudience}>
      <div className="run-audience-copy">
        <h3>내 기본 관점</h3>
        <p>수집 결과의 기사와 리포트를 처음 볼 때 적용할 관점입니다.</p>
      </div>
      <div className="field">
        <label htmlFor="default-audience">기본 관점</label>
        <select id="default-audience" name="audience" defaultValue={audience}>
          {AUDIENCES.map((value) => (
            <option value={value} key={value}>{AUDIENCE_LABELS[value]}</option>
          ))}
        </select>
      </div>
      <button type="submit" disabled={updateAudience.isPending}>
        {updateAudience.isPending ? '저장 중…' : '기본 관점 저장'}
      </button>
      <MutationStatus
        error={updateAudience.error}
        success={saved ? '기본 관점을 저장했습니다.' : null}
      />
    </form>
  )
}
