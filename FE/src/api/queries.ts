import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  apiGet,
  apiPut,
  get,
  notificationDelete,
  notificationGet,
  notificationPatch,
  notificationPost,
  notificationPut,
  post,
} from './client'
import type {
  ArticleDetail,
  ArticleFilters,
  ArticleSummary,
  Audience,
  AudienceSetting,
  Combination,
  CombinationPage,
  CollectionRunCreated,
  LlmPlan,
  LlmPlanSetting,
  LlmUsage,
  DeliveryLogPage,
  GroupPerspective,
  NotificationChannel,
  NotificationGroup,
  NotificationPreview,
  NotificationRecipient,
  NotificationSendBatch,
  PaidExhaustedAction,
  PageResult,
  ReportDetail,
  ReportSummary,
  Source,
  SourceCreateRequest,
  Topic,
  TopicCreateRequest,
} from './types'

const keys = {
  combinations: ['topic-sources'] as const,
  sources: ['sources'] as const,
  articles: (filters: ArticleFilters) => ['articles', filters] as const,
  article: (id: number | null) => ['article', id] as const,
  reports: ['reports', 'list'] as const,
  latestReport: ['reports', 'latest'] as const,
  report: (id: number | null) => ['reports', id] as const,
  llmPlan: ['settings', 'llm-plan'] as const,
  llmUsage: ['usage', 'llm'] as const,
  audience: ['settings', 'audience'] as const,
  notificationChannels: ['notifications', 'channels'] as const,
  notificationRecipients: ['notifications', 'recipients'] as const,
  notificationGroups: ['notifications', 'groups'] as const,
  deliveryLogs: (filters: { channelType?: string; status?: string }) => ['notifications', 'delivery-logs', filters] as const,
}

const PAGE_SIZE = 100

async function getAllPages<T, TPage extends PageResult<T> = PageResult<T>>(path: string): Promise<TPage> {
  const first = await get<TPage>(path, { page: 0, size: PAGE_SIZE })
  const content = [...first.content]

  for (let page = first.page + 1; page < first.totalPages; page += 1) {
    const next = await get<TPage>(path, { page, size: PAGE_SIZE })
    content.push(...next.content)
  }

  return { ...first, content, hasNext: false }
}

async function getAllNotificationPages<T>(path: string): Promise<PageResult<T>> {
  const first = await notificationGet<PageResult<T>>(path, { page: 0, size: PAGE_SIZE })
  const content = [...first.content]
  for (let page = first.page + 1; page < first.totalPages; page += 1) {
    const next = await notificationGet<PageResult<T>>(path, { page, size: PAGE_SIZE })
    content.push(...next.content)
  }
  return { ...first, content, hasNext: false }
}

/** 설정 화면의 "등록된 수집 주제" 테이블. 한 행 = (주제 × 소스). */
export function useCombinations() {
  return useQuery({
    queryKey: keys.combinations,
    queryFn: () => getAllPages<Combination, CombinationPage>('/topic-sources'),
  })
}

/** 주제 등록 폼의 소스 선택 후보. size 상한이 100이라 페이지를 이어서 받는다. */
export function useSources() {
  return useQuery({
    queryKey: keys.sources,
    queryFn: () => getAllPages<Source>('/sources'),
  })
}

/**
 * 등록에 성공하면 조합 테이블을 다시 읽는다. 소스를 새로 만들면 주제 폼의 선택 후보도 늘어나므로
 * 둘 다 무효화한다 — 영상 1~3이 보여 주는 흐름(소스 등록 → 주제 등록 → 테이블에 나타남)이 이것이다.
 */
function useRefreshOnSuccess() {
  const queryClient = useQueryClient()
  return () => {
    void queryClient.invalidateQueries({ queryKey: keys.combinations })
    void queryClient.invalidateQueries({ queryKey: keys.sources })
  }
}

export function useCreateSource() {
  const refresh = useRefreshOnSuccess()
  return useMutation({
    mutationFn: (body: SourceCreateRequest) => post<Source>('/sources', body),
    onSuccess: refresh,
  })
}

export function useCreateTopic() {
  const refresh = useRefreshOnSuccess()
  return useMutation({
    mutationFn: (body: TopicCreateRequest) => post<Topic>('/topics', body),
    onSuccess: refresh,
  })
}

export function useArticles(filters: ArticleFilters) {
  return useQuery({
    queryKey: keys.articles(filters),
    queryFn: () => get<PageResult<ArticleSummary>>('/articles', {
      riskLevel: filters.riskLevel,
      relevance: filters.relevance,
      category: filters.category,
      language: filters.language,
      audience: filters.audience,
      minAudienceRelevance: filters.audience ? (filters.minAudienceRelevance ?? 'medium') : undefined,
      sort: filters.sort,
      page: filters.page,
      size: filters.size,
    }),
    placeholderData: keepPreviousData,
  })
}

export function useArticle(articleId: number | null) {
  return useQuery({
    queryKey: keys.article(articleId),
    queryFn: () => get<ArticleDetail>(`/articles/${articleId}`),
    enabled: articleId !== null,
  })
}

export function useReports() {
  return useQuery({
    queryKey: keys.reports,
    queryFn: () => getAllPages<ReportSummary>('/reports'),
  })
}

export function useLatestReport() {
  return useQuery({
    queryKey: keys.latestReport,
    queryFn: () => get<ReportDetail | null>('/reports/latest', { includeFindings: true }),
  })
}

export function useReport(reportId: number | null) {
  return useQuery({
    queryKey: keys.report(reportId),
    queryFn: () => get<ReportDetail>(`/reports/${reportId}`, { includeFindings: true }),
    enabled: reportId !== null,
  })
}

export function useNotificationChannels() {
  return useQuery({
    queryKey: keys.notificationChannels,
    queryFn: () => notificationGet<NotificationChannel[]>('/channels'),
  })
}

export function useNotificationRecipients() {
  return useQuery({
    queryKey: keys.notificationRecipients,
    queryFn: () => getAllNotificationPages<NotificationRecipient>('/recipients'),
  })
}

export function useNotificationGroups() {
  return useQuery({
    queryKey: keys.notificationGroups,
    queryFn: () => getAllNotificationPages<NotificationGroup>('/groups'),
  })
}

export function useDeliveryLogs(filters: { channelType?: string; status?: string } = {}) {
  return useQuery({
    queryKey: keys.deliveryLogs(filters),
    queryFn: () => notificationGet<DeliveryLogPage>('/delivery-logs', {
      channelType: filters.channelType,
      status: filters.status,
      page: 0,
      size: 50,
    }),
  })
}

function useRefreshNotifications() {
  const queryClient = useQueryClient()
  return () => {
    void queryClient.invalidateQueries({ queryKey: keys.notificationChannels })
    void queryClient.invalidateQueries({ queryKey: keys.notificationRecipients })
    void queryClient.invalidateQueries({ queryKey: keys.notificationGroups })
    void queryClient.invalidateQueries({ queryKey: ['notifications', 'delivery-logs'] })
    void queryClient.invalidateQueries({ queryKey: keys.reports })
  }
}

export function useUpdateNotificationChannel() {
  const refresh = useRefreshNotifications()
  return useMutation({
    mutationFn: ({ channelId, body }: { channelId: number; body: Partial<NotificationChannel> }) =>
      notificationPatch<NotificationChannel>(`/channels/${channelId}`, body),
    onSuccess: refresh,
  })
}

export function useCreateNotificationRecipient() {
  const refresh = useRefreshNotifications()
  return useMutation({
    mutationFn: (body: {
      name: string
      email?: string
      memo?: string
      destinations: Array<{ channelId: number; address: string; use: boolean }>
    }) => notificationPost<NotificationRecipient>('/recipients', body),
    onSuccess: refresh,
  })
}

export function useDeleteNotificationRecipient() {
  const refresh = useRefreshNotifications()
  return useMutation({
    mutationFn: (recipientId: number) => notificationDelete(`/recipients/${recipientId}`),
    onSuccess: refresh,
  })
}

export function useReplaceRecipientDestinations() {
  const refresh = useRefreshNotifications()
  return useMutation({
    mutationFn: ({ recipientId, destinations }: {
      recipientId: number
      destinations: Array<{ channelId: number; address: string; use: boolean }>
    }) => notificationPut(`/recipients/${recipientId}/destinations`, { destinations }),
    onSuccess: refresh,
  })
}

export function useCreateNotificationGroup() {
  const refresh = useRefreshNotifications()
  return useMutation({
    mutationFn: (body: {
      name: string
      perspective?: GroupPerspective
      recipientIds: number[]
    }) => notificationPost<NotificationGroup>('/groups', body),
    onSuccess: refresh,
  })
}

export function useDeleteNotificationGroup() {
  const refresh = useRefreshNotifications()
  return useMutation({
    mutationFn: (groupId: number) => notificationDelete(`/groups/${groupId}`),
    onSuccess: refresh,
  })
}

export function usePreviewNotification() {
  return useMutation({
    mutationFn: ({ reportId, channelId }: { reportId: number; channelId: number }) =>
      notificationPost<NotificationPreview>(`/reports/${reportId}/preview`, { channelId }),
  })
}

export function useSendNotification() {
  const refresh = useRefreshNotifications()
  return useMutation({
    mutationFn: ({ reportId, groupIds, channelIds }: {
      reportId: number
      groupIds: number[]
      channelIds: number[]
    }) => notificationPost<NotificationSendBatch>(`/reports/${reportId}/send`, {
      groupIds,
      channelIds,
      idempotencyKey: `report-${reportId}-${Date.now()}`,
    }),
    onSuccess: refresh,
  })
}

export function useLlmPlan() {
  return useQuery({
    queryKey: keys.llmPlan,
    queryFn: () => apiGet<LlmPlanSetting>('/settings/llm-plan'),
  })
}

export function useLlmUsage() {
  return useQuery({
    queryKey: keys.llmUsage,
    queryFn: () => apiGet<LlmUsage>('/usage/llm'),
  })
}

export function useUpdateLlmPlan() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: { plan: LlmPlan; paidExhaustedAction: PaidExhaustedAction }) =>
      apiPut<LlmPlanSetting>('/settings/llm-plan', body),
    onSuccess: (setting) => {
      queryClient.setQueryData(keys.llmPlan, setting)
      void queryClient.invalidateQueries({ queryKey: keys.llmUsage })
    },
  })
}

export function useAudienceSetting() {
  return useQuery({
    queryKey: keys.audience,
    queryFn: () => apiGet<AudienceSetting>('/settings/audience'),
  })
}

export function useUpdateAudienceSetting() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (audience: Audience) =>
      apiPut<AudienceSetting>('/settings/audience', { audience }),
    onSuccess: (setting) => queryClient.setQueryData(keys.audience, setting),
  })
}

export function useStartCollectionRun() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: { idempotencyKey: string; topicIds?: number[]; plan?: LlmPlan }) =>
      post<CollectionRunCreated>('/runs', {
        idempotencyKey: request.idempotencyKey,
        // 빈 배열을 그대로 보내면 서버가 "전체 활성 주제"로 읽는다.
        ...(request.topicIds?.length ? { topicIds: request.topicIds } : {}),
        ...(request.plan ? { plan: request.plan } : {}),
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: keys.llmUsage })
    },
  })
}

export type { Combination, Source }
