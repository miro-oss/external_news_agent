import type { Source } from '../../api/types'

export interface TopicPreset {
  name: string
  queryText: string
  requiredKeywords: string
  optionalKeywords: string
  excludedKeywords: string
}

export const AI_TECHNOLOGY_PRESET: TopicPreset = {
  name: 'AI 기술 동향',
  queryText: 'AI 기술',
  requiredKeywords: '',
  optionalKeywords: 'LLM, language model, 언어 모델, 언어모델, AI 모델, AI모델, foundation model, 파운데이션 모델, multimodal, 멀티모달, Claude, 클로드, GPT, Gemini, 제미나이, DeepSeek, 딥시크, Qwen, Llama, Mistral, AI agent, AI 에이전트, AI에이전트, agentic, coding agent, 코딩 에이전트, MCP, RAG, GraphRAG, retrieval-augmented generation, 검색 증강 생성, 검색증강생성, ontology, 온톨로지, knowledge graph, 지식 그래프, 지식그래프, context engineering, 컨텍스트 엔지니어링, fine-tuning, 파인튜닝, 미세조정, model distillation, 지식 증류, 모델 증류, model quantization, 모델 양자화, machine learning, 머신러닝, deep learning, 딥러닝, reinforcement learning, 강화학습, 강화 학습, diffusion model, 확산 모델, computer vision, 컴퓨터 비전, AI 알고리즘',
  excludedKeywords: '[광고], [협찬], 체험단 모집, 리뷰어 모집, 공동구매 모집, 수강생 모집, 교육생 모집',
}

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
