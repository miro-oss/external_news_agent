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

export interface TopicSourceBrief {
  id: number
  name: string
  sourceKind: SourceKind
}

export interface Topic {
  id: number
  name: string
  queryText: string | null
  requiredKeywords: string[]
  optionalKeywords: string[]
  excludedKeywords: string[]
  batchSize: number
  intervalMinutes: number
  active: boolean
}

/** GET /topics 목록의 주제 1건. */
export interface TopicSummary extends Topic {
  linkedSourceCount: number
  lastCollectedAt: string | null
}

/** POST /topics 응답의 주제 1건. */
export interface TopicCreated extends Topic {
  sources: TopicSourceBrief[]
}

export type ChangeType = 'NEW' | 'UPDATED'
export type Relevance = 'important' | 'watch' | 'reference'
export type RiskLevel = 'low' | 'medium' | 'high'
export type Sentiment = 'positive' | 'neutral' | 'negative'

export const CHANGE_TYPE_LABELS: Record<ChangeType, string> = {
  NEW: '신규',
  UPDATED: '갱신',
}

export const RISK_LEVEL_LABELS: Record<RiskLevel, string> = {
  low: '낮은 민감도',
  medium: '중간 민감도',
  high: '높은 민감도',
}

export const AUDIENCES = ['CHIP_MAKER', 'EQUIPMENT_MAKER', 'MARKET_INVESTOR', 'IT_INFRA'] as const
export type Audience = (typeof AUDIENCES)[number]
export type AudienceRelevance = 'none' | 'low' | 'medium' | 'high'

export const AUDIENCE_LABELS: Record<Audience, string> = {
  CHIP_MAKER: '반도체 제조사',
  EQUIPMENT_MAKER: '장비·소재사',
  MARKET_INVESTOR: '시장·투자',
  IT_INFRA: 'IT 인프라',
}

export interface PerspectiveTag {
  audience: Audience
  relevance: AudienceRelevance
  hook: string | null
  evidenceSentenceIds: number[]
}

export interface ArticleSummary {
  id: number
  title: string
  publisher: string | null
  canonicalUrl: string
  urlHash: string
  language: string | null
  publishedAt: string | null
  fetchedAt: string
  fetchStatus: 'OK' | 'BLOCKED'
  topicId: number
  topicName: string
  sourceId: number
  sourceName: string
  changeType: ChangeType
  summary: string
  category: '제품/공정' | '기업' | '정책' | '공급망'
  relevance: Relevance
  riskLevel: RiskLevel
  sentiment: Sentiment
  perspectiveTags: PerspectiveTag[]
}

export interface ArticleSentence {
  index: number
  text: string
}

export interface ArticleKeyPoint {
  text: string
  evidence: number[]
  groundedness: 'grounded' | 'weak' | 'ungrounded'
}

export interface ArticleAnalysis {
  changeType: ChangeType
  summary: string
  keyPoints: ArticleKeyPoint[]
  intent: string | null
  sentiment: Sentiment
  riskLevel: RiskLevel
  relevance: Relevance
  category: string
  perspectiveTags: PerspectiveTag[]
  analyzedAt: string
  runId: number
}

export interface ArticleDetail {
  id: number
  title: string
  publisher: string | null
  canonicalUrl: string
  language: string | null
  publishedAt: string | null
  fetchedAt: string
  fetchStatus: 'OK' | 'BLOCKED'
  topicId: number
  topicName: string
  sourceId: number
  sourceName: string
  bodyText: string | null
  sentences: ArticleSentence[]
  analysis: ArticleAnalysis | null
  analysisArticleId: number
  issueId: number | null
  relatedArticles: Array<{ id: number; title: string; publisher: string | null }>
}

export interface ArticleFilters {
  riskLevel?: RiskLevel
  relevance?: Relevance
  category?: ArticleSummary['category']
  language?: string
  audience?: Audience
  minAudienceRelevance?: AudienceRelevance
  sort?: 'PUBLISHED_DESC' | 'PUBLISHED_ASC' | 'RISK_DESC'
  page: number
  size: number
}

export interface ReportSummary {
  id: number
  runId: number
  title: string
  generatedAt: string
  modelName: string
  findingCount: number
  highRiskCount: number
  deliveryStatus: 'NOT_SENT' | 'SENT' | 'FAILED'
}

export interface ReportSummaryStats {
  findingCount: number
  newCount: number
  updatedCount: number
  byRiskLevel: Partial<Record<RiskLevel, number>>
  byCategory: Record<string, number>
}

export interface ReportFinding {
  id: number
  articleId: number
  articleTitle: string
  canonicalUrl: string
  changeType: ChangeType
  summary: string
  keyPoints: ArticleKeyPoint[]
  intent: string | null
  sentiment: Sentiment
  riskLevel: RiskLevel
  relevance: Relevance
  category: string
}

export interface ReportDetail {
  id: number
  runId: number
  title: string
  markdownBody: string
  modelName: string
  generatedAt: string
  summaryStats: ReportSummaryStats
  findings?: ReportFinding[]
}

export type NotificationChannelType = 'TELEGRAM' | 'EMAIL'
export type DeliveryStatus = 'SENT' | 'FAILED' | 'SKIPPED'
export type GroupPerspective = 'EXECUTIVE' | 'PURCHASING' | 'TECHNOLOGY' | 'SALES'

export interface NotificationChannel {
  id: number
  channelType: NotificationChannelType
  name: string
  config: Record<string, string | number | boolean>
  maxLength: number
  active: boolean
  tokenConfigured: boolean
}

export interface RecipientDestination {
  channelId: number
  channelType: NotificationChannelType
  address: string | null
  use: boolean
  onboarded: boolean
}

export interface NotificationRecipient {
  id: number
  name: string
  phone: string | null
  email: string | null
  memo: string | null
  active: boolean
  destinations: RecipientDestination[]
  groupNames?: string[]
}

export interface NotificationGroup {
  id: number
  name: string
  perspective: GroupPerspective | null
  active: boolean
  memberCount: number
  activeMemberCount: number
  members?: Array<{ recipientId: number; name: string; active: boolean }>
}

export interface NotificationPreview {
  reportId: number
  channelId: number
  channelType: NotificationChannelType
  parseMode: string | null
  maxLength: number
  subject: string | null
  chunks: Array<{ seq: number; length: number; body: string }>
  chunkCount: number
}

export interface NotificationSendBatch {
  deliveryBatchId: string
  reportId: number
  requestedAt: string
  targetCount: number
  sentCount: number
  failedCount: number
  skippedCount: number
  results: Array<{
    recipientId: number
    recipientName: string
    channelType: NotificationChannelType
    address: string
    status: DeliveryStatus
    externalMessageId: string | null
    chunkCount: number | null
    sentAt: string
    reason?: string
    message?: string
  }>
}

export interface DeliveryLog {
  id: number
  deliveryBatchId: string
  reportId: number
  runId: number
  recipientId: number
  recipientName: string
  channelType: NotificationChannelType
  address: string
  status: DeliveryStatus
  externalMessageId: string | null
  chunkSeq: number | null
  chunkCount: number | null
  errorMessage: string | null
  sentAt: string
}

export type DeliveryLogPage = PageResult<DeliveryLog> & {
  summary: { sentCount: number; failedCount: number; skippedCount: number }
}

export type LlmPlan = 'FREE' | 'PAID'
export type PaidExhaustedAction = 'STUB' | 'FALLBACK_FREE'

export interface LlmPlanSetting {
  plan: LlmPlan
  allowRunOverride: boolean
  paidExhaustedAction: PaidExhaustedAction
}

export interface AudienceSetting {
  audience: Audience
}

export interface LlmUsage {
  currentPlan: LlmPlan
  free: {
    dailyCallsUsed: number
    dailyCallsLimit: number
    dailyCallsRemaining: number
    resetAt: string
  }
  paid: {
    dailyCreditsUsed: number
    dailyCreditsLimit: number
    dailyCreditsRemaining: number
    analysisCreditsRemaining: number
    reportReserve: number
    monthlyCreditsUsed: number
    monthlyCreditsLimit: number
    monthlyCreditsRemaining: number
    dailyResetAt: string
    monthlyResetAt: string
  }
}

export interface CollectionRunCreated {
  runId: number
  status: string
  triggerType: string
  idempotencyKey: string | null
  llmPlan: LlmPlan
  targetTopicIds?: number[]
  targetCombinationCount?: number
  startedAt: string
}
