import { useThemePreference, type ThemePreference } from '../lib/theme'
import './theme-switcher.css'

export function ThemeSwitcher() {
  const [preference, setPreference] = useThemePreference()

  return <label className="theme-switcher">
    <svg aria-hidden="true" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
      {preference === 'dark' ? <path d="M20.5 13a8.5 8.5 0 0 1-9.5-9.5A8.5 8.5 0 1 0 20.5 13Z" />
        : preference === 'light' ? <><circle cx="12" cy="12" r="4" /><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5" /></>
          : <><rect x="3" y="4" width="18" height="13" rx="2" /><path d="M8 21h8m-4-4v4" /></>}
    </svg>
    <span className="sr-only">화면 테마</span>
    <select value={preference} onChange={(event) => setPreference(event.target.value as ThemePreference)}>
      <option value="system">시스템</option>
      <option value="light">라이트</option>
      <option value="dark">다크</option>
    </select>
  </label>
}
