import { useId, useMemo, useState } from 'react'
import { EMAIL_PREVIEW_SANDBOX, emailPreviewDocument } from './emailPreviewDocument'

export function EmailMessagePreview({ body }: { body: string }) {
  const [narrow, setNarrow] = useState(false)
  const frameId = useId()
  const source = useMemo(() => emailPreviewDocument(body), [body])
  return <div className="email-message-preview">
    <div className="email-preview-tools" role="group" aria-label="이메일 미리보기 너비">
      <button type="button" className="secondary-button" aria-pressed={!narrow} aria-controls={frameId}
        onClick={() => setNarrow(false)}>넓은 화면</button>
      <button type="button" className="secondary-button" aria-pressed={narrow} aria-controls={frameId}
        onClick={() => setNarrow(true)}>좁은 화면 · 360px</button>
    </div>
    <p className="muted">공용 본문 미리보기입니다. 받는 메일 앱에 따라 모양이 조금 다를 수 있습니다. 원문 링크는 새 창에서 열립니다.</p>
    <div className="email-preview-frame" data-narrow={narrow}>
      <iframe id={frameId} title="이메일 본문 미리보기" srcDoc={source}
        sandbox={EMAIL_PREVIEW_SANDBOX} referrerPolicy="no-referrer" />
    </div>
  </div>
}
