import type { QueryClient } from '@tanstack/react-query'
import { get, patch } from './client.ts'
import type { Combination, PageResult, Topic, TopicSummary } from './types'

export function topicScheduleOptions(topicId: number) {
  return {
    queryKey: ['topic-schedule', topicId],
    queryFn: ({ signal }: { signal: AbortSignal }) => get<Topic>(`/topics/${topicId}`, undefined, signal),
    staleTime: 0,
  }
}

export function saveTopicScheduleOptions(client: QueryClient, topicId: number) {
  const detailKey = topicScheduleOptions(topicId).queryKey
  return {
    mutationFn: ({ intervalMinutes }: Pick<Topic, 'intervalMinutes'>) =>
      patch<Topic>(`/topics/${topicId}`, { intervalMinutes }),
    retry: false,
    onSuccess: async (saved: Topic) => {
      // 저장 전에 시작한 조회가 늦게 도착해 이전 주기를 되살리지 않도록 취소한다.
      await Promise.all([
        client.cancelQueries({ queryKey: ['topics'] }),
        client.cancelQueries({ queryKey: ['topic-sources'] }),
        client.cancelQueries({ queryKey: detailKey }),
      ])
      client.setQueryData<Topic>(detailKey, current => ({ ...(current ?? saved), intervalMinutes: saved.intervalMinutes }))
      client.setQueriesData<PageResult<TopicSummary>>({ queryKey: ['topics'] }, current => current ? {
        ...current,
        content: current.content.map(topic => topic.id === topicId ? { ...topic, intervalMinutes: saved.intervalMinutes } : topic),
      } : current)
      client.setQueriesData<PageResult<Combination>>({ queryKey: ['topic-sources'] }, current => current ? {
        ...current,
        content: current.content.map(combination => combination.topicId === topicId
          ? { ...combination, intervalMinutes: saved.intervalMinutes } : combination),
      } : current)
      await Promise.all([
        client.invalidateQueries({ queryKey: ['topics'] }),
        client.invalidateQueries({ queryKey: ['topic-sources'] }),
      ])
    },
  }
}
