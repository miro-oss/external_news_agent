import { useState } from 'react'
import './App.css'
import { ArticlesPage } from './features/articles/ArticlesPage'
import { ReportsPage } from './features/reports/ReportsPage'
import { SettingsPage } from './features/settings/SettingsPage'

function App() {
  const [page, setPage] = useState<'articles' | 'reports' | 'settings'>('articles')

  return (
    <div className="app-shell">
      <nav className="app-nav" aria-label="주요 화면">
        <strong>News Signal Desk</strong>
        <div className="nav-links">
          <button
            type="button"
            className={page === 'articles' ? 'nav-link active' : 'nav-link'}
            aria-current={page === 'articles' ? 'page' : undefined}
            onClick={() => setPage('articles')}
          >
            분석 기사
          </button>
          <button
            type="button"
            className={page === 'reports' ? 'nav-link active' : 'nav-link'}
            aria-current={page === 'reports' ? 'page' : undefined}
            onClick={() => setPage('reports')}
          >
            리포트
          </button>
          <button
            type="button"
            className={page === 'settings' ? 'nav-link active' : 'nav-link'}
            aria-current={page === 'settings' ? 'page' : undefined}
            onClick={() => setPage('settings')}
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
