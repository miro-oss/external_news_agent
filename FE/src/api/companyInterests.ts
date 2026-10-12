import { ApiError } from './client.ts'
import type { ApiEnvelope, ReportScope } from './types'

export type CompanyInterestStatus = 'DRAFT' | 'ACTIVE' | 'REVOKED'
export interface CompanyInterestApplication {
  reportId: number
  reportScope: ReportScope
  appliedAt: string
  evaluatedCount: number
  suppressedCount: number
}
export interface CompanyInterestCriterion {
  id: number
  feedbackId: number
  sourceReportId: number
  eventTitle: string
  comment: string
  diagnosis: string | null
  topics: Array<{ id: number; name: string }>
  topicId: number | null
  instruction: string
  reason: string | null
  status: CompanyInterestStatus
  version: number
  createdAt: string
  updatedAt: string
  activatedAt: string | null
  lastApplication: CompanyInterestApplication | null
}
export interface CompanyInterestUpdate {
  version: number
  instruction: string
  topicId: number | null
  status: CompanyInterestStatus
}
export interface CompanyInterests { criteria: CompanyInterestCriterion[] }

async function request<T>(path: string, init: RequestInit): Promise<T> {
  const response = await fetch(`/api/news/company-interests${path}`, {
    ...init, cache: 'no-store', headers: { 'Content-Type': 'application/json' },
  })
  let envelope: ApiEnvelope<T>
  try { envelope = await response.json() }
  catch { throw new ApiError('NETWORK', '서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.', response.status) }
  if (!response.ok || !envelope.isSuccess) throw new ApiError(envelope.code, envelope.message, response.status)
  return envelope.result
}
export function loadCompanyInterests(signal?: AbortSignal) {
  return request<CompanyInterests>('', { method: 'GET', signal })
}
export function updateCompanyInterest(id: number, update: CompanyInterestUpdate, signal?: AbortSignal) {
  return request<CompanyInterestCriterion>(`/${id}`, { method: 'PATCH', body: JSON.stringify(update), signal })
}
