import { useId, useRef, useState, type ReactNode } from 'react'
import Markdown from 'react-markdown'
import type { ReportDetail } from '../../api/types'
import { CollapsibleSection } from '../../components/CollapsibleSection'
import { prefersReducedMotion } from '../../lib/motion'
import { collectionHighlightTerms, groupLegacyReportSections, rehypeCollectionHighlights, reportSourceFindings, splitReportMarkdown } from './reportReading'
import { ReportKeywordText } from './ReportKeywordText'

export function ReportReadingContent({ report, onEvidenceSelect }: {
  report: ReportDetail
  onEvidenceSelect: (articleId: number, runId: number, sentences: number[]) => void
}) {
  const content = report.structuredContent
  const terms = collectionHighlightTerms(report.collectionContexts ?? [])
  if (!content) return <LegacyReportBody key={report.id} markdown={report.markdownBody} terms={terms} />
  const byId = new Map((report.findings ?? []).map(finding => [finding.id, finding]))
  const referenced = new Set([...content.importantEvents, ...content.watchItems].flatMap(item => item.sourceFindingIds))
  const other = (report.findings ?? []).filter(finding => !referenced.has(finding.id) && finding.keyPoints.length > 0)
  const notes = content.sourceNotes.filter(note => !/제외 사항이 없습니다/.test(note))
  function references(ids: number[]) {
    const findings = reportSourceFindings(ids, byId)
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
        <PaginatedAnalysis key={report.id}>
          {other.map(finding => <article className="report-event-card report-analysis-card" key={finding.id}>
            <h4><ReportKeywordText text={finding.articleTitle} terms={terms} /></h4><p><ReportKeywordText text={finding.summary} terms={terms} /></p>{references([finding.id])}
          </article>)}
        </PaginatedAnalysis>
      </ReadingDisclosure>}
      {notes.length > 0 && <ReadingDisclosure title="수집 상태"><ul>{notes.map((note, index) => <li key={index}><ReportKeywordText text={note} terms={terms} /></li>)}</ul></ReadingDisclosure>}
    </div>
  )
}

function ReadingDisclosure({ title, children }: { title: string; children: ReactNode }) {
  const [open, setOpen] = useState(false)
  return <CollapsibleSection title={title} open={open} onToggle={() => setOpen(value => !value)}>{children}</CollapsibleSection>
}

const ANALYSIS_PER_PAGE = 3

function PaginatedAnalysis({ children }: { children: ReactNode[] }) {
  const [page, setPage] = useState(0)
  const listId = useId()
  const listRef = useRef<HTMLDivElement>(null)
  const pageCount = Math.ceil(children.length / ANALYSIS_PER_PAGE)
  const currentPage = Math.min(page, Math.max(0, pageCount - 1))

  function changePage(nextPage: number) {
    if (nextPage === currentPage) return
    setPage(nextPage)
    requestAnimationFrame(() => {
      listRef.current?.focus({ preventScroll: true })
      listRef.current?.scrollIntoView({ behavior: prefersReducedMotion() ? 'auto' : 'smooth', block: 'start' })
    })
  }

  return <>
    <div className="report-event-list report-analysis-list" id={listId} ref={listRef} tabIndex={-1}
      role="group" aria-label={`기타 분석 ${currentPage + 1}페이지`}>
      {children.slice(currentPage * ANALYSIS_PER_PAGE, (currentPage + 1) * ANALYSIS_PER_PAGE)}
    </div>
    {pageCount > 1 && <nav className="pagination report-analysis-pagination" aria-label="기타 분석 페이지 이동">
      {Array.from({ length: pageCount }, (_, index) => <button type="button" className="secondary-button" key={index}
        aria-label={`기타 분석 ${index + 1}페이지`} aria-current={index === currentPage ? 'page' : undefined}
        aria-controls={listId} onClick={() => changePage(index)}>{index + 1}</button>)}
    </nav>}
  </>
}

function LegacyReportBody({ markdown, terms }: { markdown: string; terms: string[] }) {
  return <div className="report-reading-content">{groupLegacyReportSections(markdown).map((group, index) => {
    if (group.kind === 'other') {
      return <ReadingDisclosure key={index} title={group.title}>
        <PaginatedAnalysis>
          {group.sections.flatMap((section, sectionIndex) => splitReportMarkdown(section.body, 3).map((issue, issueIndex) => {
            const sectionTitle = /기타\s*분석/.test(section.title) ? '' : section.title
            const title = issue.title && sectionTitle ? `${sectionTitle} · ${issue.title}` : issue.title || sectionTitle
            return <article className="report-event-card report-analysis-card" key={`${sectionIndex}:${issueIndex}`}>
              {title && <h4><ReportKeywordText text={title} terms={terms} /></h4>}
              <MarkdownText text={issue.body} terms={terms} />
            </article>
          }))}
        </PaginatedAnalysis>
      </ReadingDisclosure>
    }
    if (group.kind === 'notes') {
      return <ReadingDisclosure key={index} title={group.title}>
        {group.sections.map((section, sectionIndex) => <article className="report-event-card" key={sectionIndex}>
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
