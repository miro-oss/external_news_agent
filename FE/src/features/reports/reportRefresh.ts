import type { QueryClient } from '@tanstack/react-query'
import type { ReportDetail } from '../../api/types'

export async function refreshReportReading(
  client: QueryClient,
  report: Pick<ReportDetail, 'id' | 'reportScope'>,
  refetchReport: () => Promise<unknown>,
) {
  const result = await refetchReport()
  // The list and comparison panel show their own fetch errors; the report read stays successful.
  await Promise.all([
    client.invalidateQueries({ queryKey: ['reports', 'list'] }),
    report.reportScope === 'DAILY'
      ? client.invalidateQueries({ queryKey: ['reports', report.id, 'changes'], exact: true })
      : Promise.resolve(),
  ])
  return result
}
