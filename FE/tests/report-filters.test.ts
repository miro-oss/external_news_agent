import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { ReportCollectionContext, ReportSummary } from '../src/api/types.ts'
import {
  DEFAULT_REPORT_FILTERS, filterReports, hasReportFilters, reportDateRangeError,
  reportFilterDate, reportTopicOptions, type ReportFilters,
} from '../src/features/reports/reportFilters.ts'

const now = new Date('2026-09-10T16:00:00Z') // September 11 in Korea, September 10 in UTC.
const topic = (id: number, name: string): ReportCollectionContext['topics'][number] => ({
  topicId: id, topicName: name, queryText: 'HBM 반도체', requiredKeywords: ['메모리'],
  optionalKeywords: ['SK하이닉스'], excludedKeywords: ['채용'], batchSize: 100, intervalMinutes: 1440,
})
const report = (id: number, changes: Partial<ReportSummary> = {}): ReportSummary => ({
  id, runId: id, reportScope: 'RUN', reportDate: null, sourceRunIds: [id],
  title: '실행별 산업 리포트', generatedAt: '2026-09-12T00:10:00+09:00',
  collectionStartedAt: '2026-09-11T23:50:00+09:00',
  collectionContexts: [{ runId: id, topics: [topic(29, '과거 수집 주제')] }],
  modelName: 'fixture', findingCount: 1, highSensitivityCount: 0, deliveryStatus: 'NOT_SENT', ...changes,
})
const filters = (changes: Partial<ReportFilters> = {}): ReportFilters => ({ ...DEFAULT_REPORT_FILTERS, ...changes })
const ids = (items: ReportSummary[]) => items.map(item => item.id)

test('search uses saved titles, topic names, queries and every keyword bucket', () => {
  const saved = [report(1)]
  for (const search of ['산업', '과거 수집', ' hbm ', '메모리', 'sk하이닉스', '채용']) {
    assert.deepEqual(ids(filterReports(saved, filters({ search }), now)), [1], search)
  }
  assert.deepEqual(filterReports(saved, filters({ search: '새로 수정한 현재 주제' }), now), [])
  assert.deepEqual(filterReports(saved, filters({ search: '없는 단어' }), now), [])
  assert.deepEqual(ids(filterReports(saved, filters({ search: '   ' }), now)), [1])
})

test('topic IDs combine with search and inclusive date range across all supplied pages', () => {
  const items = Array.from({ length: 105 }, (_, i) => report(i, { collectionContexts: [] }))
  items[104] = report(104, {
    collectionContexts: [{ runId: 104, topics: [topic(29, '첫 주제'), topic(31, '둘째 주제')] }],
  })
  assert.deepEqual(ids(filterReports(items, filters({ topicId: 31, search: '둘째', period: 'TODAY' }), now)), [104])
  assert.deepEqual(filterReports(items, filters({ topicId: 31, search: '없는 단어' }), now), [])
  assert.deepEqual(filterReports(items, filters({ topicId: 31, period: 'CUSTOM', from: '2026-09-12' }), now), [])
})

test('topic options deduplicate IDs and use snapshot names even after a topic was renamed', () => {
  const items = [report(1), report(2, { collectionContexts: [{ runId: 2, topics: [topic(29, '더 오래된 이름'), topic(31, '다른 주제')] }] })]
  const options = reportTopicOptions(items)
  assert.equal(options.length, 2)
  assert.deepEqual(options.map(option => option.id).sort(), [29, 31])
  assert.ok(options.every(option => option.label.trim().length > 0))
  assert.deepEqual(ids(filterReports(items, filters({ topicId: 29 }), now)), [1, 2])
})

test('RUN uses KST start date and DAILY uses aggregation date, never generation date', () => {
  assert.equal(reportFilterDate(report(1, { collectionStartedAt: '2026-09-10T15:00:00Z' })), '2026-09-11')
  assert.equal(reportFilterDate(report(2, { collectionStartedAt: '2026-09-10T14:59:59Z' })), '2026-09-10')
  const daily = report(3, { reportScope: 'DAILY', runId: null, reportDate: '2026-09-10', collectionStartedAt: null })
  assert.equal(reportFilterDate(daily), '2026-09-10')
  assert.deepEqual(ids(filterReports([report(1), daily], filters({ period: 'TODAY' }), now)), [1])
})

test('recent periods include today and exactly N KST dates, excluding future runs', () => {
  const items = ['2026-09-12', '2026-09-11', '2026-09-05', '2026-09-04', '2026-08-13', '2026-08-12']
    .map((day, i) => report(i, { collectionStartedAt: `${day}T00:00:00+09:00` }))
  assert.deepEqual(ids(filterReports(items, filters({ period: '7' }), now)), [1, 2])
  assert.deepEqual(ids(filterReports(items, filters({ period: '30' }), now)), [1, 2, 3, 4])
  assert.deepEqual(ids(filterReports(items, filters({ period: 'CUSTOM', from: '2026-09-05', to: '2026-09-11' }), now)), [1, 2])
})

test('custom ranges allow one bound, include same-day reports and reject reversal', () => {
  const items = [report(1), report(2, { collectionStartedAt: '2026-09-10T23:59:59+09:00' })]
  assert.deepEqual(ids(filterReports(items, filters({ period: 'CUSTOM', from: '2026-09-11' }), now)), [1])
  assert.deepEqual(ids(filterReports(items, filters({ period: 'CUSTOM', to: '2026-09-10' }), now)), [2])
  const same = filters({ period: 'CUSTOM', from: '2026-09-11', to: '2026-09-11' })
  assert.equal(reportDateRangeError(same), null)
  assert.deepEqual(ids(filterReports(items, same, now)), [1])
  const invalid = filters({ period: 'CUSTOM', from: '2026-09-12', to: '2026-09-11' })
  assert.ok(reportDateRangeError(invalid))
  assert.deepEqual(filterReports(items, invalid, now), [])
})

test('legacy missing timestamps and contexts remain available for ALL and title search', () => {
  const legacy = report(1, { collectionStartedAt: null, collectionContexts: undefined })
  const missing = report(2, { collectionStartedAt: undefined, collectionContexts: [] })
  assert.equal(reportFilterDate(legacy), null)
  assert.deepEqual(reportTopicOptions([legacy, missing]), [])
  assert.deepEqual(ids(filterReports([legacy, missing], filters({ search: '산업' }), now)), [1, 2])
  for (const period of ['TODAY', '7', '30', 'CUSTOM'] as const) {
    assert.deepEqual(filterReports([legacy, missing], filters({ period }), now), [])
  }
  assert.equal(hasReportFilters(filters()), false)
  assert.equal(hasReportFilters(filters({ search: '   ' })), false)
  assert.equal(hasReportFilters(filters({ topicId: 29 })), true)
  assert.equal(hasReportFilters(filters({ period: 'TODAY' })), true)
})

test('invalid dates are rejected instead of rolling into another month', () => {
  const badRange = filters({ period: 'CUSTOM', from: '2026-02-30' })
  assert.ok(reportDateRangeError(badRange))
  assert.deepEqual(filterReports([report(1)], badRange, now), [])
  assert.equal(reportFilterDate(report(2, { reportScope: 'DAILY', reportDate: '2026-02-30' })), null)
  assert.equal(reportFilterDate(report(3, { collectionStartedAt: 'unknown' })), null)
})
