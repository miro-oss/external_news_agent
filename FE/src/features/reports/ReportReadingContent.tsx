import { useState, type ReactNode } from 'react'
import Markdown from 'react-markdown'
import type { ReportDetail, ReportFinding } from '../../api/types'
import { CollapsibleSection } from '../../components/CollapsibleSection'
import { collectionHighlightTerms, groupLegacyReportSections, rehypeCollectionHighlights, splitReportMarkdown } from './reportReading'
import { ReportKeywordText } from './ReportKeywordText'

export function ReportReadingContent({ report, onEvidenceSelect }: {
  report: ReportDetail
  onEvidenceSelect: (articleId: number, runId: number, sentences: number[]) => void
}) {
  const content = report.structuredContent
  const terms = collectionHighlightTerms(report.collectionContexts ?? [])
  if (!content) return <LegacyReportBody markdown={report.markdownBody} terms={terms} />
  const byId = new Map((report.findings ?? []).map(finding => [finding.id, finding]))
  const referenced = new Set([...content.importantEvents, ...content.watchItems].flatMap(item => item.sourceFindingIds))
  const other = (report.findings ?? []).filter(finding => !referenced.has(finding.id) && finding.keyPoints.length > 0)
  const notes = content.sourceNotes.filter(note => !/제외 사항이 없습니다/.test(note))
  function references(ids: number[]) {
    const findings = ids.map(id => byId.get(id)).filter((finding): finding is ReportFinding => Boolean(finding))
    return <div className="report-event-sources">{findings.map(finding => (
      <button type="button" className="text-button" key={finding.id}
        onClick={() => onEvidenceSelect(finding.articleId, finding.runId, [])}>
        <ReportKeywordText text={finding.articleTitle} terms={terms} /> ↗
      </button>
    ))}</div>
  }
  return (
    <div className="report-reading-content">
      <section className="card report-summary-card">
        <h3>핵심 요약</h3>
        {content.executiveSummary.length > 0
          ? <ul>{content.executiveSummary.map((summary, index) => <li key={index}><ReportKeywordText text={summary} terms={terms} /></li>)}</ul>
          : <p className="muted">근거가 확인된 요약이 없습니다.</p>}
      </section>
      <section className="card report-important-events">
        <h3>중요 이벤트</h3>
        {content.importantEvents.length > 0 ? <div className="report-event-list">
          {content.importantEvents.map((event, index) => <article className="report-event-card" key={index}>
            <h4><ReportKeywordText text={event.title} terms={terms} /></h4>
            <p><ReportKeywordText text={event.summaryKo} terms={terms} /></p>
            {event.significance && event.significance !== event.summaryKo && <p className="report-event-significance"><ReportKeywordText text={event.significance} terms={terms} /></p>}
            {references(event.sourceFindingIds)}
          </article>)}
        </div> : <p className="muted">중요 이벤트가 없습니다.</p>}
      </section>
      {content.watchItems.length > 0 && <section className="card report-watch-items">
        <h3>관찰 항목</h3>
        {content.watchItems.map((item, index) => <article className="report-event-card" key={index}>
          <h4><ReportKeywordText text={item.topic} terms={terms} /></h4><p><ReportKeywordText text={item.reason} terms={terms} /></p>{references(item.sourceFindingIds)}
        </article>)}
      </section>}
      {other.length > 0 && <ReadingDisclosure title="기타 분석">
        {other.map(finding => <article className="report-event-card" key={finding.id}>
          <h4><ReportKeywordText text={finding.articleTitle} terms={terms} /></h4><p><ReportKeywordText text={finding.summary} terms={terms} /></p>{references([finding.id])}
        </article>)}
      </ReadingDisclosure>}
      {notes.length > 0 && <ReadingDisclosure title="수집 상태"><ul>{notes.map((note, index) => <li key={index}><ReportKeywordText text={note} terms={terms} /></li>)}</ul></ReadingDisclosure>}
    </div>
  )
}

function ReadingDisclosure({ title, children }: { title: string; children: ReactNode }) {
  const [open, setOpen] = useState(false)
  return <CollapsibleSection title={title} open={open} onToggle={() => setOpen(value => !value)}>{children}</CollapsibleSection>
}

function LegacyReportBody({ markdown, terms }: { markdown: string; terms: string[] }) {
  return <div className="report-reading-content">{groupLegacyReportSections(markdown).map((group, index) => {
    if (group.kind === 'notes' || group.kind === 'other') {
      return <ReadingDisclosure key={index} title={group.title}>
        {group.sections.map((section, sectionIndex) => <article className="report-event-card" key={sectionIndex}>
          {group.kind === 'other' && !/기타\s*분석/.test(section.title)
            && <h4><ReportKeywordText text={section.title} terms={terms} /></h4>}
          <MarkdownText text={section.body} terms={terms} />
        </article>)}
      </ReadingDisclosure>
    }
    const section = group.sections[0]
    if (group.kind === 'events') {
      return <section className="card report-important-events" key={index}><h3>중요 이벤트</h3><div className="report-event-list">
        {splitReportMarkdown(section.body, 3).map((event, eventIndex) => <article className="report-event-card" key={eventIndex}>
          {event.title && <h4><ReportKeywordText text={event.title} terms={terms} /></h4>}<MarkdownText text={event.body} terms={terms} />
        </article>)}
      </div></section>
    }
    return <section className="card report-legacy-section" key={index}>
      {section.title && <h3>{/경영진 요약|오늘의 핵심/.test(section.title) ? '핵심 요약' : <ReportKeywordText text={section.title} terms={terms} />}</h3>}
      <MarkdownText text={section.body} terms={terms} />
    </section>
  })}</div>
}

function MarkdownText({ text, terms }: { text: string; terms: string[] }) {
  return <div className="report-markdown-text"><Markdown rehypePlugins={[[rehypeCollectionHighlights, { terms }]]} components={{
    h1: ({ children }) => <h4>{children}</h4>,
    h2: ({ children }) => <h4>{children}</h4>,
    h3: ({ children }) => <h4>{children}</h4>,
    a: ({ href, children }) => <a href={href} target="_blank" rel="noreferrer">{children}</a>,
  }}>{text}</Markdown></div>
}
