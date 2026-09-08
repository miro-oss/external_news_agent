import { type FormEvent, useState } from 'react'
import { useUpdateNotificationRecipientEmail } from '../../api/queries'
import type { NotificationRecipient } from '../../api/types'
import { recipientEmail } from '../../lib/recipientEmail'
import { MutationStatus } from '../settings/MutationStatus'

export function RecipientEmailForm({ recipient, emailChannelId }: {
  recipient: NotificationRecipient
  emailChannelId?: number
}) {
  const update = useUpdateNotificationRecipientEmail()
  const savedEmail = recipientEmail(recipient)
  const [email, setEmail] = useState(savedEmail)
  const changed = email.trim() !== savedEmail || email.trim() !== (recipient.email ?? '')
    || !recipient.destinations.some((destination) => destination.channelType === 'EMAIL')
  const saved = update.isSuccess && (update.data.profileSynced || recipient.email === update.data.email)

  function submit(event: FormEvent) {
    event.preventDefault()
    if (emailChannelId === undefined || !email.trim()) return
    update.mutate({ recipientId: recipient.id, email: email.trim(), emailChannelId }, {
      onSuccess: (result) => setEmail(result.email),
    })
  }

  return <form className="recipient-email-form" onSubmit={submit}>
    <label htmlFor={`recipient-email-${recipient.id}`}>이메일</label>
    <div className="recipient-email-fields">
      <input id={`recipient-email-${recipient.id}`} aria-label={`${recipient.name} 이메일`} type="email" required
        autoComplete="email" value={email} placeholder="user@example.com" disabled={update.isPending}
        onChange={(event) => { setEmail(event.target.value); update.reset() }} />
      <button type="submit" aria-label={`${recipient.name} 이메일 저장`}
        disabled={update.isPending || emailChannelId === undefined || !email.trim() || !changed}>
        {update.isPending ? '저장 중…' : '저장'}
      </button>
    </div>
    {emailChannelId === undefined && <p className="muted">이메일 수신 설정을 불러올 수 없습니다.</p>}
    <MutationStatus error={update.error}
      success={saved ? '이메일을 저장했습니다.' : null}
      warning={update.isSuccess && !saved ? '수신 주소는 변경됐지만 이메일 정보 저장을 마치지 못했습니다. 다시 저장해 주세요.' : null} />
  </form>
}
