import { test } from 'node:test'
import assert from 'node:assert/strict'
import { collectionHighlightTerms, collectionKeywords, groupLegacyReportSections, highlightKeywordParts, splitReportMarkdown } from '../src/features/reports/reportReading.ts'

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

test('legacy secondary analysis shares one disclosure while retaining section order and distinct content', () => {
  const groups = groupLegacyReportSections(`# 보고서
## 핵심 요약
첫 요약
## 기타 분석 이슈
장비 납품 동향
## 관찰 항목
일정 확인
## 기사별 분석
개별 기사 요약
## 보고서 제외
원문이 부족한 기사
## 기타분석
장비 납품 동향
## 수집 및 출처 참고
- 수집 또는 분석 제외 사항이 없습니다.`)
  assert.deepEqual(groups.map(group => group.title), ['핵심 요약', '기타 분석', '관찰 항목'])
  const other = groups.filter(group => group.kind === 'other')
  assert.equal(other.length, 1)
  assert.deepEqual(other[0].sections.map(section => section.body), ['장비 납품 동향', '개별 기사 요약', '원문이 부족한 기사'])
  assert.deepEqual(other[0].sections.map(section => section.title), ['기타 분석 이슈', '기사별 분석', '보고서 제외'])
})

test('empty secondary headings and markdown code examples do not create extra disclosures', () => {
  const groups = groupLegacyReportSections('## 핵심 요약\n```md\n## 기타 분석\n예시\n```\n## 기타 분석\n\n## 기사별 분석\n')
  assert.equal(groups.length, 1)
  assert.equal(groups[0].kind, 'section')
  assert.match(groups[0].sections[0].body, /## 기타 분석/)
})

test('identical text under analysis and exclusion retains its different meaning', () => {
  const groups = groupLegacyReportSections('## 기타 분석\nHBM 공급 전망\n## 보고서 제외\nHBM 공급 전망')
  assert.equal(groups.length, 1)
  assert.deepEqual(groups[0].sections.map(section => section.title), ['기타 분석', '보고서 제외'])
})

test('report highlights include saved topic names and scope keywords to the selected snapshot', () => {
  const topic = (topicId: number, topicName: string, word: string) => ({ topicId, topicName,
    queryText: `${word} OR "첨단 패키징" NOT 채용 -광고`, requiredKeywords: [word], optionalKeywords: [],
    excludedKeywords: ['채용', '광고'], batchSize: 100, intervalMinutes: 1440 })
  const contexts = [{ runId: 42, topics: [topic(1, 'HBM 시장', 'HBM4'), topic(2, '로봇 시장', '로봇')] },
    { runId: 43, topics: [topic(1, '변경된 주제', 'DRAM')] }]
  assert.deepEqual(collectionHighlightTerms(contexts, 42, 1), ['HBM 시장', 'HBM4', '첨단 패키징'])
  assert(collectionHighlightTerms(contexts).includes('변경된 주제'))
  assert.deepEqual(collectionHighlightTerms([], 42, 1), [])
})
