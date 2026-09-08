import { highlightKeywordParts } from './reportReading'

export function ReportKeywordText({ text, terms }: { text: string; terms: string[] }) {
  return <>{highlightKeywordParts(text, terms).map((part, index) => part.matched
    ? <mark className="collection-keyword-match" key={index}>{part.text}</mark>
    : part.text)}</>
}
