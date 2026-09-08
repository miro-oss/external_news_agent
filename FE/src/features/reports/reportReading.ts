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

export function collectionKeywords(contexts: ReportCollectionContext[], runId: number | null, topicId?: number): string[] {
  const topics = contexts.filter(context => runId === null || context.runId === runId).flatMap(context => context.topics)
  const matching = topics.filter(topic => topic.topicId === topicId)
  return [...new Set((matching.length ? matching : topics).flatMap(topic => {
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
