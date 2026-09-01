import { useTopics } from '../../api/queries'
import { ApiError } from '../../api/client'

/** 오프셋이 붙은 ISO-8601을 그대로 보여 주면 열이 넘친다. 날짜와 분까지만 남긴다. */
function formatCollectedAt(value: string | null) {
  if (!value) return '—'

  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return value

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

function formatKeywords(keywords: string[]) {
  return keywords.length > 0 ? keywords.join(', ') : '—'
}

export function TopicTable() {
  const topics = useTopics()

  if (topics.isPending) return <p className="muted">불러오는 중…</p>

  if (topics.error || !topics.data) {
    const reason = topics.error instanceof ApiError
      ? `${topics.error.message} (${topics.error.code})`
      : '주제 목록을 불러오지 못했습니다.'
    return <p className="error">{reason}</p>
  }

  if (topics.data.content.length === 0) {
    return <p className="muted">등록된 수집 주제가 없습니다. 위에서 새 주제를 등록해 주세요.</p>
  }

  return (
    <div className="table-scroll">
      <table className="topic-table">
        <colgroup>
          <col className="topic-name-column" />
          <col className="topic-query-column" />
          <col className="topic-required-column" />
          <col className="topic-optional-column" />
          <col className="topic-excluded-column" />
          <col className="topic-interval-column" />
          <col className="topic-collected-column" />
          <col className="topic-status-column" />
        </colgroup>
        <thead>
          <tr>
            <th>주제</th>
            <th>검색어</th>
            <th title="모두 포함되어야 하는 AND 조건입니다.">필수 키워드</th>
            <th title="하나라도 포함되면 통과하는 OR 조건입니다.">선택 키워드</th>
            <th title="하나라도 포함되면 제외하는 NOT 조건입니다.">제외 키워드</th>
            <th title="새로운 기사를 다시 확인하는 주기입니다.">수집 주기</th>
            <th>마지막 수집</th>
            <th>상태</th>
          </tr>
        </thead>
        <tbody>
          {topics.data.content.map((topic) => (
            <tr key={topic.id}>
              <td className="topic-name-cell">
                <strong
                  className="topic-name-scroll"
                  title={topic.name}
                  tabIndex={topic.name.length > 24 ? 0 : undefined}
                >
                  {topic.name}
                </strong>
              </td>
              <td>{topic.queryText ?? '—'}</td>
              <td>{formatKeywords(topic.requiredKeywords)}</td>
              <td>{formatKeywords(topic.optionalKeywords)}</td>
              <td>{formatKeywords(topic.excludedKeywords)}</td>
              <td>{formatInterval(topic.intervalMinutes)}</td>
              <td>{formatCollectedAt(topic.lastCollectedAt)}</td>
              <td>
                <span className={topic.active ? 'dot-on' : 'dot-off'}>
                  {topic.active ? '활성' : '비활성'}
                </span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
