function uniqueKeywords(values: string[]) {
  return [...new Map(values.map((value) => [value.toLocaleLowerCase(), value])).values()]
}

export function baseTopicKeywords(value: string): string[] {
  return uniqueKeywords(value.trim().split(/[\s,]+/).filter(Boolean))
}

export function detailedTopicKeywords(value: string): string[] {
  return uniqueKeywords(value.split(',').map((item) => item.trim()).filter(Boolean))
}

export function buildTopicKeywordInput(input: {
  queryText: string
  requiredKeywords: string | null
  optionalKeywords: string
  excludedKeywords: string
}) {
  return {
    queryText: input.queryText.replaceAll(',', ' ').trim().replace(/\s+/g, ' '),
    requiredKeywords: input.requiredKeywords === null
      ? baseTopicKeywords(input.queryText)
      : detailedTopicKeywords(input.requiredKeywords),
    optionalKeywords: detailedTopicKeywords(input.optionalKeywords),
    excludedKeywords: detailedTopicKeywords(input.excludedKeywords),
  }
}
