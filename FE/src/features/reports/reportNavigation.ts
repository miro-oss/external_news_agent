import type { ReportScope } from '../../api/types'

export function reportScopeFromHash(hash: string): ReportScope {
  const scope = new URLSearchParams(hash.split('?')[1] ?? '').get('reportScope')
  return scope === 'DAILY' || scope === 'WEEKLY' ? scope : 'RUN'
}

export function reportHash(scope: ReportScope, reportId: number | null = null) {
  const params = new URLSearchParams({ reportScope: scope })
  if (reportId !== null) params.set('reportId', String(reportId))
  return `#/reports?${params}`
}
