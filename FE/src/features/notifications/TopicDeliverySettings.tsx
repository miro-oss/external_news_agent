import { useState } from 'react'
import { useDeliveryPolicy, useSaveDeliveryPolicy, type DeliveryPolicy } from '../../api/notificationConnections'
import { DeliveryTargetPicker } from './DeliveryTargetPicker'
import { useDeliveryTargetAvailability } from './useDeliveryTargetAvailability'
import { MutationStatus } from '../settings/MutationStatus'
import './notifications-refinement.css'

export function TopicDeliverySettings({ topicId }: { topicId: number }) {
  const policy = useDeliveryPolicy(topicId)
  if (policy.isPending) return <p className="muted">자동 전달 설정을 불러오는 중입니다.</p>
  if (policy.error) return <p role="alert">{policy.error.message}</p>
  return policy.data ? <PolicyForm key={topicId} topicId={topicId} initial={policy.data} /> : null
}
function PolicyForm({ topicId, initial }: { topicId: number; initial: DeliveryPolicy }) {
  const [value, setValue] = useState(initial)
  const save = useSaveDeliveryPolicy(topicId)
  const availability = useDeliveryTargetAvailability(value)
  const valid = !value.enabled || (availability.valid && (value.run || value.daily) && value.channelIds.length > 0 && (value.groupIds.length > 0 || value.recipientIds.length > 0))
  function change(next: DeliveryPolicy) { save.reset(); setValue(next) }
  return <section className="notification-card-stack delivery-policy-card" aria-label="주제 자동 전달 설정">
    <div className="section-heading"><h3>보고서 자동 전달</h3></div>
    <p className="muted">보고서가 완성되면 선택한 사람에게 핵심 요약을 보냅니다.</p>
    <label className="delivery-toggle"><input type="checkbox" disabled={save.isPending} checked={value.enabled} onChange={(event) => change({ ...value, enabled: event.target.checked })} />완성된 보고서 자동 전달</label>
    {value.enabled && <>
      <fieldset className="delivery-scope" disabled={save.isPending}><legend>전달할 보고서</legend>
        <label><input type="checkbox" checked={value.run} onChange={(event) => change({ ...value, run: event.target.checked })} />실행별 보고서</label>
        <label><input type="checkbox" checked={value.daily} onChange={(event) => change({ ...value, daily: event.target.checked })} />일일 통합 보고서</label>
        <p className="muted">둘 다 선택하면 실행별 요약과 하루 전체 요약을 각각 전달합니다.</p>
      </fieldset>
      <DeliveryTargetPicker value={value} disabled={save.isPending} onChange={(targets) => change({ ...value, ...targets })} />
    </>}
    <button type="button" className="primary-button" disabled={save.isPending || !valid} onClick={() => save.mutate(value)}>{save.isPending ? '저장 중…' : '전달 설정 저장'}</button>
    <MutationStatus error={save.error} success={save.isSuccess ? '자동 전달 설정을 저장했습니다.' : null} />
  </section>
}
