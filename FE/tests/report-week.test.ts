import assert from 'node:assert/strict'
import { test } from 'node:test'
import { lastCompletedReportWeek, reportWeekError, reportWeekStart, shiftReportDate } from '../src/features/reports/reportWeek.ts'

test('the default week changes exactly at Monday midnight in Korea', () => {
  assert.equal(lastCompletedReportWeek(new Date('2026-09-27T14:59:59Z')), '2026-09-14')
  assert.equal(lastCompletedReportWeek(new Date('2026-09-27T15:00:00Z')), '2026-09-21')
  assert.equal(lastCompletedReportWeek(new Date('2026-09-30T20:00:00-07:00')), '2026-09-21')
})

test('picking any date selects its Monday through Sunday week across month and year boundaries', () => {
  assert.equal(reportWeekStart('2026-09-27'), '2026-09-21')
  assert.equal(reportWeekStart('2026-09-21'), '2026-09-21')
  assert.equal(reportWeekStart('2026-01-01'), '2025-12-29')
  assert.equal(shiftReportDate('2025-12-29', 6), '2026-01-04')
  assert.equal(reportWeekStart('2024-02-29'), '2024-02-26')
  assert.equal(shiftReportDate('2024-02-26', 6), '2024-03-03')
})

test('unfinished weeks and invalid dates are rejected without restricting older completed weeks', () => {
  const now = new Date('2026-09-28T00:00:00+09:00')
  assert.equal(reportWeekError('2026-09-21', now), null)
  assert.equal(reportWeekError('2020-01-06', now), null)
  assert.ok(reportWeekError('2026-09-28', now))
  assert.ok(reportWeekError('2026-10-05', now))
  assert.ok(reportWeekError('2026-09-22', now))
  for (const invalid of ['', 'invalid', '2026-02-29', '2026-04-31', '2026-1-5']) {
    assert.ok(reportWeekError(invalid, now))
    assert.equal(reportWeekStart(invalid), '')
  }
})
