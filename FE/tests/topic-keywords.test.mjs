import { test } from 'node:test'
import assert from 'node:assert/strict'
import { baseTopicKeywords, buildTopicKeywordInput } from '../src/features/settings/topicKeywordInput.ts'

const emptyDetails = { requiredKeywords: null, optionalKeywords: '', excludedKeywords: '' }

test('one basic input supplies search and an AND filter for both SEARCH and RSS articles', () => {
  assert.deepEqual(buildTopicKeywordInput({ ...emptyDetails, queryText: ' HBM, 반도체  ' }), {
    queryText: 'HBM 반도체', requiredKeywords: ['HBM', '반도체'], optionalKeywords: [], excludedKeywords: [],
  })
})

test('advanced conditions stay independent and preserve phrases', () => {
  assert.deepEqual(buildTopicKeywordInput({ queryText: 'HBM 반도체', requiredKeywords: '',
    optionalKeywords: 'SK하이닉스, 고대역폭 메모리', excludedKeywords: '채용, 광고' }), {
    queryText: 'HBM 반도체', requiredKeywords: [], optionalKeywords: ['SK하이닉스', '고대역폭 메모리'], excludedKeywords: ['채용', '광고'],
  })
})

test('untouched defaults follow edited search keywords, explicit filters do not', () => {
  assert.deepEqual(buildTopicKeywordInput({ ...emptyDetails, queryText: 'DRAM' }).requiredKeywords, ['DRAM'])
  assert.deepEqual(buildTopicKeywordInput({ ...emptyDetails, queryText: 'DRAM', requiredKeywords: 'HBM' }).requiredKeywords, ['HBM'])
})

test('separators alone are empty and repeated keywords do not add duplicate constraints', () => {
  assert.deepEqual(baseTopicKeywords(' , ,  '), [])
  assert.deepEqual(baseTopicKeywords('HBM, HBM, 반도체'), ['HBM', '반도체'])
})
