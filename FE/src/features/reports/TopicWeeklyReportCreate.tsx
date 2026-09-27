import { useEffect, useState, type RefObject } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../../api/client'
import { createTopicWeeklyReportOptions, type TopicWeeklyReportCreated } from '../../api/topicWeeklyReport'
import { useReports, useTopics } from '../../api/queries'
import { TopicWeeklyReportDialog } from './TopicWeeklyReportDialog'

export function TopicWeeklyReportCreate({ id, initialTopicId, now, onDismiss, onReady, returnFocusRef }: {
  id: string
  initialTopicId: number | null
  now: Date
  onDismiss: () => void
  onReady: (result: TopicWeeklyReportCreated) => void
  returnFocusRef: RefObject<HTMLButtonElement | null>
}) {
  const client = useQueryClient()
  const topics = useTopics()
  const create = useMutation(createTopicWeeklyReportOptions())
  const [waiting, setWaiting] = useState<TopicWeeklyReportCreated | null>(null)
  const reports = useReports('WEEKLY', { enabled: waiting !== null, refetchInterval: waiting ? 3000 : false })

  useEffect(() => {
    if (waiting && reports.data?.content.some(report => report.id === waiting.reportId)) {
      onReady({ ...waiting, reportReady: true })
    }
  }, [onReady, reports.data, waiting])

  return <TopicWeeklyReportDialog id={id} initialTopicId={initialTopicId} now={now}
    topics={topics.data?.content ?? []} topicsLoading={topics.isPending}
    topicsError={topics.isError ? topics.error instanceof ApiError ? topics.error.message : '주제를 불러오지 못했습니다. 다시 시도해 주세요.' : null}
    onRetryTopics={() => { void topics.refetch() }} pending={create.isPending} waiting={waiting !== null}
    error={waiting && reports.isError ? '완료 여부를 확인하지 못했습니다. 잠시 후 다시 확인합니다.'
      : create.error ? create.error instanceof ApiError ? create.error.message : '보고서를 만들지 못했습니다. 다시 시도해 주세요.' : null}
    onDraftChange={create.reset} returnFocusRef={returnFocusRef} onDismiss={onDismiss}
    onCreate={(topicId, weekStartDate) => create.mutate({ topicId, weekStartDate }, {
      onSuccess: result => {
        setWaiting(result)
        // The completed report is opened only after it appears in the existing list.
        // A concurrent PENDING request never triggers a second POST or a premature detail request.
        void client.invalidateQueries({ queryKey: ['reports', 'list'] })
      },
    })} />
}
