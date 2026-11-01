import assert from 'node:assert/strict'
import test from 'node:test'
import { emailPreviewLink } from '../src/features/notifications/emailPreviewDocument.ts'

test('preview links allow only absolute web addresses without credentials or control characters', () => {
  assert.equal(emailPreviewLink('https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20261102000123'),
    'https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20261102000123')
  assert.equal(emailPreviewLink('https://example.com/news?a=1&b=2'), 'https://example.com/news?a=1&b=2')
  for (const value of [null, '', '/api/notifications/reports/17/send', '//example.com',
    'javascript:alert(1)', 'data:text/html,hi', 'file:///tmp/test', 'https://user:password@example.com/',
    'https://example.com/\nnews', ' https://example.com/', 'https://example.com/\u007f']) {
    assert.equal(emailPreviewLink(value), null, String(value))
  }
})
