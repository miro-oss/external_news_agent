import { useRef, useState } from 'react'
import { useCombinations, useLlmPlan, useStartCollectionRun } from '../../api/queries'
import type { LlmPlan } from '../../api/types'
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
  // 피드 소스는 발행된 만큼 들어와 실행 전에 규모를 셀 수 없다. 몇 곳이 그런지만 문장으로 알린다.
  const feedCount = targetCombinations.filter(
    (combination) => combination.sourceKind === 'FEED',
  ).length
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

      {/*
        기사 수 상한은 빼 둔다. 주제의 batchSize 합계라 사용자가 화면에서 바꿀 수 없고, 보고 나서
        할 수 있는 일이 없다. 실행 전에 정말 알아야 하는 건 몇 개 조합이 도는지뿐이다.
      */}
      <div className="collection-run-preview" aria-live="polite">
        <div>
          <span>실행 조합</span>
          <strong>{targetCombinations.length.toLocaleString()}개</strong>
        </div>
        <p>
          {scope === 'ALL'
            ? '모든 활성 주제와 연결된 소스를 실행합니다.'
            : effectiveTopic
              ? `‘${effectiveTopic.name}’에 연결된 활성 소스만 실행합니다.`
              : '실행할 활성 주제를 먼저 등록해 주세요.'}
          {feedCount > 0 && ` 피드 ${feedCount}곳은 새로 올라온 만큼 가져옵니다.`}
        </p>
      </div>

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
