import type { QueryClient } from '@tanstack/react-query'
import { apiPut, get } from './client.ts'
import type { PageResult, Source, TopicDetail, TopicSourceBrief, TopicSummary } from './types'

export interface TopicSourceEditData {
  topic: TopicDetail
  sources: Source[]
}

export interface TopicSourceSaveResult {
  topicId: number
  sources: TopicSourceBrief[]
  addedCount: number
  removedCount: number
  combinationCount: number
}

export function availableTopicSources(topic: TopicDetail, activeSources: Source[]): Source[] {
  const available = new Map(activeSources.map(source => [source.id, source]))
  for (const source of topic.sources) {
    if (available.has(source.id)) continue
    available.set(source.id, {
      ...source, urlTemplate: '', country: null, crawlPolicy: null, robotsCheckedAt: null, reliabilityScore: null,
    })
  }
  return [...available.values()]
}

async function activeSources(signal: AbortSignal): Promise<Source[]> {
  const sources: Source[] = []
  for (let page = 0; ; page++) {
    const result = await get<PageResult<Source>>('/sources', { active: true, page, size: 100 }, signal)
    sources.push(...result.content)
    if (!result.hasNext) return sources
  }
}

export function topicSourceEditOptions(topicId: number) {
  return {
    queryKey: ['topic-source-edit', topicId],
    staleTime: 0,
    retry: false,
    queryFn: async ({ signal }: { signal: AbortSignal }): Promise<TopicSourceEditData> => {
      const [topic, sources] = await Promise.all([
        get<TopicDetail>(`/topics/${topicId}`, undefined, signal), activeSources(signal),
      ])
      return { topic, sources: availableTopicSources(topic, sources) }
    },
  }
}

export function saveTopicSourcesOptions(client: QueryClient, topicId: number) {
  const affectedKeys = [['topics'], ['topic-sources'], ['sources'], ['topic-edit', topicId],
    ['topic-schedule', topicId], topicSourceEditOptions(topicId).queryKey]
  return {
    retry: false,
    mutationFn: (sourceIds: number[]) => apiPut<TopicSourceSaveResult>(`/news/topics/${topicId}/sources`, { sourceIds }),
    onSuccess: async (saved: TopicSourceSaveResult) => {
      await Promise.all(affectedKeys.map(queryKey => client.cancelQueries({ queryKey })))
      client.setQueriesData<PageResult<TopicSummary>>({ queryKey: ['topics'] }, current => current ? {
        ...current,
        content: current.content.map(topic => topic.id === topicId ? { ...topic, linkedSourceCount: saved.combinationCount } : topic),
      } : current)
      await Promise.all(affectedKeys.map(queryKey => client.invalidateQueries({ queryKey })))
    },
  }
}
