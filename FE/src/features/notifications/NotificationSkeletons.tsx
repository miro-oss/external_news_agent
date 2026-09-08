import { Skeleton, SkeletonRegion, SkeletonText } from '../../components/Skeleton'
import './notification-skeletons.css'

export function NotificationPanelSkeleton({ kind }: { kind: 'recipient' | 'group' }) {
  return <SkeletonRegion label={kind === 'recipient' ? '수신자를 불러오는 중입니다.' : '수신 그룹을 불러오는 중입니다.'}
    className="notification-card-stack notification-panel-skeleton">
    <div className="section-heading notification-panel-heading"><Skeleton width="5rem" height="1.4rem" /><Skeleton width="1.5rem" height=".85rem" /></div>
    <div className={`notification-form ${kind === 'recipient' ? 'recipient-create-form' : ''}`}>
      <div className="notification-form-heading"><Skeleton width="7rem" height="1rem" /><Skeleton width={kind === 'recipient' ? '85%' : '65%'} height=".8rem" /></div>
      <div className="notification-form-grid">
        <NotificationFieldSkeleton />
        <NotificationFieldSkeleton />
      </div>
      {kind === 'group' && <NotificationFieldSkeleton />}
      <Skeleton height="2.75rem" className="notification-skeleton-action" />
    </div>
    <div className="notification-skeleton-disclosure"><Skeleton width="7rem" height="1rem" /><Skeleton width="1rem" height="1rem" /></div>
  </SkeletonRegion>
}

function NotificationFieldSkeleton() {
  return <div className="notification-skeleton-field"><Skeleton width="3.5rem" height=".85rem" /><Skeleton className="skeleton-control" /></div>
}

export function DeliveryLogSkeleton() {
  return <SkeletonRegion label="발송 이력을 불러오는 중입니다." className="delivery-log-skeleton">
    <div className="skeleton-row notification-skeleton-summary">
      {[0, 1, 2].map(item => <Skeleton key={item} width="4.5rem" height="1.75rem" />)}
    </div>
    <div className="table-scroll"><table className="delivery-table"><thead><tr>
      {[0, 1, 2, 3, 4, 5].map(item => <th key={item}><Skeleton width="65%" height=".8rem" /></th>)}
    </tr></thead><tbody>
      {[0, 1, 2, 3].map(row => <tr key={row}>
        <td><Skeleton width="85%" height=".9rem" /></td>
        <td><Skeleton width={row % 2 ? '72%' : '90%'} height=".9rem" /></td>
        <td><Skeleton width="55%" height=".9rem" /></td>
        <td><Skeleton width="65%" height=".9rem" /></td>
        <td><Skeleton width="75%" height=".9rem" /></td>
        <td><Skeleton width="2.8rem" height="1.4rem" /></td>
      </tr>)}
    </tbody></table></div>
    <div className="pagination"><Skeleton width="4.5rem" height="2.6rem" /><Skeleton width="3rem" height=".9rem" /><Skeleton width="4.5rem" height="2.6rem" /></div>
  </SkeletonRegion>
}

export function DeliveryTargetSkeleton() {
  return <SkeletonRegion label="수신 대상을 불러오는 중입니다." contentClassName="delivery-target-picker delivery-target-skeleton">
    {[0, 1, 2].map(column => <div className={`delivery-target-skeleton-card ${column === 0 ? 'delivery-channel-skeleton' : ''}`} key={column}>
      <Skeleton width="4.5rem" height=".9rem" />
      {[0, 1].map(row => <div className="skeleton-row" key={row}><Skeleton width="1.2rem" height="1.2rem" /><Skeleton width={row ? '42%' : '58%'} height=".9rem" /></div>)}
    </div>)}
  </SkeletonRegion>
}

export function DeliveryPolicySkeleton() {
  return <SkeletonRegion label="자동 전달 설정을 불러오는 중입니다." className="notification-card-stack delivery-policy-card" contentClassName="skeleton-stack">
    <Skeleton width="8rem" height="1.15rem" />
    <SkeletonText lines={1} />
    <div className="skeleton-row"><Skeleton width="1rem" height="1rem" /><Skeleton width="11rem" height=".9rem" /></div>
    <Skeleton width="8rem" height="2.75rem" />
  </SkeletonRegion>
}

export function TelegramConnectionSkeleton() {
  return <SkeletonRegion label="텔레그램 연결 상태를 확인하는 중입니다." contentClassName="skeleton-stack">
    <SkeletonText lines={2} />
    <Skeleton width="8rem" height="2.6rem" />
  </SkeletonRegion>
}

export function MessagePreviewSkeleton() {
  return <SkeletonRegion label="전달할 요약을 준비하는 중입니다." className="report-message-preview" contentClassName="skeleton-stack">
    <Skeleton width="60%" height="1rem" />
    <SkeletonText lines={4} />
  </SkeletonRegion>
}
