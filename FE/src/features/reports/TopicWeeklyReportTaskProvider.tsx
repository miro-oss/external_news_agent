import { useEffect, type ReactNode } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useReports } from '../../api/queries'
import { reportHash } from './reportNavigation'
import { shiftReportDate } from './reportWeek'
import { clearTopicWeeklyReportTask, observeTopicWeeklyReportTask, releaseTopicWeeklyReportTask, startTopicWeeklyReportTask,
  topicWeeklyReportTaskKey, type TopicWeeklyReportTask } from './topicWeeklyReportTask'
import { TopicWeeklyReportTaskContext, useTopicWeeklyReportTask } from './topicWeeklyReportTaskContext'
import './topic-weekly-report.css'

export function TopicWeeklyReportTaskProvider({ children }: { children: ReactNode }) {
  const client = useQueryClient()
  const { data: task } = useQuery<TopicWeeklyReportTask | null>({
    queryKey: topicWeeklyReportTaskKey, queryFn: () => null, initialData: null,
    enabled: false, staleTime: Infinity, gcTime: Infinity,
  })
  useEffect(() => observeTopicWeeklyReportTask(client), [client])
  const reports = useReports('WEEKLY', { enabled: task?.status === 'waiting', refetchInterval: task?.status === 'waiting' ? 3000 : false })
  const pollingError = task?.status === 'waiting' && reports.isError
    ? '완료 여부를 확인하지 못했습니다. 잠시 후 다시 확인합니다.' : null
  return <TopicWeeklyReportTaskContext.Provider value={{ task, pollingError,
    start: (request, topicName) => startTopicWeeklyReportTask(client, request, topicName),
    clear: () => clearTopicWeeklyReportTask(client), release: () => releaseTopicWeeklyReportTask(client) }}>
    {children}
  </TopicWeeklyReportTaskContext.Provider>
}

export function TopicWeeklyReportTaskBanner() {
  const { task, pollingError, clear, release } = useTopicWeeklyReportTask()
  if (!task) return null
  const busy = task.status === 'creating' || task.status === 'waiting'
  return <section className="topic-weekly-task-banner" data-state={task.status} role={task.status === 'error' ? 'alert' : 'status'}
    aria-label="주제별 주간 보고서 생성 상태">
    <div className="topic-weekly-task-content">
      <strong>{task.status === 'ready' ? '주제별 주간 보고서가 준비되었습니다.'
        : task.status === 'error' ? '주제별 주간 보고서를 만들지 못했습니다.' : '주제별 주간 보고서를 만들고 있습니다.'}</strong>
      <span className="topic-weekly-task-meta">{task.topicName} · {task.request.weekStartDate} ~ {shiftReportDate(task.request.weekStartDate, 6)}</span>
      {busy && <p>{pollingError ?? '다른 화면을 이용해도 생성은 계속됩니다. 준비되면 여기에서 알려드립니다.'}</p>}
      {task.status === 'waiting' && <p>확인을 그만두어도 생성은 취소되지 않습니다. 완료된 보고서는 주간 보고서 목록에서 확인할 수 있습니다.</p>}
      {task.error && <p>{task.error}</p>}
    </div>
    <div className="topic-weekly-task-actions">
      {task.status === 'ready' && task.result && <a className="secondary-button" href={reportHash('WEEKLY', task.result.reportId)} onClick={clear}>보고서 열기</a>}
      {task.status === 'waiting' && <button type="button" className="text-button" onClick={release}>나중에 목록에서 확인</button>}
      {!busy && <button type="button" className="text-button" onClick={clear}>알림 닫기</button>}
    </div>
  </section>
}
