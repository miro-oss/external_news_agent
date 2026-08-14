import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { get, post } from './client'
import type {
  Combination,
  CombinationPage,
  PageResult,
  Source,
  SourceCreateRequest,
  Topic,
  TopicCreateRequest,
} from './types'

const keys = {
  combinations: ['topic-sources'] as const,
  sources: ['sources'] as const,
}

/** 설정 화면의 "등록된 수집 주제" 테이블. 한 행 = (주제 × 소스). */
export function useCombinations() {
  return useQuery({
    queryKey: keys.combinations,
    queryFn: () => get<CombinationPage>('/topic-sources', { size: 100 }),
  })
}

/** 주제 등록 폼의 소스 선택 후보. 목록이 짧아 페이징 UI 없이 상한까지 받는다. */
export function useSources() {
  return useQuery({
    queryKey: keys.sources,
    queryFn: () => get<PageResult<Source>>('/sources', { size: 100 }),
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

export type { Combination, Source }
