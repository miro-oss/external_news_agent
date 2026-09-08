import { useState, type ReactNode } from 'react'
import Markdown from 'react-markdown'
import type { ReportDetail, ReportFinding } from '../../api/types'
import { CollapsibleSection } from '../../components/CollapsibleSection'
import { splitReportMarkdown } from './reportReading'

export function ReportReadingContent({ report, onEvidenceSelect }: {
  report: ReportDetail
  onEvidenceSelect: (articleId: number, runId: number, sentences: number[]) => void
}) {
  const content = report.structuredContent
  if (!content) return <LegacyReportBody markdown={report.markdownBody} />
  const byId = new Map((report.findings ?? []).map(finding => [finding.id, finding]))
  const referenced = new Set([...content.importantEvents, ...content.watchItems].flatMap(item => item.sourceFindingIds))
  const other = (report.findings ?? []).filter(finding => !referenced.has(finding.id) && finding.keyPoints.length > 0)
  const notes = content.sourceNotes.filter(note => !/제외 사항이 없습니다/.test(note))
  function references(ids: number[]) {
    const findings = ids.map(id => byId.get(id)).filter((finding): finding is ReportFinding => Boolean(finding))
    return <div className="report-event-sources">{findings.map(finding => (
      <button type="button" className="text-button" key={finding.id}
        onClick={() => onEvidenceSelect(finding.articleId, finding.runId, [])}>
        {finding.articleTitle} ↗
      </button>
    ))}</div>
  }
  return (
    <div className="report-reading-content">
      <section className="card report-summary-card">
        <h3>핵심 요약</h3>
        {content.executiveSummary.length > 0
          ? <ul>{content.executiveSummary.map((summary, index) => <li key={index}>{summary}</li>)}</ul>
          : <p className="muted">근거가 확인된 요약이 없습니다.</p>}
      </section>
      <section className="card report-important-events">
        <h3>중요 이벤트</h3>
        {content.importantEvents.length > 0 ? <div className="report-event-list">
          {content.importantEvents.map((event, index) => <article className="report-event-card" key={index}>
            <h4>{event.title}</h4>
            <p>{event.summaryKo}</p>
            {event.significance && event.significance !== event.summaryKo && <p className="report-event-significance">{event.significance}</p>}
            {references(event.sourceFindingIds)}
          </article>)}
        </div> : <p className="muted">중요 이벤트가 없습니다.</p>}
      </section>
      {content.watchItems.length > 0 && <section className="card report-watch-items">
        <h3>관찰 항목</h3>
        {content.watchItems.map((item, index) => <article className="report-event-card" key={index}>
          <h4>{item.topic}</h4><p>{item.reason}</p>{references(item.sourceFindingIds)}
        </article>)}
      </section>}
      {other.length > 0 && <ReadingDisclosure title="기타 분석">
        {other.map(finding => <article className="report-event-card" key={finding.id}>
          <h4>{finding.articleTitle}</h4><p>{finding.summary}</p>{references([finding.id])}
        </article>)}
      </ReadingDisclosure>}
      {notes.length > 0 && <ReadingDisclosure title="수집 상태"><ul>{notes.map((note, index) => <li key={index}>{note}</li>)}</ul></ReadingDisclosure>}
    </div>
  )
}

function ReadingDisclosure({ title, children }: { title: string; children: ReactNode }) {
  const [open, setOpen] = useState(false)
  return <CollapsibleSection title={title} open={open} onToggle={() => setOpen(value => !value)}>{children}</CollapsibleSection>
}

function LegacyReportBody({ markdown }: { markdown: string }) {
  return <div className="report-reading-content">{splitReportMarkdown(markdown).map((section, index) => {
    if (/수집 및 출처 참고|수집 상태/.test(section.title)) {
      if (/제외 사항이 없습니다/.test(section.body) && section.body.split('\n').filter(Boolean).length === 1) return null
      return <ReadingDisclosure key={index} title="수집 상태"><MarkdownText text={section.body} /></ReadingDisclosure>
    }
    if (/기타 분석|기사별 분석|보고서 제외/.test(section.title)) {
      return <ReadingDisclosure key={index} title={section.title === '기사별 분석' ? '기사별 분석' : '기타 분석'}><MarkdownText text={section.body} /></ReadingDisclosure>
    }
    if (/중요 이벤트/.test(section.title)) {
      return <section className="card report-important-events" key={index}><h3>중요 이벤트</h3><div className="report-event-list">
        {splitReportMarkdown(section.body, 3).map((event, eventIndex) => <article className="report-event-card" key={eventIndex}>
          {event.title && <h4>{event.title}</h4>}<MarkdownText text={event.body} />
        </article>)}
      </div></section>
    }
    return <section className="card report-legacy-section" key={index}>
      {section.title && <h3>{/경영진 요약|오늘의 핵심/.test(section.title) ? '핵심 요약' : section.title}</h3>}
      <MarkdownText text={section.body} />
    </section>
  })}</div>
}

function MarkdownText({ text }: { text: string }) {
  return <div className="report-markdown-text"><Markdown components={{
    h1: ({ children }) => <h4>{children}</h4>,
    h2: ({ children }) => <h4>{children}</h4>,
    h3: ({ children }) => <h4>{children}</h4>,
    a: ({ href, children }) => <a href={href} target="_blank" rel="noreferrer">{children}</a>,
  }}>{text}</Markdown></div>
}
