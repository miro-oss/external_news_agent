import { useEffect, useRef, useState, type RefObject } from 'react'
import { ApiError } from '../../api/client'
import type { TopicWeeklyReportCreated } from '../../api/topicWeeklyReport'
import { useTopics } from '../../api/queries'
import { TopicWeeklyReportDialog } from './TopicWeeklyReportDialog'
import { useTopicWeeklyReportTask } from './topicWeeklyReportTaskContext'

export function TopicWeeklyReportCreate({ id, initialTopicId, now, onDismiss, onReady, returnFocusRef }: {
  id: string
  initialTopicId: number | null
  now: Date
  onDismiss: () => void
  onReady: (result: TopicWeeklyReportCreated) => void
  returnFocusRef: RefObject<HTMLButtonElement | null>
}) {
  const topics = useTopics(true)
  const { task, pollingError, start, clear } = useTopicWeeklyReportTask()
  // Preserve the draft while a terminal task notice is cleared on the first edit.
  const [initialDraft] = useState(task?.request)
  const openedResult = useRef<number | null>(task?.status === 'ready' ? task.result?.reportId ?? null : null)
  const busy = task?.status === 'creating' || task?.status === 'waiting'
  const topicOptions = topics.data?.content ?? []
  const visibleTopics = busy && task ? [
    { id: task.request.topicId, name: task.topicName, active: true },
    ...topicOptions.filter(topic => topic.id !== task.request.topicId),
  ] : topicOptions

  useEffect(() => {
    if (task?.status === 'ready' && task.result && openedResult.current !== task.result.reportId) {
      openedResult.current = task.result.reportId
      onReady(task.result)
    }
  }, [onReady, task])

  return <TopicWeeklyReportDialog id={id} initialTopicId={initialDraft?.topicId ?? initialTopicId}
    initialWeekStart={initialDraft?.weekStartDate} now={now}
    topics={visibleTopics} topicsLoading={topics.isPending}
    topicsError={topics.isError ? topics.error instanceof ApiError ? topics.error.message : '주제를 불러오지 못했습니다. 다시 시도해 주세요.' : null}
    onRetryTopics={() => { void topics.refetch() }} pending={task?.status === 'creating'} waiting={task?.status === 'waiting'}
    error={pollingError ?? task?.error} onDraftChange={clear} returnFocusRef={returnFocusRef} onDismiss={onDismiss}
    onCreate={(topicId, weekStartDate) => {
      openedResult.current = null
      start({ topicId, weekStartDate }, topics.data?.content.find(topic => topic.id === topicId)?.name ?? `주제 #${topicId}`)
    }} />
}
