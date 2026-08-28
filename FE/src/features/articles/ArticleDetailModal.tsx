import { useEffect, useMemo, useRef, useState } from 'react'
import { useArticle } from '../../api/queries'
import {
  AUDIENCES,
  AUDIENCE_LABELS,
  RISK_LEVEL_LABELS,
  type ArticleAnalysis,
  type Audience,
} from '../../api/types'
import { KeyPointList } from '../../components/KeyPointList'
import { formatMediumDate } from '../../lib/datetime'
import { normalizeKeyPoints } from '../../lib/keyPoints'
import { scrollIntoViewGently } from '../../lib/motion'

interface Props {
  articleId: number | null
  runId?: number
  defaultAudience?: Audience
  initialEvidence?: number[]
  onClose: () => void
}

export function ArticleDetailModal({
  articleId,
  runId,
  defaultAudience = 'CHIP_MAKER',
  initialEvidence,
  onClose,
}: Props) {
  const article = useArticle(articleId, runId)
  const closeButton = useRef<HTMLButtonElement>(null)
  const appliedInitialEvidence = useRef<string | null>(null)
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

  useEffect(() => {
    if (articleId === null) {
      appliedInitialEvidence.current = null
      return
    }
    if (!article.data || !initialEvidence || initialEvidence.length === 0) return

    const selectionKey = `${runId ?? 'latest'}:${articleId}:${initialEvidence.join(',')}`
    if (appliedInitialEvidence.current === selectionKey) return
    appliedInitialEvidence.current = selectionKey
    setEvidenceSelection({ articleId, sentences: initialEvidence })

    const firstSentence = initialEvidence[0]
    requestAnimationFrame(() => {
      const target = document.getElementById(`article-${articleId}-sentence-${firstSentence}`)
      if (target) scrollIntoViewGently(target)
    })
  }, [article.data, articleId, initialEvidence, runId])

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
      const target = document.getElementById(`article-${articleId}-sentence-${firstSentence}`)
      if (target) scrollIntoViewGently(target)
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
  const keyPoints = useMemo(() => normalizeKeyPoints(analysis.keyPoints), [analysis.keyPoints])

  return (
    <section className="analysis-panel">
      <div className="analysis-title-row">
        <div>
          <h3>한국어 요약</h3>
        </div>
        <div className="analysis-labels">
          <span>{analysis.category}</span>
          <span>{RISK_LEVEL_LABELS[analysis.riskLevel]}</span>
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
      {/*
        key에 관점을 건다. 관점을 바꾸면 이 노드가 새로 붙어서 CSS의 페이드가 다시 재생된다 —
        같은 노드의 글자만 갈리면 애니메이션은 처음 한 번만 돌고 그다음부터는 소리 없이 바뀐다.
      */}
      <div className="perspective-reason" role="tabpanel" key={selectedAudience}>
        {/*
          여기 있던 "관련도 보통"을 이름으로 바꿨다. 그 값은 바로 위 탭이 관점 이름 아래에
          이미 달고 있어서 같은 말이 한 줄 더 붙은 것으로만 읽혔는데, 정작 이 칸이 무엇인지는
          아무 데도 없었다 — 위 요약은 기사 전체를 줄인 것이고 이 칸만 고른 관점에 따라
          갈린다는 걸, 탭을 눌러 보기 전에는 알 수 없었다.
        */}
        <span className="perspective-reason-label">{AUDIENCE_LABELS[selectedAudience]} 관점</span>
        {selectedPerspective?.hook ? (
          <>
            <p>{selectedPerspective.hook}</p>
            <div className="perspective-evidence-list" role="group" aria-label="관점 관련 근거">
              {selectedPerspective.evidenceSentenceIds.map((sentenceId, localIndex) => (
                <button
                  type="button"
                  className="perspective-evidence-button"
                  key={`${sentenceId}-${localIndex}`}
                  aria-label={`근거 ${localIndex + 1} · 본문 ${sentenceId + 1}번째 문장으로 이동`}
                  onClick={() => onEvidenceSelect([sentenceId])}
                >
                  근거 {localIndex + 1}
                </button>
              ))}
            </div>
          </>
        ) : (
          <p className="perspective-empty">이 관점과 직접 연결되는 근거가 없습니다.</p>
        )}
      </div>
      <KeyPointList
        points={keyPoints}
        onEvidenceSelect={(sentenceId) => onEvidenceSelect([sentenceId])}
      />
    </section>
  )
}

function perspectiveRelevanceLabel(value: 'none' | 'low' | 'medium' | 'high') {
  return { none: '해당 없음', low: '낮음', medium: '보통', high: '높음' }[value]
}
