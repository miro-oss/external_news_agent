import { useId, useState } from 'react'
import type { ArticleKeyPoint } from '../api/types'

interface Props {
  points: ArticleKeyPoint[]
  /** 누른 근거가 가리키는 본문 문장으로 데려간다. */
  onEvidenceSelect: (sentenceId: number) => void
  /** 근거 버튼의 스크린리더 문구에 붙일 기사 제목. 리포트는 카드마다 기사가 달라 필요하다. */
  articleTitle?: string
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

const CLAIM_TYPE_LABELS: Record<ArticleKeyPoint['claimType'], string> = {
  FACT: '사실',
  FORECAST: '전망',
  OPINION: '견해',
}

/**
 * 문장 단위 근거가 달린 핵심 목록. 기사 상세와 리포트 근거 카드가 같은 것을 보여 준다.
 *
 * <p>기본으로 접어 둔다. 무슨 일이 있었는지는 위 요약 한 문단이 이미 말했고, 같은 내용을 번호를
 * 붙여 네다섯 줄로 다시 늘어놓으면 읽을 글만 늘어난다.
 *
 * <p>그렇다고 지우지는 않는다. 요약에는 문장 단위 근거 계약이 없어서(BE `ReportEvidencePolicy`)
 * 무엇을 보고 그렇게 썼는지 되짚을 방법이 없다. 주장을 본문 문장에 이어 주는 것도, 모델이
 * 지어냈을 때 "근거 없음"으로 세워 주는 것도 여기뿐이다. 훑을 때는 접혀 있고, 믿어도 되는지
 * 확인할 때 펴는 자리다 — 여는 단추에 "근거 보기"라고 적는 이유다.
 */
export function KeyPointList({ points, onEvidenceSelect, articleTitle }: Props) {
  const [open, setOpen] = useState(false)
  const panelId = `${useId()}-key-points`

  if (points.length === 0) return null

  return (
    <div className="key-points">
      <button
        type="button"
        className="key-points-toggle"
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => setOpen((current) => !current)}
      >
        <span className="key-points-heading">
          <strong>핵심</strong>
          <span>{points.length}</span>
        </span>
        <span className="key-points-action">{open ? '접기' : '근거 보기'}</span>
      </button>
      {open && (
        <div className="key-point-list" id={panelId}>
          {points.map((point, index) => {
            const warning = GROUNDEDNESS_WARNINGS[point.groundedness]
            return (
              <div className="key-point" key={`${index}-${point.text}`}>
                <span className="key-point-number">{index + 1}</span>
                <div className="key-point-content">
                  <div className="key-point-main">
                    {point.claimType !== 'FACT' && (
                      <span
                        className={`claim-type-label ${point.claimType.toLowerCase()}`}
                        title={point.attributedTo
                          ? `${CLAIM_TYPE_LABELS[point.claimType]} · ${point.attributedTo}`
                          : CLAIM_TYPE_LABELS[point.claimType]}
                      >
                        {CLAIM_TYPE_LABELS[point.claimType]}
                      </span>
                    )}
                    <span className="key-point-text">{point.text}</span>
                  </div>
                  {(warning || point.evidence.length > 0) && (
                    <div className="key-point-evidence">
                      {warning && (
                        <span
                          className={`groundedness ${point.groundedness}`}
                          title={point.groundingReason ?? undefined}
                        >
                          {warning}
                        </span>
                      )}
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
                          원문 근거 {localIndex + 1}
                        </button>
                      ))}
                    </div>
                  )}
                </div>
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}
