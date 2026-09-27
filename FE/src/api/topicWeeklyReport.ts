import { post } from './client.ts'

export interface TopicWeeklyReportRequest {
  topicId: number
  weekStartDate: string
}

export interface TopicWeeklyReportCreated {
  reportId: number
  created: boolean
  reportReady: boolean
}

export function createTopicWeeklyReportOptions() {
  return {
    mutationFn: ({ topicId, weekStartDate }: TopicWeeklyReportRequest) =>
      post<TopicWeeklyReportCreated>('/reports/weekly', { topicId, weekStartDate }),
    retry: false,
  }
}
