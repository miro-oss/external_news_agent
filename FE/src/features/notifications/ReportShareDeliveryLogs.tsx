import { useState } from 'react'
import { useDeliveryLogs } from '../../api/queries'

const LOG_STATUS_LABELS: Record<string, string> = {
  SENT: '전달됨',
  FAILED: '전달 실패',
  SKIPPED: '전달 제외',
}

export function ReportShareDeliveryLogs({ reportId, deliveryBatchId }: { reportId: number; deliveryBatchId: string }) {
  const [page, setPage] = useState(0)
  const logs = useDeliveryLogs({ reportId: String(reportId), deliveryBatchId, page })
  return <section className="report-share-result" aria-label="공유 발송 결과">
    <div className="report-share-result-heading">
      <strong>수신자별 발송 결과</strong>
      <button type="button" className="text-button" disabled={logs.isFetching} onClick={() => void logs.refetch()}>결과 새로고침</button>
    </div>
    {logs.isPending && <p className="muted" role="status">발송 결과를 확인하고 있습니다.</p>}
    {logs.isError && <p className="error" role="alert">발송 결과를 불러오지 못했습니다. 결과 새로고침을 눌러 다시 확인해 주세요.</p>}
    {logs.data && <>
      {logs.data.content.length ? <ul>{logs.data.content.map((log) => <li key={log.id}>
        <strong>{log.recipientName} · {log.channelType === 'EMAIL' ? '이메일' : '텔레그램'} · {LOG_STATUS_LABELS[log.status] ?? log.status}</strong>
        {log.errorMessage && <p>{log.errorMessage}</p>}
      </li>)}</ul> : <p className="muted">저장된 발송 결과가 없습니다. 잠시 후 결과를 새로고침해 주세요.</p>}
      {logs.data.totalPages > 1 && <div className="pagination" aria-label="공유 발송 결과 페이지 이동">
        <button className="secondary-button" type="button" disabled={!page || logs.isFetching} onClick={() => setPage(page - 1)}>이전</button>
        <span>{page + 1} / {logs.data.totalPages}</span>
        <button className="secondary-button" type="button" disabled={!logs.data.hasNext || logs.isFetching} onClick={() => setPage(page + 1)}>다음</button>
      </div>}
    </>}
  </section>
}
