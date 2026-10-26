import { ApiError } from '../../api/client'
import { TransientStatus } from '../../components/TransientStatus'

export function MutationStatus({ error, success, warning = null }: {
  error: unknown
  success: string | null
  warning?: string | null
}) {
  let message: string | null = null
  if (error) {
    message = error instanceof ApiError ? error.message : '요청에 실패했습니다.'
    if (error instanceof ApiError && error.code === 'QUOTA429' && isQuotaDetails(error.details)) {
      message += ` · 일 잔량 ${error.details.dailyRemaining ?? '-'}, 월 잔량 ${error.details.monthlyRemaining ?? '-'}`
    }
  }
  return <>
    {message ? <p className="error" role="alert">{message}</p>
      : warning ? <p className="warning-message" role="status">{warning}</p> : null}
    <TransientStatus className="success" message={success} suppressed={Boolean(error || warning)} />
  </>
}

function isQuotaDetails(value: unknown): value is {
  dailyRemaining?: number
  monthlyRemaining?: number
} {
  return typeof value === 'object' && value !== null
}
