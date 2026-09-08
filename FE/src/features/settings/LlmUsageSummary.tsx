import { useLlmUsage } from '../../api/queries'
import { LlmUsageSummarySkeleton } from './SettingsSkeletons'

export function LlmUsageSummary() {
  const usage = useLlmUsage()

  if (usage.isPending) return <LlmUsageSummarySkeleton />
  if (!usage.data) return <div className="run-usage-summary"><span>오늘 무료 호출</span><span className="run-usage-unavailable">사용량 확인 불가</span></div>

  const { dailyCallsUsed, dailyCallsLimit } = usage.data.free
  return (
    <div className="run-usage-summary" aria-label={`오늘 무료 호출 ${dailyCallsUsed.toLocaleString()}회, 일일 한도 ${dailyCallsLimit.toLocaleString()}회`}>
      <span>오늘 무료 호출</span>
      <div><strong>{dailyCallsUsed.toLocaleString()}</strong><span> / {dailyCallsLimit.toLocaleString()}회</span></div>
    </div>
  )
}
