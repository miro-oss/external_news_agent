import { useQuery } from '@tanstack/react-query'
import { get } from './client'
import type { PageResult } from './types'

export interface CollectionProgress {
  runId: number
  status: 'PENDING' | 'RUNNING' | 'SUCCESS' | 'PARTIAL' | 'FAILED'
  queuedAt: string
  startedAt: string | null
  reportId: number | null
}

export type ActiveRunStatus = 'PENDING' | 'RUNNING'
export type CollectionRunSummary = CollectionProgress & { triggerType: 'MANUAL' | 'SCHEDULED' }

export function useActiveCollectionRuns(status: ActiveRunStatus, page: number) {
  return useQuery({
    queryKey: ['collection-queue', 'runs', status, page],
    queryFn: () => get<PageResult<CollectionRunSummary>>('/runs', { status, page, size: 10 }),
    refetchInterval: 3000,
  })
}

export function useCollectionRunTopics(runId: number) {
  return useQuery({
    queryKey: ['collection-run-topics', runId],
    queryFn: () => get<{ breakdown: Array<{ topicId: number; topicName: string }> }>(`/runs/${runId}`),
    // 같은 주제가 여러 소스와 연결되어도 이름은 한 번만 표시한다.
    select: detail => [...new Map(detail.breakdown.map(item => [item.topicId, item.topicName])).values()],
    staleTime: 60_000,
  })
}

export function useCollectionQueue() {
  return useQuery({
    queryKey: ['collection-queue'],
    queryFn: async () => {
      const pages = await Promise.all(['PENDING', 'RUNNING'].map((status) =>
        get<PageResult<CollectionProgress>>('/runs', { status, size: 1 })))
      return { pending: pages[0].totalElements, running: pages[1].totalElements }
    },
    refetchInterval: 3000,
  })
}

export function useCollectionProgress(runId?: number) {
  return useQuery({
    queryKey: ['collection-progress', runId],
    queryFn: () => get<CollectionProgress>(`/runs/${runId}`),
    enabled: runId !== undefined,
    refetchInterval: (query) =>
      !query.state.data || ['PENDING', 'RUNNING'].includes(query.state.data.status) ? 2000 : false,
  })
}
