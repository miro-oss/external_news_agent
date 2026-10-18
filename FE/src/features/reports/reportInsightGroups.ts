import type { ReportInsightIssue } from '../../api/reportInsights'
import type { ReportContent, ReportFinding, ReportScope } from '../../api/types'
import { safeReportEvidenceUrl } from './reportChangesDisplay'

interface IssueGroup {
  key: string
  title: string
  issues: ReportInsightIssue[]
}

export function groupReportInsightIssues(
  issues: ReportInsightIssue[],
  findings: Map<number, ReportFinding>,
  reportScope: ReportScope,
  events: ReportContent['importantEvents'],
): IssueGroup[] {
  const memberships = new Map<number, number[]>()
  events.forEach((event, index) => {
    for (const id of new Set(event.sourceFindingIds)) {
      memberships.set(id, [...(memberships.get(id) ?? []), index])
    }
  })
  const groups: Array<IssueGroup & { identities: Set<string> }> = []
  for (const issue of [...issues].sort((a, b) => a.rank - b.rank)) {
    const finding = findings.get(issue.findingId)
    const eventIds = memberships.get(issue.findingId) ?? []
    const eventIndex = finding && eventIds.length === 1 ? eventIds[0] : undefined
    const identities = new Set<string>()
    if (eventIndex !== undefined) {
      identities.add(`event:${eventIndex}`)
    } else if (finding && eventIds.length === 0) {
      if (finding.articleId > 0) identities.add(`article:${finding.articleId}`)
      const url = safeReportEvidenceUrl(finding.canonicalUrl)
      if (url) identities.add(`url:${url}`)
      // Live issue membership must not regroup historical DAILY/WEEKLY evidence.
      if (reportScope === 'RUN' && finding.issueId != null) identities.add(`issue:${finding.issueId}`)
    }
    // Missing findings and ambiguous saved-event membership remain independent.
    if (!identities.size) identities.add(`finding:${issue.findingId}`)
    const matching = groups.filter(group => [...identities].some(id => group.identities.has(id)))
    const group = matching[0]
    if (!group) {
      groups.push({ key: [...identities][0], identities, issues: [issue],
        title: (eventIndex !== undefined ? events[eventIndex].title.trim() : '')
          || finding?.articleTitle || `보고서 근거 #${issue.findingId}` })
      continue
    }
    group.issues.push(issue)
    identities.forEach(id => group.identities.add(id))
    // A shared article can connect two RUN issue groups; retain every assessment.
    for (const other of matching.slice(1)) {
      group.issues.push(...other.issues)
      other.identities.forEach(id => group.identities.add(id))
      groups.splice(groups.indexOf(other), 1)
    }
    group.issues.sort((a, b) => a.rank - b.rank)
  }
  return groups.map(({ key, title, issues }) => ({ key, title, issues }))
}
