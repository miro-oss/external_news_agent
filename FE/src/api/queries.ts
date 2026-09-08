import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ApiError,
  apiGet,
  apiPut,
  get,
  notificationDelete,
  notificationGet,
  notificationPatch,
  notificationPost,
  notificationPut,
  post,
  patch,
  remove,
} from './client'
import { saveRecipientEmail } from '../lib/recipientEmail'
import { cacheDeletedNotificationRecipient, notificationRecipientsKey } from './notificationRecipientCache'
import { cacheDeletedNotificationGroup, notificationGroupsKey } from './notificationGroupCache'
import type { CollectionRunDelivery } from './notificationConnections'
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
  IssueDetail,
  InsightResult,
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
  TopicCreated,
  TopicActivation,
  TopicCreateRequest,
  TopicKeywordProposal,
  TopicKeywordProposalFilter,
  TopicSummary,
} from './types'

const keys = {
  combinations: ['topic-sources'] as const,
  sources: ['sources'] as const,
  topics: ['topics'] as const,
  topicKeywordProposals: (status: TopicKeywordProposalFilter) =>
    ['topic-keyword-proposals', status] as const,
  articles: (filters: ArticleFilters) => ['articles', filters] as const,
  article: (id: number | null, runId?: number) => ['article', id, runId ?? null] as const,
  reports: ['reports', 'list'] as const,
  latestReport: ['reports', 'latest'] as const,
  report: (id: number | null) => ['reports', id] as const,
  issue: (id: number | null) => ['issues', id] as const,
  insight: (issueId: number, audience: Audience) => ['insights', issueId, audience] as const,
  llmPlan: ['settings', 'llm-plan'] as const,
  llmUsage: ['usage', 'llm'] as const,
  audience: ['settings', 'audience'] as const,
  notificationChannels: ['notifications', 'channels'] as const,
  notificationRecipients: notificationRecipientsKey,
  notificationGroups: notificationGroupsKey,
  deliveryLogs: (filters: DeliveryLogFilters) => ['notifications', 'delivery-logs', filters] as const,
}

export type DeliveryLogFilters = {
  reportId?: string
  channelType?: string
  status?: string
  page?: number
}

const PAGE_SIZE = 100

async function getAllPages<T, TPage extends PageResult<T> = PageResult<T>>(
  path: string,
  params: Record<string, string | number | boolean | undefined> = {},
): Promise<TPage> {
  const first = await get<TPage>(path, { ...params, page: 0, size: PAGE_SIZE })
  const content = [...first.content]

  for (let page = first.page + 1; page < first.totalPages; page += 1) {
    const next = await get<TPage>(path, { ...params, page, size: PAGE_SIZE })
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

/** 수집 실행 범위 계산에 쓰는 (주제 × 소스) 조합 목록. */
export function useCombinations() {
  return useQuery({
    queryKey: keys.combinations,
    queryFn: () => getAllPages<Combination, CombinationPage>('/topic-sources'),
  })
}

/** 주제 등록 폼의 활성 소스 선택 후보. size 상한이 100이라 페이지를 이어서 받는다. */
export function useSources() {
  return useQuery({
    queryKey: keys.sources,
    queryFn: () => getAllPages<Source>('/sources', { active: true }),
  })
}

/** 설정 화면의 등록 주제 목록과 주제 등록 후 캐시 갱신에 쓰는 주제 목록. */
export function useTopics(active?: boolean) {
  return useQuery({
    queryKey: [...keys.topics, active ?? 'all'],
    queryFn: () => getAllPages<TopicSummary>('/topics', { active }),
  })
}

export function useTopicKeywordProposals(status: TopicKeywordProposalFilter = 'PENDING') {
  return useQuery({
    queryKey: keys.topicKeywordProposals(status),
    queryFn: () => getAllPages<TopicKeywordProposal>(
      '/topics/keyword-proposals',
      status === 'ALL' ? {} : { status },
    ),
  })
}

/**
 * 등록에 성공하면 조합·소스·주제 목록을 다시 읽는다. 소스를 만들면 선택 후보가 늘고, 주제를
 * 만들면 키워드 표가 늘어나므로 관련 캐시를 함께 무효화한다.
 */
function useRefreshOnSuccess() {
  const queryClient = useQueryClient()
  return () => {
    void queryClient.invalidateQueries({ queryKey: keys.combinations })
    void queryClient.invalidateQueries({ queryKey: keys.sources })
    void queryClient.invalidateQueries({ queryKey: keys.topics })
  }
}

function useRefreshTopicKeywordProposals() {
  const queryClient = useQueryClient()
  return () => {
    void queryClient.invalidateQueries({ queryKey: ['topic-keyword-proposals'] })
    void queryClient.invalidateQueries({ queryKey: keys.topics })
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
    mutationFn: (body: TopicCreateRequest) => post<TopicCreated>('/topics', body),
    onSuccess: refresh,
  })
}

export function useSetTopicActivation() {
  const refresh = useRefreshOnSuccess()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ topicId, active }: { topicId: number; active: boolean }) =>
      patch<TopicActivation>(`/topics/${topicId}/activation`, { active }),
    onSuccess: async () => {
      refresh()
      await queryClient.invalidateQueries({ queryKey: ['topic-keyword-proposals'] })
    },
  })
}

export function useApproveTopicKeywordProposal() {
  const refresh = useRefreshTopicKeywordProposals()
  return useMutation({
    mutationFn: (proposalId: number) =>
      post<TopicKeywordProposal>(`/topics/keyword-proposals/${proposalId}/approve`, {}),
    onSuccess: refresh,
  })
}

export function useRejectTopicKeywordProposal() {
  const refresh = useRefreshTopicKeywordProposals()
  return useMutation({
    mutationFn: (proposalId: number) =>
      post<TopicKeywordProposal>(`/topics/keyword-proposals/${proposalId}/reject`, {}),
    onSuccess: refresh,
  })
}

export function useArticles(filters: ArticleFilters) {
  return useQuery({
    queryKey: keys.articles(filters),
    queryFn: () => get<PageResult<ArticleSummary>>('/articles', {
      sensitivityLevel: filters.sensitivityLevel,
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

export function useArticle(articleId: number | null, runId?: number) {
  return useQuery({
    queryKey: keys.article(articleId, runId),
    queryFn: () => get<ArticleDetail>(`/articles/${articleId}`, { runId }),
    enabled: articleId !== null,
  })
}

export function useReports(reportScope?: 'RUN' | 'DAILY') {
  return useQuery({
    queryKey: [...keys.reports, reportScope ?? 'ALL'],
    queryFn: () => getAllPages<ReportSummary>('/reports', { reportScope }),
  })
}

export function useLatestReport(reportScope?: 'RUN' | 'DAILY') {
  return useQuery({
    queryKey: [...keys.latestReport, reportScope ?? 'ALL'],
    queryFn: () => get<ReportDetail | null>('/reports/latest', { includeFindings: true, reportScope }),
  })
}

export function useReport(reportId: number | null) {
  return useQuery({
    queryKey: keys.report(reportId),
    queryFn: () => get<ReportDetail>(`/reports/${reportId}`, { includeFindings: true }),
    enabled: reportId !== null,
  })
}

export function useDeleteReport() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (reportId: number) => remove<{ id: number; deleted: boolean }>(`/reports/${reportId}`),
    onSuccess: async (_, reportId) => {
      await client.cancelQueries({ queryKey: ['reports'] })
      client.setQueriesData<PageResult<ReportSummary>>({ queryKey: keys.reports }, current => current ? {
        ...current,
        content: current.content.filter(report => report.id !== reportId),
        totalElements: Math.max(0, current.totalElements - Number(current.content.some(report => report.id === reportId))),
      } : current)
      client.setQueriesData<ReportDetail | null>({ queryKey: keys.latestReport }, current => current?.id === reportId ? null : current)
      client.removeQueries({ queryKey: keys.report(reportId), exact: true })
      void client.invalidateQueries({ queryKey: ['reports'] })
    },
  })
}

export function useIssue(issueId: number | null, enabled = true) {
  return useQuery({
    queryKey: keys.issue(issueId),
    queryFn: () => get<IssueDetail>(`/issues/${issueId}`),
    enabled: issueId !== null && enabled,
  })
}

export function useGenerateInsight() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ issueId, audience }: { issueId: number; audience: Audience }) =>
      post<InsightResult>('/insights', {
        targetType: 'ISSUE',
        targetId: issueId,
        audiences: [audience],
      }),
    onSuccess: (result, variables) => {
      queryClient.setQueryData(keys.insight(variables.issueId, variables.audience), result)
      void queryClient.invalidateQueries({ queryKey: keys.llmUsage })
    },
  })
}

export function useInsight(issueId: number, audience: Audience) {
  return useQuery({
    queryKey: keys.insight(issueId, audience),
    queryFn: () => get<InsightResult>('/insights', {
      targetType: 'ISSUE',
      targetId: issueId,
      audience,
    }),
    retry: false,
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

export function useDeliveryLogs(filters: DeliveryLogFilters = {}) {
  return useQuery({
    queryKey: keys.deliveryLogs(filters),
    queryFn: () => notificationGet<DeliveryLogPage>('/delivery-logs', {
      reportId: filters.reportId,
      channelType: filters.channelType,
      status: filters.status,
      page: filters.page ?? 0,
      size: 50,
    }),
    placeholderData: keepPreviousData,
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
  const client = useQueryClient()
  const refresh = useRefreshNotifications()
  return useMutation({
    mutationFn: (recipientId: number) => notificationDelete<{
      id: number; active: false; deletedAt: string; removedGroupCount: number
    }>(`/recipients/${recipientId}`),
    onSuccess: async (_, recipientId) => {
      await cacheDeletedNotificationRecipient(client, recipientId)
      refresh()
    },
  })
}

export function useUpdateNotificationRecipientEmail() {
  const refresh = useRefreshNotifications()
  return useMutation({
    mutationFn: ({ recipientId, email, emailChannelId }: { recipientId: number; email: string; emailChannelId: number }) =>
      saveRecipientEmail(email, emailChannelId, {
        loadRecipient: async () => {
          const recipients = await getAllNotificationPages<NotificationRecipient>('/recipients')
          const recipient = recipients.content.find((item) => item.id === recipientId)
          if (!recipient) throw new ApiError('RECIPIENT404', '수신자를 찾을 수 없습니다.', 404)
          return recipient
        },
        replaceDestinations: (destinations) => notificationPut(`/recipients/${recipientId}/destinations`, { destinations }),
        updateProfile: (email) => notificationPatch(`/recipients/${recipientId}`, { email }),
      }),
    onSettled: refresh,
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
  const client = useQueryClient()
  return useMutation({
    mutationFn: (body: {
      name: string
      perspective?: GroupPerspective
      recipientIds: number[]
    }) => notificationPost<NotificationGroup>('/groups', body),
    onSuccess: async group => {
      await client.cancelQueries({ queryKey: keys.notificationGroups })
      client.setQueryData<PageResult<NotificationGroup>>(keys.notificationGroups, previous => previous && {
        ...previous,
        content: [group, ...previous.content.filter(item => item.id !== group.id)],
        totalElements: previous.totalElements + (previous.content.some(item => item.id === group.id) ? 0 : 1),
      })
      refresh()
    },
  })
}

export function useDeleteNotificationGroup() {
  const client = useQueryClient()
  const refresh = useRefreshNotifications()
  return useMutation({
    mutationFn: (groupId: number) => notificationDelete<{
      id: number; deletedAt: string; removedMemberCount: number
    }>(`/groups/${groupId}`),
    onSuccess: async (_, groupId) => {
      await cacheDeletedNotificationGroup(client, groupId)
      refresh()
    },
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
    mutationFn: ({ reportId, groupIds, channelIds, idempotencyKey }: {
      reportId: number
      groupIds: number[]
      channelIds: number[]
      idempotencyKey: string
    }) => notificationPost<NotificationSendBatch>(`/reports/${reportId}/send`, {
      groupIds,
      channelIds,
      idempotencyKey,
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
    mutationFn: (request: { idempotencyKey: string; topicIds?: number[]; plan?: LlmPlan; delivery?: CollectionRunDelivery }) =>
      post<CollectionRunCreated>('/runs', {
        idempotencyKey: request.idempotencyKey,
        // 빈 배열을 그대로 보내면 서버가 "전체 활성 주제"로 읽는다.
        ...(request.topicIds?.length ? { topicIds: request.topicIds } : {}),
        ...(request.plan ? { plan: request.plan } : {}),
        ...(request.delivery ? { delivery: request.delivery } : {}),
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: keys.llmUsage })
      void queryClient.invalidateQueries({ queryKey: ['collection-queue'] })
      void queryClient.invalidateQueries({ queryKey: ['delivery-policy'] })
    },
  })
}

export type { Combination, Source }
