import { useEffect, useRef, useState } from 'react'
import { useArticle } from '../../api/queries'
import {
  AUDIENCES,
  AUDIENCE_LABELS,
  type ArticleAnalysis,
  type Audience,
} from '../../api/types'
import { formatMediumDate } from '../../lib/datetime'

interface Props {
  articleId: number | null
  defaultAudience?: Audience
  onClose: () => void
}

export function ArticleDetailModal({ articleId, defaultAudience = 'CHIP_MAKER', onClose }: Props) {
  const article = useArticle(articleId)
  const closeButton = useRef<HTMLButtonElement>(null)
  const [evidenceSelection, setEvidenceSelection] = useState<{
    articleId: number | null
    sentences: number[]
  }>({ articleId: null, sentences: [] })
  const [perspectiveSelection, setPerspectiveSelection] = useState<{
    articleId: number | null
    audience: Audience
  }>({ articleId: null, audience: defaultAudience })

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

  const highlightedSentences = evidenceSelection.articleId === articleId
    ? evidenceSelection.sentences
    : []
  const selectedAudience = perspectiveSelection.articleId === articleId
    ? perspectiveSelection.audience
    : defaultAudience

  const highlightEvidence = (evidence: number[]) => {
    setEvidenceSelection({ articleId, sentences: evidence })
    const firstSentence = evidence[0]
    if (firstSentence === undefined) return
    requestAnimationFrame(() => {
      document.getElementById(`article-${articleId}-sentence-${firstSentence}`)?.scrollIntoView({
        behavior: 'smooth',
        block: 'center',
      })
    })
  }

  return (
    <div className="modal-backdrop" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <div
        className="article-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby={article.data ? 'article-modal-title' : undefined}
        aria-label={article.data ? undefined : '기사 상세'}
      >
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
                <span>{formatMediumDate(article.data.publishedAt)}</span>
                <span>{(article.data.language ?? '—').toUpperCase()}</span>
                <span>{article.data.fetchStatus === 'OK' ? '전문 확보' : '본문 제한'}</span>
              </div>
              <a className="original-link" href={article.data.canonicalUrl} target="_blank" rel="noreferrer">
                원문 열기 <span aria-hidden="true">↗</span>
              </a>
            </header>

            {article.data.analysis ? (
              <AnalysisPanel
                analysis={article.data.analysis}
                selectedAudience={selectedAudience}
                onAudienceSelect={(audience) => setPerspectiveSelection({ articleId, audience })}
                onEvidenceSelect={highlightEvidence}
              />
            ) : (
              <div className="analysis-empty">이 실행의 분석 결과가 아직 없습니다.</div>
            )}

            <section className="article-body-section">
              <div className="section-heading">
                <h3>기사 본문</h3>
              </div>
              {article.data.bodyText && article.data.sentences.length > 0 ? (
                <div className="sentence-list">
                  {article.data.sentences.map((sentence) => (
                    <p
                      id={`article-${articleId}-sentence-${sentence.index}`}
                      className={highlightedSentences.includes(sentence.index) ? 'highlighted' : undefined}
                      key={sentence.index}
                    >
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

function AnalysisPanel({
  analysis,
  selectedAudience,
  onAudienceSelect,
  onEvidenceSelect,
}: {
  analysis: ArticleAnalysis
  selectedAudience: Audience
  onAudienceSelect: (audience: Audience) => void
  onEvidenceSelect: (evidence: number[]) => void
}) {
  const perspectiveTags = analysis.perspectiveTags ?? []
  const selectedPerspective = perspectiveTags.find(
    (tag) => tag.audience === selectedAudience,
  )

  return (
    <section className="analysis-panel">
      <div className="analysis-title-row">
        <div>
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
      <div className="perspective-tabs" role="tablist" aria-label="독자 관점별 분석">
        {AUDIENCES.map((audience) => {
          const tag = perspectiveTags.find((item) => item.audience === audience)
          return (
            <button
              key={audience}
              type="button"
              role="tab"
              aria-selected={selectedAudience === audience}
              className={selectedAudience === audience ? 'perspective-tab active' : 'perspective-tab'}
              onClick={() => onAudienceSelect(audience)}
            >
              {AUDIENCE_LABELS[audience]}
              <small>{perspectiveRelevanceLabel(tag?.relevance ?? 'none')}</small>
            </button>
          )
        })}
      </div>
      <div className="perspective-reason" role="tabpanel">
        {selectedPerspective?.hook ? (
          <>
            <span className={`perspective-level level-${selectedPerspective.relevance}`}>
              관련도 {perspectiveRelevanceLabel(selectedPerspective.relevance)}
            </span>
            <p>{selectedPerspective.hook}</p>
            <button
              type="button"
              className="perspective-evidence-button"
              onClick={() => onEvidenceSelect(selectedPerspective.evidenceSentenceIds)}
            >
              {selectedPerspective.evidenceSentenceIds.map((id) => `근거 ${id + 1}`).join(' · ')}
            </button>
          </>
        ) : (
          <p className="perspective-empty">이 관점과 직접 연결되는 근거가 없습니다.</p>
        )}
      </div>
      <div className="key-points">
        {analysis.keyPoints.map((point, index) => (
          <div className="key-point" key={`${point.text}-${index}`}>
            <span className="key-point-number">{index + 1}</span>
            <button
              type="button"
              className="key-point-content"
              disabled={point.evidence.length === 0}
              onClick={() => onEvidenceSelect(point.evidence)}
            >
              <span className="key-point-text">{point.text}</span>
              <span className={`groundedness ${point.groundedness}`}>{groundednessLabel(point.groundedness)}</span>
              {point.evidence.map((evidence) => <span className="evidence" key={evidence}>근거 {evidence + 1}</span>)}
            </button>
          </div>
        ))}
      </div>
    </section>
  )
}

function perspectiveRelevanceLabel(value: 'none' | 'low' | 'medium' | 'high') {
  return { none: '해당 없음', low: '낮음', medium: '보통', high: '높음' }[value]
}

function groundednessLabel(value: ArticleAnalysis['keyPoints'][number]['groundedness']) {
  return { grounded: '근거 확인', weak: '근거 약함', ungrounded: '근거 없음' }[value]
}
