import { createContext, useContext } from 'react'
import type { TopicWeeklyReportRequest } from '../../api/topicWeeklyReport'
import type { TopicWeeklyReportTask } from './topicWeeklyReportTask'

interface TaskContext {
  task: TopicWeeklyReportTask | null
  pollingError: string | null
  start: (request: TopicWeeklyReportRequest, topicName: string) => boolean
  clear: () => void
  release: () => void
}

export const TopicWeeklyReportTaskContext = createContext<TaskContext | null>(null)

export function useTopicWeeklyReportTask() {
  const value = useContext(TopicWeeklyReportTaskContext)
  if (!value) throw new Error('주간 보고서 작업 관리 영역이 필요합니다.')
  return value
}
