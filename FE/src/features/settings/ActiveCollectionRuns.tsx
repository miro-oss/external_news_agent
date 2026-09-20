import { useEffect, useId, useState } from 'react'
import { createPortal } from 'react-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../../api/client'
import { useActiveCollectionRuns, useCollectionQueue, useCollectionRunTopics, type ActiveRunStatus, type CollectionRunSummary } from '../../api/collectionQueue'
import { runDeliverySettingsOptions, saveRunDeliverySettingsOptions, type RunDeliverySettings } from '../../api/runDeliverySettings'
import { Segmented } from '../../components/Segmented'
import { Skeleton, SkeletonRegion } from '../../components/Skeleton'
import { formatShortDate } from '../../lib/datetime'
import { CollectionDeliveryDialog } from './CollectionDeliveryPicker'
import { deliveryPolicySummary, toDeliveryPolicy } from './deliverySettings'
import './active-collection-runs.css'

export function ActiveCollectionRuns() {
  const [status, setStatus] = useState<ActiveRunStatus>('RUNNING')
  const [page, setPage] = useState(0)
  const [editing, setEditing] = useState<{ settings: RunDeliverySettings; name: string } | null>(null)
  const [loadingRun, setLoadingRun] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [success, setSuccess] = useState<string | null>(null)
  const client = useQueryClient()
  const runs = useActiveCollectionRuns(status, page)
  const queue = useCollectionQueue()
  const id = useId()

  useEffect(() => {
    if (!success) return
    const timer = window.setTimeout(() => setSuccess(null), 3000)
    return () => window.clearTimeout(timer)
  }, [success])

  async function open(runId: number, name: string) {
    setLoadingRun(runId)
    setError(null)
    setSuccess(null)
    try {
      setEditing({ settings: await client.fetchQuery(runDeliverySettingsOptions(runId)), name })
    } catch (failure) {
      setError(failure instanceof ApiError ? failure.message : '알림 설정을 불러오지 못했습니다. 다시 시도해 주세요.')
    } finally {
      setLoadingRun(null)
    }
  }

  return <section className="active-collection-runs" aria-labelledby={`${id}-title`}>
    <header className="active-runs-heading">
      <div><h2 id={`${id}-title`}>진행 중인 수집</h2><p className="muted">수집을 시작한 뒤에도 이번 보고서의 알림을 설정할 수 있습니다.</p></div>
      <Segmented label="수집 상태" value={status} options={[
        { value: 'RUNNING', label: `수집 중${queue.data ? ` ${queue.data.running}` : ''}` },
        { value: 'PENDING', label: `대기${queue.data ? ` ${queue.data.pending}` : ''}` },
      ]} onSelect={value => { setStatus(value); setPage(0) }} />
    </header>
    {error && <p className="field-error" role="alert">{error}</p>}
    {success && <p className="hint" role="status">{success}</p>}
    {runs.isPending ? <SkeletonRegion label="진행 중인 수집을 불러오는 중"><Skeleton height="4rem" /></SkeletonRegion>
      : runs.isError ? <div className="active-runs-error" role="alert"><p>진행 중인 수집을 불러오지 못했습니다.</p><button type="button" className="text-button" onClick={() => void runs.refetch()}>다시 불러오기</button></div>
      : <>
        {!runs.data.content.length ? <p className="empty-block">{page > 0 ? '이 페이지의 수집이 종료되었습니다. 이전 페이지를 확인해 주세요.' : status === 'PENDING' ? '대기 중인 수집이 없습니다.' : '진행 중인 수집이 없습니다.'}</p>
          : <ul className="active-runs-list">
            {runs.data.content.map(run => <ActiveCollectionRunItem key={run.runId} run={run}
              loadingRun={loadingRun} editing={editing?.settings.runId === run.runId}
              dialogId={`${id}-dialog`} onOpen={open} />)}
          </ul>}
        {(page > 0 || runs.data.hasNext) && <nav className="active-runs-pagination" aria-label="진행 중인 수집 페이지">
          <button type="button" className="ghost-button" disabled={page === 0} onClick={() => setPage(value => value - 1)}>이전</button>
          <span>{page + 1} 페이지</span>
          <button type="button" className="ghost-button" disabled={!runs.data.hasNext} onClick={() => setPage(value => value + 1)}>다음</button>
        </nav>}
      </>}
    {editing && createPortal(<RunDeliveryEditor key={editing.settings.runId} id={`${id}-dialog`} initial={editing.settings} name={editing.name}
      onDismiss={() => setEditing(null)} onSaved={() => { setSuccess(`${editing.name}의 보고서 알림을 저장했습니다.`); setEditing(null) }} />, document.body)}
  </section>
}

function ActiveCollectionRunItem({ run, loadingRun, editing, dialogId, onOpen }: {
  run: CollectionRunSummary; loadingRun: number | null; editing: boolean; dialogId: string
  onOpen: (runId: number, name: string) => Promise<void>
}) {
  const topics = useCollectionRunTopics(run.runId)
  const name = topics.data?.join(', ') || '수집 주제 정보 없음'
  return <li>
    <div>
      <strong>{topics.data ? name : topics.isError ? '주제명을 불러오지 못했습니다.' : '주제명 불러오는 중…'}</strong>
      <span>#{run.runId} · {run.triggerType === 'SCHEDULED' ? '정기 수집' : '직접 실행'} · {formatShortDate(run.queuedAt)} 접수</span>
      {topics.isError && <button type="button" className="text-button" onClick={() => void topics.refetch()}>주제명 다시 불러오기</button>}
    </div>
    <button type="button" className="ghost-button" disabled={loadingRun !== null || topics.isPending}
      aria-label={`${name} 이번 보고서 알림 설정`} aria-haspopup="dialog"
      aria-expanded={editing} aria-controls={editing ? dialogId : undefined}
      onClick={() => void onOpen(run.runId, name)}>{loadingRun === run.runId ? '불러오는 중…' : '이번 보고서 알림'}</button>
  </li>
}

function RunDeliveryEditor({ id, initial, name, onDismiss, onSaved }: {
  id: string; initial: RunDeliverySettings; name: string; onDismiss: () => void; onSaved: () => void
}) {
  const client = useQueryClient()
  const current = useQuery({ ...runDeliverySettingsOptions(initial.runId), initialData: initial,
    refetchInterval: query => query.state.data?.editable ? 2000 : false })
  const save = useMutation(saveRunDeliverySettingsOptions(client, initial.runId))
  const settings = current.data
  const conflict = save.error instanceof ApiError && save.error.status === 409
  const ended = !settings.editable || conflict
  return <CollectionDeliveryDialog id={id} value={{ ...toDeliveryPolicy(initial), mode: 'ONCE' }}
    context={{ scope: 'RUN', name, inherited: initial.source === 'TOPIC',
      description: initial.source === 'TOPIC' ? '저장하면 이번 수집은 아래 설정만 사용합니다. 주제 설정은 유지됩니다.' : undefined }}
    pending={save.isPending} readOnly={ended}
    onDraftChange={save.reset}
    error={save.error ? save.error instanceof ApiError ? save.error.message : '알림 설정을 저장하지 못했습니다. 다시 시도해 주세요.' : null}
    status={ended ? <>
      <p>{settings.reportReady ? '보고서가 완성되어 알림 설정을 변경할 수 없습니다. 보고서의 공유하기에서 전달할 수 있습니다.' : '수집이 종료되어 알림 설정을 변경할 수 없습니다.'}</p>
      {settings.reportReady && settings.reportId !== null && <a href={`#/reports?reportId=${settings.reportId}`} onClick={onDismiss}>완성된 보고서 보기</a>}
      {current.isError && <><p>최신 보고서 상태를 불러오지 못했습니다.</p><button type="button" className="text-button" onClick={() => void current.refetch()}>다시 확인</button></>}
    </> : settings.source === 'TOPIC' ? <>
      <p><strong>현재 주제에 저장된 설정을 사용하고 있어요.</strong></p>
      {settings.topicPolicies.length > 0 ? <ul>{settings.topicPolicies.map(policy => <li key={policy.topicId}>{policy.topicName} · {deliveryPolicySummary(policy)}</li>)}</ul> : <p>적용된 주제 알림 설정이 없습니다.</p>}
      <p>별도 설정을 저장하지 않고 닫으면 현재 설정을 유지합니다.</p>
    </> : undefined}
    onDismiss={onDismiss} onApply={value => { if (value) save.mutate(toDeliveryPolicy(value), { onSuccess: onSaved }) }} />
}
