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
