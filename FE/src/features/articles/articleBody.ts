import type { ArticleSentence } from '../../api/types'

// Match complete menu labels only; a word such as "경제" inside article prose is content.
const NAVIGATION_LABEL = /^(최신\s*뉴스|생활\s*[·/]\s*문화|생활문화|IT\/과학|정치|경제|사회|생활|문화|스포츠|국제|날씨|산업|전국|세계)(?=$|[\s|·])/u
const SEPARATOR = /^[\s|·]+/u

function navigationPrefix(line: string) {
  const labels: string[] = []
  let end = SEPARATOR.exec(line)?.[0].length ?? 0
  let delimitedEnd = 0
  let delimitedLabelCount = 0
  while (end < line.length) {
    const match = NAVIGATION_LABEL.exec(line.slice(end))
    if (!match) break
    labels.push(match[1].replace(/[\s·/]/gu, ''))
    end += match[0].length
    const separator = SEPARATOR.exec(line.slice(end))?.[0] ?? ''
    end += separator.length
    if (separator.includes('|')) {
      delimitedEnd = end
      delimitedLabelCount = labels.length
    }
  }
  return { labels, end, delimitedEnd, delimitedLabelCount }
}

function articleStart(text: string): number {
  const groupLabels = new Set<string>()
  let end = 0
  for (const line of text.matchAll(/([^\r\n]*)(?:\r\n|\r|\n|$)/gu)) {
    const value = line[1]
    if (!value.trim()) continue
    const prefix = navigationPrefix(value)
    if (prefix.labels.length === 0) break
    const menuOnly = prefix.end === value.length
    if (!menuOnly) {
      // Spaces and middle dots can belong to prose, so only a pipe confirms an inline boundary.
      prefix.labels = prefix.labels.slice(0, prefix.delimitedLabelCount)
      prefix.end = prefix.delimitedEnd
      if (prefix.labels[0] !== '최신뉴스' || new Set(prefix.labels).size < 3) break
    }
    // A repeated explicit menu starts a new group, which must qualify independently.
    if (menuOnly && prefix.labels[0] === '최신뉴스' && groupLabels.size >= 3) groupLabels.clear()
    if (prefix.labels.some((label) => groupLabels.has(label))) break
    prefix.labels.forEach((label) => groupLabels.add(label))
    // Keep the previous confirmed boundary when a new group is only a lone heading.
    if (groupLabels.size >= 3) end = line.index + prefix.end
    if (!menuOnly) break
  }
  return end > 0 ? text.length - text.slice(end).trimStart().length : 0
}

export function withoutLeadingNavigation(text: string): string {
  return text.slice(articleStart(text))
}

export function withoutLeadingSentenceNavigation(sentences: ArticleSentence[]): ArticleSentence[] {
  const start = articleStart(sentences.map((sentence) => sentence.text).join('\n'))
  if (start === 0) return sentences

  let offset = 0
  for (let index = 0; index < sentences.length; index++) {
    const sentence = sentences[index]
    if (offset + sentence.text.length > start) {
      const text = sentence.text.slice(Math.max(0, start - offset))
      // Keep the original evidence index even when navigation shared the first sentence.
      return [{ ...sentence, text }, ...sentences.slice(index + 1)]
    }
    offset += sentence.text.length + 1
  }
  return []
}
