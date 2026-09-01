import type { Sensitivity } from '../api/types'

const AXIS_LABELS: Record<keyof Sensitivity['axes'], string> = {
  customerMove: '고객 움직임',
  dealSignal: '사업 기회',
  competitorThreat: '경쟁 위협',
  industryShift: '산업 변화',
}

export function SensitivityAxes({ sensitivity, onEvidenceSelect }: {
  sensitivity: Sensitivity
  onEvidenceSelect: (evidence: number[]) => void
}) {
  return (
    <section className="sensitivity-axes" aria-label="민감도 산정 근거">
      <div className="sensitivity-axes-heading">
        <strong>민감도 산정 근거</strong>
        <span>가용 축 기준 {sensitivity.score.toFixed(1)} / 100</span>
      </div>
      <div className="sensitivity-axis-grid">
        {(Object.keys(AXIS_LABELS) as Array<keyof Sensitivity['axes']>).map((name) => {
          const axis = sensitivity.axes[name]
          return (
            <div className="sensitivity-axis" key={name}>
              <span>{AXIS_LABELS[name]}</span>
              <strong>{axis.score === null ? '판정 불가' : `${axis.score} / 3`}</strong>
              {axis.evidenceSentenceIds.length > 0 && (
                <button type="button" onClick={() => onEvidenceSelect(axis.evidenceSentenceIds)}>
                  근거 {axis.evidenceSentenceIds.length}개
                </button>
              )}
            </div>
          )
        })}
      </div>
    </section>
  )
}
