import { ApiError } from './client.ts'
import type { ApiEnvelope, ReportContent } from './types'

export type ReportWatchItem = ReportContent['watchItems'][number]

export async function promoteReportWatchItem(reportId: number, item: ReportWatchItem, signal?: AbortSignal) {
  const response = await fetch(`/api/news/reports/${reportId}/watch-items/promote`, {
    method: 'POST', cache: 'no-store', signal,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ topic: item.topic, reason: item.reason, sourceFindingIds: item.sourceFindingIds }),
  })
  let envelope: ApiEnvelope<{ reportId: number }>
  try { envelope = await response.json() }
  catch { throw new ApiError('NETWORK', '서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.', response.status) }
  if (!response.ok || !envelope.isSuccess) throw new ApiError(envelope.code, envelope.message, response.status)
  return envelope.result
}
