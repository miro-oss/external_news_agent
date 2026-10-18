import type { QueryClient } from '@tanstack/react-query'
import { get, post } from './client.ts'
import type { PageResult, ReportDetail } from './types'

export interface DartCompany {
  corpCode: string
  corpName: string
  stockCode: string
}

export interface DartCompanySearch extends PageResult<DartCompany> {
  status: 'READY' | 'PREPARING' | 'DISABLED' | 'FAILED'
  message: string
}

export interface DartFiling {
  rceptNo: string
  reportName: string
  filedAt: string
  url: string
  corrected: boolean
}

export interface DartQuarter {
  year: number
  quarter: number
  revenue: string | null
  operatingProfit: string | null
  operatingMargin: number | null
  derived: boolean
  sources: DartFiling[]
}

export interface DartFinancialData {
  fsDiv: 'CFS' | 'OFS' | null
  currency: string | null
  fetchedAt: string
  quarters: DartQuarter[]
  disclosures: DartFiling[]
  notes: string[]
}

export interface ReportDartCompany {
  company: DartCompany
  status: 'PENDING' | 'READY' | 'PARTIAL' | 'FAILED' | 'DISABLED'
  message: string
  data: DartFinancialData | null
}

export interface ReportDart {
  reportId: number
  companies: ReportDartCompany[]
}

export function dartCompanySearchOptions(query: string, page = 0) {
  const trimmed = query.trim()
  return {
    queryKey: ['dart-companies', trimmed, page],
    queryFn: ({ signal }: { signal: AbortSignal }) => get<DartCompanySearch>('/dart/companies', { query: trimmed, page, size: 20 }, signal),
    enabled: trimmed.length >= 2 && trimmed.length <= 100 && page >= 0,
    staleTime: 60_000,
    retry: false,
    refetchOnWindowFocus: false,
  }
}

/** Use the report's frozen company selection, with a legacy fallback only for RUN snapshots. */
export function reportDartCompanies(report: Pick<ReportDetail, 'reportScope' | 'topicId' | 'dartCompanies' | 'collectionContexts'>): DartCompany[] {
  const supported = report.reportScope === 'RUN' || (report.reportScope === 'WEEKLY' && report.topicId != null)
  if (!supported) return []
  if (report.dartCompanies !== undefined) return report.dartCompanies
  if (report.reportScope !== 'RUN') return []
  const companies = new Map<string, DartCompany>()
  for (const context of report.collectionContexts ?? []) {
    for (const topic of context.topics) {
      if (topic.dartCompany && !companies.has(topic.dartCompany.corpCode)) {
        companies.set(topic.dartCompany.corpCode, topic.dartCompany)
      }
    }
  }
  return [...companies.values()]
}

export const DART_POLL_WINDOW = 5 * 60_000
export const reportDartKey = (reportId: number) => ['report-dart', reportId] as const

export function dartPollInterval(data: ReportDart | undefined, pollUntil: number, now = Date.now()): number | false {
  return now < pollUntil && data?.companies.some(company => company.status === 'PENDING') ? 15_000 : false
}

export function reportDartOptions(reportId: number, pollUntil: number) {
  return {
    queryKey: reportDartKey(reportId),
    queryFn: ({ signal }: { signal: AbortSignal }) => get<ReportDart>(`/reports/${reportId}/dart`, undefined, signal),
    staleTime: 60_000,
    retry: false,
    refetchOnWindowFocus: false,
    refetchIntervalInBackground: false,
    refetchInterval: (query: { state: { data: ReportDart | undefined; status: string } }) =>
      query.state.status === 'success' ? dartPollInterval(query.state.data, pollUntil) : false,
  }
}

export function retryReportDartOptions(client: QueryClient, reportId: number) {
  const queryKey = reportDartKey(reportId)
  return {
    // No body is defined by the Notion contract.
    mutationFn: () => post<ReportDart>(`/reports/${reportId}/dart/retry`, undefined),
    retry: false,
    onMutate: () => client.cancelQueries({ queryKey }),
    onSuccess: async (result: ReportDart) => {
      await client.cancelQueries({ queryKey })
      client.setQueryData(queryKey, result)
    },
  }
}
