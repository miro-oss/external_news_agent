import type { Source } from '../../api/types'

export interface TopicPreset {
  id: string
  name: string
  description: string
  queryText: string
  requiredKeywords: string
  optionalKeywords: string
  excludedKeywords: string
}

export const AI_TOPIC_PRESETS: readonly TopicPreset[] = [
  {
    id: 'ai-models', name: 'AI 모델 업데이트', description: '모델 출시·기능·이용 조건',
    queryText: 'AI 모델', requiredKeywords: '',
    optionalKeywords: 'LLM, language model, foundation model, multimodal, 모델, Claude, GPT, Gemini, Llama, Qwen, DeepSeek',
    excludedKeywords: '',
  },
  {
    id: 'ai-agents', name: 'AI Agent 구현', description: '도구 사용·메모리·자동화 사례',
    queryText: 'AI Agent', requiredKeywords: '',
    optionalKeywords: 'agent, 에이전트, agentic, tool use, tool calling, function calling, MCP, Model Context Protocol, multi-agent, 멀티에이전트, computer use',
    excludedKeywords: '',
  },
  {
    id: 'ai-reasoning', name: 'AI 학습·검색·추론 기술', description: '학습 방법·RAG·지식 그래프',
    queryText: 'LLM 학습 검색 추론', requiredKeywords: '',
    optionalKeywords: 'RAG, retrieval, reasoning, 추론, ontology, 온톨로지, knowledge graph, 지식 그래프, GraphRAG, context engineering, 컨텍스트 엔지니어링, fine-tuning, LoRA, distillation, quantization, 학습, 증류, 양자화',
    excludedKeywords: '',
  },
]

export function topicPresetFields(preset: TopicPreset) {
  return {
    name: preset.name,
    queryText: preset.queryText,
    requiredKeywords: preset.requiredKeywords,
    optionalKeywords: preset.optionalKeywords,
    excludedKeywords: preset.excludedKeywords,
  }
}

const TECHNICAL_FEEDS = new Set([
  'openai.com/news/rss.xml',
  'huggingface.co/blog/feed.xml',
  'research.google/blog/rss',
  'microsoft.com/en-us/research/feed',
  'simonwillison.net/tags/llms.atom',
])

export function recommendedTechnicalSourceIds(sources: Source[]): number[] {
  return sources.filter(source => {
    if (!source.active || source.sourceKind !== 'FEED') return false
    try {
      const url = new URL(source.urlTemplate)
      if (url.protocol !== 'https:') return false
      const key = url.hostname.toLowerCase().replace(/^www\./, '') + url.pathname.replace(/\/$/, '')
      return TECHNICAL_FEEDS.has(key)
    } catch {
      return false
    }
  }).map(source => source.id)
}

export function selectedActiveSourceIds(sources: Source[], selected: number[] | null): number[] {
  return sources.filter(source => source.active && (selected === null || selected.includes(source.id)))
    .map(source => source.id)
}

export function recommendedTopicSourceIds(sources: Source[], retainedSelection: number[] = []): number[] {
  return [...recommendedTechnicalSourceIds(sources), ...sources
    .filter(source => !source.active && retainedSelection.includes(source.id)).map(source => source.id)]
}
