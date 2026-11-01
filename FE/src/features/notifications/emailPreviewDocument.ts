const ELEMENTS = new Set(['A', 'B', 'BR', 'DIV', 'EM', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6', 'HR',
  'I', 'LI', 'OL', 'P', 'SECTION', 'SMALL', 'SPAN', 'STRONG', 'TABLE', 'TBODY', 'TD', 'TFOOT', 'TH', 'THEAD', 'TR', 'UL'])
const ATTRIBUTES = new Set(['align', 'bgcolor', 'border', 'cellpadding', 'cellspacing', 'class', 'colspan',
  'dir', 'height', 'lang', 'role', 'rowspan', 'style', 'valign', 'width'])

export const EMAIL_PREVIEW_SANDBOX = 'allow-popups allow-popups-to-escape-sandbox'
export const EMAIL_PREVIEW_CSP = "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'"

export function emailPreviewLink(value: string | null): string | null {
  if (!value || [...value].some(character => character.charCodeAt(0) <= 32 || character.charCodeAt(0) === 127)) return null
  try {
    const url = new URL(value)
    return (url.protocol === 'https:' || url.protocol === 'http:') && !url.username && !url.password
      ? url.href : null
  } catch { return null }
}

/** Parse in an inert template, then copy only static email elements into an isolated document. */
export function emailPreviewDocument(html: string): string {
  if (typeof document === 'undefined') return ''
  const template = document.createElement('template')
  template.innerHTML = html
  const preview = document.implementation.createHTMLDocument('이메일 본문 미리보기')
  preview.documentElement.lang = 'ko'
  const policy = preview.createElement('meta')
  policy.httpEquiv = 'Content-Security-Policy'
  policy.content = EMAIL_PREVIEW_CSP
  preview.head.prepend(policy)
  const viewport = preview.createElement('meta')
  viewport.name = 'viewport'
  viewport.content = 'width=device-width, initial-scale=1'
  preview.head.append(viewport)
  preview.body.style.cssText = "margin:0;background:#f2f4f6;color:#191f28;font-family:-apple-system,BlinkMacSystemFont,'Apple SD Gothic Neo','Malgun Gothic',Arial,sans-serif;line-height:1.7;overflow-wrap:anywhere"

  function append(source: Node, target: Node) {
    if (source.nodeType === Node.TEXT_NODE) {
      target.appendChild(preview.createTextNode(source.textContent ?? ''))
      return
    }
    if (!(source instanceof HTMLElement)) return
    if (source.tagName === 'STYLE') {
      const style = preview.createElement('style')
      style.textContent = source.textContent
      preview.head.append(style)
      return
    }
    if (!ELEMENTS.has(source.tagName)) return
    const element = preview.createElement(source.tagName.toLowerCase())
    for (const { name, value } of source.attributes) {
      if (ATTRIBUTES.has(name.toLowerCase())) element.setAttribute(name, value)
    }
    if (source.tagName === 'A') {
      const href = emailPreviewLink(source.getAttribute('href'))
      if (href) {
        element.setAttribute('href', href)
        element.setAttribute('target', '_blank')
        element.setAttribute('rel', 'noopener noreferrer')
      }
    }
    for (const child of source.childNodes) append(child, element)
    target.appendChild(element)
  }
  for (const child of template.content.childNodes) append(child, preview.body)
  return '<!doctype html>\n' + preview.documentElement.outerHTML
}
