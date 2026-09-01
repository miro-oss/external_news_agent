import { useState } from 'react'
import { CollapsibleSection } from '../../components/CollapsibleSection'
import { useTopics } from '../../api/queries'
import { TopicTable } from './TopicTable'
import { SourceForm } from './SourceForm'
import { TopicForm } from './TopicForm'
import { LlmControlPanel } from './LlmControlPanel'
import { CollectionRunPanel } from './CollectionRunPanel'

type PanelKey = 'llm' | 'source' | 'topic' | 'topics'

/**
 * M2 설정 화면. 소스를 등록하고 주제를 만들면 활성 소스가 자동으로 연결되며, 아래 목록에는
 * 사용자가 직접 관리하는 주제만 한 번씩 나타난다.
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
 * <p>상단이 좌우 두 열이다. 왼쪽은 실행, 오른쪽은 실행하기 전에 준비하는 것들(주제·소스 등록,
 * 플랜 확인)이다. 실행과 주제 등록이 여기서 제일 자주 하는 일인데 둘 사이에 사용량 패널이 끼어
 * 있어서, 주제를 하나 만들고 바로 돌려 보려면 매번 그걸 지나 스크롤해야 했다. 같은 줄에 두면
 * 등록하고 실행하는 왕복이 한 화면 안에서 끝난다.
 *
 * <p>주제 목록만 두 열 아래 전체 폭을 쓴다. 검색어와 키워드 조건까지 한 줄에 보여 주므로 절반
 * 폭에서는 열이 서로를 밀어낸다.
 */
export function SettingsPage() {
  const topics = useTopics()
  const [open, setOpen] = useState<Record<PanelKey, boolean>>({
    llm: false,
    source: false,
    topic: false,
    topics: true,
  })

  function toggle(key: PanelKey) {
    setOpen((current) => ({ ...current, [key]: !current[key] }))
  }

  return (
    <main>
      <header className="page-header">
        <div>
          <h1>수집 설정</h1>
          <p className="muted">수집할 주제와 소스를 등록하고, 내가 등록한 주제를 확인합니다.</p>
        </div>
      </header>

      <div className="settings-top-row">
        <CollectionRunPanel />

        {/*
          오른쪽은 카드 하나가 아니라 준비 작업 묶음이다. 주제 등록만 두면 접혔을 때 84px짜리
          띠가 491px 실행 패널 옆에 남아 덜 그려진 화면처럼 보인다. 셋을 쌓으면 열이 채워지고,
          "왼쪽은 실행 · 오른쪽은 준비"라는 구분도 생긴다.

          셋 다 접이식이고 접힌 채로 시작한다. 주제 폼은 펼치면 1198px라, 펼친 것을 기본으로
          삼으면 이번엔 반대쪽이 700px 비었다. 길이를 정하는 쪽은 화면이 아니라 사용자여야 한다.
        */}
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
        </div>
      </div>

      <CollapsibleSection
        id="topics"
        title="등록된 수집 주제"
        description="내가 등록한 검색 주제와 조건을 주제별로 확인합니다."
        count={topics.data?.totalElements}
        open={open.topics}
        onToggle={() => toggle('topics')}
      >
        <TopicTable />
      </CollapsibleSection>
    </main>
  )
}
