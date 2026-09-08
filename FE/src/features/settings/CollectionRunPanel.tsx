import { useRef, useState } from 'react'
import { useCollectionProgress, useCollectionQueue } from '../../api/collectionQueue'
import './collection-queue.css'
import {
  useAudienceSetting,
  useCombinations,
  useStartCollectionRun,
  useUpdateAudienceSetting,
} from '../../api/queries'
import {
  AUDIENCES,
  AUDIENCE_LABELS,
  type Audience,
} from '../../api/types'
import { Segmented, type SegmentedOption } from '../../components/Segmented'
import { MutationStatus } from './MutationStatus'
import { LlmUsageSummary } from './LlmUsageSummary'
import { CollectionTopicPicker } from './CollectionTopicPicker'
import { AudienceSettingSkeleton, CollectionRunSkeleton } from './SettingsSkeletons'
import type { CollectionRunDelivery } from '../../api/notificationConnections'
import { CollectionDeliveryPicker } from './CollectionDeliveryPicker'

type RunScope = 'SELECTED' | 'ALL'

const AUDIENCE_OPTIONS: ReadonlyArray<SegmentedOption<Audience>> = AUDIENCES.map((value) => ({
  value,
  label: AUDIENCE_LABELS[value],
}))

export function CollectionRunPanel() {
  const combinations = useCombinations()
  const startRun = useStartCollectionRun()
  const [scope, setScope] = useState<RunScope>('SELECTED')
  const [selectedTopicIds, setSelectedTopicIds] = useState<number[] | null>(null)
  const [delivery, setDelivery] = useState<CollectionRunDelivery>()
  const queue = useCollectionQueue()
  const progress = useCollectionProgress(startRun.data?.runId)
  const pendingRunKey = useRef<string | null>(null)

  if (combinations.isPending) {
    return <CollectionRunSkeleton />
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
  const effectiveTopicIds = (selectedTopicIds ?? activeTopics.slice(0, 1).map((topic) => topic.id))
    .filter((id) => activeTopics.some((topic) => topic.id === id))
  const targetCombinations = scope === 'ALL'
    ? activeCombinations
    : activeCombinations.filter((combination) => effectiveTopicIds.includes(combination.topicId))

  function resetRequestState() {
    pendingRunKey.current = null
    startRun.reset()
  }

  function changeScope(nextScope: RunScope) {
    resetRequestState()
    setScope(nextScope)
  }

  function selectTopics(topicIds: number[]) {
    resetRequestState()
    setSelectedTopicIds(topicIds)
  }

  function runNow() {
    if (scope === 'SELECTED' && effectiveTopicIds.length === 0) return
    if (progress.data && !['PENDING', 'RUNNING'].includes(progress.data.status)) pendingRunKey.current = null
    const idempotencyKey = pendingRunKey.current ?? `manual-${crypto.randomUUID()}`
    pendingRunKey.current = idempotencyKey
    startRun.mutate(
      {
        idempotencyKey,
        ...(scope === 'SELECTED' ? { topicIds: effectiveTopicIds } : {}),
        ...(delivery ? { delivery } : {}),
      },
      { onSuccess: () => { if (delivery?.mode === 'ONCE') setDelivery(undefined) } },
    )
  }

  const canRun = targetCombinations.length > 0 && !startRun.isPending

  return (
    <section className="collection-run-panel" aria-labelledby="collection-run-title">
      <div className="collection-run-top">
        <div className="collection-run-heading">
          <div>
            <h2 id="collection-run-title">수집 실행</h2>
            <p className="muted">주제를 여러 개 골라 한 번에 수집할 수 있습니다.</p>
          </div>
          {queue.data && (queue.data.pending > 0 || queue.data.running > 0) && (
            <span className="run-default-badge">수집 중 {queue.data.running} · 대기 {queue.data.pending}</span>
          )}
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

          <CollectionTopicPicker topics={activeTopics}
            selected={scope === 'ALL' ? activeTopics.map(topic => topic.id) : effectiveTopicIds}
            disabled={scope === 'ALL' || startRun.isPending} onChange={selectTopics} />
        </div>
      </div>

      <CollectionDeliveryPicker value={delivery} disabled={startRun.isPending}
        onChange={value => { resetRequestState(); setDelivery(value) }} />

      <div className="collection-run-summary">
        <DefaultAudienceSetting />
        <div className="collection-run-usage">
          <LlmUsageSummary />
        </div>

        <div className="collection-run-footer">
          <button className="collection-run-button" type="button" onClick={runNow} disabled={!canRun}>
            {startRun.isPending
              ? '실행 요청 중…'
              : scope === 'ALL' ? '모든 활성 주제 수집' : '선택 주제 수집'}
          </button>
          <MutationStatus error={startRun.error} success={startRun.data ? '수집 요청을 접수했습니다.' : null} />
          {progress.data && <p className="hint" role="status">
            {progress.data.status === 'PENDING' ? '준비가 끝나면 자동으로 수집합니다.'
              : progress.data.status === 'RUNNING' ? '선택한 주제를 수집하고 있습니다.'
              : progress.data.status === 'FAILED' ? '수집을 완료하지 못했습니다. 다시 요청할 수 있습니다.'
              : progress.data.status === 'PARTIAL' ? '수집을 마쳤습니다. 일부 출처를 가져오지 못했습니다.'
              : '수집을 마쳤습니다.'}
            {progress.data.reportId && <> <a href={`#/reports?reportId=${progress.data.reportId}`}>보고서 보기</a></>}
          </p>}
        </div>
      </div>
    </section>
  )
}

function DefaultAudienceSetting() {
  const audienceQuery = useAudienceSetting()
  const updateAudience = useUpdateAudienceSetting()

  function selectAudience(next: Audience) {
    if (next === audienceQuery.data?.audience) return
    updateAudience.mutate(next)
  }

  if (audienceQuery.isPending) {
    return <AudienceSettingSkeleton />
  }
  if (audienceQuery.error || !audienceQuery.data) {
    return (
      <div className="run-audience-setting state-panel error" role="alert">
        기본 관점 설정을 불러오지 못했습니다.
      </div>
    )
  }

  // 저장이 끝나기 전에도 방금 누른 칸이 선택돼 보여야 한다. 응답을 기다리는 동안은 요청 값을 쓴다.
  const audience = updateAudience.isPending ? updateAudience.variables : audienceQuery.data.audience
  return (
    <div className="run-audience-setting">
      <div className="run-audience-copy">
        <h3>내 기본 관점</h3>
        <p>기사와 리포트를 처음 볼 때 사용할 관점입니다.</p>
      </div>
      <Segmented
        label="기본 관점"
        className="run-audience-options"
        value={audience}
        options={AUDIENCE_OPTIONS}
        onSelect={selectAudience}
      />
      <MutationStatus
        error={updateAudience.error}
        success={null}
      />
    </div>
  )
}
