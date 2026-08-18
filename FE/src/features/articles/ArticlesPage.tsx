import { useCallback, useState } from 'react'
import { useArticles } from '../../api/queries'
import type { ArticleFilters, ArticleSummary } from '../../api/types'
import { formatShortDate } from '../../lib/datetime'
import { ArticleDetailModal } from './ArticleDetailModal'

const PAGE_SIZE = 20

export function ArticlesPage() {
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [filters, setFilters] = useState<ArticleFilters>({
    page: 0,
    size: PAGE_SIZE,
    sort: 'PUBLISHED_DESC',
  })
  const articles = useArticles(filters)
  const closeArticle = useCallback(() => setSelectedId(null), [])

  function changeFilter<K extends keyof ArticleFilters>(key: K, value: ArticleFilters[K]) {
    setFilters((current) => ({ ...current, [key]: value || undefined, page: 0 }))
  }

  return (
    <main>
      <header className="page-header article-header">
        <div>
          <p className="eyebrow">PHASE 2 · M4</p>
          <h1>분석 기사</h1>
          <p className="muted">수집된 신호의 한국어 요약과 분류를 확인하고, 원문 근거를 펼쳐봅니다.</p>
        </div>
        <div className="summary-count" aria-live="polite">
          <strong>{articles.data?.totalElements ?? 0}</strong>
          <span>분석 완료</span>
        </div>
      </header>

      <section className="filter-bar" aria-label="기사 필터">
        <label>
          위험도
          <select
            value={filters.riskLevel ?? ''}
            onChange={(event) => changeFilter('riskLevel', event.target.value as ArticleFilters['riskLevel'])}
          >
            <option value="">전체</option>
            <option value="high">높음</option>
            <option value="medium">보통</option>
            <option value="low">낮음</option>
          </select>
        </label>
        <label>
          관련도
          <select
            value={filters.relevance ?? ''}
            onChange={(event) => changeFilter('relevance', event.target.value as ArticleFilters['relevance'])}
          >
            <option value="">전체</option>
            <option value="important">중요</option>
            <option value="watch">관찰</option>
            <option value="reference">참고</option>
          </select>
        </label>
        <label>
          분류
          <select
            value={filters.category ?? ''}
            onChange={(event) => changeFilter('category', event.target.value as ArticleFilters['category'])}
          >
            <option value="">전체</option>
            <option value="제품/공정">제품/공정</option>
            <option value="기업">기업</option>
            <option value="정책">정책</option>
            <option value="공급망">공급망</option>
          </select>
        </label>
        <label>
          언어
          <select value={filters.language ?? ''} onChange={(event) => changeFilter('language', event.target.value)}>
            <option value="">전체</option>
            <option value="ko">한국어</option>
            <option value="en">영어</option>
          </select>
        </label>
        <label>
          정렬
          <select
            value={filters.sort}
            onChange={(event) => changeFilter('sort', event.target.value as ArticleFilters['sort'])}
          >
            <option value="PUBLISHED_DESC">최신순</option>
            <option value="PUBLISHED_ASC">오래된순</option>
            <option value="RISK_DESC">위험도순</option>
          </select>
        </label>
      </section>

      {articles.isPending && <ArticleListSkeleton />}
      {articles.isError && (
        <div className="state-panel error" role="alert">
          기사를 불러오지 못했습니다. {articles.error.message}
        </div>
      )}
      {articles.data && articles.data.content.length === 0 && (
        <div className="state-panel">
          <strong>조건에 맞는 분석 기사가 없습니다.</strong>
          <span>수집을 실행하거나 필터 조건을 바꿔보세요.</span>
        </div>
      )}
      {articles.data && articles.data.content.length > 0 && (
        <>
          <div className="article-grid">
            {articles.data.content.map((article) => (
              <ArticleCard key={article.id} article={article} onOpen={() => setSelectedId(article.id)} />
            ))}
          </div>
          <div className="pagination" aria-label="기사 페이지 이동">
            <button
              type="button"
              className="secondary-button"
              disabled={filters.page === 0}
              onClick={() => setFilters((current) => ({ ...current, page: current.page - 1 }))}
            >
              이전
            </button>
            <span>{filters.page + 1} / {Math.max(articles.data.totalPages, 1)}</span>
            <button
              type="button"
              className="secondary-button"
              disabled={!articles.data.hasNext}
              onClick={() => setFilters((current) => ({ ...current, page: current.page + 1 }))}
            >
              다음
            </button>
          </div>
        </>
      )}

      <ArticleDetailModal articleId={selectedId} onClose={closeArticle} />
    </main>
  )
}

function ArticleCard({ article, onOpen }: { article: ArticleSummary; onOpen: () => void }) {
  return (
    <article className="article-card">
      <div className="article-card-topline">
        <span className={`signal-dot risk-${article.riskLevel}`} aria-hidden="true" />
        <span>{article.category}</span>
        <span className="separator">·</span>
        <span>{article.topicName}</span>
        <time dateTime={article.publishedAt ?? undefined}>{formatShortDate(article.publishedAt)}</time>
      </div>
      <h2>{article.title}</h2>
      <p className="article-summary">{article.summary}</p>
      <div className="article-meta">
        <span>{article.publisher || article.sourceName}</span>
        <span className={`status-pill relevance-${article.relevance}`}>{relevanceLabel(article.relevance)}</span>
        <span className={`status-pill sentiment-${article.sentiment}`}>{sentimentLabel(article.sentiment)}</span>
        <span className="language-pill">{(article.language ?? '—').toUpperCase()}</span>
      </div>
      <button type="button" className="detail-button" onClick={onOpen}>
        본문보기 <span aria-hidden="true">↗</span>
      </button>
    </article>
  )
}

function ArticleListSkeleton() {
  return (
    <div className="article-grid" aria-label="기사 목록을 불러오는 중" aria-busy="true">
      {[0, 1, 2].map((index) => <div className="article-card skeleton" key={index} />)}
    </div>
  )
}

function relevanceLabel(value: ArticleSummary['relevance']) {
  return { important: '중요', watch: '관찰', reference: '참고' }[value]
}

function sentimentLabel(value: ArticleSummary['sentiment']) {
  return { positive: '긍정', neutral: '중립', negative: '부정' }[value]
}
