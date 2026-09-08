import { Skeleton, SkeletonRegion, SkeletonText } from '../../components/Skeleton'
import './settings-skeletons.css'

export function CollectionRunSkeleton() {
  return (
    <section className="collection-run-panel" aria-labelledby="collection-run-title" aria-busy="true">
      <div className="collection-run-top">
        <div className="collection-run-heading">
          <div>
            <h2 id="collection-run-title">수집 실행</h2>
            <p className="muted">주제를 여러 개 골라 한 번에 수집할 수 있습니다.</p>
          </div>
        </div>
        <SkeletonRegion label="수집 실행 정보를 불러오는 중" contentClassName="collection-run-controls">
          <SettingFieldSkeleton />
          <SettingFieldSkeleton />
        </SkeletonRegion>
      </div>
      <div className="collection-delivery-trigger" aria-hidden="true"><Skeleton width="10rem" height="2.5rem" /><Skeleton width="3rem" /></div>
      <div className="run-audience-setting" aria-hidden="true"><AudienceSkeletonContent /></div>
      <div className="collection-run-summary" aria-hidden="true">
        <div className="collection-run-usage">
          <div className="run-usage-summary"><Skeleton width="5rem" height=".75rem" /><Skeleton width="6rem" height="1rem" /></div>
        </div>
        <div className="collection-run-footer">
          <Skeleton className="settings-skeleton-run-button" height="3.4rem" />
        </div>
      </div>
    </section>
  )
}

export function AudienceSettingSkeleton() {
  return (
    <SkeletonRegion label="기본 관점을 불러오는 중" className="run-audience-setting">
      <AudienceSkeletonContent />
    </SkeletonRegion>
  )
}

function AudienceSkeletonContent() {
  return (
    <div className="settings-skeleton-audience">
      <Skeleton width="6rem" height="1.15rem" />
      <Skeleton width="min(100%, 28rem)" height="0.9rem" />
      <div className="settings-skeleton-segments">
        {Array.from({ length: 4 }, (_, index) => <Skeleton key={index} height="2.7rem" />)}
      </div>
    </div>
  )
}

export function SettingFieldSkeleton() {
  return (
    <div className="settings-skeleton-field">
      <Skeleton width="5rem" height="1.29rem" />
      <Skeleton className="skeleton-control" height="var(--settings-control-height, 3.25rem)" />
    </div>
  )
}

export function TopicTableSkeleton() {
  return (
    <SkeletonRegion label="수집 주제를 불러오는 중">
      <div className="topic-list-toolbar">
        <Skeleton width="9rem" />
        <Skeleton width="10rem" height="2.6rem" />
      </div>
      <div className="table-scroll">
        <table className="topic-table topic-management-table settings-skeleton-topic-table">
          <colgroup>
            <col className="topic-name-column" />
            <col className="topic-query-column" />
            <col className="topic-conditions-column" />
            <col className="topic-surge-column" />
            <col className="topic-related-column" />
            <col className="topic-schedule-column" />
            <col className="topic-actions-column" />
          </colgroup>
          <thead><tr>{['주제', '검색 키워드', '기사 조건', '지난주 대비 증가', '연관 키워드', '수집 일정', '관리']
            .map((label) => <th key={label}>{label}</th>)}</tr></thead>
          <tbody>
            {Array.from({ length: 3 }, (_, row) => (
              <tr key={row}>
                {Array.from({ length: 7 }, (_, column) => (
                  <td key={column}>
                    {column === 6 ? <div className="settings-skeleton-management-actions"><Skeleton height="1.875rem" /></div>
                      : <SkeletonText lines={column === 2 || column === 5 ? 2 : 1} />}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </SkeletonRegion>
  )
}

export function KeywordProposalsSkeleton() {
  return (
    <SkeletonRegion label="키워드 제안을 불러오는 중">
      <div className="proposal-toolbar"><Skeleton width="min(100%, 23rem)" height="2.75rem" /></div>
      <div className="proposal-stack">
        {Array.from({ length: 2 }, (_, index) => (
          <div key={index} className="proposal-card settings-skeleton-proposal">
            <div className="settings-skeleton-proposal-body">
              <Skeleton width="min(70%, 12rem)" height="1.1rem" />
              {[0, 1].map(row => <div className="skeleton-row settings-skeleton-keyword-row" key={row}>
                <Skeleton width="4rem" height=".75rem" />
                <Skeleton width={row ? '4.5rem' : '5.5rem'} height="1.6rem" />
                <Skeleton width={row ? '5.5rem' : '4rem'} height="1.6rem" />
              </div>)}
            </div>
            <div className="skeleton-row settings-skeleton-proposal-actions"><Skeleton width="3rem" height="1.875rem" /><Skeleton width="3rem" height="1.875rem" /></div>
          </div>
        ))}
      </div>
    </SkeletonRegion>
  )
}

export function LlmUsageSummarySkeleton() {
  return (
    <SkeletonRegion label="오늘 호출 사용량을 불러오는 중" contentClassName="run-usage-summary">
      <Skeleton width="5rem" height=".75rem" />
      <Skeleton width="6rem" height="1rem" />
    </SkeletonRegion>
  )
}

export function TopicSourcesSkeleton() {
  return (
    <SkeletonRegion label="활성 수집 소스를 확인하는 중" className="settings-skeleton-source-status">
      <Skeleton width="min(100%, 16rem)" height="1rem" />
    </SkeletonRegion>
  )
}
