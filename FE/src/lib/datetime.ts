const locale = 'ko-KR'

export function formatShortDate(value: string | null) {
  if (!value) return '발행일 미상'
  return new Intl.DateTimeFormat(locale, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

export function formatMediumDate(value: string | null) {
  if (!value) return '발행일 미상'
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

export function formatFullDate(value: string | null) {
  if (!value) return '발행일 미상'
  return new Intl.DateTimeFormat(locale, { dateStyle: 'full', timeStyle: 'short' }).format(new Date(value))
}
