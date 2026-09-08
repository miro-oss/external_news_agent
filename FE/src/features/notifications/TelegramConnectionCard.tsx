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
  const [copyError, setCopyError] = useState(false)
  const connected = connection.data?.status === 'CONNECTED'
  const waiting = connection.data?.status === 'WAITING'
  useEffect(() => {
    if (connected) void client.invalidateQueries({ queryKey: ['notifications', 'recipients'] })
  }, [connected, client])

  async function copyLink() {
    if (!link.data) return
    setCopyError(false)
    try {
      await navigator.clipboard.writeText(link.data.url)
      setCopied(true)
    } catch {
      setCopied(false)
      setCopyError(true)
    }
  }

  return <div className="telegram-connection" aria-label={`${recipientName} 텔레그램 연결`}>
    <div className="telegram-connection-heading">
      <strong>텔레그램</strong>
      {connected && <span className="connection-label">연결됨</span>}
      {waiting && <span className="connection-label" data-waiting="true" role="status">연결 확인 중</span>}
    </div>
    {connection.isPending ? <p className="muted">연결 상태를 확인하는 중입니다.</p>
      : connection.isError ? <button type="button" className="text-button" disabled={connection.isFetching} onClick={() => { void connection.refetch() }}>연결 상태 다시 확인</button>
      : connected ? <div className="telegram-connected">
        <p className="muted">{recipientName}님의 텔레그램으로 보고서를 받을 수 있습니다.</p>
        <button type="button" className="text-button" disabled={disconnect.isPending} onClick={() => disconnect.mutate()}>연결 해제</button>
      </div>
      : <>
        <p className="muted">{waiting ? `${recipientName}님의 연결을 기다리고 있습니다. Telegram에서 Start를 누르면 자동으로 확인합니다.` : `연결 링크를 ${recipientName}님에게 전달해 주세요. 수신자가 Telegram에서 Start를 누르면 연결됩니다.`}</p>
        {connection.data?.status === 'EXPIRED' && <p className="muted">이전 링크가 만료됐습니다. 새 링크를 만들어 주세요.</p>}
        {!waiting && <button type="button" className="secondary-button" disabled={link.isPending || disconnect.isPending}
          onClick={() => { setCopied(false); setCopyError(false); link.mutate() }}>{link.isPending ? '링크 준비 중…' : '연결 링크 만들기'}</button>}
        {waiting && !link.data && <div className="telegram-link-actions">
          <p className="muted">앞서 만든 링크가 아직 유효합니다. Start를 눌렀다면 연결 상태를 다시 확인해 주세요.</p>
          <div className="telegram-link-buttons">
            <button type="button" className="secondary-button" disabled={connection.isFetching} onClick={() => { void connection.refetch() }}>{connection.isFetching ? '확인 중…' : '연결 상태 확인'}</button>
            <button type="button" className="text-button" disabled={link.isPending || disconnect.isPending} onClick={() => { setCopied(false); setCopyError(false); link.mutate() }}>{link.isPending ? '링크 준비 중…' : '링크 다시 만들기'}</button>
          </div>
        </div>}
      </>}
    {!connected && waiting && link.data && <div className="telegram-link-actions">
      <div className="telegram-link-buttons">
        <button type="button" className="secondary-button" onClick={() => { void copyLink() }}>{copied ? '복사됨' : '연결 링크 복사'}</button>
        <a className="text-button" href={link.data.url} target="_blank" rel="noreferrer">내 계정으로 연결</a>
        <button type="button" className="text-button" disabled={disconnect.isPending} onClick={() => disconnect.mutate()}>취소</button>
      </div>
      <p className="muted" role="status">{copyError ? '링크를 복사하지 못했습니다. 다시 시도해 주세요.' : copied ? `${recipientName}님에게 붙여넣기로 전달해 주세요. 링크는 10분 동안 유효합니다.` : '링크는 10분 동안 유효합니다. 본인이 수신자라면 내 계정으로 연결을 선택하세요.'}</p>
    </div>}
    <MutationStatus error={connection.error ?? link.error ?? disconnect.error} success={null} />
  </div>
}
