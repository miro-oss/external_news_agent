import { createContext } from 'react'
import type { ReportDartCompany } from '../../api/dart'

export interface ReportDartNavigation {
  reportId: number
  entries: ReportDartCompany[]
  selection: { corpCode: string; findingIds: number[] | null; origin: HTMLElement | null } | null
  select: (corpCode: string, findingIds: number[] | null, origin?: HTMLElement) => void
}

export const ReportDartContext = createContext<ReportDartNavigation | null>(null)

export const reportDartPanelId = (reportId: number) => `report-${reportId}-companies`

/** Company identity is supplied by the server, never inferred from a title or a live issue. */
export function issueDartCompanies(entries: ReportDartCompany[], findingIds: number[]) {
  const sources = new Set(findingIds)
  return entries.filter(entry => entry.issueLinks?.some(link => sources.has(link.findingId)))
}
