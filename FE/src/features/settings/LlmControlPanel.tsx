import { useState } from 'react'
import {
  useAudienceSetting,
  useLlmPlan,
  useLlmUsage,
  useUpdateLlmPlan,
  useUpdateAudienceSetting,
} from '../../api/queries'
import {
  AUDIENCES,
  AUDIENCE_LABELS,
  type Audience,
  type LlmPlan,
  type PaidExhaustedAction,
} from '../../api/types'
import { MutationStatus } from './MutationStatus'

export function LlmControlPanel() {
  const planQuery = useLlmPlan()
  const usageQuery = useLlmUsage()
  const audienceQuery = useAudienceSetting()
  const updatePlan = useUpdateLlmPlan()
  const updateAudience = useUpdateAudienceSetting()
  const [saved, setSaved] = useState(false)
  const [audienceSaved, setAudienceSaved] = useState(false)

  if (planQuery.isPending || usageQuery.isPending || audienceQuery.isPending) {
    return <div className="llm-panel state-panel">LLM 설정과 사용량을 불러오는 중입니다.</div>
  }
  if (audienceQuery.error || !audienceQuery.data) {
    return (
      <div className="llm-panel state-panel error" role="alert">
        기본 관점 설정을 불러오지 못했습니다.
      </div>
    )
  }
  if (planQuery.error || usageQuery.error || !planQuery.data || !usageQuery.data) {
    return (
      <div className="llm-panel state-panel error" role="alert">
        LLM 설정을 불러오지 못했습니다.
      </div>
    )
  }

  const setting = planQuery.data
  const usage = usageQuery.data
  const audience = audienceQuery.data.audience

  function save(event: React.FormEvent) {
    event.preventDefault()
    setSaved(false)
    const values = new FormData(event.currentTarget as HTMLFormElement)
    updatePlan.mutate(
      {
        plan: values.get('plan') as LlmPlan,
        paidExhaustedAction: values.get('paidExhaustedAction') as PaidExhaustedAction,
      },
      { onSuccess: () => setSaved(true) },
    )
  }

  function saveAudience(event: React.FormEvent) {
    event.preventDefault()
    setAudienceSaved(false)
    const values = new FormData(event.currentTarget as HTMLFormElement)
    updateAudience.mutate(
      values.get('audience') as Audience,
      { onSuccess: () => setAudienceSaved(true) },
    )
  }

  return (
    <div className="llm-panel">
      <div className="llm-panel-heading">
        <span className="muted">현재 기본 플랜</span>
        <span className={`plan-badge plan-${setting.plan.toLowerCase()}`}>{setting.plan}</span>
      </div>

      <div className="usage-grid">
        <UsageCard
          label="FREE · 오늘 호출"
          used={usage.free.dailyCallsUsed}
          limit={usage.free.dailyCallsLimit}
          unit="회"
        />
        <UsageCard
          label="PAID · 오늘"
          used={usage.paid.dailyCreditsUsed}
          limit={usage.paid.dailyCreditsLimit}
          unit="크레딧"
          note={`분석 잔량 ${usage.paid.analysisCreditsRemaining} · 보고서 예약 ${usage.paid.reportReserve}`}
        />
        <UsageCard
          label="PAID · 이번 달"
          used={usage.paid.monthlyCreditsUsed}
          limit={usage.paid.monthlyCreditsLimit}
          unit="크레딧"
        />
      </div>

      <div className="llm-actions-grid">
        <form
          key={`${setting.plan}-${setting.paidExhaustedAction}`}
          className="llm-setting-form"
          onSubmit={save}
        >
          <fieldset className="field">
            <legend>기본 플랜</legend>
            <div className="plan-options">
              <PlanOption value="FREE" current={setting.plan} label="Gemini 무료" />
              <PlanOption value="PAID" current={setting.plan} label="Claude 유료" />
            </div>
          </fieldset>
          <div className="field">
            <label htmlFor="paid-exhausted-action">PAID 실행 중 소진 시</label>
            <select
              id="paid-exhausted-action"
              name="paidExhaustedAction"
              defaultValue={setting.paidExhaustedAction}
            >
              <option value="STUB">임시 응답으로 계속 — 기본</option>
              <option value="FALLBACK_FREE">FREE Gemini로 계속</option>
            </select>
          </div>
          <button type="submit" disabled={updatePlan.isPending}>
            {updatePlan.isPending ? '저장 중…' : '플랜 설정 저장'}
          </button>
          <MutationStatus
            error={updatePlan.error}
            success={saved ? 'LLM 플랜 설정을 저장했습니다.' : null}
          />
        </form>

        <form
          key={audience}
          className="audience-setting-form"
          onSubmit={saveAudience}
        >
          <div>
            <h3 className="card-title">내 기본 관점</h3>
            <p className="muted">기사 목록과 상세 화면에서 처음 선택할 독자 관점입니다.</p>
          </div>
          <div className="field">
            <label htmlFor="default-audience">기본 관점</label>
            <select id="default-audience" name="audience" defaultValue={audience}>
              {AUDIENCES.map((value) => (
                <option value={value} key={value}>{AUDIENCE_LABELS[value]}</option>
              ))}
            </select>
          </div>
          <button type="submit" disabled={updateAudience.isPending}>
            {updateAudience.isPending ? '저장 중…' : '기본 관점 저장'}
          </button>
          <MutationStatus
            error={updateAudience.error}
            success={audienceSaved ? '기본 관점을 저장했습니다.' : null}
          />
        </form>

      </div>
    </div>
  )
}

function PlanOption({
  value,
  current,
  label,
}: {
  value: LlmPlan
  current: LlmPlan
  label: string
}) {
  return (
    <label className="plan-option">
      <input
        type="radio"
        name="plan"
        value={value}
        defaultChecked={current === value}
      />
      <span>{label}</span>
      <small>{value === 'FREE' ? '비용 없이 호출량만 보호' : '월 3,000 credits 한도'}</small>
    </label>
  )
}

function UsageCard({
  label,
  used,
  limit,
  unit,
  note,
}: {
  label: string
  used: number
  limit: number
  unit: string
  note?: string
}) {
  const safeLimit = Math.max(limit, 1)
  return (
    <article className="usage-card">
      <span>{label}</span>
      <strong>{used.toLocaleString()} <small>/ {limit.toLocaleString()} {unit}</small></strong>
      <progress value={Math.min(used, safeLimit)} max={safeLimit} />
      {note && <em>{note}</em>}
    </article>
  )
}
