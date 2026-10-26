import type { QueryClient } from '@tanstack/react-query'
import { ApiError, post } from './client.ts'
import type { PageResult, Topic, TopicDetail, TopicKeywordProposal, TopicSummary } from './types'

const proposalKey = ['topic-keyword-proposals'] as const

function affectedKeys(topicId?: number) {
  return [proposalKey, ['topics'],
    topicId === undefined ? ['topic-edit'] : ['topic-edit', topicId],
    topicId === undefined ? ['topic-schedule'] : ['topic-schedule', topicId]]
}

async function cacheReviewedProposal(client: QueryClient, saved: TopicKeywordProposal) {
  const keys = affectedKeys(saved.topicId)
  // 승인 전에 시작한 조회가 늦게 도착해 대기 상태와 이전 키워드를 복구하지 않도록 한다.
  await Promise.all(keys.map(queryKey => client.cancelQueries({ queryKey })))
  for (const query of client.getQueryCache().findAll({ queryKey: proposalKey })) {
    const filter = query.queryKey[1]
    client.setQueryData<PageResult<TopicKeywordProposal>>(query.queryKey, current => {
      if (!current) return current
      const matches = filter === 'ALL' || filter === saved.status
      const content = current.content.filter(proposal => proposal.id !== saved.id).map(proposal =>
        proposal.topicId === saved.topicId ? { ...proposal, currentKeywords: saved.currentKeywords } : proposal)
      if (matches) content.push(saved)
      content.sort((left, right) => right.createdAt.localeCompare(left.createdAt) || right.id - left.id)
      const totalElements = current.totalElements + content.length - current.content.length
      return { ...current, content, totalElements, totalPages: Math.ceil(totalElements / current.size) }
    })
  }
  client.setQueriesData<PageResult<TopicSummary>>({ queryKey: ['topics'] }, current => current && {
    ...current,
    content: current.content.map(topic => topic.id === saved.topicId ? { ...topic, ...saved.currentKeywords } : topic),
  })
  client.setQueryData<TopicDetail>(['topic-edit', saved.topicId], current => current && { ...current, ...saved.currentKeywords })
  client.setQueryData<Topic>(['topic-schedule', saved.topicId], current => current && { ...current, ...saved.currentKeywords })
  // 재조회가 실패해도 서버가 이미 확정한 검토 결과는 캐시에 남는다.
  await Promise.all(keys.map(queryKey => client.invalidateQueries({ queryKey })))
}

async function refreshOnConflict(client: QueryClient, error: unknown, proposalId: number) {
  if (!(error instanceof ApiError) || error.code !== 'TOPIC409') return
  const proposal = client.getQueriesData<PageResult<TopicKeywordProposal>>({ queryKey: proposalKey })
    .flatMap(([, page]) => page?.content ?? []).find(item => item.id === proposalId)
  const keys = affectedKeys(proposal?.topicId)
  await Promise.all(keys.map(queryKey => client.cancelQueries({ queryKey })))
  await Promise.all(keys.map(queryKey => client.invalidateQueries({ queryKey })))
}

export function approveTopicKeywordProposalOptions(client: QueryClient) {
  return {
    mutationFn: ({ proposalId, selectedChangeIndexes }: { proposalId: number; selectedChangeIndexes: number[] }) =>
      post<TopicKeywordProposal>(`/topics/keyword-proposals/${proposalId}/approve`, { selectedChangeIndexes }),
    onSuccess: (saved: TopicKeywordProposal) => cacheReviewedProposal(client, saved),
    onError: (error: unknown, { proposalId }: { proposalId: number }) => refreshOnConflict(client, error, proposalId),
  }
}

export function rejectTopicKeywordProposalOptions(client: QueryClient) {
  return {
    mutationFn: (proposalId: number) => post<TopicKeywordProposal>(`/topics/keyword-proposals/${proposalId}/reject`, {}),
    onSuccess: (saved: TopicKeywordProposal) => cacheReviewedProposal(client, saved),
    onError: (error: unknown, proposalId: number) => refreshOnConflict(client, error, proposalId),
  }
}
