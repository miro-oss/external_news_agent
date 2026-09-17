import type { ArticleSentence } from '../../api/types'

// Match complete menu labels only; a word such as "경제" inside article prose is content.
const NAVIGATION_LABEL = /^(최신\s*뉴스|생활\s*[·/]\s*문화|생활문화|IT\/과학|정치|경제|사회|생활|문화|스포츠|국제|날씨|산업|전국|세계)(?=$|[\s|·])/u
const SEPARATOR = /^[\s|·]+/u

function navigationPrefix(line: string) {
  const labels: string[] = []
  const starts: number[] = []
  let end = SEPARATOR.exec(line)?.[0].length ?? 0
  while (end < line.length) {
    const match = NAVIGATION_LABEL.exec(line.slice(end))
    if (!match) break
    starts.push(end)
    labels.push(match[1].replace(/[\s·/]/gu, ''))
    end += match[0].length
    end += SEPARATOR.exec(line.slice(end))?.[0].length ?? 0
  }
  return { labels, starts, end }
}

function articleStart(text: string): number {
  const labels = new Set<string>()
  let end = 0
  for (const line of text.matchAll(/([^\r\n]*)(?:\r\n|\r|\n|$)/gu)) {
    const value = line[1]
    if (!value.trim()) continue
    const prefix = navigationPrefix(value)
    if (prefix.labels.length === 0) break
    const menuOnly = prefix.end === value.length
    if (!menuOnly) {
      const seen = new Set<string>()
      for (let index = 0; index < prefix.labels.length; index++) {
        const label = prefix.labels[index]
        if (seen.has(label)) {
          // Once a menu category repeats, it may be the first word of the actual article.
          prefix.end = prefix.starts[index]
          prefix.labels = prefix.labels.slice(0, index)
          break
        }
        seen.add(label)
      }
    }
    // A menu merged with prose needs an explicit first marker. Ordinary prose such as
    // "정치 경제 사회 분야의 변화" must never lose its opening words.
    if (!menuOnly && (prefix.labels[0] !== '최신뉴스' || new Set(prefix.labels).size < 3)) break
    prefix.labels.forEach((label) => labels.add(label))
    end = line.index + prefix.end
    if (!menuOnly) break
  }
  return labels.size >= 3 ? text.length - text.slice(end).trimStart().length : 0
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
