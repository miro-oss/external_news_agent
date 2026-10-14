import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { runInNewContext } from 'node:vm'

const themeSource = readFileSync(new URL('../public/theme.js', import.meta.url), 'utf8')
const storageKey = 'news-signal-desk.theme'

function createBrowser({ savedPreference, systemDark = false, storageFailure } = {}) {
  const values = new Map(savedPreference === undefined ? [] : [[storageKey, savedPreference]])
  const listeners = new Map()
  const mediaListeners = new Set()
  const documentElement = { dataset: {}, style: {} }
  const localStorage = {
    getItem(key) {
      if (storageFailure === 'read') throw new Error('Storage access denied')
      return values.get(key) ?? null
    },
    setItem(key, value) {
      if (storageFailure === 'write') throw new Error('Storage quota exceeded')
      values.set(key, String(value))
    },
    removeItem(key) {
      if (storageFailure === 'write') throw new Error('Storage access denied')
      values.delete(key)
    },
  }
  const media = {
    matches: systemDark,
    addEventListener(name, callback) {
      if (name === 'change') mediaListeners.add(callback)
    },
    removeEventListener(name, callback) {
      if (name === 'change') mediaListeners.delete(callback)
    },
  }
  const window = {
    document: { documentElement },
    matchMedia(query) {
      assert.equal(query, '(prefers-color-scheme: dark)')
      return media
    },
    addEventListener(name, callback) {
      const callbacks = listeners.get(name) ?? new Set()
      callbacks.add(callback)
      listeners.set(name, callbacks)
    },
    removeEventListener(name, callback) {
      listeners.get(name)?.delete(callback)
    },
  }
  Object.defineProperty(window, 'localStorage', {
    get() {
      if (storageFailure === 'unavailable') throw new Error('Storage is unavailable')
      return localStorage
    },
  })
  const context = { window, document: window.document, matchMedia: window.matchMedia }
  Object.defineProperty(context, 'localStorage', {
    get: () => window.localStorage,
  })
  runInNewContext(themeSource, context, { filename: 'theme.js' })

  return {
    theme: window.newsSignalTheme,
    documentElement,
    values,
    setSystemDark(matches) {
      media.matches = matches
      for (const listener of mediaListeners) listener({ matches })
    },
    receiveStorage(key, newValue, storageArea = localStorage) {
      if (storageArea === localStorage) {
        if (key === null) values.clear()
        else if (newValue === null) values.delete(key)
        else values.set(key, newValue)
      }
      for (const listener of listeners.get('storage') ?? []) {
        listener({ key, newValue, storageArea })
      }
    },
  }
}

function assertTheme(browser, preference, resolvedTheme) {
  assert.equal(browser.theme.getPreference(), preference)
  assert.equal(browser.documentElement.dataset.theme, resolvedTheme)
  assert.equal(browser.documentElement.style.colorScheme, resolvedTheme)
}

test('first load synchronously follows the system without writing a user preference', () => {
  for (const systemDark of [false, true]) {
    const browser = createBrowser({ systemDark })
    assertTheme(browser, 'system', systemDark ? 'dark' : 'light')
    assert.equal(browser.values.has(storageKey), false)
  }
})

test('saved light, dark, and system choices are restored before rendering', () => {
  for (const savedPreference of ['light', 'dark', 'system']) {
    for (const systemDark of [false, true]) {
      const browser = createBrowser({ savedPreference, systemDark })
      const resolvedTheme = savedPreference === 'system' ? (systemDark ? 'dark' : 'light') : savedPreference
      assertTheme(browser, savedPreference, resolvedTheme)
    }
  }
})

test('explicit choices persist across reloads and ignore subsequent system changes', () => {
  const browser = createBrowser({ systemDark: true })
  browser.theme.setPreference('light')
  assertTheme(browser, 'light', 'light')
  assert.equal(browser.values.get(storageKey), 'light')
  browser.setSystemDark(false)
  browser.setSystemDark(true)
  assertTheme(browser, 'light', 'light')
  assertTheme(createBrowser({ savedPreference: browser.values.get(storageKey), systemDark: true }), 'light', 'light')

  browser.theme.setPreference('dark')
  browser.setSystemDark(false)
  assertTheme(browser, 'dark', 'dark')
  assert.equal(browser.values.get(storageKey), 'dark')
  assertTheme(createBrowser({ savedPreference: browser.values.get(storageKey) }), 'dark', 'dark')
})

test('returning to system mode immediately resolves the current preference and follows later changes', () => {
  const browser = createBrowser({ savedPreference: 'dark' })
  browser.theme.setPreference('system')
  assertTheme(browser, 'system', 'light')
  browser.setSystemDark(true)
  assertTheme(browser, 'system', 'dark')
  browser.setSystemDark(false)
  assertTheme(browser, 'system', 'light')
  assertTheme(createBrowser({ savedPreference: browser.values.get(storageKey), systemDark: true }), 'system', 'dark')
})

test('changes, removal, and clearing in another tab update the theme and restore system behavior', () => {
  const browser = createBrowser({ systemDark: false })
  browser.receiveStorage(storageKey, 'dark')
  assertTheme(browser, 'dark', 'dark')
  browser.receiveStorage(storageKey, 'light')
  assertTheme(browser, 'light', 'light')
  browser.setSystemDark(true)
  browser.receiveStorage(storageKey, null)
  assertTheme(browser, 'system', 'dark')

  browser.receiveStorage(storageKey, 'light')
  browser.receiveStorage(null, null)
  assertTheme(browser, 'system', 'dark')
  browser.setSystemDark(false)
  assertTheme(browser, 'system', 'light')
})

test('unrelated local storage and session storage changes do not replace the selected theme', () => {
  const browser = createBrowser({ savedPreference: 'dark' })
  browser.receiveStorage('unrelated-setting', 'light')
  assertTheme(browser, 'dark', 'dark')
  const sessionStorage = {}
  browser.receiveStorage(storageKey, 'light', sessionStorage)
  browser.receiveStorage(null, null, sessionStorage)
  assertTheme(browser, 'dark', 'dark')
})

test('invalid stored values fall back to the system on startup and across tabs', () => {
  for (const savedPreference of ['', 'DARK', 'unknown', '{"theme":"dark"}']) {
    assertTheme(createBrowser({ savedPreference, systemDark: true }), 'system', 'dark')
  }
  const browser = createBrowser({ savedPreference: 'light', systemDark: true })
  browser.receiveStorage(storageKey, 'invalid')
  assertTheme(browser, 'system', 'dark')
})

test('unavailable or denied storage does not prevent startup or in-memory theme changes', () => {
  for (const storageFailure of ['unavailable', 'read', 'write']) {
    const browser = createBrowser({ systemDark: true, storageFailure })
    assertTheme(browser, 'system', 'dark')
    assert.doesNotThrow(() => browser.theme.setPreference('light'))
    assertTheme(browser, 'light', 'light')
    browser.theme.setPreference('system')
    browser.setSystemDark(false)
    assertTheme(browser, 'system', 'light')
  }
})

test('subscribers observe preference changes from this tab and other tabs until unsubscribed', () => {
  const browser = createBrowser()
  const observedPreferences = []
  const unsubscribe = browser.theme.subscribe(() => observedPreferences.push(browser.theme.getPreference()))
  browser.theme.setPreference('dark')
  assert.equal(observedPreferences.at(-1), 'dark')
  browser.receiveStorage(storageKey, 'light')
  assert.equal(observedPreferences.at(-1), 'light')
  browser.receiveStorage(null, null)
  assert.equal(observedPreferences.at(-1), 'system')

  const notificationCount = observedPreferences.length
  unsubscribe()
  browser.theme.setPreference('light')
  browser.receiveStorage(storageKey, 'dark')
  browser.setSystemDark(true)
  assert.equal(observedPreferences.length, notificationCount)
  assertTheme(browser, 'dark', 'dark')
})
