export const COLLECTION_INTERVALS = [
  { value: '60', label: '1시간마다' },
  { value: '720', label: '12시간마다' },
  { value: '1440', label: '24시간마다' },
] as const

export function isCollectionInterval(value: string) {
  return COLLECTION_INTERVALS.some(interval => interval.value === value)
}

/** 기존에 저장된 사용자 지정 주기도 그대로 읽을 수 있게 표시한다. */
export function formatCollectionInterval(minutes: number) {
  if (minutes === 1440) return '24시간마다'
  if (minutes % 1440 === 0) return `${minutes / 1440}일마다`
  if (minutes % 60 === 0) return `${minutes / 60}시간마다`
  return `${minutes}분마다`
}
