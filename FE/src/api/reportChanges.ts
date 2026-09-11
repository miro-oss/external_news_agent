import { queryOptions, useQuery } from '@tanstack/react-query'
import { get } from './client.ts'
import type { ReportChanges } from './types'

export function reportChangesOptions(reportId: number) {
  return queryOptions({
    queryKey: ['reports', reportId, 'changes'] as const,
    queryFn: ({ signal }) => get<ReportChanges>(`/reports/${reportId}/changes`, undefined,
      AbortSignal.any([signal, AbortSignal.timeout(30_000)])),
    staleTime: 30_000,
    retry: false,
    refetchOnWindowFocus: false,
    refetchIntervalInBackground: false,
    // A stuck job must not keep a tab polling forever. The button remains a read-only refresh.
    refetchInterval: (query) => query.state.status === 'success'
      && ['PENDING', 'RUNNING'].includes(query.state.data?.status ?? '')
      && query.state.dataUpdateCount < 60 ? 5_000 : false,
  })
}

export function useReportChanges(reportId: number) {
  return useQuery(reportChangesOptions(reportId))
}
