import type { QueryClient } from '@tanstack/react-query'
import { get, patch } from './client.ts'
import type { Combination, PageResult, Topic, TopicDetail, TopicSummary } from './types'

const EDIT_FIELDS = ['name', 'queryText', 'requiredKeywords', 'optionalKeywords', 'excludedKeywords'] as const
export type TopicEditRequest = Partial<Pick<Topic, (typeof EDIT_FIELDS)[number]>>

function editFields(input: TopicEditRequest): TopicEditRequest {
  return Object.fromEntries(EDIT_FIELDS.filter(field => input[field] !== undefined).map(field => [field, input[field]]))
}

export function topicEditOptions(topicId: number) {
  return {
    queryKey: ['topic-edit', topicId],
    queryFn: ({ signal }: { signal: AbortSignal }) => get<TopicDetail>(`/topics/${topicId}`, undefined, signal),
    staleTime: 0,
  }
}

export function saveTopicEditOptions(client: QueryClient, topicId: number) {
  const detailKey = topicEditOptions(topicId).queryKey
  const scheduleKey = ['topic-schedule', topicId]
  const affectedKeys = [['topics'], ['topic-sources'], ['topic-keyword-proposals'], detailKey, scheduleKey]
  return {
    mutationFn: (input: TopicEditRequest) => patch<Topic>(`/topics/${topicId}`, editFields(input)),
    retry: false,
    onSuccess: async (saved: Topic, input: TopicEditRequest) => {
      // 편집 전에 시작한 조회가 뒤늦게 완료돼 저장한 조건을 덮어쓰지 않도록 한다.
      await Promise.all(affectedKeys.map(queryKey => client.cancelQueries({ queryKey })))
      // 변경하지 않은 일정·활성 상태와 상세 조회의 소스 정보는 캐시에 유지한다.
      const changes: TopicEditRequest = Object.fromEntries(
        EDIT_FIELDS.filter(field => input[field] !== undefined).map(field => [field, saved[field]]),
      )
      client.setQueryData<TopicDetail>(detailKey, current => current ? { ...current, ...changes } : current)
      client.setQueryData<Topic>(scheduleKey, current => current ? { ...current, ...changes } : current)
      client.setQueriesData<PageResult<TopicSummary>>({ queryKey: ['topics'] }, current => current ? {
        ...current,
        content: current.content.map(topic => topic.id === topicId ? { ...topic, ...changes } : topic),
      } : current)
      client.setQueriesData<PageResult<Combination>>({ queryKey: ['topic-sources'] }, current => current ? {
        ...current,
        content: current.content.map(combination => combination.topicId === topicId ? {
          ...combination,
          ...(changes.name !== undefined ? { topicName: changes.name } : {}),
          ...(changes.queryText !== undefined ? { queryText: changes.queryText } : {}),
        } : combination),
      } : current)
      await Promise.all(affectedKeys.map(queryKey => client.invalidateQueries({ queryKey })))
    },
  }
}
