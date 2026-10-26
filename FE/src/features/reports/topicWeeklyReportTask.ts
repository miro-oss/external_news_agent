import type { QueryClient } from '@tanstack/react-query'
import { ApiError } from '../../api/client.ts'
import { createTopicWeeklyReportOptions, type TopicWeeklyReportCreated, type TopicWeeklyReportRequest } from '../../api/topicWeeklyReport.ts'
import type { PageResult, ReportSummary } from '../../api/types'

export interface TopicWeeklyReportTask {
  request: TopicWeeklyReportRequest
  topicName: string
  status: 'creating' | 'waiting' | 'ready' | 'error'
  result: TopicWeeklyReportCreated | null
  error: string | null
}

export const topicWeeklyReportTaskKey = ['topic-weekly-report-task'] as const

export function isTopicWeeklyReportTaskBusy(task: TopicWeeklyReportTask | null | undefined) {
  return task?.status === 'creating' || task?.status === 'waiting'
}

export function clearTopicWeeklyReportTask(client: QueryClient) {
  if (isTopicWeeklyReportTaskBusy(client.getQueryData<TopicWeeklyReportTask>(topicWeeklyReportTaskKey))) return
  client.setQueryData(topicWeeklyReportTaskKey, null)
}

/** Release list tracking explicitly; the server's generation is neither cancelled nor repeated. */
export function releaseTopicWeeklyReportTask(client: QueryClient) {
  const task = client.getQueryData<TopicWeeklyReportTask | null>(topicWeeklyReportTaskKey)
  if (task?.status === 'waiting') client.setQueryData(topicWeeklyReportTaskKey, null)
}

/** The task belongs to the shared client, so closing its form never drops a POST result. */
export function startTopicWeeklyReportTask(client: QueryClient, request: TopicWeeklyReportRequest, topicName: string) {
  if (isTopicWeeklyReportTaskBusy(client.getQueryData<TopicWeeklyReportTask>(topicWeeklyReportTaskKey))) return false
  const task: TopicWeeklyReportTask = { request: { ...request }, topicName, status: 'creating', result: null, error: null }
  client.setQueryData(topicWeeklyReportTaskKey, task)
  const mutation = client.getMutationCache().build(client, {
    ...createTopicWeeklyReportOptions(),
    onSuccess: (result: TopicWeeklyReportCreated) => {
      client.setQueryData<TopicWeeklyReportTask>(topicWeeklyReportTaskKey, { ...task, status: 'waiting', result })
      // Completed and concurrent reports both appear in the list before opening their detail.
      void client.invalidateQueries({ queryKey: ['reports', 'list'] })
    },
    onError: (error: Error) => {
      client.setQueryData<TopicWeeklyReportTask>(topicWeeklyReportTaskKey, {
        ...task, status: 'error', error: error instanceof ApiError ? error.message : '보고서를 만들지 못했습니다. 다시 시도해 주세요.',
      })
    },
  })
  // The mutation records the error for both the reopened dialog and the global notice.
  void mutation.execute(task.request).catch(() => {})
  return true
}

/** Only a fresh successful list read can establish that a PENDING report has become readable. */
export function observeTopicWeeklyReportTask(client: QueryClient) {
  return client.getQueryCache().subscribe(event => {
    if (event.type !== 'updated' || event.action.type !== 'success' || event.action.manual
      || event.query.queryKey.length !== 3 || event.query.queryKey[0] !== 'reports'
      || event.query.queryKey[1] !== 'list' || event.query.queryKey[2] !== 'WEEKLY') return
    const task = client.getQueryData<TopicWeeklyReportTask | null>(topicWeeklyReportTaskKey)
    if (task?.status !== 'waiting' || !task.result) return
    const reports = event.action.data as PageResult<ReportSummary>
    if (!reports.content.some(report => report.id === task.result?.reportId)) return
    client.setQueryData<TopicWeeklyReportTask>(topicWeeklyReportTaskKey, {
      ...task, status: 'ready', result: { ...task.result, reportReady: true },
    })
  })
}
