import assert from 'node:assert/strict'
import test from 'node:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import Markdown from 'react-markdown'
import { rehypeCollectionHighlights } from '../src/features/reports/reportReading.ts'

function render(markdown, terms) {
  return renderToStaticMarkup(createElement(Markdown, {
    rehypePlugins: [[rehypeCollectionHighlights, { terms }]], children: markdown,
  }))
}

test('markdown highlights rendered headings, emphasis, and link labels without altering destinations or code', () => {
  const html = render('# HBM 시장\n\n**HBM4** [HBM 공급](https://example.invalid/HBM?query=HBM)\n\n`HBM`\n\n```txt\nHBM4\n```', ['HBM 시장', 'HBM4', 'HBM'])
  assert.match(html, /<h1><mark class="collection-keyword-match">HBM 시장<\/mark><\/h1>/)
  assert.match(html, /<strong><mark class="collection-keyword-match">HBM4<\/mark><\/strong>/)
  assert.match(html, /href="https:\/\/example.invalid\/HBM\?query=HBM"><mark class="collection-keyword-match">HBM<\/mark> 공급<\/a>/)
  assert.match(html, /<code>HBM<\/code>/)
  assert.match(html, /<pre><code class="language-txt">HBM4\n<\/code><\/pre>/)
  assert.equal((html.match(/class="collection-keyword-match"/g) ?? []).length, 3)
})

test('raw HTML and image properties never become highlighted or executable markup', () => {
  const html = render('HBM 본문\n\n<img src="HBM" onerror="HBM">\n\n![HBM](https://example.invalid/HBM.png)', ['HBM', 'onerror'])
  assert.equal((html.match(/class="collection-keyword-match"/g) ?? []).length, 1)
  assert.match(html, /&lt;img/)
  assert.doesNotMatch(html, /<img[^>]*onerror=/)
  assert.match(html, /alt="HBM"/)
  assert.match(html, /https:\/\/example.invalid\/HBM\.png/)
})

test('reports without saved highlight terms retain their ordinary markdown rendering', () => {
  assert.equal(render('HBM **시장**', []), renderToStaticMarkup(createElement(Markdown, { children: 'HBM **시장**' })))
})
