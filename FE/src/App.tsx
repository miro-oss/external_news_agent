import { useCallback, useEffect, useState } from 'react'
import './App.css'
import { ArticlesPage } from './features/articles/ArticlesPage'
import { ReportsPage } from './features/reports/ReportsPage'
import { SettingsPage } from './features/settings/SettingsPage'

const PAGES = ['articles', 'reports', 'settings'] as const

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

function App() {
  const { page, go } = usePageRoute()

  return (
    <div className="app-shell">
      <nav className="app-nav" aria-label="주요 화면">
        {/* 로고는 홈으로 가는 길이다. 어디서든 분석 기사로 돌아올 수 있게 누를 수 있게 둔다. */}
        <button type="button" className="app-logo" onClick={() => go('articles')}>
          <strong>News Signal Desk</strong>
        </button>
        <div className="nav-links">
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
      {page === 'settings' && <SettingsPage />}
    </div>
  )
}

export default App
