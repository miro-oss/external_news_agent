import type { ReportDetail, ReportFinding, SensitivityLevel } from '../../api/types'

export function defaultSensitivity(findings: Pick<ReportFinding, 'sensitivity'>[]): '' | SensitivityLevel {
  return (['high', 'medium', 'low'] as const).find(level => findings.some(finding => finding.sensitivity.level === level)) ?? ''
}

export function categoryTone(category: string): string {
  if (/공급망/.test(category)) return 'supply'
  if (/기업/.test(category)) return 'company'
  if (/정책|규제/.test(category)) return 'policy'
  if (/제품|공정|기술/.test(category)) return 'technology'
  if (/시장|투자|금융/.test(category)) return 'market'
  return 'general'
}

/** Legacy issue labels describe included stories; they never stand in for saved collection keywords. */
export function dailyReportTopics(report: Pick<ReportDetail, 'sourceRunIds' | 'collectionContexts' | 'findings'>) {
  const contexts = report.collectionContexts ?? []
  const sourceRunIds = report.sourceRunIds ?? []
  const completeContext = sourceRunIds.length > 0 && sourceRunIds.every(runId =>
    contexts.some(context => context.runId === runId && context.topics.some(topic => topic.topicName.trim())))
  const uniqueNames = (names: Array<string | null | undefined>) => [...new Set(names.map(name => name?.trim()).filter((name): name is string => Boolean(name)))]
  if (completeContext) return {
    label: '통합 주제',
    names: uniqueNames(contexts.filter(context => sourceRunIds.includes(context.runId)).flatMap(context => context.topics.map(topic => topic.topicName))),
  }
  return {
    label: '포함된 이슈 주제',
    names: uniqueNames((report.findings ?? []).map(finding => finding.issue?.topicName)),
  }
}
