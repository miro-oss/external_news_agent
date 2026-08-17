import { useCombinations } from '../../api/queries'
import { ApiError } from '../../api/client'

/** 오프셋이 붙은 ISO-8601을 그대로 보여 주면 열이 넘친다. 날짜와 분까지만 남긴다. */
function formatCollectedAt(value: string | null) {
  if (!value) {
    return '—'
  }
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) {
    return value
  }
  return parsed.toLocaleString('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

export function CombinationTable() {
  const { data, isPending, error } = useCombinations()

  if (isPending) {
    return <p className="muted">불러오는 중…</p>
  }

  if (error) {
    const reason = error instanceof ApiError ? `${error.message} (${error.code})` : '목록을 불러오지 못했습니다.'
    return <p className="error">{reason}</p>
  }

  if (data.content.length === 0) {
    return (
      <p className="muted">
        등록된 조합이 없습니다. 아래에서 소스를 먼저 등록하고, 주제를 만들 때 그 소스를 연결하세요.
      </p>
    )
  }

  return (
    <>
      <p className="muted">
        한 행이 <strong>(주제 × 소스)</strong> 조합 하나입니다. 조합 {data.combinationCount}건.
      </p>
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th>주제</th>
              <th>소스</th>
              <th>종류</th>
              <th>검색어</th>
              <th className="numeric">수집 건수</th>
              <th className="numeric">주기(분)</th>
              <th>활성</th>
              <th>마지막 수집</th>
            </tr>
          </thead>
          <tbody>
            {data.content.map((row) => (
              <tr key={`${row.topicId}-${row.sourceId}`}>
                <td>{row.topicName}</td>
                <td>{row.sourceName}</td>
                <td>
                  <span className={`badge badge-${row.sourceKind.toLowerCase()}`}>{row.sourceKind}</span>
                </td>
                {/* FEED 조합은 질의어가 없다. 빈 칸으로 두면 누락처럼 보이므로 명시적으로 표시한다. */}
                <td>{row.queryText ?? '—'}</td>
                <td className="numeric">{row.batchSize}</td>
                <td className="numeric">{row.intervalMinutes}</td>
                <td>{row.active ? '활성' : '비활성'}</td>
                <td>{formatCollectedAt(row.lastCollectedAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  )
}
