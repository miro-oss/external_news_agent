import { test } from 'node:test'
import assert from 'node:assert/strict'
import { collectionKeywords, highlightKeywordParts, splitReportMarkdown } from '../src/features/reports/reportReading.ts'

test('keyword marks preserve the exact original text and escape user regex characters', () => {
  const text = 'HBM4와 hbm, C++ / [공정]\n<원문>  두 칸'
  const parts = highlightKeywordParts(text, ['HBM', 'HBM4', 'C++', '[공정]', '.*'])
  assert.equal(parts.map(part => part.text).join(''), text)
  assert.deepEqual(parts.filter(part => part.matched).map(part => part.text), ['HBM4', 'hbm', 'C++', '[공정]'])
})

test('old markdown keeps code examples intact and splits the actual report sections', () => {
  const sections = splitReportMarkdown('# 예전 보고서\n\n## 핵심 요약\n요약\n```md\n## 가짜 제목\n```\n## 중요 이벤트\n### 첫 이벤트\n본문\n### 둘째 이벤트\n다른 본문')
  assert.deepEqual(sections.map(section => section.title), ['핵심 요약', '중요 이벤트'])
  assert.match(sections[0].body, /## 가짜 제목/)
  assert.deepEqual(splitReportMarkdown(sections[1].body, 3).map(section => section.title), ['첫 이벤트', '둘째 이벤트'])
})

test('highlight conditions use the selected run snapshot and omit excluded search words', () => {
  const contexts = [42, 43].map(runId => ({ runId, topics: [{ topicId: 1, topicName: 'HBM',
    queryText: '"첨단 패키징" OR HBM NOT 채용 -광고', requiredKeywords: [runId === 42 ? 'HBM4' : '변경된 키워드'],
    optionalKeywords: [], excludedKeywords: ['채용', '광고'], batchSize: 100, intervalMinutes: 1440 }] }))
  const terms = collectionKeywords(contexts, 42, 1)
  assert.deepEqual(terms, ['HBM4', '첨단 패키징', 'HBM'])
  assert.deepEqual(collectionKeywords([], 42, 1), [])
})

test('no snapshot or no matching word leaves the original sentence unmarked', () => {
  const sentence = '반도체 공급 계획이 발표됐다.'
  assert.deepEqual(highlightKeywordParts(sentence, []), [{ text: sentence, matched: false }])
  assert.deepEqual(highlightKeywordParts(sentence, ['HBM']), [{ text: sentence, matched: false }])
})
