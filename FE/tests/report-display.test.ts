import test from 'node:test'
import assert from 'node:assert/strict'
import { dailyReportTopics, defaultSensitivity } from '../src/features/reports/reportDisplay.ts'
import type { ReportDetail, ReportFinding, SensitivityLevel } from '../src/api/types.ts'

const findings = (...levels: SensitivityLevel[]) => levels.map(level => ({ sensitivity: { level } } as ReportFinding))

test('report first opens on the highest sensitivity actually present', () => {
  assert.equal(defaultSensitivity(findings('low', 'medium', 'high')), 'high')
  assert.equal(defaultSensitivity(findings('low', 'medium')), 'medium')
  assert.equal(defaultSensitivity(findings('low')), 'low')
  assert.equal(defaultSensitivity([]), '')
})

const topicContext = (runId: number, topicName: string) => ({ runId, topics: [{ topicId: 1, topicName,
  queryText: null, requiredKeywords: [], optionalKeywords: [], excludedKeywords: [], batchSize: 10, intervalMinutes: 1440 }] })
const topicFindings = (...names: Array<string | null>) => names.map(topicName => ({ issue: topicName === null ? null : { topicName } } as ReportFinding))
type TopicReport = Pick<ReportDetail, 'sourceRunIds' | 'collectionContexts' | 'findings'>

test('daily topics prefer the complete historical collection names over renamed live issues', () => {
  const report: TopicReport = { sourceRunIds: [42, 43, 44], collectionContexts: [topicContext(42, 'HBM 시장'), topicContext(43, 'AI 인프라'), topicContext(44, ' HBM 시장 '), topicContext(999, '관계없는 주제')], findings: topicFindings('이름이 바뀐 주제') }
  assert.deepEqual(dailyReportTopics(report), { label: '통합 주제', names: ['HBM 시장', 'AI 인프라'] })
})

test('legacy daily topics describe included issue classifications without claiming saved collection conditions', () => {
  const report: TopicReport = { sourceRunIds: [42], collectionContexts: [], findings: topicFindings(' 반도체 ', null, 'AI', '반도체', ' ') }
  assert.deepEqual(dailyReportTopics(report), { label: '포함된 이슈 주제', names: ['반도체', 'AI'] })
})

test('partial snapshots do not masquerade as the complete set of daily collection topics', () => {
  const report: TopicReport = { sourceRunIds: [42, 43], collectionContexts: [topicContext(42, '당시 주제')], findings: topicFindings('현재 이슈 분류') }
  assert.deepEqual(dailyReportTopics(report), { label: '포함된 이슈 주제', names: ['현재 이슈 분류'] })
  assert.deepEqual(dailyReportTopics({ sourceRunIds: [42], findings: [] }).names, [])
})
