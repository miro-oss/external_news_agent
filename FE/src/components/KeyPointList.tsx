import { useState } from 'react'
import type { ArticleKeyPoint } from '../api/types'

interface Props {
  points: ArticleKeyPoint[]
  /** 누른 근거가 가리키는 본문 문장으로 데려간다. */
  onEvidenceSelect: (sentenceId: number) => void
  /** 근거 버튼의 스크린리더 문구에 붙일 기사 제목. 리포트는 카드마다 기사가 달라 필요하다. */
  articleTitle?: string
  /** 이 개수를 넘는 항목은 접어 둔다. */
  collapseAfter?: number
}

/**
 * 근거가 약하거나 없을 때만 라벨을 붙인다.
 *
 * <p>근거가 붙은 항목에 "근거 확인"까지 쓰면 한 줄이 "근거 확인 근거1 근거2"가 된다. 같은 말이
 * 세 번이라 정작 눈에 걸려야 할 "근거 없음"이 묻힌다. 정상은 근거 버튼으로 이미 보이므로
 * 예외에만 이름을 준다 — 리포트 근거 카드가 원래 쓰던 규칙이고, 기사 상세를 여기에 맞춘다.
 */
const GROUNDEDNESS_WARNINGS: Partial<Record<ArticleKeyPoint['groundedness'], string>> = {
  weak: '근거 약함',
  ungrounded: '근거 없음',
}

/**
 * 문장 단위 근거가 달린 핵심 목록. 기사 상세와 리포트 근거 카드가 같은 것을 보여 준다.
 *
 * <p>기본으로 앞의 몇 건만 펴 둔다. 한 기사에 핵심이 네다섯 건이면 요약 바로 아래에 같은 굵기의
 * 글이 그만큼 쌓여서, 무엇을 먼저 읽어야 하는지가 사라진다.
 */
export function KeyPointList({ points, onEvidenceSelect, articleTitle, collapseAfter = 2 }: Props) {
  const [expanded, setExpanded] = useState(false)

  if (points.length === 0) return null

  const hiddenCount = Math.max(points.length - collapseAfter, 0)
  const visible = expanded || hiddenCount === 0 ? points : points.slice(0, collapseAfter)

  return (
    <div className="key-points">
      <div className="key-points-heading">
        <strong>핵심</strong>
        <span>{points.length}</span>
      </div>
      {visible.map((point, index) => {
        const warning = GROUNDEDNESS_WARNINGS[point.groundedness]
        return (
          <div className="key-point" key={`${index}-${point.text}`}>
            <span className="key-point-number">{index + 1}</span>
            <div className="key-point-content">
              <span className="key-point-text">{point.text}</span>
              {warning && <span className={`groundedness ${point.groundedness}`}>{warning}</span>}
              {point.evidence.map((sentenceId, localIndex) => (
                <button
                  type="button"
                  className="evidence"
                  key={`${sentenceId}-${localIndex}`}
                  aria-label={[
                    `핵심 ${index + 1}의 근거 ${localIndex + 1}`,
                    articleTitle,
                    `본문 ${sentenceId + 1}번째 문장으로 이동`,
                  ].filter(Boolean).join(' · ')}
                  onClick={() => onEvidenceSelect(sentenceId)}
                >
                  근거 {localIndex + 1}
                </button>
              ))}
            </div>
          </div>
        )
      })}
      {hiddenCount > 0 && (
        <button
          type="button"
          className="key-points-toggle"
          aria-expanded={expanded}
          onClick={() => setExpanded((current) => !current)}
        >
          {expanded ? '접기' : `${hiddenCount}건 더 보기`}
        </button>
      )}
    </div>
  )
}
