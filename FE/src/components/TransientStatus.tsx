import { useEffect, useState } from 'react'
import { FEEDBACK_DURATION_MS } from '../lib/feedback'

type Props = {
  message: string | null
  as?: 'p' | 'small'
  className?: string
  suppressed?: boolean
}

/** 작업 결과는 유지하고, 완료 안내만 잠시 표시한다. 새 안내에는 새 타이머를 적용한다. */
export function TransientStatus({ message, ...props }: Props) {
  return message ? <TimedStatus key={message} message={message} {...props} /> : null
}

function TimedStatus({ message, as: Tag = 'p', className, suppressed = false }: Props) {
  const [visible, setVisible] = useState(true)

  useEffect(() => {
    const timer = window.setTimeout(() => setVisible(false), FEEDBACK_DURATION_MS)
    return () => window.clearTimeout(timer)
  }, [])

  return visible && !suppressed ? <Tag className={className} role="status">{message}</Tag> : null
}
