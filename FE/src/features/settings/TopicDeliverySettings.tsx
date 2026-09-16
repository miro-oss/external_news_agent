import { useId, useState } from 'react'
import { createPortal } from 'react-dom'
import { ApiError } from '../../api/client'
import { useDeliveryPolicy, useSaveDeliveryPolicy, type DeliveryPolicy } from '../../api/notificationConnections'
import { CollectionDeliveryDialog } from './CollectionDeliveryPicker'
import { deliveryPolicySummary, toDeliveryPolicy } from './deliverySettings'

export function TopicDeliverySettings({ topicId, topicName }: { topicId: number; topicName: string }) {
  const policy = useDeliveryPolicy(topicId)
  const save = useSaveDeliveryPolicy(topicId)
  const [editing, setEditing] = useState<DeliveryPolicy | null>(null)
  const [loading, setLoading] = useState(false)
  const [success, setSuccess] = useState(false)
  const id = useId()

  async function open() {
    setLoading(true)
    setSuccess(false)
    save.reset()
    const latest = await policy.refetch()
    setLoading(false)
    if (latest.isSuccess) setEditing(latest.data)
  }

  return <div className="saved-delivery-setting">
    <small>{policy.isPending ? '불러오는 중…' : policy.isError ? '설정을 불러오지 못했습니다.' : deliveryPolicySummary(policy.data)}</small>
    {success && <small role="status">저장했습니다.</small>}
    <button type="button" className="ghost-button topic-management-action" disabled={loading || policy.isPending}
      aria-label={`${topicName} 보고서 알림 설정`} aria-haspopup="dialog" aria-expanded={editing !== null}
      aria-controls={editing ? id : undefined} onClick={() => void open()}>{loading ? '불러오는 중…' : policy.isError ? '다시 불러오기' : '알림 설정'}</button>
    {editing && createPortal(<CollectionDeliveryDialog id={id} value={{ ...editing, mode: 'TOPIC' }}
      context={{ scope: 'TOPIC', name: topicName }} pending={save.isPending}
      error={save.error ? save.error instanceof ApiError ? save.error.message : '알림 설정을 저장하지 못했습니다. 다시 시도해 주세요.' : null}
      onDismiss={() => setEditing(null)} onApply={value => {
        if (!value) return
        save.mutate(toDeliveryPolicy(value), { onSuccess: () => { setEditing(null); setSuccess(true) } })
      }} />, document.body)}
  </div>
}
