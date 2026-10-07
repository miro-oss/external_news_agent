import type { CollectionProgress, CollectionRunStage } from '../../api/collectionQueue'

const labels: Record<CollectionRunStage, string> = {
  COLLECTING: '수집',
  CLUSTERING: '분류',
  ANALYZING: '분석',
  INVESTIGATING: '추가 조사',
  GENERATING_REPORT: '보고서 생성',
  FINALIZING: '마무리',
}

export function collectionRunStageLabel(run: Pick<CollectionProgress, 'status' | 'stage'>): string | null {
  if (run.status !== 'RUNNING') return null
  return run.stage ? labels[run.stage] ?? '진행 중' : '진행 중'
}
