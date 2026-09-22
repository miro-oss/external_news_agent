import type { Topic } from '../../api/types'
import type { TopicEditRequest } from '../../api/topicEditing'
import { detailedTopicKeywords } from './topicKeywordInput.ts'

export interface TopicEditDraft {
  name: string
  queryText: string
  requiredKeywords: string
  optionalKeywords: string
  excludedKeywords: string
}

export function topicEditDraft(initial: Topic): TopicEditDraft {
  return {
    name: initial.name,
    queryText: initial.queryText ?? '',
    requiredKeywords: initial.requiredKeywords.join(', '),
    optionalKeywords: initial.optionalKeywords.join(', '),
    excludedKeywords: initial.excludedKeywords.join(', '),
  }
}

export function buildTopicEditRequest(initial: Topic, draft: TopicEditDraft): TopicEditRequest {
  const original = topicEditDraft(initial)
  const changes: TopicEditRequest = {}
  if (draft.name !== original.name && draft.name.trim() !== initial.name) changes.name = draft.name.trim()
  if (draft.queryText !== original.queryText) {
    const queryText = draft.queryText.replaceAll(',', ' ').trim().replace(/\s+/g, ' ')
    if (queryText !== original.queryText) changes.queryText = queryText
  }
  for (const field of ['requiredKeywords', 'optionalKeywords', 'excludedKeywords'] as const) {
    // Unchanged fields stay untouched, including empty arrays and stored phrases.
    if (draft[field] === original[field]) continue
    const keywords = detailedTopicKeywords(draft[field])
    if (keywords.length !== initial[field].length || keywords.some((keyword, index) => keyword !== initial[field][index])) {
      changes[field] = keywords
    }
  }
  return changes
}
