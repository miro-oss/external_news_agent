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

/** 저장된 분 값을 설정 화면에서 쓰는 주기 표현으로 바꾼다. 기존 사용자 지정 값도 읽을 수 있게 남긴다. */
function formatInterval(minutes: number) {
  if (minutes === 1440) return '매일 한 번'
  if (minutes % 1440 === 0) return `${minutes / 1440}일마다`
  if (minutes % 60 === 0) return `${minutes / 60}시간마다`
  return `${minutes}분마다`
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
        등록된 조합이 없습니다. 위에서 소스를 먼저 등록하고, 주제를 만들 때 그 소스를 연결하세요.
      </p>
    )
  }

  return (
    /*
      열 구성은 #76을 따른다 — 수집 건수는 시스템이 관리하게 되어 화면에서 뺐고, 주기는 분 단위
      숫자 대신 formatInterval이 읽을 수 있는 말로 바꾼다.

      건수 안내 문단은 두지 않는다. 조합 수는 접이식 카드 제목 옆 배지로 이미 보이고,
      "한 행이 (주제 × 소스)"라는 설명도 그 카드의 설명 줄에 있다.
    */
    <div className="table-scroll">
      <table>
        <thead>
          <tr>
            <th>주제</th>
            <th>소스</th>
            <th>종류</th>
            <th>검색어</th>
            <th title="새로운 기사를 다시 확인하는 주기입니다.">수집 주기</th>
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
              <td>{formatInterval(row.intervalMinutes)}</td>
              <td>
                <span className={row.active ? 'dot-on' : 'dot-off'}>{row.active ? '활성' : '비활성'}</span>
              </td>
              <td>{formatCollectedAt(row.lastCollectedAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
