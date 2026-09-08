import { useEffect, useId, useLayoutEffect, useRef, useState, type RefObject } from 'react'
import { createPortal } from 'react-dom'
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
import { Segmented, type SegmentedOption } from '../../components/Segmented'
import { MutationStatus } from './MutationStatus'
import { KeywordProposalsSkeleton } from './SettingsSkeletons'
import '../notifications/notifications-refinement.css'

const FILTER_OPTIONS: ReadonlyArray<SegmentedOption<TopicKeywordProposalFilter>> = [
  { value: 'PENDING', label: '대기 중' },
  { value: 'APPROVED', label: '승인됨' },
  { value: 'REJECTED', label: '반려됨' },
]

const STATUS_LABELS: Record<TopicKeywordProposalStatus, string> = {
  PENDING: '검토 대기',
  APPROVED: '승인됨',
  REJECTED: '반려됨',
}

const BUCKET_LABELS: Record<TopicKeywordBucket, string> = {
  REQUIRED: '모두 포함',
  OPTIONAL: '하나 이상 포함',
  EXCLUDED: '제외',
}

const ACTION_LABELS: Record<TopicKeywordChangeAction, string> = {
  ADD: '추가',
  REMOVE: '제거',
}

const PREVIEW_GROUPS = [
  { action: 'ADD', excluded: false, label: '추천 키워드', tone: 'add' },
  { action: 'ADD', excluded: true, label: '제외할 키워드', tone: 'remove' },
  { action: 'REMOVE', excluded: false, label: '제거 제안', tone: 'remove' },
  { action: 'REMOVE', excluded: true, label: '제외 해제', tone: 'add' },
] as const

function emptyMessage(filter: TopicKeywordProposalFilter) {
  if (filter === 'PENDING') return '검토 대기 중인 키워드 제안이 없습니다.'
  if (filter === 'APPROVED') return '승인된 키워드 제안이 없습니다.'
  if (filter === 'REJECTED') return '반려된 키워드 제안이 없습니다.'
  return '표시할 키워드 제안이 없습니다.'
}

export function TopicKeywordProposalPanel() {
  const [filter, setFilter] = useState<TopicKeywordProposalFilter>('PENDING')
  const [actingIds, setActingIds] = useState<Set<number>>(() => new Set())
  const [selected, setSelected] = useState<TopicKeywordProposal | null>(null)
  const [reviewErrors, setReviewErrors] = useState<Map<number, unknown>>(() => new Map())
  const toolbar = useRef<HTMLDivElement>(null)
  const completedReviewIds = useRef(new Set<number>())
  const proposals = useTopicKeywordProposals(filter)
  const approve = useApproveTopicKeywordProposal()
  const reject = useRejectTopicKeywordProposal()

  function resetFeedback() {
    setReviewErrors(new Map())
    approve.reset()
    reject.reset()
  }

  function changeFilter(next: TopicKeywordProposalFilter) {
    resetFeedback()
    setActingIds(new Set())
    setSelected(null)
    setFilter(next)
  }

  async function review(proposalId: number, action: 'approve' | 'reject') {
    if (actingIds.has(proposalId)) return

    resetFeedback()
    setActingIds((current) => new Set(current).add(proposalId))
    const mutation = action === 'approve' ? approve : reject
    try {
      await mutation.mutateAsync(proposalId)
      completedReviewIds.current.add(proposalId)
      setSelected(current => current?.id === proposalId ? null : current)
    } catch (error) {
      setReviewErrors(current => new Map(current).set(proposalId, error))
    } finally {
      setActingIds((current) => {
        const next = new Set(current)
        next.delete(proposalId)
        return next
      })
    }
  }

  if (proposals.isPending) {
    return <KeywordProposalsSkeleton />
  }

  if (!proposals.data) {
    const reason = proposals.error instanceof ApiError
      ? proposals.error.message
      : '키워드 제안을 불러오지 못했습니다.'
    return <p className="error">{reason}</p>
  }

  const actionPending = actingIds.size > 0
  const mutationError = reviewErrors.values().next().value
  const selectedProposal = selected && (proposals.data.content.find(proposal => proposal.id === selected.id) ?? selected)

  return (
    <>
      <div ref={toolbar} className="proposal-toolbar" tabIndex={-1}>
        <Segmented
          label="제안 상태"
          value={filter}
          options={FILTER_OPTIONS}
          onSelect={changeFilter}
          disabled={actionPending}
        />
      </div>

      {proposals.data.content.length === 0 ? (
        <p className="empty-block">{emptyMessage(filter)}</p>
      ) : (
        <div className="proposal-stack">
          {proposals.data.content.map((proposal) => (
            <ProposalCard
              key={proposal.id}
              proposal={proposal}
              isActing={actingIds.has(proposal.id)}
              onOpen={() => { resetFeedback(); completedReviewIds.current.delete(proposal.id); setSelected(proposal) }}
              onApprove={() => review(proposal.id, 'approve')}
              onReject={() => review(proposal.id, 'reject')}
            />
          ))}
        </div>
      )}

      {!selectedProposal && <MutationStatus error={mutationError} success={null} />}
      {selectedProposal && createPortal(
        <ProposalDetailDialog proposal={selectedProposal} isActing={actingIds.has(selectedProposal.id)}
          error={reviewErrors.get(selectedProposal.id)} fallbackFocus={toolbar}
          completedReviewIds={completedReviewIds}
          onDismiss={() => setSelected(null)}
          onApprove={() => review(selectedProposal.id, 'approve')}
          onReject={() => review(selectedProposal.id, 'reject')} />,
        document.body,
      )}
    </>
  )
}

function ProposalCard({
  proposal,
  isActing,
  onOpen,
  onApprove,
  onReject,
}: {
  proposal: TopicKeywordProposal
  isActing: boolean
  onOpen: () => void
  onApprove: () => void
  onReject: () => void
}) {
  const previewGroups = PREVIEW_GROUPS.map(group => ({
    ...group,
    keywords: [...new Set(proposal.changes.filter(change => change.action === group.action
      && (change.bucket === 'EXCLUDED') === group.excluded).map(change => change.keyword))],
  })).filter(group => group.keywords.length > 0)
  const recommended = previewGroups.find(group => group.action === 'ADD' && !group.excluded)
  const secondaryKeywords = [...new Set(previewGroups
    .filter(group => group !== recommended)
    .flatMap(group => group.keywords))]

  return (
    <article className="proposal-card proposal-review-card">
      <button type="button" className="proposal-card-toggle" aria-haspopup="dialog"
        aria-label={`${proposal.topicName} 변경 상세 보기`} onClick={onOpen} />
      <div className="proposal-header">
        <div className="proposal-title-row">
          <div className="proposal-title-group">
            <h3 title={proposal.topicName}>{proposal.topicName}</h3>
            <span className={`status-pill proposal-status proposal-status-${proposal.status.toLowerCase()}`}>
              {STATUS_LABELS[proposal.status]}
            </span>
          </div>
        </div>
        <div className="proposal-change-preview">
          {recommended && (
            <div className="proposal-preview-row proposal-recommended-row">
              <span className="proposal-preview-label">{recommended.label}</span>
              <div className="proposal-preview-keywords">
                {recommended.keywords.map(keyword => <span className="proposal-change-chip proposal-change-add" key={keyword}>{keyword}</span>)}
              </div>
            </div>
          )}
          {secondaryKeywords.length > 0 && <div className="proposal-secondary-group">
            <span className="proposal-preview-label">제외 키워드</span>
            <OverflowKeywords keywords={secondaryKeywords} tone="remove" />
          </div>}
          {previewGroups.length === 0 && <p className="muted proposal-empty">변경 항목이 없습니다.</p>}
        </div>
      </div>

      <div className="proposal-card-footer">
        <ProposalActions proposal={proposal} isActing={isActing} onApprove={onApprove} onReject={onReject} />
        <span className="proposal-detail-label" aria-hidden="true">
          <span>변경 {proposal.changes.length}개 · 상세</span>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="m9 5 7 7-7 7" /></svg>
        </span>
      </div>
    </article>
  )
}

function OverflowKeywords({ keywords, tone }: { keywords: string[]; tone: 'add' | 'remove' }) {
  const container = useRef<HTMLDivElement>(null)
  const measurement = useRef<HTMLDivElement>(null)
  const countMeasurement = useRef<HTMLSpanElement>(null)
  const [visibleCount, setVisibleCount] = useState(keywords.length)
  const keywordKey = JSON.stringify(keywords)
  const keywordCount = keywords.length

  useLayoutEffect(() => {
    const element = container.current
    const measure = measurement.current
    const countElement = countMeasurement.current
    if (!element || !measure || !countElement) return

    function updateVisibleCount() {
      if (!element || !measure || !countElement) return
      const widths = Array.from(measure.children, child => child.getBoundingClientRect().width)
      const gap = Number.parseFloat(getComputedStyle(measure).columnGap) || 0
      const available = element.clientWidth
      const fullWidth = widths.reduce((sum, width) => sum + width, 0) + Math.max(0, widths.length - 1) * gap
      if (fullWidth <= available) {
        setVisibleCount(keywordCount)
        return
      }
      const contentWidth = available - countElement.getBoundingClientRect().width - gap
      let occupied = 0
      let count = 0
      for (const width of widths) {
        const nextWidth = occupied + (count ? gap : 0) + width
        if (nextWidth > contentWidth) break
        occupied = nextWidth
        count += 1
      }
      setVisibleCount(count)
    }

    updateVisibleCount()
    const observer = new ResizeObserver(updateVisibleCount)
    observer.observe(element)
    observer.observe(measure)
    return () => observer.disconnect()
  }, [keywordKey, keywordCount])

  const hiddenCount = Math.max(0, keywords.length - visibleCount)
  return <div ref={container} className="proposal-overflow-keywords">
    <div className="proposal-overflow-visible">
      {keywords.slice(0, visibleCount).map(keyword => <span className={`proposal-change-chip proposal-change-${tone}`} key={keyword} title={keyword}>{keyword}</span>)}
      {hiddenCount > 0 && <span className="proposal-preview-more" title={`${hiddenCount}개 키워드는 상세에서 확인`}>+{hiddenCount}</span>}
    </div>
    <div ref={measurement} className="proposal-overflow-measure" aria-hidden="true">
      {keywords.map(keyword => <span className={`proposal-change-chip proposal-change-${tone}`} key={keyword}>{keyword}</span>)}
    </div>
    <span ref={countMeasurement} className="proposal-overflow-count-measure proposal-preview-more" aria-hidden="true">+{keywords.length}</span>
  </div>
}

function ProposalDetailDialog({ proposal, isActing, error, fallbackFocus, completedReviewIds, onDismiss, onApprove, onReject }: {
  proposal: TopicKeywordProposal
  isActing: boolean
  error: unknown
  fallbackFocus: RefObject<HTMLDivElement | null>
  completedReviewIds: RefObject<Set<number>>
  onDismiss: () => void
  onApprove: () => void
  onReject: () => void
}) {
  const id = useId()
  const dialog = useRef<HTMLDialogElement>(null)
  const closeButton = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    const element = dialog.current
    const previousFocus = document.activeElement
    const fallbackElement = fallbackFocus.current
    const reviewedIds = completedReviewIds.current
    element?.showModal()
    closeButton.current?.focus()
    document.body.classList.add('modal-open')
    return () => {
      element?.close()
      document.body.classList.remove('modal-open')
      if (reviewedIds.has(proposal.id)) fallbackElement?.focus()
      else if (previousFocus instanceof HTMLElement && previousFocus.isConnected) previousFocus.focus()
      else fallbackElement?.focus()
    }
  }, [fallbackFocus, completedReviewIds, proposal.id])

  return <dialog ref={dialog} className="recipient-selection-dialog proposal-detail-dialog" aria-labelledby={`${id}-title`}
    onCancel={event => { event.preventDefault(); onDismiss() }}
    onClick={event => {
      if (event.target !== event.currentTarget) return
      const bounds = event.currentTarget.getBoundingClientRect()
      if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) onDismiss()
    }}>
    <header className="recipient-selection-heading">
      <h2 id={`${id}-title`}>키워드 제안 상세</h2>
      <button ref={closeButton} type="button" className="text-button recipient-selection-close" aria-label="키워드 제안 상세 닫기" onClick={onDismiss}>×</button>
    </header>
    <div className="proposal-detail-body">
      <div className="proposal-detail-topic">
        <h3>{proposal.topicName}</h3>
        <span className={`status-pill proposal-status proposal-status-${proposal.status.toLowerCase()}`}>{STATUS_LABELS[proposal.status]}</span>
      </div>
      <div className="proposal-grid">
        <section className="proposal-section">
          <h4>현재 키워드</h4>
          <KeywordGroup label="모두 포함" keywords={proposal.currentKeywords.requiredKeywords} />
          <KeywordGroup label="하나 이상 포함" keywords={proposal.currentKeywords.optionalKeywords} />
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
                  <div className="proposal-change-title">
                    <span className={`proposal-change-kind proposal-change-${change.action.toLowerCase()}`}>{BUCKET_LABELS[change.bucket]} · {ACTION_LABELS[change.action]}</span>
                    <strong>{change.keyword}</strong>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>
    </div>
    <footer className="proposal-detail-footer">
      <MutationStatus error={error} success={null} />
      <ProposalActions proposal={proposal} isActing={isActing} onApprove={onApprove} onReject={onReject} />
    </footer>
  </dialog>
}

function ProposalActions({ proposal, isActing, onApprove, onReject }: {
  proposal: TopicKeywordProposal
  isActing: boolean
  onApprove: () => void
  onReject: () => void
}) {
  return <div className="proposal-actions">
    {proposal.status !== 'APPROVED' && <button type="button" disabled={isActing} onClick={onApprove}>{isActing ? '처리 중…' : '승인'}</button>}
    {proposal.status !== 'REJECTED' && <button type="button" className="secondary-button proposal-reject-button" disabled={isActing} onClick={onReject}>{isActing ? '처리 중…' : '반려'}</button>}
  </div>
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
