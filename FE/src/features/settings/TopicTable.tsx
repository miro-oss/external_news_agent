import { useEffect, useState } from 'react'
import { FEEDBACK_DURATION_MS } from '../../lib/feedback'
import type { TopicRelatedKeyword, TopicSurgeKeyword } from '../../api/types'
import { useSetTopicActivation, useTopics } from '../../api/queries'
import { ApiError } from '../../api/client'
import { MutationStatus } from './MutationStatus'
import { TopicTableSkeleton } from './SettingsSkeletons'
import { TopicDeliverySettings } from './TopicDeliverySettings'
import { TopicScheduleSettings } from './TopicScheduleSettings'
import { TopicEditSettings } from './TopicEditSettings'
import { TopicSourceSettings } from './TopicSourceSettings'

function formatSignedDelta(value: number) {
  return value > 0 ? `+${value}` : `${value}`
}

function surgeKeywordTitle(keyword: TopicSurgeKeyword) {
  const burstDescription = keyword.burst ? ' · 평소보다 크게 늘었어요' : ''
  return `${keyword.keyword} · 직전 7일 ${keyword.previousArticleCount}건 → 최근 7일 ${keyword.articleCount}건 · ${formatSignedDelta(keyword.deltaArticleCount)}건${burstDescription}`
}

function relatedKeywordTitle(keyword: TopicRelatedKeyword) {
  return `${keyword.keyword} · 최근 7일 ${keyword.articleCount}건 · 수집 기사 중 ${keyword.sharePercent.toFixed(0)}%`
}

export function TopicTable() {
  const [showInactive, setShowInactive] = useState(false)
  const [success, setSuccess] = useState<string | null>(null)
  const topics = useTopics(!showInactive)
  const activation = useSetTopicActivation()

  // 목록 조회 오류로 알림이 잠시 사라져도 예전 성공 상태가 복구 시 다시 표시되지 않게 한다.
  useEffect(() => {
    if (!success) return
    const timer = window.setTimeout(() => setSuccess(null), FEEDBACK_DURATION_MS)
    return () => window.clearTimeout(timer)
  }, [success])

  if (topics.isPending) return <TopicTableSkeleton />

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
        <button type="button" className="ghost-button topic-visibility-toggle" aria-pressed={showInactive}
          onClick={() => { setShowInactive((value) => !value); setSuccess(null); activation.reset() }}>
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
              <col className="topic-surge-column" />
              <col className="topic-related-column" />
              <col className="topic-schedule-column" />
              <col className="topic-delivery-column" />
              <col className="topic-actions-column" />
            </colgroup>
            <thead>
              <tr>
                <th>주제</th>
                <th>검색 키워드</th>
                <th title="최근 7일과 직전 7일에 수집한 기사에서 키워드가 등장한 기사 수의 차이입니다.">지난주 대비 증가</th>
                <th title="최근 7일 수집 기사에서 찾은 키워드와, 전체 수집 기사 중 해당 키워드가 등장한 비율입니다.">연관 키워드</th>
                <th>수집 일정</th>
                <th>보고서 알림</th>
                <th>관리</th>
              </tr>
            </thead>
            <tbody>
              {topics.data.content.map((topic) => (
                <tr key={topic.id}>
                  <td className="topic-name-cell"><strong className="topic-name-scroll" title={topic.name}
                    tabIndex={topic.name.length > 24 ? 0 : undefined}>{topic.name}</strong></td>
                  <td>{topic.queryText ?? '—'}</td>
                  <td className="topic-signal-cell"><KeywordSignalList items={topic.surgeKeywords ?? []} emptyLabel="—"
                    renderLabel={(keyword) => `${keyword.keyword} ${formatSignedDelta(keyword.deltaArticleCount)}건`}
                    isEmphasized={(keyword) => keyword.deltaArticleCount > 50}
                    renderTitle={surgeKeywordTitle} /></td>
                  <td className="topic-signal-cell"><KeywordSignalList items={topic.relatedKeywords ?? []} emptyLabel="—"
                    renderLabel={(keyword) => `${keyword.keyword} ${keyword.sharePercent.toFixed(0)}%`}
                    renderTitle={relatedKeywordTitle} /></td>
                  <td><TopicScheduleSettings topic={topic} /></td>
                  <td><TopicDeliverySettings topicId={topic.id} topicName={topic.name} /></td>
                  <td><div className="topic-management-actions"><button type="button" className="ghost-button topic-management-action" disabled={activation.isPending}
                    onClick={() => setActive(topic.id, !topic.active)}>
                    {activation.isPending && activation.variables?.topicId === topic.id ? '처리 중…' : topic.active ? '수집 중지' : '수집 재개'}
                  </button>
                  <TopicEditSettings topic={topic} />
                  <TopicSourceSettings topic={topic} />
                  </div></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {topics.data.content.length > 0 && (
        <div className="hint topic-signal-help">
          <p>지난주 대비 증가: 최근 7일에 키워드가 등장한 기사 수에서 직전 7일의 기사 수를 뺀 값입니다. 키워드에 마우스를 올리면 두 기간의 건수를 볼 수 있습니다.</p>
          <p>연관 키워드: 최근 7일 수집 기사에서 찾은 키워드와, 전체 수집 기사 중 그 키워드가 등장한 비율입니다.</p>
          <p>수집 시각 기준으로 본문을 확보한 새 기사·내용이 바뀐 기사를 집계합니다. 같은 기사는 각 기간에서 한 번만 세며, 주제명과 등록한 키워드는 제외합니다.</p>
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
  isEmphasized,
}: {
  items: T[]
  emptyLabel: string
  renderLabel: (item: T) => string
  renderTitle: (item: T) => string
  isEmphasized?: (item: T) => boolean
}) {
  if (items.length === 0) {
    return <span className="topic-signal-empty">{emptyLabel}</span>
  }

  return (
    <div className="topic-signal-list">
      {items.map((item) => (
        <span
          className={`topic-signal-chip${isEmphasized?.(item) ? ' is-emphasized' : ''}`}
          key={renderLabel(item)}
          title={renderTitle(item)}
        >
          {renderLabel(item)}
        </span>
      ))}
    </div>
  )
}
