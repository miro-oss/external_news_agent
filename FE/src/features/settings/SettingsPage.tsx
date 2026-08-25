import { useState } from 'react'
import { CollapsibleSection } from '../../components/CollapsibleSection'
import { useCombinations } from '../../api/queries'
import { CombinationTable } from './CombinationTable'
import { SourceForm } from './SourceForm'
import { TopicForm } from './TopicForm'
import { LlmControlPanel } from './LlmControlPanel'

type PanelKey = 'source' | 'topic' | 'combinations'

/**
 * M2 설정 화면. 영상 1~3의 흐름을 재현한다 — 소스를 등록하고, 주제를 만들며 그 소스를 연결하면,
 * 아래 테이블에 조합이 나타난다.
 *
 * <p>조회와 등록만 있다. 수정·삭제·활성 토글은 API가 있지만 이번 범위가 아니다.
 *
 * <p>등록 폼이 조합 표 아래에 있었다. 조합이 늘수록 표가 길어지고 정작 사용자가 하려는 등록은
 * 화면 밖으로 밀려나, 등록을 많이 할수록 등록이 어려워졌다. 폼을 위로 올리고 세 영역 모두
 * 접이식으로 바꿔, 길이를 결정하는 쪽이 표가 아니라 사용자가 되게 한다.
 */
export function SettingsPage() {
  const combinations = useCombinations()
  const [open, setOpen] = useState<Record<PanelKey, boolean>>({
    source: false,
    topic: false,
    combinations: true,
  })

  function toggle(key: PanelKey) {
    setOpen((current) => ({ ...current, [key]: !current[key] }))
  }

  return (
    <main>
      <header className="page-header">
        <div>
          <h1>수집 설정</h1>
          <p className="muted">수집할 주제와 소스를 등록하고, 등록된 조합을 확인합니다.</p>
        </div>
      </header>

      <section>
        <LlmControlPanel />
      </section>

      <CollapsibleSection
        id="source"
        title="수집 소스 등록"
        description="주제를 만들기 전에 소스가 먼저 있어야 연결할 수 있습니다."
        open={open.source}
        onToggle={() => toggle('source')}
      >
        <SourceForm />
      </CollapsibleSection>

      <CollapsibleSection
        id="topic"
        title="수집 주제 등록"
        description="주제를 만들면서 위에서 등록한 소스를 함께 연결합니다."
        open={open.topic}
        onToggle={() => toggle('topic')}
      >
        <TopicForm />
      </CollapsibleSection>

      <CollapsibleSection
        id="combinations"
        title="등록된 수집 조합"
        description="한 행이 (주제 × 소스) 조합 하나입니다."
        count={combinations.data?.combinationCount}
        open={open.combinations}
        onToggle={() => toggle('combinations')}
      >
        <CombinationTable />
      </CollapsibleSection>
    </main>
  )
}
