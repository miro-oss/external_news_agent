import { useLlmUsage } from '../../api/queries'
import { LlmUsageSummarySkeleton } from './SettingsSkeletons'

export function LlmUsageSummary() {
  const usage = useLlmUsage()

  if (usage.isPending) return <LlmUsageSummarySkeleton />
  if (!usage.data) return <div className="run-usage-summary"><span>오늘 OpenAI 사용량</span><span className="run-usage-unavailable">사용량 확인 불가</span></div>

  const { dailyCallsUsed, dailyCallsLimit, dailyEstimatedCostUsd } = usage.data.free
  const estimatedCost = Number.isFinite(dailyEstimatedCostUsd) && dailyEstimatedCostUsd >= 0
    ? `$${dailyEstimatedCostUsd.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 6 })}`
    : '확인 불가'
  return (
    <div className="run-usage-details">
      <div className="run-usage-summary" aria-label={`오늘 OpenAI 사용량 ${dailyCallsUsed.toLocaleString()}회, 일일 한도 ${dailyCallsLimit.toLocaleString()}회`}>
        <span>오늘 OpenAI 사용량</span>
        <div><strong>{dailyCallsUsed.toLocaleString()}</strong><span> / {dailyCallsLimit.toLocaleString()}회</span></div>
      </div>
      <div className="run-usage-summary">
        <span>오늘 예상 비용 (USD)</span>
        <strong>{estimatedCost}</strong>
      </div>
    </div>
  )
}
