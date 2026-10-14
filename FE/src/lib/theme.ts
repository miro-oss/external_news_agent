import { useSyncExternalStore } from 'react'

export type ThemePreference = 'light' | 'dark' | 'system'

interface ThemeStore {
  getPreference: () => ThemePreference
  setPreference: (preference: ThemePreference) => void
  subscribe: (listener: () => void) => () => void
}

declare global {
  interface Window {
    newsSignalTheme?: ThemeStore
  }
}

const serverPreference = (): ThemePreference => 'system'
const getPreference = () => window.newsSignalTheme?.getPreference() ?? serverPreference()
const subscribe = (listener: () => void) => window.newsSignalTheme?.subscribe(listener) ?? (() => {})
const setPreference = (preference: ThemePreference) => window.newsSignalTheme?.setPreference(preference)

export function useThemePreference() {
  const preference = useSyncExternalStore(subscribe, getPreference, serverPreference)
  return [preference, setPreference] as const
}
