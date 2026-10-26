const kstCalendar = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit',
})

function calendarDate(value: string) {
  if (!/^\d{4}-\d{2}-\d{2}$/u.test(value)) return null
  const date = new Date(`${value}T00:00:00Z`)
  return Number.isNaN(date.getTime()) || date.toISOString().slice(0, 10) !== value ? null : date
}

export function shiftReportDate(value: string, days: number): string {
  const date = calendarDate(value)
  if (!date) return ''
  date.setUTCDate(date.getUTCDate() + days)
  return date.toISOString().slice(0, 10)
}

/** Dates are calendar values: UTC arithmetic avoids the viewer's timezone/DST. */
export function reportWeekStart(value: string): string {
  const date = calendarDate(value)
  return date ? shiftReportDate(value, -((date.getUTCDay() + 6) % 7)) : ''
}

export function lastCompletedReportWeek(now = new Date()): string {
  const parts = kstCalendar.formatToParts(now)
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find(item => item.type === type)?.value
  return shiftReportDate(reportWeekStart(`${part('year')}-${part('month')}-${part('day')}`), -7)
}

export function reportWeekError(weekStart: string, now = new Date()): string | null {
  if (!calendarDate(weekStart)) return '보고서를 만들 주차를 선택해 주세요.'
  if (reportWeekStart(weekStart) !== weekStart) return '주차는 월요일부터 시작합니다.'
  if (weekStart > lastCompletedReportWeek(now)) return '한국 시간 기준으로 종료된 주차만 선택할 수 있습니다.'
  return null
}
