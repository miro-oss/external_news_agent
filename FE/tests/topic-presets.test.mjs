import { test } from 'node:test'
import assert from 'node:assert/strict'
import { AI_TOPIC_PRESETS, recommendedTechnicalSourceIds, selectedActiveSourceIds, topicPresetFields } from '../src/features/settings/topicPresets.ts'
import { buildTopicKeywordInput } from '../src/features/settings/topicKeywordInput.ts'
import { buildTopicEditRequest, topicEditDraft } from '../src/features/settings/topicEditDraft.ts'

const source = (id, urlTemplate, extra = {}) => ({ id, name: '임의 소스명', sourceKind: 'FEED', active: true, urlTemplate, ...extra })

test('technology presets admit alternative Korean and English terms without requiring every term', () => {
  const [models, agents, reasoning] = AI_TOPIC_PRESETS.map(preset => buildTopicKeywordInput(topicPresetFields(preset)))
  assert.deepEqual([models.queryText, agents.queryText, reasoning.queryText], ['AI 모델', 'AI Agent', 'LLM 학습 검색 추론'])
  for (const input of [models, agents, reasoning]) {
    assert.deepEqual(input.requiredKeywords, [])
    assert.deepEqual(input.excludedKeywords, [])
  }
  assert.ok(models.optionalKeywords.includes('language model') && models.optionalKeywords.includes('모델'))
  assert.ok(agents.optionalKeywords.includes('agent') && agents.optionalKeywords.includes('에이전트'))
  assert.ok(reasoning.optionalKeywords.includes('ontology') && reasoning.optionalKeywords.includes('온톨로지'))
  assert.ok(reasoning.optionalKeywords.includes('fine-tuning') && reasoning.optionalKeywords.includes('양자화'))
})

test('applying an editable preset uses only existing topic fields and preserves company and schedule', () => {
  const company = { corpCode: '00126380', corpName: '삼성전자', stockCode: '005930' }
  const initial = { name: '기존 AI', queryText: '인공지능', requiredKeywords: ['인공지능'],
    optionalKeywords: [], excludedKeywords: ['데이터센터'], intervalMinutes: 720, active: false, dartCompany: company }
  const draft = { ...topicEditDraft(initial), ...topicPresetFields(AI_TOPIC_PRESETS[0]) }
  draft.optionalKeywords += ', custom model'
  const request = buildTopicEditRequest(initial, draft)
  assert.equal(request.name, 'AI 모델 업데이트')
  assert.equal(request.queryText, 'AI 모델')
  assert.deepEqual(request.requiredKeywords, [])
  assert.deepEqual(request.excludedKeywords, [])
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
  assert.deepEqual(selectedActiveSourceIds(sources, recommended), [])
})

test('manual registration retains all-source defaults while explicit selection excludes inactive and unknown sources', () => {
  const sources = [source(1, 'https://openai.com/news/rss.xml'), source(2, 'https://news.example.com/feed.xml'),
    source(3, 'https://old.example.com/feed.xml', { active: false })]
  assert.deepEqual(selectedActiveSourceIds(sources, null), [1, 2])
  assert.deepEqual(selectedActiveSourceIds(sources, [1, 3, 99]), [1])
  assert.deepEqual(selectedActiveSourceIds(sources, []), [])
})
