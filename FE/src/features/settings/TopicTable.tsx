import { useState } from 'react'
import type { TopicRelatedKeyword, TopicSurgeKeyword } from '../../api/types'
import { useSetTopicActivation, useTopics } from '../../api/queries'
import { ApiError } from '../../api/client'
import { MutationStatus } from './MutationStatus'
import { TopicDeliverySettings } from '../notifications/TopicDeliverySettings'

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
  if (minutes === 1440) return '24시간마다'
  if (minutes % 1440 === 0) return `${minutes / 1440}일마다`
  if (minutes % 60 === 0) return `${minutes / 60}시간마다`
  return `${minutes}분마다`
}

function formatKeywords(keywords: string[]) {
  return keywords.length > 0 ? keywords.join(', ') : '—'
}

function formatSignedDelta(value: number) {
  return value > 0 ? `+${value}` : `${value}`
}

function surgeKeywordTitle(keyword: TopicSurgeKeyword) {
  const burstDescription = keyword.burst ? ' · 평소보다 크게 늘었어요' : ''
  return `${keyword.keyword} · 최근 7일 ${keyword.issueCount}이슈 · 전주 대비 ${formatSignedDelta(keyword.deltaIssueCount)}${burstDescription}`
}

function relatedKeywordTitle(keyword: TopicRelatedKeyword) {
  return `${keyword.keyword} · 최근 7일 ${keyword.issueCount}이슈 · 주제 내 비중 ${keyword.sharePercent.toFixed(0)}%`
}

export function TopicTable() {
  const [showInactive, setShowInactive] = useState(false)
  const [success, setSuccess] = useState<string | null>(null)
  const [deliveryTopicId, setDeliveryTopicId] = useState<number | null>(null)
  const topics = useTopics(!showInactive)
  const activation = useSetTopicActivation()

  if (topics.isPending) return <p className="muted">불러오는 중…</p>

  if (topics.error || !topics.data) {
    const reason = topics.error instanceof ApiError
      ? topics.error.message
      : '주제 목록을 불러오지 못했습니다.'
    return <p className="error">{reason}</p>
  }

  function setActive(topicId: number, active: boolean) {
    setSuccess(null)
    activation.mutate({ topicId, active }, {
      onSuccess: (topic) => setSuccess(`"${topic.name}" 수집을 ${active ? '재개' : '중지'}했습니다.`),
    })
  }

  return (
    <>
      <div className="topic-list-toolbar">
        <span className="muted">{showInactive ? '중지한 주제' : '수집 중인 주제'} · {topics.data.totalElements}개</span>
        <button type="button" className="secondary-button" aria-pressed={showInactive}
          onClick={() => { setShowInactive((value) => !value); setSuccess(null); setDeliveryTopicId(null); activation.reset() }}>
          {showInactive ? '수집 중인 주제 보기' : '중지한 주제 보기'}
        </button>
      </div>
      <MutationStatus error={activation.error} success={success} />
      {topics.data.content.length === 0 ? (
        <p className="empty-block">{showInactive ? '중지한 수집 주제가 없습니다.' : '수집 중인 주제가 없습니다. 새 주제를 등록하거나 중지한 주제를 다시 시작해 주세요.'}</p>
      ) : (
        <div className="table-scroll">
          <table className="topic-table topic-management-table">
            <colgroup>
              <col className="topic-name-column" />
              <col className="topic-query-column" />
              <col className="topic-conditions-column" />
              <col className="topic-surge-column" />
              <col className="topic-related-column" />
              <col className="topic-schedule-column" />
              <col className="topic-actions-column" />
            </colgroup>
            <thead>
              <tr>
                <th>주제</th>
                <th>검색 키워드</th>
                <th>기사 조건</th>
                <th title="최근 7일에 전주보다 언급이 늘어난 키워드입니다. 숫자는 관련 주제 묶음의 증가 건수입니다.">지난주 대비 증가</th>
                <th title="최근 7일에 함께 등장한 키워드와 비중입니다.">연관 키워드</th>
                <th>수집 일정</th>
                <th>관리</th>
              </tr>
            </thead>
            <tbody>
              {topics.data.content.map((topic) => (
                <tr key={topic.id}>
                  <td className="topic-name-cell"><strong className="topic-name-scroll" title={topic.name}
                    tabIndex={topic.name.length > 24 ? 0 : undefined}>{topic.name}</strong></td>
                  <td>{topic.queryText ?? '—'}</td>
                  <td>
                    <div className="topic-condition-lines">
                      {topic.requiredKeywords.length > 0 && <span><small>모두</small> {formatKeywords(topic.requiredKeywords)}</span>}
                      {topic.optionalKeywords.length > 0 && <span><small>하나 이상</small> {formatKeywords(topic.optionalKeywords)}</span>}
                      {topic.excludedKeywords.length > 0 && <span><small>제외</small> {formatKeywords(topic.excludedKeywords)}</span>}
                      {topic.requiredKeywords.length + topic.optionalKeywords.length + topic.excludedKeywords.length === 0 && '—'}
                    </div>
                  </td>
                  <td className="topic-signal-cell"><KeywordSignalList items={topic.surgeKeywords ?? []} emptyLabel="—"
                    renderLabel={(keyword) => `${keyword.keyword} ${formatSignedDelta(keyword.deltaIssueCount)}`}
                    renderTitle={surgeKeywordTitle} /></td>
                  <td className="topic-signal-cell"><KeywordSignalList items={topic.relatedKeywords ?? []} emptyLabel="—"
                    renderLabel={(keyword) => `${keyword.keyword} ${keyword.sharePercent.toFixed(0)}%`}
                    renderTitle={relatedKeywordTitle} /></td>
                  <td><div className="topic-condition-lines"><span>{formatInterval(topic.intervalMinutes)}</span>
                    <small title="마지막 수집">{formatCollectedAt(topic.lastCollectedAt)}</small></div></td>
                  <td><div className="topic-management-actions"><button type="button" className="secondary-button topic-management-action" disabled={activation.isPending}
                    onClick={() => setActive(topic.id, !topic.active)}>
                    {activation.isPending && activation.variables?.topicId === topic.id ? '처리 중…' : topic.active ? '수집 중지' : '수집 재개'}
                  </button>
                    <button type="button" className="ghost-button topic-management-action"
                      aria-expanded={deliveryTopicId === topic.id} aria-controls="topic-delivery-settings"
                      onClick={() => setDeliveryTopicId((current) => current === topic.id ? null : topic.id)}>자동 전달</button>
                  </div></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {topics.data.content.some((topic) => topic.id === deliveryTopicId) && deliveryTopicId !== null && (
        <div id="topic-delivery-settings" className="topic-delivery-settings">
          <h3>{topics.data.content.find((topic) => topic.id === deliveryTopicId)?.name}</h3>
          <TopicDeliverySettings key={deliveryTopicId} topicId={deliveryTopicId} />
        </div>
      )}
    </>
  )
}

function KeywordSignalList<T extends TopicSurgeKeyword | TopicRelatedKeyword>({
  items,
  emptyLabel,
  renderLabel,
  renderTitle,
}: {
  items: T[]
  emptyLabel: string
  renderLabel: (item: T) => string
  renderTitle: (item: T) => string
}) {
  if (items.length === 0) {
    return <span className="topic-signal-empty">{emptyLabel}</span>
  }

  return (
    <div className="topic-signal-list">
      {items.map((item) => (
        <span
          className="topic-signal-chip"
          key={renderLabel(item)}
          title={renderTitle(item)}
        >
          {renderLabel(item)}
        </span>
      ))}
    </div>
  )
}
