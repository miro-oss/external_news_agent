// Runs before styles and React so the first paint uses the saved appearance.
;(() => {
  const key = 'news-signal-desk.theme'
  const normalize = (value) => value === 'light' || value === 'dark' ? value : 'system'
  const media = window.matchMedia('(prefers-color-scheme: dark)')
  const listeners = new Set()
  let preference = 'system'

  try {
    preference = normalize(window.localStorage.getItem(key))
  } catch {
    // Private or restricted browser contexts can deny storage access.
  }

  function apply() {
    const theme = preference === 'system' ? (media.matches ? 'dark' : 'light') : preference
    document.documentElement.dataset.theme = theme
    document.documentElement.style.colorScheme = theme
  }

  function update(value) {
    preference = normalize(value)
    apply()
    listeners.forEach((listener) => listener())
  }

  window.newsSignalTheme = {
    getPreference: () => preference,
    setPreference(value) {
      update(value)
      try {
        window.localStorage.setItem(key, preference)
      } catch {
        // The selected theme still works for this page when persistence is unavailable.
      }
    },
    subscribe(listener) {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
  }

  media.addEventListener('change', () => {
    if (preference === 'system') apply()
  })
  window.addEventListener('storage', (event) => {
    if (event.key !== key && event.key !== null) return
    try {
      if (event.storageArea !== window.localStorage) return
    } catch {
      return
    }
    update(event.newValue)
  })
  apply()
})()
