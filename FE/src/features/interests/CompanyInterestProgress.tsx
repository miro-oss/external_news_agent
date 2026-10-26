import type { CompanyInterestCriterion } from '../../api/companyInterests'
import { formatMediumDate } from '../../lib/datetime'
import { reportHash } from '../reports/reportNavigation'
import { companyInterestStatus } from './companyInterestState'
import './companyInterests.css'

export function CompanyInterestProgress({ criterion }: { criterion: CompanyInterestCriterion }) {
  const application = criterion.lastApplication
  return <div className="company-interest-progress">
    <strong className={`company-interest-status is-${criterion.status.toLowerCase()}${application ? ' has-application' : ''}`}>
      {companyInterestStatus(criterion)}
    </strong>
    {criterion.status === 'DRAFT' && <p>제안된 기준과 적용 주제를 확인한 뒤 적용해 주세요.</p>}
    {criterion.status === 'ACTIVE' && !application && <p>기준을 저장했습니다. 이후 생성되는 해당 주제의 보고서에서 선정 결과를 확인할 수 있습니다.</p>}
    {criterion.status === 'REVOKED' && <p>이후 보고서 선정에 이 기준을 사용하지 않습니다.</p>}
    {application && <div className="company-interest-receipt">
      <a href={reportHash(application.reportScope, application.reportId)}>{criterion.status === 'REVOKED' ? '중지 전 마지막 반영 보고서' : '반영 보고서'} #{application.reportId}</a>
      <time dateTime={application.appliedAt}>{formatMediumDate(application.appliedAt)}</time>
      <span>검토 {application.evaluatedCount.toLocaleString()}건 · 제외 {application.suppressedCount.toLocaleString()}건</span>
      {application.suppressedCount === 0 && <span>기준을 검토했으며 제외 대상은 없었습니다.</span>}
    </div>}
  </div>
}
