import { test } from 'node:test'
import assert from 'node:assert/strict'
import { AI_TECHNOLOGY_PRESET, recommendedTechnicalSourceIds, topicPresetFields } from '../src/features/settings/topicPresets.ts'
import { buildTopicKeywordInput } from '../src/features/settings/topicKeywordInput.ts'
import { buildTopicEditRequest, topicEditDraft } from '../src/features/settings/topicEditDraft.ts'

const source = (id, urlTemplate, extra = {}) => ({ id, name: '임의 소스명', sourceKind: 'FEED', active: true, urlTemplate, ...extra })

test('one AI technology topic covers models, agents, learning and knowledge techniques with alternative terms', () => {
  const input = buildTopicKeywordInput(topicPresetFields(AI_TECHNOLOGY_PRESET))
  assert.equal(input.queryText, 'AI 기술')
  assert.deepEqual(input.requiredKeywords, [])
  for (const keyword of ['LLM', 'language model', '언어 모델', 'AI agent', 'AI 에이전트', 'ontology', '온톨로지',
    'fine-tuning', '모델 양자화', 'reinforcement learning', '강화학습', '컴퓨터 비전', '확산 모델']) {
    assert.ok(input.optionalKeywords.includes(keyword), keyword)
  }
  for (const broadWord of ['AI', 'model', 'agent', '학습', '추론']) assert.ok(!input.optionalKeywords.includes(broadWord))
  assert.ok(input.optionalKeywords.length <= 100)
  assert.deepEqual(input.excludedKeywords, ['[광고]', '[협찬]', '체험단 모집', '리뷰어 모집', '공동구매 모집', '수강생 모집', '교육생 모집'])
})

test('applying an editable preset uses only existing topic fields and preserves company and schedule', () => {
  const company = { corpCode: '00126380', corpName: '삼성전자', stockCode: '005930' }
  const initial = { name: '기존 AI', queryText: '인공지능', requiredKeywords: ['인공지능'],
    optionalKeywords: [], excludedKeywords: ['데이터센터'], intervalMinutes: 720, active: false, dartCompany: company }
  const draft = { ...topicEditDraft(initial), ...topicPresetFields(AI_TECHNOLOGY_PRESET) }
  draft.optionalKeywords += ', custom model'
  const request = buildTopicEditRequest(initial, draft)
  assert.equal(request.name, 'AI 기술 동향')
  assert.equal(request.queryText, 'AI 기술')
  assert.deepEqual(request.requiredKeywords, [])
  assert.ok(!request.excludedKeywords.includes('데이터센터'))
  assert.ok(request.excludedKeywords.includes('[광고]'))
  assert.ok(request.optionalKeywords.includes('custom model'))
  assert.deepEqual(Object.keys(request).sort(), ['excludedKeywords', 'name', 'optionalKeywords', 'queryText', 'requiredKeywords'])
  assert.deepEqual(draft.dartCompany, company)
  assert.equal(initial.intervalMinutes, 720)
  assert.equal(initial.active, false)
  assert.deepEqual(initial.requiredKeywords, ['인공지능'])
})

test('technical source recommendations require a registered active feed with a verified URL', () => {
  const sources = [
    source(1, 'https://openai.com/news/rss.xml'),
    source(2, 'https://huggingface.co/blog/feed.xml'),
    source(3, 'https://research.google/blog/rss/'),
    source(4, 'https://www.microsoft.com/en-us/research/feed/'),
    source(5, 'https://simonwillison.net/tags/llms.atom'),
    source(6, 'https://news.example.com/feed.xml', { name: 'OpenAI News' }),
    source(7, 'https://openai.com.evil.example/news/rss.xml'),
    source(8, 'https://openai.com/news/rss.xml', { active: false }),
    source(9, 'NAVER', { sourceKind: 'SEARCH' }),
    source(10, 'https://openai.com/news/rss.xml', { sourceKind: 'SEARCH' }),
    source(11, 'not-a-url'),
  ]
  assert.deepEqual(recommendedTechnicalSourceIds(sources), [1, 2, 3, 4, 5])
})

test('missing technical feeds never falls back to all general-news sources', () => {
  const sources = [source(1, 'https://news.example.com/feed.xml'), source(2, 'NAVER', { sourceKind: 'SEARCH' })]
  const recommended = recommendedTechnicalSourceIds(sources)
  assert.deepEqual(recommended, [])
})
