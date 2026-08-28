import type { ArticleKeyPoint } from '../api/types'

/**
 * 서버가 준 핵심 항목을 화면이 다룰 수 있는 모양으로 맞춘다.
 *
 * <p>핵심 항목은 원래 문장 문자열의 배열이었고 #100에서 문장 단위 근거가 붙으면서 객체가 됐다.
 * 두 계약이 섞여 돌아다니면(예: 화면만 새로 뜨고 서버는 이전 빌드) `evidence`가 없는 채로 오고,
 * 그대로 `.map`을 부르면 그 자리에서 화면 전체가 죽는다. 실제로 리포트 탭이 그렇게 흰 화면이
 * 됐다. 읽는 쪽에서 한 번 정규화해 두면 모르는 모양이 와도 읽을 수 있는 만큼은 그린다.
 *
 * <p>근거를 알 수 없는 항목은 `ungrounded`로 둔다. 근거가 없다고 표시되는 편이, 없는 근거가
 * 있는 것처럼 보이는 것보다 안전하다.
 */
export function normalizeKeyPoints(points: unknown): ArticleKeyPoint[] {
  if (!Array.isArray(points)) return []
  return points.flatMap((point) => {
    if (typeof point === 'string') {
      return point.trim() ? [{ text: point, evidence: [], groundedness: 'ungrounded' as const }] : []
    }
    if (typeof point !== 'object' || point === null) return []
    const { text, evidence, groundedness } = point as Partial<ArticleKeyPoint>
    if (typeof text !== 'string' || !text.trim()) return []
    return [{
      text,
      evidence: Array.isArray(evidence) ? evidence.filter((id) => typeof id === 'number') : [],
      groundedness: groundedness === 'grounded' || groundedness === 'weak' ? groundedness : 'ungrounded',
    }]
  })
}
