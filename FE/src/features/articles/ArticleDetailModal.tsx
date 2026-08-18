import { useEffect, useRef } from 'react'
import { useArticle } from '../../api/queries'
import type { ArticleAnalysis } from '../../api/types'

interface Props {
  articleId: number | null
  onClose: () => void
}

export function ArticleDetailModal({ articleId, onClose }: Props) {
  const article = useArticle(articleId)
  const closeButton = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (articleId === null) return undefined
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKeyDown)
    document.body.classList.add('modal-open')
    closeButton.current?.focus()
    return () => {
      document.removeEventListener('keydown', onKeyDown)
      document.body.classList.remove('modal-open')
    }
  }, [articleId, onClose])

  if (articleId === null) return null

  return (
    <div className="modal-backdrop" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <div className="article-modal" role="dialog" aria-modal="true" aria-labelledby="article-modal-title">
        <button ref={closeButton} type="button" className="modal-close" aria-label="본문보기 닫기" onClick={onClose}>×</button>
        {article.isPending && <div className="modal-state">기사를 불러오는 중입니다.</div>}
        {article.isError && <div className="modal-state error" role="alert">{article.error.message}</div>}
        {article.data && (
          <>
            <header className="modal-header">
              <div className="modal-kicker">
                <span>{article.data.topicName}</span>
                <span>·</span>
                <span>{article.data.publisher || article.data.sourceName}</span>
              </div>
              <h2 id="article-modal-title">{article.data.title}</h2>
              <div className="modal-meta">
                <span>{formatDate(article.data.publishedAt)}</span>
                <span>{(article.data.language ?? '—').toUpperCase()}</span>
                <span>{article.data.fetchStatus === 'OK' ? '전문 확보' : '본문 제한'}</span>
              </div>
              <a className="original-link" href={article.data.canonicalUrl} target="_blank" rel="noreferrer">
                원문 열기 <span aria-hidden="true">↗</span>
              </a>
            </header>

            {article.data.analysis ? <AnalysisPanel analysis={article.data.analysis} /> : (
              <div className="analysis-empty">이 실행의 분석 결과가 아직 없습니다.</div>
            )}

            <section className="article-body-section">
              <div className="section-heading">
                <p className="eyebrow">SOURCE TEXT</p>
                <h3>기사 본문</h3>
              </div>
              {article.data.bodyText && article.data.sentences.length > 0 ? (
                <div className="sentence-list">
                  {article.data.sentences.map((sentence) => (
                    <p id={`sentence-${sentence.index}`} key={sentence.index}>
                      <span className="sentence-index">{String(sentence.index + 1).padStart(2, '0')}</span>
                      <span>{sentence.text}</span>
                    </p>
                  ))}
                </div>
              ) : (
                <div className="blocked-body">
                  <strong>본문을 가져올 수 없는 기사입니다.</strong>
                  <p>페이월 또는 수집 정책에 따라 제목·한국어 요약·원문 링크만 제공합니다.</p>
                </div>
              )}
            </section>
          </>
        )}
      </div>
    </div>
  )
}

function AnalysisPanel({ analysis }: { analysis: ArticleAnalysis }) {
  return (
    <section className="analysis-panel">
      <div className="analysis-title-row">
        <div>
          <p className="eyebrow">STUB ANALYSIS</p>
          <h3>한국어 요약</h3>
        </div>
        <div className="analysis-labels">
          <span>{analysis.category}</span>
          <span>{analysis.riskLevel === 'high' ? '높은 위험' : analysis.riskLevel === 'medium' ? '중간 위험' : '낮은 위험'}</span>
          <span>{analysis.relevance === 'important' ? '중요' : analysis.relevance === 'watch' ? '관찰' : '참고'}</span>
        </div>
      </div>
      <p className="analysis-summary">{analysis.summary}</p>
      {analysis.intent && <p className="intent">의도 · {analysis.intent}</p>}
      <div className="key-points">
        {analysis.keyPoints.map((point, index) => (
          <div className="key-point" key={`${point.text}-${index}`}>
            <span className="key-point-number">{index + 1}</span>
            <div>
              <p>{point.text}</p>
              <span className={`groundedness ${point.groundedness}`}>{point.groundedness}</span>
              {point.evidence.map((evidence) => <span className="evidence" key={evidence}>근거 {evidence + 1}</span>)}
            </div>
          </div>
        ))}
      </div>
    </section>
  )
}

function formatDate(value: string | null) {
  if (!value) return '발행일 미상'
  return new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}
