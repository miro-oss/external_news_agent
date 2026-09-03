import { useState } from 'react'
import { ApiError } from '../../api/client'
import {
  useApproveTopicKeywordProposal,
  useRejectTopicKeywordProposal,
  useTopicKeywordProposals,
} from '../../api/queries'
import type {
  TopicKeywordChangeAction,
  TopicKeywordBucket,
  TopicKeywordProposal,
  TopicKeywordProposalFilter,
  TopicKeywordProposalStatus,
} from '../../api/types'
import { MutationStatus } from './MutationStatus'

const FILTER_OPTIONS: Array<{ value: TopicKeywordProposalFilter; label: string }> = [
  { value: 'PENDING', label: '대기 중만' },
  { value: 'ALL', label: '전체' },
  { value: 'APPROVED', label: '승인됨' },
  { value: 'REJECTED', label: '반려됨' },
]

const STATUS_LABELS: Record<TopicKeywordProposalStatus, string> = {
  PENDING: '검토 대기',
  APPROVED: '승인됨',
  REJECTED: '반려됨',
}

const BUCKET_LABELS: Record<TopicKeywordBucket, string> = {
  REQUIRED: '필수',
  OPTIONAL: '선택',
  EXCLUDED: '제외',
}

const ACTION_LABELS: Record<TopicKeywordChangeAction, string> = {
  ADD: '추가',
  REMOVE: '제거',
}

function formatDateTime(value: string | null) {
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

function emptyMessage(filter: TopicKeywordProposalFilter) {
  if (filter === 'PENDING') return '검토 대기 중인 키워드 제안이 없습니다.'
  if (filter === 'APPROVED') return '승인된 키워드 제안이 없습니다.'
  if (filter === 'REJECTED') return '반려된 키워드 제안이 없습니다.'
  return '표시할 키워드 제안이 없습니다.'
}

export function TopicKeywordProposalPanel() {
  const [filter, setFilter] = useState<TopicKeywordProposalFilter>('PENDING')
  const [success, setSuccess] = useState<string | null>(null)
  const [actingIds, setActingIds] = useState<Set<number>>(() => new Set())
  const proposals = useTopicKeywordProposals(filter)
  const approve = useApproveTopicKeywordProposal()
  const reject = useRejectTopicKeywordProposal()

  function resetFeedback() {
    setSuccess(null)
    approve.reset()
    reject.reset()
  }

  function changeFilter(next: TopicKeywordProposalFilter) {
    resetFeedback()
    setActingIds(new Set())
    setFilter(next)
  }

  function review(proposalId: number, action: 'approve' | 'reject') {
    if (actingIds.has(proposalId)) return

    resetFeedback()
    setActingIds((current) => new Set(current).add(proposalId))
    const mutation = action === 'approve' ? approve : reject
    mutation.mutate(proposalId, {
      onSuccess: (proposal) => {
        setSuccess(`"${proposal.topicName}" 키워드 제안을 ${action === 'approve' ? '승인' : '반려'}했습니다.`)
      },
      onSettled: () => setActingIds((current) => {
        const next = new Set(current)
        next.delete(proposalId)
        return next
      }),
    })
  }

  if (proposals.isPending) {
    return <p className="muted">키워드 제안을 불러오는 중…</p>
  }

  if (proposals.error || !proposals.data) {
    const reason = proposals.error instanceof ApiError
      ? `${proposals.error.message} (${proposals.error.code})`
      : '키워드 제안을 불러오지 못했습니다.'
    return <p className="error">{reason}</p>
  }

  const actionPending = actingIds.size > 0
  const mutationError = approve.error ?? reject.error

  return (
    <>
      <div className="proposal-toolbar">
        <div className="segmented" role="group" aria-label="제안 상태">
          {FILTER_OPTIONS.map((option) => (
            <button
              key={option.value}
              type="button"
              aria-pressed={filter === option.value}
              className={filter === option.value ? 'segmented-option active' : 'segmented-option'}
              disabled={actionPending}
              onClick={() => changeFilter(option.value)}
            >
              {option.label}
            </button>
          ))}
        </div>
        {/* 승인해야 반영된다는 말은 섹션 설명이 이미 하고 있다. 여기서는 목록의 범위만 밝힌다. */}
        <p className="proposal-toolbar-hint">지난 자동 수집이 만든 제안만 보입니다.</p>
      </div>

      {proposals.data.content.length === 0 ? (
        <p className="proposal-empty-state">{emptyMessage(filter)}</p>
      ) : (
        <div className="proposal-stack">
          {proposals.data.content.map((proposal) => (
            <ProposalCard
              key={proposal.id}
              proposal={proposal}
              isActing={actingIds.has(proposal.id)}
              onApprove={() => review(proposal.id, 'approve')}
              onReject={() => review(proposal.id, 'reject')}
            />
          ))}
        </div>
      )}

      <MutationStatus error={mutationError} success={success} />
    </>
  )
}

function ProposalCard({
  proposal,
  isActing,
  onApprove,
  onReject,
}: {
  proposal: TopicKeywordProposal
  isActing: boolean
  onApprove: () => void
  onReject: () => void
}) {
  return (
    <article className="proposal-card">
      <div className="proposal-header">
        <div className="proposal-title-row">
          <h3>{proposal.topicName}</h3>
          <span className={`status-pill proposal-status proposal-status-${proposal.status.toLowerCase()}`}>
            {STATUS_LABELS[proposal.status]}
          </span>
        </div>
        <p className="proposal-summary">{proposal.summary}</p>
        <p className="proposal-meta">
          자동 수집 #{proposal.collectionRunId} · 제안 {formatDateTime(proposal.createdAt)}
          {proposal.reviewedAt && ` · 검토 ${formatDateTime(proposal.reviewedAt)}`}
        </p>
      </div>

      <div className="proposal-grid">
        <section className="proposal-section">
          <h4>현재 키워드</h4>
          <KeywordGroup label="필수" keywords={proposal.currentKeywords.requiredKeywords} />
          <KeywordGroup label="선택" keywords={proposal.currentKeywords.optionalKeywords} />
          <KeywordGroup label="제외" keywords={proposal.currentKeywords.excludedKeywords} />
        </section>

        <section className="proposal-section">
          <h4>제안 변경</h4>
          {proposal.changes.length === 0 ? (
            <p className="muted proposal-empty">변경 항목이 없습니다.</p>
          ) : (
            <ul className="proposal-change-list">
              {proposal.changes.map((change, index) => (
                <li className="proposal-change-item" key={`${proposal.id}-${change.bucket}-${change.keyword}-${index}`}>
                  <strong>
                    {BUCKET_LABELS[change.bucket]} · {ACTION_LABELS[change.action]} · {change.keyword}
                  </strong>
                  <p>{change.reason}</p>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>

      {proposal.status === 'PENDING' && (
        <div className="proposal-actions">
          <button type="button" className="secondary-button" disabled={isActing} onClick={onReject}>
            {isActing ? '처리 중…' : '반려'}
          </button>
          <button type="button" disabled={isActing} onClick={onApprove}>
            {isActing ? '처리 중…' : '승인'}
          </button>
        </div>
      )}
    </article>
  )
}

function KeywordGroup({ label, keywords }: { label: string; keywords: string[] }) {
  return (
    <div className="proposal-keyword-group">
      <strong>{label}</strong>
      {keywords.length > 0 ? (
        <div className="proposal-keyword-list">
          {keywords.map((keyword) => (
            <span className="proposal-keyword-chip" key={`${label}-${keyword}`}>{keyword}</span>
          ))}
        </div>
      ) : (
        <p className="muted proposal-empty">없음</p>
      )}
    </div>
  )
}
