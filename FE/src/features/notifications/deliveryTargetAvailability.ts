import type { DeliveryTargets } from '../../api/notificationConnections'

type NamedTarget = { id: number; name: string; active: boolean }
export type DeliveryTargetCatalog = Record<keyof DeliveryTargets, NamedTarget[]>
export type UnavailableDeliveryTarget = { key: keyof DeliveryTargets; id: number; label: string }

const LABELS: Record<keyof DeliveryTargets, string> = {
  channelIds: '전달 방식', groupIds: '수신 그룹', recipientIds: '수신자',
}

/** Keep saved choices visible even when the corresponding entry was stopped or removed. */
export function unavailableDeliveryTargets(value: DeliveryTargets, catalog: DeliveryTargetCatalog): UnavailableDeliveryTarget[] {
  return (Object.keys(LABELS) as Array<keyof DeliveryTargets>).flatMap((key) => value[key].flatMap((id) => {
    const target = catalog[key].find((item) => item.id === id)
    if (target?.active) return []
    return [{ key, id, label: target ? `${target.name} · 사용 중지됨` : `${LABELS[key]} · 더 이상 사용할 수 없음` }]
  }))
}
