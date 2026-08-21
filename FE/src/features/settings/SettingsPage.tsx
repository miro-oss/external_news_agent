import { CombinationTable } from './CombinationTable'
import { SourceForm } from './SourceForm'
import { TopicForm } from './TopicForm'
import { LlmControlPanel } from './LlmControlPanel'

/**
 * M2 설정 화면. 영상 1~3의 흐름을 재현한다 — 소스를 등록하고, 주제를 만들며 그 소스를 연결하면,
 * 위 테이블에 조합이 나타난다.
 *
 * <p>조회와 등록만 있다. 수정·삭제·활성 토글은 API가 있지만 이번 범위가 아니다.
 */
export function SettingsPage() {
  return (
    <main>
      <header className="page-header">
        <h1>수집 설정</h1>
        <p className="muted">수집할 주제와 소스를 등록하고, 등록된 조합을 확인합니다.</p>
      </header>

      <section>
        <LlmControlPanel />
      </section>

      <section>
        <h2>등록된 수집 조합</h2>
        <CombinationTable />
      </section>

      <div className="forms">
        <section>
          <h2>수집 소스 등록</h2>
          <p className="muted">주제를 만들기 전에 소스가 먼저 있어야 연결할 수 있습니다.</p>
          <SourceForm />
        </section>

        <section>
          <h2>수집 주제 등록</h2>
          <p className="muted">주제를 만들면서 위에서 등록한 소스를 함께 연결합니다.</p>
          <TopicForm />
        </section>
      </div>
    </main>
  )
}
