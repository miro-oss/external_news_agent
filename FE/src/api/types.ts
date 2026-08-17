/**
 * Notion `외부 뉴스 크롤링 에이전트 API`가 기준이다. 여기 있는 이름과 옵셔널 여부는
 * 명세를 그대로 옮긴 것이고, 임의로 넓히지 않는다.
 */

/** 모든 응답이 쓰는 공통 봉투. */
export interface ApiEnvelope<T> {
  isSuccess: boolean
  code: string
  message: string
  result: T
}

/** 목록 응답의 공통 형태. */
export interface PageResult<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  hasNext: boolean
}

export type SourceKind = 'FEED' | 'SEARCH'

/** SEARCH 소스는 URL이 아니라 provider 키를 받는다. 셋은 인증 방식이 서로 달라 URL 하나로 표현되지 않는다. */
export const SEARCH_PROVIDERS = ['NAVER', 'TAVILY', 'SERPAPI'] as const

export interface CrawlPolicy {
  robotsMode: string
  maxArticlesPerRun: number
  fullTextAllowed: boolean
}

export interface Source {
  id: number
  sourceKind: SourceKind
  name: string
  urlTemplate: string
  country: string | null
  language: string | null
  crawlPolicy: CrawlPolicy | null
  robotsStatus: string
  robotsCheckedAt: string | null
  reliabilityScore: number | null
  active: boolean
  linkedTopicCount?: number
}

export interface SourceCreateRequest {
  sourceKind: SourceKind
  name: string
  urlTemplate: string
  country?: string
  language?: string
}

/** 한 행 = (주제 × 소스). 서버가 펼쳐서 내려주므로 화면에서 조인하지 않는다. */
export interface Combination {
  topicId: number
  topicName: string
  sourceId: number
  sourceName: string
  sourceKind: SourceKind
  queryText: string | null
  batchSize: number
  intervalMinutes: number
  active: boolean
  lastCollectedAt: string | null
  lastCollectedCount: number | null
}

/** 공통 PageResult에 combinationCount 한 칸이 더 붙는다. */
export type CombinationPage = PageResult<Combination> & { combinationCount: number }

export interface TopicCreateRequest {
  name: string
  queryText?: string
  requiredKeywords?: string[]
  optionalKeywords?: string[]
  excludedKeywords?: string[]
  batchSize?: number
  intervalMinutes?: number
  sourceIds?: number[]
}

export interface Topic {
  id: number
  name: string
  queryText: string | null
  batchSize: number
  intervalMinutes: number
  active: boolean
}
