import { useState } from 'react'
import { CollapsibleSection } from '../../components/CollapsibleSection'
import { useCombinations } from '../../api/queries'
import { CombinationTable } from './CombinationTable'
import { SourceForm } from './SourceForm'
import { TopicForm } from './TopicForm'
import { LlmControlPanel } from './LlmControlPanel'
import { CollectionRunPanel } from './CollectionRunPanel'

type PanelKey = 'llm' | 'source' | 'topic' | 'combinations'

/**
 * M2 설정 화면. 영상 1~3의 흐름을 재현한다 — 소스를 등록하고, 주제를 만들며 그 소스를 연결하면,
 * 아래 테이블에 조합이 나타난다.
 *
 * <p>조회와 등록만 있다. 수정·삭제·활성 토글은 API가 있지만 이번 범위가 아니다.
 *
 * <p>등록 폼이 조합 표 아래에 있었다. 조합이 늘수록 표가 길어지고 정작 사용자가 하려는 등록은
 * 화면 밖으로 밀려나, 등록을 많이 할수록 등록이 어려워졌다. 폼을 위로 올리고 세 영역 모두
 * 접이식으로 바꿔, 길이를 결정하는 쪽이 표가 아니라 사용자가 되게 한다.
 *
 * <p>주제가 소스보다 위에 있다. 소스는 관리자가 한 번 깔아 두면 오래 가는 값이고, 주제는
 * 쓰는 사람이 계속 더한다. 의존 순서(소스가 있어야 연결한다)는 주제 폼 안에서 "등록된 소스가
 * 없습니다"로 이미 드러나므로, 화면 순서는 의존 순서 대신 손이 가는 빈도를 따른다.
 *
 * <p>실행과 주제 등록이 상단에 좌우로 나란히 있다. 이 둘이 여기서 제일 자주 하는 일인데 사이에
 * 사용량 패널이 끼어 있어서, 주제를 하나 만들고 바로 돌려 보려면 매번 그걸 지나 스크롤해야 했다.
 * 같은 줄에 두면 등록하고 실행하는 왕복이 한 화면 안에서 끝난다.
 */
export function SettingsPage() {
  const combinations = useCombinations()
  const [open, setOpen] = useState<Record<PanelKey, boolean>>({
    llm: false,
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

      {/*
        주제 등록은 접이식을 유지하고 기본은 접어 둔다. 폼이 세로로 길어서 펼친 채 두면 상단
        한 줄이 화면을 다 먹는다. 접혀 있으면 두 카드가 나란히 짧고, 펼치면 오른쪽 열만 자란다.
      */}
      <div className="settings-top-row">
        <CollectionRunPanel />

        <CollapsibleSection
          id="topic"
          title="수집 주제 등록"
          description="무엇을 모을지 정하고, 등록된 소스 중에서 골라 연결합니다."
          open={open.topic}
          onToggle={() => toggle('topic')}
        >
          <TopicForm />
        </CollapsibleSection>
      </div>

      <CollapsibleSection
        id="llm"
        title="LLM 플랜과 사용량"
        description="기본 플랜과 사용량, 보고서 예약분, 기본 독자 관점을 확인합니다."
        open={open.llm}
        onToggle={() => toggle('llm')}
      >
        <LlmControlPanel />
      </CollapsibleSection>

      <CollapsibleSection
        id="source"
        title="수집 소스 등록"
        description="기사를 가져올 곳입니다. 한 번 등록해 두면 여러 주제에서 함께 씁니다."
        open={open.source}
        onToggle={() => toggle('source')}
      >
        <SourceForm />
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
