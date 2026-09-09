import { useState } from 'react'
import { CollapsibleSection } from '../../components/CollapsibleSection'
import { useTopicKeywordProposals, useTopics } from '../../api/queries'
import { TopicTable } from './TopicTable'
import { SourceRegistration } from './SourceRegistration'
import { TopicForm } from './TopicForm'
import { CollectionRunPanel } from './CollectionRunPanel'
import { TopicKeywordProposalPanel } from './TopicKeywordProposalPanel'
import './settings-refinement.css'

type PanelKey = 'keywordProposals' | 'topics'

/** 수집 실행, 주제 등록, 제안 검토와 주제 관리를 기존 접이식 카드로 구성한다. */
export function SettingsPage() {
  const topics = useTopics(true)
  const pendingProposals = useTopicKeywordProposals('PENDING')
  const [open, setOpen] = useState<Record<PanelKey, boolean>>({
    keywordProposals: false,
    topics: true,
  })

  function toggle(key: PanelKey) {
    setOpen((current) => ({ ...current, [key]: !current[key] }))
  }

  return (
    <main className="settings-page">
      <header className="page-header">
        <div>
          <h1>수집 설정</h1>
          <p className="muted">수집할 주제와 소스를 등록하고, 내가 등록한 주제를 확인합니다.</p>
        </div>
      </header>

      <div className="settings-top-row">
        <CollectionRunPanel />

        <section id="topic" className="topic-registration-card" aria-labelledby="topic-registration-title" tabIndex={-1}>
          <header className="topic-registration-heading">
            <h2 id="topic-registration-title">수집 주제 등록</h2>
            <p className="muted">무엇을 모을지 정하면 수집 소스가 자동으로 연결됩니다.</p>
          </header>
          <TopicForm />
        </section>
      </div>

      <CollapsibleSection
        id="keyword-proposals"
        title="키워드 제안 검토"
        description="자동 수집에서 찾은 키워드를 검토합니다. 승인하면 다음 수집부터 반영됩니다."
        count={pendingProposals.data?.totalElements}
        open={open.keywordProposals}
        onToggle={() => toggle('keywordProposals')}
      >
        <TopicKeywordProposalPanel />
      </CollapsibleSection>

      <CollapsibleSection
        id="topics"
        title="등록된 수집 주제"
        description="수집 중인 주제를 최근 등록한 순서로 보여줍니다."
        count={topics.data?.totalElements}
        open={open.topics}
        onToggle={() => toggle('topics')}
      >
        <TopicTable />
      </CollapsibleSection>

      <SourceRegistration />
    </main>
  )
}
