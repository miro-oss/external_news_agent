import type { DartQuarter } from '../../api/dart'

const numberFormat = new Intl.NumberFormat('ko-KR')

export function dartAmountUnit(currency: string | null) {
  return currency === 'KRW' ? '억원' : currency || '통화 미확인'
}

/** Keep monetary strings exact; only the chart converts them to floating point. */
export function formatDartAmount(value: string | null, currency: string | null): string {
  const match = value?.match(/^(-?)(\d+)(?:\.(\d+))?$/)
  if (!match) return '자료 없음'
  const [, sign, integer, fraction = ''] = match
  if (currency !== 'KRW') {
    return `${sign}${numberFormat.format(BigInt(integer))}${fraction ? `.${fraction}` : ''}`
  }
  const divisor = 10n ** BigInt(7 + fraction.length)
  const absolute = BigInt(integer + fraction)
  const tenths = (absolute + divisor / 2n) / divisor
  // A small profit/loss is still nonzero; do not round it into an actual zero.
  if (tenths === 0n && absolute !== 0n) {
    const padded = integer.padStart(9, '0')
    const decimal = `${padded.slice(-8)}${fraction}`.replace(/0+$/, '')
    return `${sign}${numberFormat.format(BigInt(padded.slice(0, -8)))}.${decimal}`
  }
  const remainder = tenths % 10n
  return `${tenths === 0n ? '' : sign}${numberFormat.format(tenths / 10n)}${remainder ? `.${remainder}` : ''}`
}

export function formatDartMargin(value: number | null) {
  return value !== null && Number.isFinite(value) ? `${value.toLocaleString('ko-KR', { maximumFractionDigits: 2 })}%` : '자료 없음'
}

export function safeDartFilingUrl(value: string, rceptNo: string): string | null {
  if (!/^\d{14}$/.test(rceptNo)) return null
  try {
    const url = new URL(value)
    if (url.protocol !== 'https:' || url.hostname !== 'dart.fss.or.kr' || url.port || url.username || url.password
      || url.pathname !== '/dsaf001/main.do' || url.hash || [...url.searchParams].length !== 1
      || url.searchParams.get('rcpNo') !== rceptNo) return null
    return url.href
  } catch { return null }
}

export function dartChartAmount(value: string | null, currency: string | null): number | null {
  if (value === null || !/^-?\d+(?:\.\d+)?$/.test(value)) return null
  const amount = Number(value) / (currency === 'KRW' ? 100_000_000 : 1)
  return Number.isFinite(amount) ? amount : null
}

export function dartQuarterLabel(quarter: Pick<DartQuarter, 'year' | 'quarter'>) {
  return `${quarter.year}년 ${quarter.quarter}분기`
}

/** Missing observations split the line; they must never appear as zero or be interpolated. */
export function dartChartSegments(values: Array<number | null>): Array<Array<{ index: number; value: number }>> {
  const segments: Array<Array<{ index: number; value: number }>> = []
  let current: Array<{ index: number; value: number }> = []
  values.forEach((value, index) => {
    if (value === null) {
      if (current.length) segments.push(current)
      current = []
    } else current.push({ index, value })
  })
  if (current.length) segments.push(current)
  return segments
}
