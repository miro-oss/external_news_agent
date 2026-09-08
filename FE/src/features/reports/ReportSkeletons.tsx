import { Skeleton, SkeletonRegion, SkeletonText } from '../../components/Skeleton'
import './report-skeletons.css'

function ReportDocumentShapes({ daily }: { daily: boolean }) {
  return <div className="report-document">
    <div className="report-document-header skeleton-stack">
      <Skeleton className="report-skeleton-title" width="88%" />
      <Skeleton width="13rem" height=".8rem" />
      <div className="report-collection-context skeleton-stack">
        <Skeleton width="65%" />
        <Skeleton width="48%" />
      </div>
      {!daily && <div className="report-stat-row report-skeleton-stats">
        {[0, 1, 2].map(index => <div className="skeleton-stack" key={index}>
          <Skeleton width="3rem" height="1.3rem" />
          <Skeleton width="4rem" height=".75rem" />
        </div>)}
      </div>}
    </div>
    <div className="report-reading-content">
      <div className="card report-summary-card report-skeleton-summary skeleton-stack">
        <Skeleton width="5rem" height="1.15rem" />
        <SkeletonText />
      </div>
      <div className="card skeleton-stack">
        <Skeleton width="6rem" height="1.15rem" />
        {[0, 1].map(index => <div className="report-event-card skeleton-stack" key={index}>
          <Skeleton width={index ? '55%' : '70%'} height="1rem" />
          <SkeletonText lines={3} />
        </div>)}
      </div>
    </div>
  </div>
}

export function ReportWorkspaceSkeleton({ daily }: { daily: boolean }) {
  return <SkeletonRegion label="보고서 목록과 본문을 불러오는 중" contentClassName="report-workspace">
    <div className="report-list report-skeleton-list">
      <div className="report-list-heading"><Skeleton width="6rem" height=".8rem" /></div>
      <div className="report-list-scroll">
        {[0, 1, 2, 3, 4].map(index => <div className="report-skeleton-list-item" key={index}>
          <Skeleton width={index % 2 ? '90%' : '100%'} height=".95rem" />
          <Skeleton width="62%" height=".95rem" />
          <Skeleton width="60%" height=".7rem" />
        </div>)}
      </div>
    </div>
    <div className="report-detail-shell"><ReportDocumentShapes daily={daily} /></div>
  </SkeletonRegion>
}

export function ReportDetailSkeleton({ daily }: { daily: boolean }) {
  return <SkeletonRegion label="보고서 본문을 불러오는 중"><ReportDocumentShapes daily={daily} /></SkeletonRegion>
}

export function RelatedArticlesSkeleton() {
  return <SkeletonRegion label="관련 기사를 불러오는 중" contentClassName="skeleton-stack report-related-skeleton">
    {[0, 1, 2].map(index => <div className="skeleton-stack" key={index}>
      <Skeleton width={index % 2 ? '72%' : '88%'} />
      <Skeleton width="9rem" height=".7rem" />
    </div>)}
  </SkeletonRegion>
}
