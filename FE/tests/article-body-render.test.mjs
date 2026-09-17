import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { after, before, test } from 'node:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createServer } from 'vite'

let server, emptyEnvDir, ArticleDetailModal
before(async () => {
  emptyEnvDir = await mkdtemp(join(tmpdir(), 'article-body-test-'))
  server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false,
    envDir: emptyEnvDir, cacheDir: join(emptyEnvDir, 'vite-cache'),
    // Static SSR rendering does not need the background browser dependency optimizer.
    optimizeDeps: { noDiscovery: true, include: [] },
    server: { middlewareMode: true, watch: null, ws: false }, logLevel: 'error' })
  ArticleDetailModal = (await server.ssrLoadModule('/src/features/articles/ArticleDetailModal.tsx')).ArticleDetailModal
})
after(async () => {
  await server?.close()
  if (emptyEnvDir) await rm(emptyEnvDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 })
})

function renderBody(bodyText, sentences = []) {
  const client = new QueryClient({ defaultOptions: { queries: { staleTime: Infinity, retry: false } } })
  client.setQueryData(['article', 10, null], {
    id: 10, title: '본문 표시 검증', topicName: '검증 주제', publisher: '검증 매체',
    canonicalUrl: 'https://example.invalid/article', publishedAt: '2026-09-14T10:00:00+09:00',
    language: 'ko', fetchStatus: 'OK', analysisArticleId: 10, issueId: null,
    analysis: null, relatedArticles: [], bodyText, sentences,
  })
  try {
    return renderToStaticMarkup(createElement(QueryClientProvider, { client },
      createElement(ArticleDetailModal, { articleId: 10, onClose() {} })))
  } finally { client.clear() }
}

test('full text without analysis sentence indexes is readable instead of shown as blocked', () => {
  const html = renderBody('확보한 첫 문단.\n\n<script>두 번째 문단</script>')
  assert.match(html, /확보한 첫 문단/)
  assert.match(html, /&lt;script&gt;두 번째 문단&lt;\/script&gt;/)
  assert.doesNotMatch(html, /본문을 가져올 수 없는 기사|<script>/)
})

test('saved analysis sentences retain their existing evidence IDs', () => {
  const html = renderBody('현재 본문', [{ index: 7, text: '분석 시점의 근거 문장' }])
  assert.match(html, /article-10-sentence-7/)
  assert.match(html, /분석 시점의 근거 문장/)
  assert.doesNotMatch(html, /현재 본문|본문을 가져올 수 없는 기사/)
})

const navigation = ['최신뉴스', '정치', '경제', '사회', '생활문화', '스포츠', '국제', '날씨']

test('article body fallback hides leading publisher navigation', () => {
  const html = renderBody(`${navigation.join('\n\n')}\n\n정부는 오늘 새로운 산업 지원 계획을 발표했다.\n경제 성장률 전망도 공개됐다.`)
  assert.doesNotMatch(html, /최신뉴스|생활문화|>정치<|>날씨</)
  assert.match(html, /정부는 오늘 새로운 산업 지원 계획을 발표했다/)
  assert.match(html, /경제 성장률 전망도 공개됐다/)
})

test('saved navigation rows disappear without renumbering evidence sentences', () => {
  const sentences = [...navigation, '정부는 오늘 지원 계획을 발표했다.', '산업 전망을 설명했다.']
    .map((text, index) => ({ index: index + 30, text }))
  const html = renderBody(sentences.map(({ text }) => text).join('\n'), sentences)
  assert.doesNotMatch(html, /최신뉴스|생활문화|article-10-sentence-30|article-10-sentence-37/)
  assert.match(html, /id="article-10-sentence-38"/)
  assert.match(html, /id="article-10-sentence-39"/)
  assert.match(html, /정부는 오늘 지원 계획을 발표했다/)
})

test('navigation merged into a sentence needs a latest-news marker and an explicit end separator', () => {
  const html = renderBody('확보한 본문', [
    { index: 11, text: '최신 뉴스 | 정치 · 경제 | 생활 · 문화 | IT/과학 | 정부가 새 정책을 발표했다.' },
    { index: 15, text: '정치 경제 사회 분야 전문가들이 의견을 냈다.' },
  ])
  assert.doesNotMatch(html, /최신 뉴스|생활 · 문화|IT\/과학/)
  assert.match(html, /id="article-10-sentence-11"/)
  assert.match(html, /정부가 새 정책을 발표했다/)
  assert.match(html, /id="article-10-sentence-15"/)
  assert.match(html, /정치 경제 사회 분야 전문가들이 의견을 냈다/)
})

test('complete navigation groups may use category separators without a latest-news marker', () => {
  const html = renderBody('정치 | 경제 · 사회\n실제 기사 본문을 표시한다.')
  assert.doesNotMatch(html, /정치 \| 경제 · 사회/)
  assert.match(html, /실제 기사 본문을 표시한다/)
})

test('a recognized menu never consumes a category word from the first prose sentence', () => {
  const html = renderBody('확보한 본문', [
    { index: 21, text: '최신뉴스' },
    { index: 24, text: '정치 | 경제 · 사회' },
    { index: 26, text: '경제 전망은 밝다.' },
  ])
  assert.doesNotMatch(html, /최신뉴스|정치|article-10-sentence-21|article-10-sentence-24/)
  assert.match(html, /id="article-10-sentence-26"/)
  assert.match(html, /경제 전망은 밝다/)
  assert.match(renderBody('최신뉴스\n정치\n경제\n사회\n경제 전망은 밝다.'), /경제 전망은 밝다/)
})

test('space-only flattened navigation remains intact because the prose boundary is ambiguous', () => {
  const text = `${navigation.join(' ')} 경제 전망은 밝다.`
  for (const sentences of [[], [{ index: 17, text }]]) {
    const html = renderBody(text, sentences)
    assert.ok(html.includes(text))
    assert.match(html, /경제 전망은 밝다/)
    if (sentences.length > 0) assert.match(html, /id="article-10-sentence-17"/)
  }
})

test('space-only navigation never consumes an unseen category at the start of prose', () => {
  for (const text of [
    '최신뉴스 정치 경제 사회 국제 관계가 악화됐다.',
    '최신뉴스 정치 경제 사회 국제 정치 관계가 악화됐다.',
    '최신뉴스 정치 경제 사회 · 국제 관계가 악화됐다.',
    '최신뉴스 정치 생활·문화 국제 관계가 악화됐다.',
    '최신뉴스 | 정치 경제 사회 국제 관계가 악화됐다.',
  ]) {
    for (const sentences of [[], [{ index: 19, text }]]) {
      const html = renderBody(text, sentences)
      assert.ok(html.includes(text), text)
      if (sentences.length > 0) assert.match(html, /id="article-10-sentence-19"/)
    }
  }
})

test('a delimited menu preserves all prose after the last pipe separator', () => {
  for (const prose of ['국제 관계가 악화됐다.', '국제·정치 관계가 악화됐다.']) {
    const text = `최신뉴스 정치 경제 사회 | ${prose}`
    for (const sentences of [[], [{ index: 23, text }]]) {
      const html = renderBody(text, sentences)
      assert.doesNotMatch(html, /최신뉴스|경제|사회/)
      assert.ok(html.includes(prose), prose)
      if (sentences.length > 0) assert.match(html, /id="article-10-sentence-23"/)
    }
  }
})

test('a category heading repeated after navigation survives in every stored body representation', () => {
  const lines = ['최신뉴스', '정치', '경제', '사회', '경제', '본문이 여기서 시작된다.']
  const text = lines.join('\n')
  const fallback = renderBody(text)
  assert.doesNotMatch(fallback, /최신뉴스|정치|사회/)
  assert.match(fallback, /<p>경제<\/p>/)

  const split = renderBody(text, lines.map((line, index) => ({ index: index + 41, text: line })))
  assert.doesNotMatch(split, /최신뉴스|정치|사회|article-10-sentence-43/)
  assert.match(split, /id="article-10-sentence-45"[^>]*><span>경제<\/span>/)
  assert.match(split, /id="article-10-sentence-46"/)

  const packed = renderBody(text, [{ index: 51, text }])
  assert.doesNotMatch(packed, /최신뉴스|정치|사회/)
  assert.match(packed, /id="article-10-sentence-51"[^>]*><span>경제\n본문이 여기서 시작된다/)
})

test('repeated explicit latest-news groups are removed but a following category heading remains', () => {
  const lines = [...navigation, ...navigation, '경제', '본문이 여기서 시작된다.']
  const text = lines.join('\n')
  const html = renderBody(text, lines.map((line, index) => ({ index: index + 61, text: line })))
  assert.doesNotMatch(html, /최신뉴스|생활문화|날씨/)
  assert.match(html, /id="article-10-sentence-77"[^>]*><span>경제<\/span>/)
  assert.match(html, /id="article-10-sentence-78"/)
  assert.match(renderBody(text), /<p>경제<\/p>/)
})

test('a lone latest-news heading after a confirmed menu is not a complete repeated menu', () => {
  const lines = [...navigation, '최신뉴스', '본문이 여기서 시작된다.']
  const text = lines.join('\n')
  const html = renderBody(text, lines.map((line, index) => ({ index: index + 81, text: line })))
  assert.doesNotMatch(html, /생활문화|날씨|article-10-sentence-81/)
  assert.match(html, /id="article-10-sentence-89"[^>]*><span>최신뉴스<\/span>/)
  assert.match(html, /id="article-10-sentence-90"/)
  assert.match(renderBody(text), /<p>최신뉴스<\/p>/)
})

test('multiline navigation within one saved sentence retains its original evidence index', () => {
  const text = `${navigation.join('\n\n')}\n\n경제 전망은 밝다.`
  const html = renderBody(text, [{ index: 29, text }])
  assert.doesNotMatch(html, /최신뉴스|생활문화|날씨/)
  assert.match(html, /경제 전망은 밝다/)
  assert.match(html, /id="article-10-sentence-29"/)
})

test('pure navigation rows may repeat labels without leaking them into the article', () => {
  const html = renderBody('최신뉴스 정치 경제 정치 사회\n실제 기사 본문이다.')
  assert.doesNotMatch(html, /최신뉴스|정치|경제|사회/)
  assert.match(html, /실제 기사 본문이다/)
})

test('isolated headings, repeated labels, and category words in prose remain visible', () => {
  for (const text of [
    '경제\n한국 경제 전망을 설명한다.',
    '정치\n경제\n사회 분야 협력을 논의했다.',
    '최신뉴스\n경제\n경제\n경제 전망을 설명했다.',
    '최신뉴스\n최신뉴스\n정치\n경제\n본문이다.',
    '정치 경제 사회 분야 전문가들이 의견을 냈다.',
    '최신뉴스에서는 정치 경제 사회 분야를 보도한다.',
    '최신뉴스 생활문화 생활·문화 생활/문화 관련 소식을 전했다.',
    '새로운 정책을 발표했다.\n정치\n경제\n사회\n후속 내용을 보도한다.',
  ]) {
    const html = renderBody(text)
    for (const paragraph of text.split('\n')) assert.ok(html.includes(paragraph), paragraph)
  }
})
