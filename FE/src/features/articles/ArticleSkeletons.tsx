import { Skeleton, SkeletonRegion, SkeletonText } from '../../components/Skeleton'
import './article-skeletons.css'

export function ArticleListSkeleton() {
  return (
    <SkeletonRegion label="기사 목록을 불러오는 중입니다." contentClassName="article-grid">
      {Array.from({ length: 4 }, (_, index) => (
        <div className="article-card article-card-skeleton" key={index}>
          <Skeleton width="42%" height="0.6rem" className="article-skeleton-kicker" />
          <Skeleton height="1.05rem" />
          <Skeleton width="62%" height="1.05rem" className="article-skeleton-card-title" />
          <SkeletonText lines={3} />
          <div className="article-skeleton-badges">
            <Skeleton width="3.4rem" height="1.1rem" />
            <Skeleton width="3.4rem" height="1.1rem" />
            <Skeleton width="3.4rem" height="1.1rem" />
          </div>
        </div>
      ))}
    </SkeletonRegion>
  )
}

export function ArticleDetailSkeleton() {
  return (
    <SkeletonRegion label="기사를 불러오는 중입니다." className="article-detail-skeleton">
      <div className="modal-header skeleton-stack">
        <Skeleton width="34%" height="0.75rem" />
        <div className="article-skeleton-title">
          <Skeleton width="94%" height="1.8rem" />
          <Skeleton width="68%" height="1.8rem" />
        </div>
        <Skeleton width="45%" height="0.75rem" />
        <Skeleton width="5rem" height="0.8rem" className="article-skeleton-original" />
      </div>
      <div className="analysis-panel article-skeleton-on-dark skeleton-stack">
        <div className="skeleton-row">
          <Skeleton width="6rem" height="1.1rem" />
          <Skeleton width="4rem" height="1.2rem" />
        </div>
        <SkeletonText lines={3} />
        <div className="article-skeleton-perspectives">
          {Array.from({ length: 3 }, (_, index) => (
            <Skeleton height="3.25rem" key={index} />
          ))}
        </div>
        <SkeletonText lines={2} />
      </div>
      <div className="article-body-section skeleton-stack">
        <Skeleton width="5rem" height="1.1rem" />
        <SkeletonText lines={3} />
        <SkeletonText lines={3} />
        <SkeletonText lines={2} />
      </div>
    </SkeletonRegion>
  )
}

export function ArticleInsightSkeleton() {
  return (
    <SkeletonRegion
      label="저장된 관점 인사이트를 불러오는 중입니다."
      className="article-insight-skeleton article-skeleton-on-dark"
      contentClassName="skeleton-stack"
    >
      <Skeleton width="70%" height="1rem" />
      <SkeletonText lines={3} />
    </SkeletonRegion>
  )
}
