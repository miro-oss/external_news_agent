import { queryOptions, useIsMutating, useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { useMemo } from 'react'
import { ApiError, get, post } from './client.ts'
import type { Audience, ReportDetail } from './types'

export type ReportImportance = 'high' | 'medium' | 'low' | 'unavailable'
export interface ReportInsightOverview {
  text: string
  basisClaimIds: string[]
  assumption: string
}
export interface ReportInsightAxes {
  directness: number | null
  impact: number | null
  urgency: number | null
  novelty: null
}
export interface ReportInsightIssue {
  findingId: number
  importance: ReportImportance
  reason: string
  basisClaimIds: string[]
  axes: ReportInsightAxes
  rank: number
}
export interface ReportInsightFact {
  id: string
  text: string
  claimType: 'FACT' | 'FORECAST' | 'OPINION'
  attributedTo: string | null
  findingId: number
  articleId: number
  evidenceSentenceIds: number[]
  groundedness: 'grounded'
}
export interface ReportInsightImplication {
  text: string
  mechanism: string
  basisClaimIds: string[]
  assumption: string
  falsifiedBy: string
}
export interface ReportInsightWatchItem {
  topic: string
  indicator: string
  trigger: string
  basisClaimIds: string[]
}
export interface ReportAudienceInsight {
  audience: Audience
  headline: string
  importance: ReportImportance
  overview: ReportInsightOverview[]
  issues: ReportInsightIssue[]
  facts: ReportInsightFact[]
  implications: ReportInsightImplication[]
  watchItems: ReportInsightWatchItem[]
  llmProvider: string
  llmModel: string
  createdAt: string
}
export interface ReportInsightResult {
  cached: boolean
  reportId: number
  inputHash: string
  promptVersion: string
  rubricVersion: string
  inputFindingCount: number
  insights: ReportAudienceInsight[]
}

// The server validates its own input hash. This key also separates locally refreshed report snapshots.
export function reportInsightSnapshotKey(report: ReportDetail) {
  return JSON.stringify([report.title, report.reportScope, report.reportDate, report.reportEndDate,
    (report.findings ?? []).map(finding => [finding.id, finding.articleId, finding.runId,
      finding.articleTitle, finding.canonicalUrl, finding.keyPoints])])
}
export function reportInsightKey(reportId: number, audience: Audience, snapshot: string) {
  return ['reports', reportId, 'insights', audience, snapshot] as const
}
export function isReportInsightAbsent(error: unknown) {
  return error instanceof ApiError && error.code === 'COMMON404' && error.status === 404
}
export function isReportInsightPreparing(error: unknown) {
  return error instanceof ApiError && error.code === 'COMMON409' && error.status === 409
    && error.message === '동일한 리포트 관점 인사이트 생성 요청이 진행 중입니다. 잠시 후 다시 확인해주세요.'
}
export function selectReportInsight(result: ReportInsightResult | undefined, reportId: number, audience: Audience) {
  if (!result || result.reportId !== reportId) return undefined
  return result.insights.find(insight => insight.audience === audience)
}
function verifyResult(result: ReportInsightResult, reportId: number, audience: Audience) {
  if (!selectReportInsight(result, reportId, audience)) {
    throw new ApiError('CONTRACT', '요청한 리포트와 관점에 맞는 분석 결과를 확인하지 못했습니다.')
  }
  return result
}
const PREPARING_POLL_INTERVAL_MS = 10_000
const PREPARING_SLOW_POLL_INTERVAL_MS = 30_000
const PREPARING_SLOW_AFTER_MS = 15 * 60_000
export function reportInsightOptions(reportId: number, audience: Audience, snapshot: string) {
  let preparingStartedAt: number | null = null
  return queryOptions({
    queryKey: reportInsightKey(reportId, audience, snapshot),
    queryFn: async ({ signal }) => {
      try {
        const result = verifyResult(await get<ReportInsightResult>(`/reports/${reportId}/insights`,
          { audience }, AbortSignal.any([signal, AbortSignal.timeout(30_000)])), reportId, audience)
        preparingStartedAt = null
        return result
      } catch (error) {
        if (isReportInsightPreparing(error)) preparingStartedAt ??= Date.now()
        else preparingStartedAt = null
        throw error
      }
    },
    staleTime: 0,
    retry: false,
    // Keep observing queued work even when earlier reports take longer than fifteen minutes.
    // The timer belongs to the mounted observer; missing results and real failures stop it.
    refetchInterval: query => {
      if (!isReportInsightPreparing(query.state.error)) return false
      return preparingStartedAt !== null && Date.now() - preparingStartedAt >= PREPARING_SLOW_AFTER_MS
        ? PREPARING_SLOW_POLL_INTERVAL_MS : PREPARING_POLL_INTERVAL_MS
    },
    retryOnMount: true,
    refetchOnWindowFocus: query => isReportInsightPreparing(query.state.error),
    refetchOnMount: 'always',
  })
}
export interface GenerateReportInsightRequest { reportId: number; audience: Audience; snapshot: string }
export function generateReportInsightOptions(client: QueryClient) {
  return {
    mutationKey: ['report-insights', 'generate'] as const,
    mutationFn: async ({ reportId, audience }: GenerateReportInsightRequest) => verifyResult(
      await post<ReportInsightResult>(`/reports/${reportId}/insights`, { audiences: [audience] }), reportId, audience),
    retry: false,
    onSuccess: async (result: ReportInsightResult, { reportId, audience, snapshot }: GenerateReportInsightRequest) => {
      const queryKey = reportInsightKey(reportId, audience, snapshot)
      await client.cancelQueries({ queryKey, exact: true })
      client.setQueryData(queryKey, result)
    },
    onError: (error: Error, { reportId, audience, snapshot }: GenerateReportInsightRequest) => {
      if (isReportInsightPreparing(error)) {
        void client.invalidateQueries({ queryKey: reportInsightKey(reportId, audience, snapshot), exact: true })
      }
    },
    onSettled: () => { void client.invalidateQueries({ queryKey: ['usage', 'llm'] }) },
  }
}
export function useReportInsight(reportId: number, audience: Audience, snapshot: string) {
  const options = useMemo(() => reportInsightOptions(reportId, audience, snapshot), [reportId, audience, snapshot])
  return useQuery(options)
}
export function useGenerateReportInsight() {
  const client = useQueryClient()
  return useMutation(generateReportInsightOptions(client))
}

export function useReportInsightGenerating(reportId: number, audience: Audience, snapshot: string) {
  return useIsMutating({
    mutationKey: ['report-insights', 'generate'],
    predicate: mutation => {
      const request = mutation.state.variables as GenerateReportInsightRequest | undefined
      return request?.reportId === reportId && request.audience === audience && request.snapshot === snapshot
    },
  }) > 0
}
