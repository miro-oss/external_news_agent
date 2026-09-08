import { useEffect, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useDisconnectTelegram, useTelegramConnection, useTelegramLink } from '../../api/notificationConnections'
import { MutationStatus } from '../settings/MutationStatus'

export function TelegramConnectionCard({ recipientId, recipientName }: { recipientId: number; recipientName: string }) {
  const connection = useTelegramConnection(recipientId)
  const link = useTelegramLink(recipientId)
  const disconnect = useDisconnectTelegram(recipientId)
  const client = useQueryClient()
  const [copied, setCopied] = useState(false)
  const connected = connection.data?.status === 'CONNECTED'
  const waiting = connection.data?.status === 'WAITING'
  useEffect(() => {
    if (connected) void client.invalidateQueries({ queryKey: ['notifications', 'recipients'] })
  }, [connected, client])
  return <div className="telegram-connection" aria-label={`${recipientName} 텔레그램 연결`}>
    <span className="connection-label" data-connected={connected}>{connected ? '텔레그램 연결됨' : waiting ? 'Telegram에서 Start를 눌러 주세요' : connection.data?.status === 'EXPIRED' ? '연결 링크 만료' : '텔레그램 미연결'}</span>
    {connected ? <button type="button" className="text-button" disabled={disconnect.isPending} onClick={() => disconnect.mutate()}>연결 해제</button>
      : <button type="button" className="secondary-button" disabled={link.isPending || connection.isPending} onClick={() => { setCopied(false); link.mutate() }}>{link.isPending ? '링크 준비 중…' : waiting ? '연결 링크 다시 만들기' : '텔레그램 연결'}</button>}
    {!connected && waiting && link.data && <div className="telegram-link-actions">
      <p className="muted">이 링크는 {recipientName}님의 수신 주소를 연결합니다. 링크를 열고 Start를 눌러 주세요. 10분 뒤 만료됩니다.</p>
      <a className="secondary-button" href={link.data.url} target="_blank" rel="noreferrer">Telegram 열기</a>
      <button type="button" className="text-button" onClick={() => { void navigator.clipboard.writeText(link.data!.url).then(() => setCopied(true)).catch(() => setCopied(false)) }}>{copied ? '복사됨' : '연결 링크 복사'}</button>
      <button type="button" className="text-button" onClick={() => disconnect.mutate()}>취소</button>
    </div>}
    <MutationStatus error={connection.error ?? link.error ?? disconnect.error} success={null} />
  </div>
}
