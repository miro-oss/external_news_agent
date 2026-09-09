import test from 'node:test'
import assert from 'node:assert/strict'
import { reportDisplayTitle } from '../src/features/reports/reportTitle.ts'

const generatedAt = '2026-09-09T12:11:00'
const runTitle = (title: string, date = generatedAt) => reportDisplayTitle({ reportScope: 'RUN', title, generatedAt: date })

test('execution titles preserve the full collection topic name and show a shorter generation date', () => {
  assert.equal(runTitle('HBM 연구 보고서 · 2026-09-09 12:11 리포트'), 'HBM 연구 보고서 · 9월 9일')
  assert.equal(runTitle('MEASURE9-반도체-20260901 · 2026-09-09 12:11 리포트'), 'MEASURE9-반도체-20260901 · 9월 9일')
  assert.equal(runTitle('  MEASURE9-반도체-20260901\u00a0 ·  2026-09-09 12:11\u00a0리포트  '), 'MEASURE9-반도체-20260901 · 9월 9일')
  assert.equal(runTitle('MEASURE-제조장비-20260831 · 2026-09-09 12:11 리포트'), 'MEASURE-제조장비-20260831 · 9월 9일')
  assert.equal(runTitle('LIVE-AI인프라반도체-20260826-02 · 2026-09-09 12:11 리포트'), 'LIVE-AI인프라반도체-20260826-02 · 9월 9일')
  assert.equal(runTitle('발표-반도체수출규제-0827'), '발표-반도체수출규제-0827 · 9월 9일')
})

test('subject numbers and hyphens survive, and unknown or incomplete execution patterns remain meaningful', () => {
  assert.equal(runTitle('MEASURE9-HBM4-3D D램-20260901'), 'MEASURE9-HBM4-3D D램-20260901 · 9월 9일')
  assert.equal(runTitle('LIVE-HBM4-20260826-02'), 'LIVE-HBM4-20260826-02 · 9월 9일')
  assert.equal(runTitle('HBM4-3D D램 공급망 보고서'), 'HBM4-3D D램 공급망 · 9월 9일')
  assert.equal(runTitle('AI-반도체-20260901'), 'AI-반도체-20260901 · 9월 9일')
  assert.equal(runTitle('MEASURE9-반도체'), 'MEASURE9-반도체 · 9월 9일')
  assert.equal(runTitle('MEASURE9-반도체-1234'), 'MEASURE9-반도체-1234 · 9월 9일')
  assert.equal(runTitle('반도체 수출이 늘어난 이유 리포트'), '반도체 수출이 늘어난 이유 · 9월 9일')
  assert.equal(runTitle('테크리포트'), '테크리포트 · 9월 9일')
})

test('empty and generic titles get a readable fallback', () => {
  for (const title of ['', ' \t\n ', '리포트', '보고서', '뉴스 리포트', '2026-09-09 12:11 리포트']) {
    assert.equal(runTitle(title), '뉴스 리포트 · 9월 9일')
  }
})

test('legacy titles with the report label before the timestamp preserve the full subject', () => {
  assert.equal(runTitle('뉴스 보고서 2026-09-08 10:57'), '뉴스 리포트 · 9월 9일')
  assert.equal(runTitle('통합 뉴스 보고서 2026-09-08 09:58'), '통합 뉴스 · 9월 9일')
  assert.equal(runTitle('LIVE-AI인프라반도체-20260826-01 뉴스 보고서 2026-09-07 11:27'), 'LIVE-AI인프라반도체-20260826-01 · 9월 9일')
  assert.equal(runTitle('HBM4-3D D램 보고서 2026-09-08 10:57'), 'HBM4-3D D램 · 9월 9일')
  assert.equal(runTitle('HBM4 전망 2026-09-09'), 'HBM4 전망 2026-09-09 · 9월 9일')
})

test('long collection topic names and Unicode text remain complete without truncation', () => {
  const fullTopic = 'MEASURE9-반도체-HBM4-3D D램-글로벌 공급망 및 수출 규제-20260901-02'
  assert.equal(runTitle(`${fullTopic} · 2026-09-09 12:11 리포트`), `${fullTopic} · 9월 9일`)
  const emoji = '👩🏽‍💻'
  assert.equal(runTitle(emoji.repeat(21)), `${emoji.repeat(21)} · 9월 9일`)
  const decomposedKorean = '\u1100\u1161'
  assert.equal(runTitle(decomposedKorean.repeat(21)), `${decomposedKorean.repeat(21)} · 9월 9일`)
})

test('generation day follows the same viewer-local locale rules as other report dates', () => {
  const date = '2026-09-09T23:30:00Z'
  const expectedDay = new Intl.DateTimeFormat('ko-KR', { month: 'short', day: 'numeric' }).format(new Date(date))
  assert.equal(runTitle('반도체 보고서', date), `반도체 · ${expectedDay}`)
})

test('invalid generation dates omit the date without hiding the subject', () => {
  assert.equal(runTitle('MEASURE9-반도체-20260901 · 2026-09-09 12:11 리포트', 'invalid'), 'MEASURE9-반도체-20260901')
  assert.equal(runTitle('리포트', ''), '뉴스 리포트')
})

test('daily titles are returned byte-for-byte including spacing, length, and date text', () => {
  for (const title of ['2026-09-09 일일 통합 리포트', '  뉴스\u00a0통합\n리포트  ', '반도체'.repeat(20), '']) {
    assert.equal(reportDisplayTitle({ reportScope: 'DAILY', title, generatedAt: 'invalid' }), title)
  }
})
