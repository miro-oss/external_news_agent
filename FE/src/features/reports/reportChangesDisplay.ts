import type { ReportChangeItem, ReportChangesStatus, ReportChangeType } from '../../api/types'

export const REPORT_CHANGE_TYPES: readonly ReportChangeType[] = ['NEWLY_INCLUDED', 'UPDATED', 'REFUTATION', 'UNCHANGED', 'UNDETERMINED']
export const REPORT_CHANGE_LABELS: Record<ReportChangeType, string> = {
  NEWLY_INCLUDED: '새로 포함', UPDATED: '내용 업데이트', REFUTATION: '반박·정정 근거',
  UNCHANGED: '변화 확인 안 됨', UNDETERMINED: '판단 보류',
}
export const REPORT_CHANGES_MESSAGES: Record<ReportChangesStatus, string> = {
  PENDING: '보고서 변화 비교를 준비하고 있습니다.',
  RUNNING: '지난 보고서와 달라진 점을 확인하고 있습니다.',
  READY: '지난 보고서와의 비교가 완료되었습니다.',
  FAILED: '변화 비교를 완료하지 못했습니다. 보고서 본문은 확인할 수 있습니다.',
  NO_BASELINE: '비교할 이전 일일 보고서가 없습니다.',
  UNAVAILABLE: '비교 시점의 저장 자료가 없어 변화 비교를 제공할 수 없습니다.',
  NOT_APPLICABLE: '일일 통합 보고서에서 변화 비교를 제공합니다.',
}

export function groupReportChanges(items: ReportChangeItem[]) {
  const counts = Object.fromEntries(REPORT_CHANGE_TYPES.map(type => [type, 0])) as Record<ReportChangeType, number>
  for (const item of items) counts[item.type]++
  return { counts, visible: items.filter(item => item.type !== 'UNCHANGED'), unchanged: items.filter(item => item.type === 'UNCHANGED') }
}

export function reportDateGap(previous: string | null, current: string | null): number | null {
  if (!previous || !current || !/^\d{4}-\d{2}-\d{2}$/.test(previous) || !/^\d{4}-\d{2}-\d{2}$/.test(current)) return null
  const before = Date.parse(`${previous}T00:00:00Z`)
  const after = Date.parse(`${current}T00:00:00Z`)
  if (!Number.isFinite(before) || !Number.isFinite(after)) return null
  if (new Date(before).toISOString().slice(0, 10) !== previous || new Date(after).toISOString().slice(0, 10) !== current) return null
  const days = (after - before) / 86_400_000
  return days > 0 ? days : null
}

export function safeReportEvidenceUrl(value: string): string | null {
  try {
    const url = new URL(value)
    return ['https:', 'http:'].includes(url.protocol) && !url.username && !url.password ? url.href : null
  } catch { return null }
}
