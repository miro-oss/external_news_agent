import type { IssueToneDistribution } from '../../api/types'

export function IssueTonePanel({ distribution, articleCount }: {
  distribution: IssueToneDistribution
  articleCount: number
}) {
  const tones = [
    { key: 'optimistic', label: '낙관', count: distribution.optimisticCount, percent: distribution.optimisticPercent },
    { key: 'neutral', label: '중립', count: distribution.neutralCount, percent: distribution.neutralPercent },
    { key: 'pessimistic', label: '비관', count: distribution.pessimisticCount, percent: distribution.pessimisticPercent },
  ]

  return (
    <section className="issue-tone-panel" aria-label="견해 포함 기사 논조">
      <div className="issue-detail-heading">
        <h5>견해 포함 기사 논조</h5>
        <span>의견이 확인된 기사 {distribution.sampleCount}건</span>
      </div>
      {distribution.sampleCount > 0 ? (
        <>
          <div className="issue-tone-bar" aria-hidden="true">
            {tones.map((tone) => (
              <span
                key={tone.key}
                className={`issue-tone-${tone.key}`}
                style={{ flexGrow: tone.count, minWidth: tone.count > 0 ? 2 : 0 }}
              />
            ))}
          </div>
          <ul className="issue-tone-legend">
            {tones.map((tone) => (
              <li key={tone.key}>
                <span className={`issue-tone-dot issue-tone-${tone.key}`} aria-hidden="true" />
                <span>{tone.label} <strong>{tone.count}건</strong>{distribution.sampleCount > 1 && ` · ${tone.percent?.toFixed(0)}%`}</span>
              </li>
            ))}
          </ul>
          <p className="issue-detail-state">
            의견이 확인된 기사의 전체 분위기를 분류했습니다. 같은 내용의 전재 기사는 한 번만 셉니다.
          </p>
        </>
      ) : (
        <p className="issue-detail-state">
          {distribution.analyzedArticleCount === 0
            ? '논조를 집계할 분석이 아직 없습니다.'
            : '현재 분석에서 집계할 수 있는 견해가 없습니다.'}
        </p>
      )}
      <p className="issue-tone-coverage">
        관련 기사 {articleCount}건 중 분석 결과가 있는 {distribution.analyzedArticleCount}건을 확인했습니다.
      </p>
      <details className="issue-tone-explanation"><summary>집계 기준</summary>
        <p>분석한 기사 중 누가 말했는지와 원문 근거가 확인된 의견이 있는 기사만 포함합니다. 개별 의견의 찬반이나 언론사 전체의 여론을 나타내는 수치는 아닙니다. 보고서 작성 후 새 분석이 추가되면 이 분포도 달라질 수 있습니다.</p>
      </details>
    </section>
  )
}
