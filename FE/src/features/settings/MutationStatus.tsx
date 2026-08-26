import { ApiError } from '../../api/client'

export function MutationStatus({ error, success }: { error: unknown; success: string | null }) {
  if (error) {
    let message = error instanceof ApiError ? `${error.message} (${error.code})` : '요청에 실패했습니다.'
    if (error instanceof ApiError && error.code === 'QUOTA429' && isQuotaDetails(error.details)) {
      message += ` · 일 잔량 ${error.details.dailyRemaining ?? '-'}, 월 잔량 ${error.details.monthlyRemaining ?? '-'}`
    }
    return <p className="error" role="alert">{message}</p>
  }
  return success ? <p className="success" role="status">{success}</p> : null
}

function isQuotaDetails(value: unknown): value is {
  dailyRemaining?: number
  monthlyRemaining?: number
} {
  return typeof value === 'object' && value !== null
}
