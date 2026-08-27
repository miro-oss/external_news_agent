import { useCallback, useEffect, useState } from 'react'
import './App.css'
import { ArticlesPage } from './features/articles/ArticlesPage'
import { ReportsPage } from './features/reports/ReportsPage'
import { NotificationsPage } from './features/notifications/NotificationsPage'
import { SettingsPage } from './features/settings/SettingsPage'

const PAGES = ['articles', 'reports', 'notifications', 'settings'] as const

type Page = (typeof PAGES)[number]

const DEFAULT_PAGE: Page = 'articles'

/**
 * 주소의 해시에서 화면을 읽는다. 모르는 값이면 기본 화면으로 떨어뜨린다 — 사람이 주소를 손으로
 * 고칠 수 있고, 그때 빈 화면을 보여 주는 것보다 낫다.
 */
function pageFromHash(): Page {
  const value = window.location.hash.replace(/^#\/?/, '')
  return PAGES.includes(value as Page) ? (value as Page) : DEFAULT_PAGE
}

/**
 * 화면 전환을 주소의 해시에 적어 둔다.
 *
 * <p>화면을 useState로만 들고 있었다. 주소가 그대로라 새로 고치면 무조건 첫 화면으로 돌아갔다 —
 * 수집 설정에서 폼을 채우다 새로 고침 한 번이면 분석 기사로 튕겼다.
 *
 * <p>경로(/settings)가 아니라 해시(#/settings)를 쓴다. 경로로 하면 서버가 모르는 주소를
 * index.html로 되돌려 줘야 한다. Vite 개발 서버는 그렇게 해 주지만 지금 BE에는 그 설정이 없어서,
 * 나중에 BE가 정적 파일을 서빙하는 순간 지금 고치는 이 버그가 그대로 되살아난다. 해시는 서버에
 * 아예 전달되지 않으므로 어디서 서빙하든 같게 동작한다.
 *
 * <p>hashchange를 듣는 덕에 브라우저 뒤로 가기도 따라온다.
 */
function usePageRoute() {
  const [page, setPage] = useState<Page>(pageFromHash)

  useEffect(() => {
    function sync() {
      setPage(pageFromHash())
    }
    window.addEventListener('hashchange', sync)
    return () => window.removeEventListener('hashchange', sync)
  }, [])

  const go = useCallback((next: Page) => {
    // 해시를 바꾸면 hashchange가 상태를 따라오지만, 같은 화면을 다시 누르면 이벤트가 나지 않는다.
    // 상태를 같이 세팅해 두 경로가 항상 같은 곳에 도착하게 한다.
    window.location.hash = `#/${next}`
    setPage(next)
  }, [])

  return { page, go }
}

/**
 * 페이지가 조금이라도 내려갔는지만 알려 준다.
 *
 * <p>내비게이션은 반투명 유리판이라 아래 내용이 비쳐 지나간다. 맨 위에서는 내비게이션과 본문이
 * 이어진 한 장이라 경계선을 그을 자리가 없지만, 내용이 그 아래로 들어가기 시작하면 어디까지가
 * 떠 있는 판인지가 흐려진다. 그때만 선을 올려 두면 선이 장식이 아니라 "위에 더 있다"는 뜻이 된다.
 *
 * <p>스크롤은 손가락을 굴리는 동안 초당 수십 번 들어온다. 매번 setState를 하면 그때마다 화면
 * 전체가 다시 그려지므로, 넘었는지 여부만 boolean으로 바꾸고 값이 실제로 달라질 때만 상태를
 * 갱신한다. React는 같은 값으로 set하면 리렌더를 건너뛴다.
 *
 * <p>passive: true — 이 리스너가 스크롤을 막지 않는다고 미리 알려서, 브라우저가 리스너를 기다리지
 * 않고 바로 스크롤을 진행한다.
 */
function useScrolled(threshold = 8) {
  const [scrolled, setScrolled] = useState(() => window.scrollY > threshold)

  useEffect(() => {
    function sync() {
      setScrolled(window.scrollY > threshold)
    }
    // 뒤로 가기로 스크롤 위치가 복원된 채 들어올 수 있다. 처음 한 번은 직접 맞춘다.
    sync()
    window.addEventListener('scroll', sync, { passive: true })
    return () => window.removeEventListener('scroll', sync)
  }, [threshold])

  return scrolled
}

function App() {
  const { page, go } = usePageRoute()
  const scrolled = useScrolled()

  return (
    <div className="app-shell">
      <nav className="app-nav" aria-label="주요 화면" data-scrolled={scrolled}>
        {/* 로고는 홈으로 가는 길이다. 어디서든 분석 기사로 돌아올 수 있게 누를 수 있게 둔다. */}
        <button type="button" className="app-logo" onClick={() => go('articles')}>
          <strong>News Signal Desk</strong>
        </button>
        <div className="nav-links">
          <button
            type="button"
            className={page === 'notifications' ? 'nav-link active' : 'nav-link'}
            aria-current={page === 'notifications' ? 'page' : undefined}
            onClick={() => go('notifications')}
          >
            알림 관리
          </button>
          <button
            type="button"
            className={page === 'articles' ? 'nav-link active' : 'nav-link'}
            aria-current={page === 'articles' ? 'page' : undefined}
            onClick={() => go('articles')}
          >
            분석 기사
          </button>
          <button
            type="button"
            className={page === 'reports' ? 'nav-link active' : 'nav-link'}
            aria-current={page === 'reports' ? 'page' : undefined}
            onClick={() => go('reports')}
          >
            리포트
          </button>
          <button
            type="button"
            className={page === 'settings' ? 'nav-link active' : 'nav-link'}
            aria-current={page === 'settings' ? 'page' : undefined}
            onClick={() => go('settings')}
          >
            수집 설정
          </button>
        </div>
      </nav>
      {page === 'articles' && <ArticlesPage />}
      {page === 'reports' && <ReportsPage />}
      {page === 'notifications' && <NotificationsPage />}
      {page === 'settings' && <SettingsPage />}
    </div>
  )
}

export default App
