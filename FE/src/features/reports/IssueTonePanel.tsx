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
        <span>현재 기준 · 표본 {distribution.sampleCount}건</span>
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
                <span>{tone.label} <strong>{tone.percent?.toFixed(2)}%</strong> · {tone.count}건</span>
              </li>
            ))}
          </ul>
          <p className="issue-detail-state">
            발화 주체와 근거가 확인된 견해를 포함한 기사의 전체 논조입니다. 같은 원문은 한 번만 집계합니다.
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
        관련 {articleCount}건 중 직접 분석 {distribution.analyzedArticleCount}건 · 전체 매체의 의견 비율은 아닙니다.
      </p>
    </section>
  )
}
