import type { ReportCollectionContext } from '../../api/types'

export interface MarkdownSection { title: string; body: string }

/** Read saved headings without treating examples inside fenced code as report sections. */
export function splitReportMarkdown(markdown: string, headingLevel = 2): MarkdownSection[] {
  const sections: MarkdownSection[] = []
  let current: MarkdownSection = { title: '', body: '' }
  let fence: string | null = null
  let firstContent = true
  const heading = new RegExp(`^#{${headingLevel}}\\s+(.+?)\\s*#*\\s*$`)
  for (const line of markdown.split(/\r?\n/)) {
    const code = line.match(/^\s{0,3}(`{3,}|~{3,})/)
    if (code) {
      if (!fence) fence = code[1]
      else if (code[1][0] === fence[0] && code[1].length >= fence.length) fence = null
    }
    if (headingLevel === 2 && firstContent && line.trim()) {
      firstContent = false
      if (/^#\s+/.test(line)) continue
    }
    const match = !fence && !code ? line.match(heading) : null
    if (match) {
      if (current.title || current.body.trim()) sections.push({ ...current, body: current.body.trim() })
      current = { title: match[1], body: '' }
    } else current.body += `${line}\n`
  }
  if (current.title || current.body.trim()) sections.push({ ...current, body: current.body.trim() })
  return sections
}

export interface LegacyReportGroup {
  kind: 'section' | 'events' | 'other' | 'notes'
  title: string
  sections: MarkdownSection[]
}

/** Keep saved order, collecting all secondary analysis into its first disclosure. */
export function groupLegacyReportSections(markdown: string): LegacyReportGroup[] {
  const groups: LegacyReportGroup[] = []
  for (const section of splitReportMarkdown(markdown)) {
    const kind = /수집 및 출처 참고|수집 상태/.test(section.title) ? 'notes'
      : /기타\s*분석|기사별\s*분석|보고서\s*제외/.test(section.title) ? 'other'
      : /중요 이벤트/.test(section.title) ? 'events' : 'section'
    if (kind === 'notes' && /제외 사항이 없습니다/.test(section.body)
      && section.body.split('\n').filter(line => line.trim()).length === 1) continue
    if ((kind === 'other' || kind === 'notes') && !section.body.trim()) continue
    const existing = kind === 'other' || kind === 'notes' ? groups.find(group => group.kind === kind) : undefined
    if (existing) {
      if (!existing.sections.some(item => item.body === section.body
        && (kind !== 'other' || secondaryAnalysisKind(item.title) === secondaryAnalysisKind(section.title)))) {
        existing.sections.push(section)
      }
    } else groups.push({
      kind,
      title: kind === 'other' ? '기타 분석' : kind === 'notes' ? '수집 상태' : section.title,
      sections: [section],
    })
  }
  return groups
}

function secondaryAnalysisKind(title: string) {
  return /보고서\s*제외/.test(title) ? 'excluded' : /기사별\s*분석/.test(title) ? 'article' : 'other'
}

function collectionTopics(contexts: ReportCollectionContext[], runId: number | null, topicId?: number) {
  const topics = contexts.filter(context => runId === null || context.runId === runId).flatMap(context => context.topics)
  const matching = topics.filter(topic => topic.topicId === topicId)
  return matching.length ? matching : topics
}

export function collectionKeywords(contexts: ReportCollectionContext[], runId: number | null, topicId?: number): string[] {
  return [...new Set(collectionTopics(contexts, runId, topicId).flatMap(topic => {
    const queryTerms: string[] = []
    let negated = false
    for (const token of topic.queryText?.match(/"[^"]+"|[^\s]+/g) ?? []) {
      if (/^NOT$/i.test(token)) { negated = true; continue }
      if (/^(AND|OR)$/i.test(token)) continue
      if (!negated && !token.startsWith('-')) queryTerms.push(token.replace(/^"|"$/g, ''))
      negated = false
    }
    const excluded = new Set(topic.excludedKeywords.map(term => term.trim().toLocaleLowerCase()))
    return [...topic.requiredKeywords, ...topic.optionalKeywords, ...queryTerms]
      .map(term => term.trim()).filter(term => term && !excluded.has(term.toLocaleLowerCase()))
  }))]
}

/** Report highlights come exclusively from the saved collection context, never today's topic settings. */
export function collectionHighlightTerms(contexts: ReportCollectionContext[], runId: number | null = null, topicId?: number): string[] {
  return [...new Set([
    ...collectionTopics(contexts, runId, topicId).map(topic => topic.topicName.trim()).filter(Boolean),
    ...collectionKeywords(contexts, runId, topicId),
  ])]
}

export interface HighlightPart { text: string; matched: boolean }

/** Literal matching only: user text never becomes markup or an executable regular expression. */
export function highlightKeywordParts(text: string, keywords: string[]): HighlightPart[] {
  const terms = [...new Set(keywords.map(term => term.trim()).filter(Boolean))]
    .sort((left, right) => right.length - left.length)
  if (terms.length === 0) return [{ text, matched: false }]
  const pattern = new RegExp(terms.map(term => term.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|'), 'giu')
  const parts: HighlightPart[] = []
  let cursor = 0
  for (const match of text.matchAll(pattern)) {
    const index = match.index
    if (index > cursor) parts.push({ text: text.slice(cursor, index), matched: false })
    parts.push({ text: match[0], matched: true })
    cursor = index + match[0].length
  }
  if (cursor < text.length) parts.push({ text: text.slice(cursor), matched: false })
  return parts.length ? parts : [{ text, matched: false }]
}

interface MarkdownTextTree {
  type: string
  value?: string
  tagName?: string
  properties?: Record<string, unknown>
  children?: MarkdownTextTree[]
}

/** Transform rendered prose text nodes only; URLs, HTML source, code, and element properties stay untouched. */
export function rehypeCollectionHighlights({ terms }: { terms: string[] }) {
  return (tree: MarkdownTextTree) => {
    function visit(node: MarkdownTextTree) {
      if (node.type === 'raw' || node.type === 'html'
        || ['code', 'pre', 'script', 'style', 'textarea', 'mark'].includes(node.tagName ?? '')) return
      if (!node.children) return
      node.children = node.children.flatMap(child => {
        if (child.type !== 'text' || !child.value) { visit(child); return [child] }
        return highlightKeywordParts(child.value, terms).map(part => part.matched
          ? { type: 'element', tagName: 'mark', properties: { className: ['collection-keyword-match'] },
            children: [{ type: 'text', value: part.text }] }
          : { type: 'text', value: part.text })
      })
    }
    visit(tree)
  }
}
