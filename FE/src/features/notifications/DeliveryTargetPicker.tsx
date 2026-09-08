import type { DeliveryTargets } from '../../api/notificationConnections'
import { useDeliveryTargetAvailability } from './useDeliveryTargetAvailability'

export function DeliveryTargetPicker({ value, onChange, disabled = false }: { value: DeliveryTargets; onChange: (value: DeliveryTargets) => void; disabled?: boolean }) {
  const { groups, recipients, channels, pending, error, unavailable } = useDeliveryTargetAvailability(value)
  function toggle(key: keyof DeliveryTargets, id: number) {
    onChange({ ...value, [key]: value[key].includes(id) ? value[key].filter((selected) => selected !== id) : [...value[key], id] })
  }
  if (pending) return <p className="muted">수신 대상을 불러오는 중입니다.</p>
  if (error) return <p role="alert" className="field-error">{error.message}</p>
  const activeGroups = groups.data?.content.filter((group) => group.active) ?? []
  const activeRecipients = recipients.data?.content.filter((recipient) => recipient.active) ?? []
  const activeChannels = channels.data?.filter((channel) => channel.active) ?? []
  return <div className="delivery-target-picker">
    {unavailable.length > 0 && <div className="delivery-unavailable" role="status">
      <p>기존 선택 중 사용할 수 없는 대상이 있습니다. 해당 선택을 해제하고 필요한 대상을 다시 골라 주세요.</p>
      <ul>{unavailable.map((target) => <li key={`${target.key}-${target.id}`}>
        <span>{target.label}</span>
        <button type="button" className="text-button" disabled={disabled}
          aria-label={`${target.label} 선택 해제`} onClick={() => toggle(target.key, target.id)}>선택 해제</button>
      </li>)}</ul>
    </div>}
    <fieldset className="delivery-channel-options" disabled={disabled}><legend>전달 방식</legend><div className="delivery-choice-list">
      {activeChannels.map((channel) => <label key={channel.id}><input type="checkbox" checked={value.channelIds.includes(channel.id)} onChange={() => toggle('channelIds', channel.id)} />{channel.channelType === 'EMAIL' ? '이메일' : '텔레그램'}</label>)}
      {!activeChannels.length && <p className="muted">알림 관리에서 전달 채널을 켜 주세요.</p>}
    </div></fieldset>
    <fieldset disabled={disabled}><legend>수신 그룹</legend><div className="delivery-choice-list">
      {activeGroups.map((group) => <label key={group.id}><input type="checkbox" checked={value.groupIds.includes(group.id)} onChange={() => toggle('groupIds', group.id)} />{group.name} <span className="muted">{group.activeMemberCount}명</span></label>)}
      {!activeGroups.length && <p className="muted">등록된 그룹이 없습니다.</p>}
    </div></fieldset>
    <fieldset disabled={disabled}><legend>개별 수신자</legend><div className="delivery-choice-list">
      {activeRecipients.map((recipient) => <label key={recipient.id}><input type="checkbox" checked={value.recipientIds.includes(recipient.id)} onChange={() => toggle('recipientIds', recipient.id)} />{recipient.name}</label>)}
      {!activeRecipients.length && <p className="muted">알림 관리에서 수신자를 등록해 주세요.</p>}
    </div></fieldset>
  </div>
}
