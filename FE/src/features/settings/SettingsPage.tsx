import { useState } from 'react'
import { CollapsibleSection } from '../../components/CollapsibleSection'
import { useTopicKeywordProposals, useTopics } from '../../api/queries'
import { TopicTable } from './TopicTable'
import { SourceForm } from './SourceForm'
import { TopicForm } from './TopicForm'
import { LlmControlPanel } from './LlmControlPanel'
import { CollectionRunPanel } from './CollectionRunPanel'
import { TopicKeywordProposalPanel } from './TopicKeywordProposalPanel'
import './settings-refinement.css'

type PanelKey = 'llm' | 'source' | 'topic' | 'keywordProposals' | 'topics'

/** 수집 실행, 주제 등록, 제안 검토와 주제 관리를 기존 접이식 카드로 구성한다. */
export function SettingsPage() {
  const topics = useTopics(true)
  const pendingProposals = useTopicKeywordProposals('PENDING')
  const [open, setOpen] = useState<Record<PanelKey, boolean>>({
    llm: false,
    source: false,
    topic: false,
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

        <div className="settings-side-stack">
          <CollapsibleSection
            id="topic"
            title="수집 주제 등록"
            description="무엇을 모을지 정하면 활성 수집 소스가 자동으로 연결됩니다."
            open={open.topic}
            onToggle={() => toggle('topic')}
          >
            <TopicForm />
          </CollapsibleSection>

        </div>
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

      {/*
        소스와 플랜은 한 번 정해 두면 오래 가는 값이라 매일 지나칠 자리에 있을 이유가 없다.
        자주 하는 일(실행 · 주제 · 제안 검토 · 주제 확인)을 위로 모으고 아래에 둔다.
      */}
      <CollapsibleSection
        id="source"
        title="RSS 피드 등록"
        description="검색 provider는 기본 제공됩니다. 여기서는 추가 RSS 주소를 등록합니다."
        open={open.source}
        onToggle={() => toggle('source')}
      >
        <SourceForm />
      </CollapsibleSection>

      <CollapsibleSection
        id="llm"
        title="LLM 플랜과 사용량"
        description="기본 플랜과 사용량, 보고서 예약분을 확인합니다."
        open={open.llm}
        onToggle={() => toggle('llm')}
      >
        <LlmControlPanel />
      </CollapsibleSection>
    </main>
  )
}
